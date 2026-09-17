// dns_cache.go implements a thread-safe, high-performance in-memory DNS response cache.
//
// Cache Semantics & Optimizations:
// - TTL Enforcement: Adheres to upstream DNS TTLs clamped within configurable min/max bounds.
// - Zero-Copy Patching: Directly copies pre-packed wire responses and updates the 2-byte query ID for fast-path hits.
// - Stale Fallback: Serves expired responses within a bounded fallback window while triggering background refreshes.
// - Single-Flight Deduplication: Consolidates concurrent queries for the same domain and type to prevent upstream stampedes.

package tunnel

import (
	"container/list"
	"encoding/binary"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/miekg/dns"
)

type dnsCacheConfig struct {
	Enabled              bool   `json:"enabled"`
	Mode                 string `json:"mode"`
	MaxTTLSeconds        int64  `json:"maxTtlSeconds"`
	FixedTTLSeconds      int64  `json:"fixedTtlSeconds"`
	MinTTLEnabled        bool   `json:"minTtlEnabled"`
	MinTTLSeconds        int64  `json:"minTtlSeconds"`
	StaleFallbackEnabled bool   `json:"staleFallbackEnabled"`
	StaleFallbackSeconds int64  `json:"staleFallbackSeconds"`
}

type cacheEntry struct {
	key          string
	domain       string
	qtype        uint16
	qclass       uint16
	msg          *dns.Msg
	originalTTL  uint32
	effectiveTTL time.Duration
	createdAt    time.Time
	expiresAt    time.Time
	staleUntil   time.Time
	hitCount     atomic.Int64
	lastHitAt    atomic.Int64

	cachedWire atomic.Pointer[cachedWirePack]

	elem *list.Element
}

type cachedWirePack struct {
	sec  uint32
	wire []byte
}

type dnsCache struct {
	mu         sync.RWMutex
	config     dnsCacheConfig
	entries    map[string]*cacheEntry
	lruList    *list.List
	maxEntries int

	flightMu sync.Mutex
	inFlight map[string]*flightCall

	totalHits   atomic.Uint64
	totalMisses atomic.Uint64
}

type flightCall struct {
	wg  sync.WaitGroup
	val []byte
	err error
}

const defaultMaxCacheEntries = 4096

func newDNSCache(cfg dnsCacheConfig) *dnsCache {
	if cfg.MaxTTLSeconds <= 0 {
		cfg.MaxTTLSeconds = 3600
	}
	if cfg.FixedTTLSeconds <= 0 {
		cfg.FixedTTLSeconds = 3600
	}
	if cfg.MinTTLSeconds <= 0 {
		cfg.MinTTLSeconds = 60
	}
	if cfg.StaleFallbackSeconds <= 0 {
		cfg.StaleFallbackSeconds = 300
	}
	return &dnsCache{
		config:     cfg,
		entries:    make(map[string]*cacheEntry),
		lruList:    list.New(),
		maxEntries: defaultMaxCacheEntries,
		inFlight:   make(map[string]*flightCall),
	}
}

func (c *dnsCache) updatePolicy(cfg dnsCacheConfig) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.config = cfg
}

func (c *dnsCache) isEnabled() bool {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.config.Enabled
}

func (c *dnsCache) clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, e := range c.entries {
		if e != nil {
			e.elem = nil
		}
	}
	c.entries = make(map[string]*cacheEntry)
	c.lruList.Init()
}

func cacheKey(domain string, qtype, qclass uint16) string {
	normalized := strings.ToLower(strings.TrimSuffix(domain, "."))
	return fmt.Sprintf("%s:%d:%d", normalized, qtype, qclass)
}

func extractQuestionFromRaw(rawQuery []byte) (string, uint16, uint16, uint16, bool) {
	var msg dns.Msg
	if err := msg.Unpack(rawQuery); err != nil || len(msg.Question) == 0 {
		return "", 0, 0, 0, false
	}
	q := msg.Question[0]
	domain := strings.ToLower(strings.TrimSuffix(q.Name, "."))
	return domain, q.Qtype, q.Qclass, msg.Id, true
}

