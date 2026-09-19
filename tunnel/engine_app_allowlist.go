// engine_app_allowlist.go implements per-UID domain authorization caching for strict application allowlist mode.
//
// Key Behaviors:
// - Authorization Lifetime: Destinations are usable only while their DNS TTL remains valid for the resolving app UID.
// - System Resolver Handling: Evaluates queries against all restricted UIDs to handle Android netd system
//   resolver queries dispatched under netd UID or UIDUnknown on behalf of client apps.
// - Expiration Pruning: Cached IP mappings are pruned automatically when size exceeds thresholds.
// - Wildcard Support: Domains containing '*' are compiled into glob regexes (e.g. "api*-normal.fqnovel.com").

package tunnel

import (
	"encoding/json"
	"net"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/miekg/dns"
)

type appAllowlist struct {
	mu        sync.RWMutex
	domains   map[int]map[string]struct{}
	wildcards map[int][]*regexp.Regexp
	ips       map[int]map[string]time.Time
}

func (e *Engine) SetAppAllowlist(rulesJSON string) {
	exactRules := make(map[int]map[string]struct{})
	wildcardRules := make(map[int][]*regexp.Regexp)
	if strings.TrimSpace(rulesJSON) != "" {
		var rawMap map[string][]string
		if err := json.Unmarshal([]byte(rulesJSON), &rawMap); err == nil {
			for uidStr, domainList := range rawMap {
				uid, err := strconv.Atoi(strings.TrimSpace(uidStr))
				if err != nil || uid <= 0 {
					continue
				}
				exactSet := make(map[string]struct{})
				var wcList []*regexp.Regexp
				for _, raw := range domainList {
					domain := strings.TrimSuffix(strings.ToLower(strings.TrimSpace(raw)), ".")
					if domain == "" {
						continue
					}
					if strings.Contains(domain, "*") {
						regexStr := globToRegex(domain)
						if re, err := regexp.Compile(regexStr); err == nil {
							wcList = append(wcList, re)
						}
					} else {
						exactSet[domain] = struct{}{}
					}
				}
				if len(exactSet) > 0 {
					exactRules[uid] = exactSet
				}
				if len(wcList) > 0 {
					wildcardRules[uid] = wcList
				}
			}
		}
	}

	e.appAllowlist.mu.Lock()
	e.appAllowlist.domains = exactRules
	e.appAllowlist.wildcards = wildcardRules
	e.appAllowlist.ips = make(map[int]map[string]time.Time)
	e.appAllowlist.mu.Unlock()
}

// globToRegex converts a glob pattern (with '*' wildcards) to an anchored regex string.
func globToRegex(glob string) string {
	var sb strings.Builder
	sb.WriteString("^")
	for _, c := range glob {
		if c == '*' {
			sb.WriteString(".*")
		} else {
			sb.WriteString(regexp.QuoteMeta(string(c)))
		}
	}
	sb.WriteString("$")
	return sb.String()
}

func domainMatches(allowedDomains map[string]struct{}, wildcards []*regexp.Regexp, domain string) bool {
	if len(allowedDomains) == 0 && len(wildcards) == 0 {
		return false
	}
	domain = strings.TrimSuffix(strings.ToLower(domain), ".")
	// Exact match: walk up label-by-label (e.g. sub.example.com → example.com → com)
	for candidate := domain; candidate != ""; {
		if _, ok := allowedDomains[candidate]; ok {
			return true
		}
		dot := strings.IndexByte(candidate, '.')
		if dot < 0 {
			break
		}
		candidate = candidate[dot+1:]
	}
	// Wildcard match: test the full domain and every parent suffix
	for _, re := range wildcards {
		if re.MatchString(domain) {
			return true
		}
		for suffix := domain; ; {
			dot := strings.IndexByte(suffix, '.')
			if dot < 0 {
				break
			}
			suffix = suffix[dot+1:]
			if re.MatchString(suffix) {
				return true
			}
		}
	}
	return false
}

func (e *Engine) appAllowlistDomainAllowed(uid int, domain string) bool {
	e.appAllowlist.mu.RLock()
	defer e.appAllowlist.mu.RUnlock()
	allowedDomains, selected := e.appAllowlist.domains[uid]
	wildcards := e.appAllowlist.wildcards[uid]
	if !selected && len(wildcards) == 0 {
		return true
	}
	return domainMatches(allowedDomains, wildcards, domain)
}

func (e *Engine) appAllowlistConnectionAllowed(uid int, ip net.IP) bool {
	if ip.IsLoopback() {
		return true
	}
	e.appAllowlist.mu.RLock()
	_, selected := e.appAllowlist.domains[uid]
	_, hasWc := e.appAllowlist.wildcards[uid]
	expiry := e.appAllowlist.ips[uid][ip.String()]
	e.appAllowlist.mu.RUnlock()
	return (!selected && !hasWc) || (!expiry.IsZero() && time.Now().Before(expiry))
}

func (e *Engine) rememberAppAllowlistResponse(uid int, response *dns.Msg) {
	if response == nil || len(response.Answer) == 0 {
		return
	}
	var qname string
	if len(response.Question) > 0 {
		qname = strings.TrimSuffix(strings.ToLower(response.Question[0].Name), ".")
	}

	e.appAllowlist.mu.Lock()
	defer e.appAllowlist.mu.Unlock()

	targetUIDs := make([]int, 0, 2)
	for targetUID, allowed := range e.appAllowlist.domains {
		if qname != "" && domainMatches(allowed, e.appAllowlist.wildcards[targetUID], qname) {
			targetUIDs = append(targetUIDs, targetUID)
		} else if targetUID == uid && qname == "" {
			targetUIDs = append(targetUIDs, targetUID)
		}
	}
	// Also check UIDs that only have wildcard rules (no exact domains)
	for targetUID := range e.appAllowlist.wildcards {
		if _, hasExact := e.appAllowlist.domains[targetUID]; hasExact {
			continue
		}
		if qname != "" && domainMatches(nil, e.appAllowlist.wildcards[targetUID], qname) {
			targetUIDs = append(targetUIDs, targetUID)
		}
	}

	if len(targetUIDs) == 0 {
		return
	}

	now := time.Now()
	for _, answer := range response.Answer {
		var ip net.IP
		var ttl uint32
		switch rr := answer.(type) {
		case *dns.A:
			ip, ttl = rr.A, rr.Hdr.Ttl
		case *dns.AAAA:
			ip, ttl = rr.AAAA, rr.Hdr.Ttl
		default:
			continue
		}
		if ttl > 0 && ip != nil && !ip.IsUnspecified() {
			expiry := now.Add(time.Duration(ttl) * time.Second)
			ipStr := ip.String()
			for _, targetUID := range targetUIDs {
				if e.appAllowlist.ips[targetUID] == nil {
					e.appAllowlist.ips[targetUID] = make(map[string]time.Time)
				}
				e.appAllowlist.ips[targetUID][ipStr] = expiry

				if len(e.appAllowlist.ips[targetUID]) > 256 {
					for k, exp := range e.appAllowlist.ips[targetUID] {
						if now.After(exp) {
							delete(e.appAllowlist.ips[targetUID], k)
						}
					}
				}
			}
		}
	}
}
