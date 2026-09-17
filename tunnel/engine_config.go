// engine_config.go manages dynamic configuration and runtime control knobs for the
// Engine instance, exposing gomobile-compatible setters called from Android Kotlin.
//
// Key Configuration Responsibilities:
// - Upstream DNS endpoints (Plain, DoH, DoT, DoQ), fallbacks, and block response types
//   (CUSTOM_IP 0.0.0.0, NXDOMAIN, REFUSED).
// - PolicyEngine rule snapshots and CNAME rewrite mappings.
// - Android UI event callback bridges (LogCallback, BatchLogCallback, RaceLogCallback,
//   BootstrapLogCallback, HttpLogCallback, TrafficCallback).
// - Screen state adaptive tick intervals (e.g., 1000ms interactive vs 10000ms screen-off)
//   which conserve battery while maintaining continuous atomic byte/packet accounting.
// - Outbound proxy session configuration and domain checker bindings.

package tunnel

import (
	"encoding/json"
	"strings"
	"time"
)

func (e *Engine) ClearDNSCache() {
	if e.dnsCache != nil {
		e.dnsCache.clear()
	}
}

func (e *Engine) GetRouter() *Router {
	return e.router
}

func (e *Engine) SetOutboundAdapter(adapter OutboundAdapter) {
	e.router.SetAdapter(adapter)
}

func (e *Engine) SetDomainChecker(checker DomainChecker) {
	e.domainChecker = checker
}

func (e *Engine) ApplyRuleSnapshot(jsonSnapshot string) string {
	e.mu.Lock()
	if e.policyEngine == nil {
		e.policyEngine = newPolicyEngine()
	}
	pe := e.policyEngine
	e.mu.Unlock()

	if err := pe.applySnapshot(jsonSnapshot); err != nil {
		logf("ApplyRuleSnapshot error: %v", err)
		return err.Error()
	}
	logf("ApplyRuleSnapshot successfully updated Go policy engine rules")
	return ""
}

func (e *Engine) SetFilterDNS(enabled bool) { e.filterDNS.Store(enabled) }

func (e *Engine) SetRewriteRules(content string) {
	rules := make(map[string]string)
	if content != "" {
		if err := json.Unmarshal([]byte(content), &rules); err != nil {
			logf("SetRewriteRules: invalid JSON: %v", err)
			return
		}
	}
	clean := make(map[string]string, len(rules))
	for source, target := range rules {
		source = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(source)), ".")
		target = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(target)), ".")
		if source != "" && target != "" {
			clean[source] = target
		}
	}
	e.mu.Lock()
	e.rewriteRules = clean
	e.mu.Unlock()
}

func (e *Engine) rewriteTarget(domain string) string {
	domain = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(domain)), ".")
	e.mu.Lock()
	defer e.mu.Unlock()
	for candidate := domain; candidate != ""; {
		if target := e.rewriteRules[candidate]; target != "" {
			return target
		}
		dot := strings.IndexByte(candidate, '.')
		if dot < 0 {
			break
		}
		candidate = candidate[dot+1:]
	}
	return ""
}

func (e *Engine) SetFirewallChecker(checker FirewallChecker) {
	e.firewallChecker = checker
}

func (e *Engine) SetAppResolver(resolver AppResolver) {
	e.appResolver = resolver
}

func (e *Engine) SetAppUidResolver(resolver AppUidResolver) {
	e.appUidResolver = resolver
}

func (e *Engine) SetLogCallback(cb LogCallback) {
	e.logCallback = cb
}

func (e *Engine) SetBatchLogCallback(cb BatchLogCallback) {
	e.batchLogCallback = cb
	if e.logAggregator != nil {
		e.logAggregator.setCallback(cb)
		if cb != nil {
			e.logAggregator.start()
		}
	}
}

func (e *Engine) SetRaceLogCallback(cb RaceLogCallback) {
	e.mu.Lock()
	e.raceLogCallback = cb
	resolver := e.resolver
	e.mu.Unlock()
	if resolver != nil {
		resolver.SetRaceLogCallback(cb)
	}
}

func (e *Engine) SetBootstrapLogCallback(cb BootstrapLogCallback) {
	e.mu.Lock()
	e.bootstrapLogCallback = cb
	resolver := e.resolver
	e.mu.Unlock()
	if resolver != nil {
		resolver.SetBootstrapLogCallback(cb)
	}
}

func (e *Engine) ResetBootstrapStats() {
	e.mu.Lock()
	resolver := e.resolver
	e.mu.Unlock()
	if resolver != nil {
		resolver.ResetBootstrapStats()
	}
}

func (e *Engine) SetHttpLogCallback(cb HttpLogCallback) {
	e.httpLogCallback = cb
}

func (e *Engine) SetOutboundProxyStatusCallback(cb OutboundProxyStatusCallback) {
	e.outboundStatusCallback = cb
}

func (e *Engine) SetTrafficCallback(cb TrafficCallback) {
	e.trafficCallback = cb
	if e.trafficTracker != nil {
		e.trafficTracker.SetCallback(cb)
	}
}

func (e *Engine) SetTickIntervalMs(ms int64) {
	if e.trafficTracker != nil {
		e.trafficTracker.SetTickInterval(time.Duration(ms) * time.Millisecond)
	}
}

func (e *Engine) ConfigureOutboundProxy(configJSON string) string {
	cfg, err := parseOutboundProxyConfig(configJSON)
	if err != nil {
		return err.Error()
	}
	e.mu.Lock()
	e.outboundConfig = cfg
	e.mu.Unlock()
	return ""
}

func (e *Engine) reportOutboundStatus(state, message string) {
	if cb := e.outboundStatusCallback; cb != nil {
		cb.OnOutboundProxyStatus(state, message)
	}
}

func (e *Engine) SetDNS(protocol, primary, fallback, dohURL string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.protocol = protocol
	e.primaryDNS = primary
	e.fallbackDNS = fallback
	e.dohURL = dohURL
	if e.resolver != nil {
		e.resolver.Configure(ParseProtocol(protocol), primary, fallback, dohURL)
	}
}

func (e *Engine) SetBlockResponseType(responseType string) {
	e.responseType = ParseResponseType(responseType)
}

func (e *Engine) notifyLog(domain string, blocked bool, queryType uint16, responseTimeMs int64, appName, resolvedIPs, blockedBy, errorMessage string, cached bool) {
	if e.logAggregator != nil && e.logAggregator.hasCallback() {
		e.logAggregator.push(logItem{
			Domain:         domain,
			Blocked:        blocked,
			QueryType:      int(queryType),
			ResponseTimeMs: responseTimeMs,
			AppName:        appName,
			ResolvedIPs:    resolvedIPs,
			BlockedBy:      blockedBy,
			ErrorMessage:   errorMessage,
			Cached:         cached,
			Timestamp:      time.Now().UnixMilli(),
		})
		return
	}
	if e.logCallback != nil {
		e.logCallback.OnDNSQuery(domain, blocked, int(queryType), responseTimeMs, appName, resolvedIPs, blockedBy, errorMessage, cached)
	}
}
