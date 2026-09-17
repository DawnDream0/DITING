package tunnel

import (
	"strings"
)

type appRuleBucket struct {
	allow     map[string]struct{}
	block     map[string]string
	important map[string]string

	allowWildcards     []*wildcardMatcher
	blockWildcards     []*wildcardMatcher
	importantWildcards []*wildcardMatcher
}

type invertedRule struct {
	pattern      string
	source       string
	important    bool
	excludedApps map[string]struct{}
	wildcard     *wildcardMatcher
}

type policySnapshot struct {
	filterEnabled bool
	hasRules      bool

	globalImportant          map[string]string
	globalImportantWildcards []*wildcardMatcher
	importantInverted        []invertedRule
	importantBlockTrie       *dtriReader

	globalAllow          map[string]struct{}
	globalAllowWildcards []*wildcardMatcher
	allowInverted        []invertedRule
	importantAllowTrie   *dtriReader
	allowTrie            *dtriReader

	globalBlock          map[string]string
	globalBlockWildcards []*wildcardMatcher
	blockInverted        []invertedRule
	blockTrie            *dtriReader

	disabledRules map[string]struct{}

	appBuckets map[string]*appRuleBucket
}

// evaluate executes the 7-level rule evaluation priority:
// 1. App-specific $important blocking rules (exact match and wildcards).
// 2. Global $important blocking rules (including inverted app exclusions and subscription important tries).
// 3. App-specific whitelist rules (@@, exact and wildcards).
// 4. Global whitelist rules (@@, including inverted exclusions and subscription allowTrie).
// 5. App-specific normal blocking rules (including full-app blocks via *$app=pkg).
// 6. Global normal blocking rules (including inverted exclusions and subscription blockTrie).
// 7. Default pass (no rules matched).
func (s *policySnapshot) evaluate(domain, appName string) (blocked bool, reason string) {
	if s == nil || !s.filterEnabled {
		return false, ""
	}

	domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(domain)), ".")
	if domain == "" {
		return false, ""
	}
	appName = strings.TrimSpace(appName)

	// Level 1: App-specific $important blocking rules
	if appName != "" && len(s.appBuckets) > 0 {
		if bucket, ok := s.appBuckets[appName]; ok {
			if r, hit := matchDomainOrSuffix(domain, bucket.important); hit {
				return true, r
			}
			for _, wc := range bucket.importantWildcards {
				if wc.matches(domain) {
					return true, wc.pattern
				}
			}
		}
	}

	// Level 2: Global $important blocking rules
	for _, inv := range s.importantInverted {
		if appName != "" {
			if _, excluded := inv.excludedApps[appName]; excluded {
				continue
			}
		}
		if inv.wildcard != nil {
			if inv.wildcard.matches(domain) {
				return true, inv.pattern
			}
		} else if matchSingleDomainOrSuffix(domain, inv.pattern) {
			return true, inv.pattern
		}
	}

	if r, hit := matchDomainOrSuffix(domain, s.globalImportant); hit {
		return true, r
	}
	for _, wc := range s.globalImportantWildcards {
		if wc.matches(domain) {
			return true, wc.pattern
		}
	}
	if s.importantBlockTrie != nil {
		if hit, src := s.importantBlockTrie.containsOrParentWithDisabled(domain, s.disabledRules); hit {
			return true, src
		}
	}

	// Level 3: App-specific whitelist rules (@@)
	if appName != "" && len(s.appBuckets) > 0 {
		if bucket, ok := s.appBuckets[appName]; ok {
			if matchDomainOrSuffixSet(domain, bucket.allow) {
				return false, "__ALLOW__"
			}
			for _, wc := range bucket.allowWildcards {
				if wc.matches(domain) {
					return false, "__ALLOW__"
				}
			}
		}
	}

	// Level 4: Global whitelist rules (@@)
	for _, inv := range s.allowInverted {
		if appName != "" {
			if _, excluded := inv.excludedApps[appName]; excluded {
				continue
			}
		}
		if inv.wildcard != nil {
			if inv.wildcard.matches(domain) {
				return false, "__ALLOW__"
			}
		} else if matchSingleDomainOrSuffix(domain, inv.pattern) {
			return false, "__ALLOW__"
		}
	}

	if matchDomainOrSuffixSet(domain, s.globalAllow) {
		return false, "__ALLOW__"
	}
	for _, wc := range s.globalAllowWildcards {
		if wc.matches(domain) {
			return false, "__ALLOW__"
		}
	}

	if s.importantAllowTrie != nil {
		if hit, _ := s.importantAllowTrie.containsOrParent(domain); hit {
			return false, "__ALLOW__"
		}
	}
	if s.allowTrie != nil {
		if hit, _ := s.allowTrie.containsOrParent(domain); hit {
			return false, "__ALLOW__"
		}
	}

	// Level 5: App-specific normal blocking rules
	if appName != "" && len(s.appBuckets) > 0 {
		if bucket, ok := s.appBuckets[appName]; ok {
			if r, hit := matchDomainOrSuffix(domain, bucket.block); hit {
				return true, r
			}
			for _, wc := range bucket.blockWildcards {
				if wc.matches(domain) {
					return true, wc.pattern
				}
			}
		}
	}

	// Level 6: Global normal blocking rules
	for _, inv := range s.blockInverted {
		if appName != "" {
			if _, excluded := inv.excludedApps[appName]; excluded {
				continue
			}
		}
		if inv.wildcard != nil {
			if inv.wildcard.matches(domain) {
				return true, inv.pattern
			}
		} else if matchSingleDomainOrSuffix(domain, inv.pattern) {
			return true, inv.pattern
		}
	}

	if r, hit := matchDomainOrSuffix(domain, s.globalBlock); hit {
		return true, r
	}
	for _, wc := range s.globalBlockWildcards {
		if wc.matches(domain) {
			return true, wc.pattern
		}
	}
	if s.blockTrie != nil {
		if hit, src := s.blockTrie.containsOrParentWithDisabled(domain, s.disabledRules); hit {
			return true, src
		}
	}

	// Level 7: Default pass
	return false, ""
}