func (c *dnsCache) get(rawQuery []byte) (response []byte, hit bool, staleCandidate *cacheEntry) {
	c.mu.RLock()
	enabled := c.config.Enabled
	staleFallback := c.config.StaleFallbackEnabled
	c.mu.RUnlock()

	if !enabled {
		return nil, false, nil
	}

	domain, qtype, qclass, queryID, ok := extractQuestionFromRaw(rawQuery)
	if !ok {
		return nil, false, nil
	}

	key := cacheKey(domain, qtype, qclass)
	now := time.Now()

	c.mu.RLock()
	entry, exists := c.entries[key]
	c.mu.RUnlock()

	if !exists || entry == nil {
		c.totalMisses.Add(1)
		return nil, false, nil
	}

	if now.Before(entry.expiresAt) {
		remaining := entry.expiresAt.Sub(now)
		remainingSec := uint32(remaining.Seconds())
		if remainingSec == 0 {
			remainingSec = 1
		}

		entry.hitCount.Add(1)
		entry.lastHitAt.Store(now.UnixNano())
		c.totalHits.Add(1)

		if pack := entry.cachedWire.Load(); pack != nil && pack.sec == remainingSec {
			res := make([]byte, len(pack.wire))
			copy(res, pack.wire)
			binary.BigEndian.PutUint16(res[0:2], queryID)
			return res, true, nil
		}

		patched := patchDNSResponse(entry.msg, queryID, remainingSec)
		if patched != nil {
			entry.cachedWire.Store(&cachedWirePack{
				sec:  remainingSec,
				wire: patched,
			})
		}
		return patched, true, nil
	}

	if staleFallback && now.Before(entry.staleUntil) {
		return nil, false, entry
	}

	c.mu.Lock()
	if e, ok := c.entries[key]; ok && e == entry && now.After(e.staleUntil) {
		delete(c.entries, key)
		if e.elem != nil {
			c.lruList.Remove(e.elem)
			e.elem = nil
		}
	}
	c.mu.Unlock()

	c.totalMisses.Add(1)
	return nil, false, nil
}

func (c *dnsCache) buildStaleResponse(rawQuery []byte, entry *cacheEntry) []byte {
	if entry == nil || entry.msg == nil {
		return nil
	}
	_, _, _, queryID, ok := extractQuestionFromRaw(rawQuery)
	if !ok {
		return nil
	}
	return patchDNSResponse(entry.msg, queryID, 1)
}

func (c *dnsCache) put(rawQuery, rawResponse []byte) bool {
	var respMsg dns.Msg
	if err := respMsg.Unpack(rawResponse); err != nil {
		return false
	}
	return c.putMsg(rawQuery, rawResponse, &respMsg)
}

func (c *dnsCache) putMsg(rawQuery, rawResponse []byte, respMsg *dns.Msg) bool {
	if respMsg == nil {
		return false
	}
	c.mu.RLock()
	enabled := c.config.Enabled
	c.mu.RUnlock()
	if !enabled {
		return false
	}

	domain, qtype, qclass, _, ok := extractQuestionFromRaw(rawQuery)
	if !ok {
		return false
	}

	if respMsg.Rcode != dns.RcodeSuccess || len(respMsg.Answer) == 0 {
		return false
	}

	minTTL, found := extractMinTTL(respMsg)
	if !found || minTTL == 0 {
		return false
	}

	effectiveTTL := c.calculateEffectiveTTL(minTTL)
	if effectiveTTL <= 0 {
		return false
	}

	now := time.Now()
	expiresAt := now.Add(effectiveTTL)

	c.mu.RLock()
	staleFallback := c.config.StaleFallbackEnabled
	staleSeconds := c.config.StaleFallbackSeconds
	c.mu.RUnlock()

	staleUntil := expiresAt
	if staleFallback && staleSeconds > 0 {
		staleUntil = expiresAt.Add(time.Duration(staleSeconds) * time.Second)
	}

	key := cacheKey(domain, qtype, qclass)
	entry := &cacheEntry{
		key:          key,
		domain:       domain,
		qtype:        qtype,
		qclass:       qclass,
		msg:          respMsg.Copy(),
		originalTTL:  minTTL,
		effectiveTTL: effectiveTTL,
		createdAt:    now,
		expiresAt:    expiresAt,
		staleUntil:   staleUntil,
	}
	entry.lastHitAt.Store(now.UnixNano())

	c.mu.Lock()
	defer c.mu.Unlock()

	if old, exists := c.entries[key]; exists {
		if old.elem != nil {
			c.lruList.Remove(old.elem)
			old.elem = nil
		}
		delete(c.entries, key)
	} else if len(c.entries) >= c.maxEntries {
		back := c.lruList.Back()
		if back != nil {
			oldest := back.Value.(*cacheEntry)
			c.lruList.Remove(back)
			if oldest != nil {
				oldest.elem = nil
				delete(c.entries, oldest.key)
			}
		}
	}

	entry.elem = c.lruList.PushFront(entry)
	c.entries[key] = entry
	return true
}

