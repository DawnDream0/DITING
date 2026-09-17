// ip_domain_cache.go maintains a bounded, concurrent cache mapping IP addresses back to recently resolved domains.
// It supports reverse domain lookups, such as resolving domain context when falling back to IPv4 after an IPv6 connection failure.

package tunnel

import (
	"sync"
)

type ipDomainCache struct {
	mu      sync.RWMutex
	entries map[string]string
	queue   []string
	maxSize int
}

func newIPDomainCache(maxSize int) *ipDomainCache {
	if maxSize <= 0 {
		maxSize = 2048
	}
	return &ipDomainCache{
		entries: make(map[string]string, maxSize),
		queue:   make([]string, 0, maxSize),
		maxSize: maxSize,
	}
}

func (c *ipDomainCache) put(ip string, domain string) {
	if c == nil || ip == "" || domain == "" {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	if _, exists := c.entries[ip]; exists {
		c.entries[ip] = domain
		return
	}

	if len(c.queue) >= c.maxSize {
		oldest := c.queue[0]
		c.queue = c.queue[1:]
		delete(c.entries, oldest)
	}

	c.queue = append(c.queue, ip)
	c.entries[ip] = domain
}

func (c *ipDomainCache) get(ip string) string {
	if c == nil || ip == "" {
		return ""
	}
	c.mu.RLock()
	defer c.mu.RUnlock()
	return c.entries[ip]
}