func (c *dnsCache) calculateEffectiveTTL(upstreamTTL uint32) time.Duration {
	c.mu.RLock()
	cfg := c.config
	c.mu.RUnlock()

	ttl := int64(upstreamTTL)
	switch strings.ToLower(cfg.Mode) {
	case "follow_dns_ttl":

	case "limit_max_ttl":
		if cfg.MaxTTLSeconds > 0 && ttl > cfg.MaxTTLSeconds {
			ttl = cfg.MaxTTLSeconds
		}
	case "fixed_ttl":
		if cfg.FixedTTLSeconds > 0 {
			ttl = cfg.FixedTTLSeconds
		}
	default:
		if cfg.MaxTTLSeconds > 0 && ttl > cfg.MaxTTLSeconds {
			ttl = cfg.MaxTTLSeconds
		}
	}

	if cfg.MinTTLEnabled && cfg.MinTTLSeconds > 0 {
		if ttl < cfg.MinTTLSeconds {
			ttl = cfg.MinTTLSeconds
		}
	}

	if ttl <= 0 {
		return 0
	}
	return time.Duration(ttl) * time.Second
}

func (c *dnsCache) singleFlight(rawQuery []byte, resolveFn func() ([]byte, error)) ([]byte, bool, error) {
	domain, qtype, qclass, queryID, ok := extractQuestionFromRaw(rawQuery)
	if !ok {
		resp, err := resolveFn()
		return resp, false, err
	}

	if cachedResp, hit, _ := c.get(rawQuery); hit {
		return cachedResp, true, nil
	}

	key := cacheKey(domain, qtype, qclass)

	c.flightMu.Lock()
	if call, exists := c.inFlight[key]; exists {
		c.flightMu.Unlock()
		call.wg.Wait()
		if call.err != nil {
			return nil, false, call.err
		}

		if cachedResp, hit, _ := c.get(rawQuery); hit {
			return cachedResp, true, nil
		}

		if len(call.val) >= 2 {
			res := make([]byte, len(call.val))
			copy(res, call.val)
			binary.BigEndian.PutUint16(res[0:2], queryID)
			return res, true, nil
		}
		return call.val, true, nil
	}

	call := &flightCall{}
	call.wg.Add(1)
	c.inFlight[key] = call
	c.flightMu.Unlock()

	call.val, call.err = resolveFn()
	if call.err == nil && len(call.val) > 0 {
		c.put(rawQuery, call.val)
	}
	call.wg.Done()

	c.flightMu.Lock()
	delete(c.inFlight, key)
	c.flightMu.Unlock()

	return call.val, false, call.err
}

func extractMinTTL(msg *dns.Msg) (uint32, bool) {
	var minTTL uint32
	found := false

	scanRRs := func(rrs []dns.RR) {
		for _, rr := range rrs {
			if rr == nil || rr.Header() == nil || rr.Header().Rrtype == dns.TypeOPT {
				continue
			}
			ttl := rr.Header().Ttl
			if !found || ttl < minTTL {
				minTTL = ttl
				found = true
			}
		}
	}

	scanRRs(msg.Answer)
	scanRRs(msg.Ns)
	scanRRs(msg.Extra)

	return minTTL, found
}

func patchDNSResponse(msg *dns.Msg, queryID uint16, ttl uint32) []byte {
	if msg == nil {
		return nil
	}
	cp := msg.Copy()
	cp.Id = queryID

	patchRRs := func(rrs []dns.RR) {
		for _, rr := range rrs {
			if rr == nil || rr.Header() == nil || rr.Header().Rrtype == dns.TypeOPT {
				continue
			}
			rr.Header().Ttl = ttl
		}
	}

	patchRRs(cp.Answer)
	patchRRs(cp.Ns)
	patchRRs(cp.Extra)

	packed, err := cp.Pack()
	if err != nil {
		return nil
	}
	return packed
}
