package com.haoze.diting.normal

import com.haoze.diting.core.diagnostic.BootstrapIpEntry
import com.haoze.diting.core.dns.DnsProvider
import com.haoze.diting.core.rule.BlockResponseMode
import com.haoze.diting.core.rule.DynamicBlockResponseConfig
import com.haoze.diting.core.rule.DynamicBlockResponseTracker
import android.content.Context
import android.util.Log
import com.haoze.diting.ui.DnsLogMode
import com.haoze.diting.ui.DnsResolutionMode
import com.haoze.diting.ui.settings.AppRulesSettingsStore
import com.haoze.diting.ui.settings.BootstrapDnsSettingsStore
import com.haoze.diting.ui.settings.DnsCacheSettingsStore
import com.haoze.diting.ui.settings.ResolutionSettingsStore
import com.haoze.diting.ui.settings.SystemSettingsStore
import com.haoze.diting.core.cache.DnsCachePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages active runtime configuration state for [DnsVpnService], including
 * dynamic re-configuration of DNS resolvers, cache policy, and app allowlist/exclusion rules.
 */
class DnsVpnRuntimeConfigManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val refreshMutex: Mutex,
    private val tunnelManager: DnsVpnTunnelManager,
    private val dbComponents: DnsVpnDatabaseComponents,
    private val onNotificationRefresh: () -> Unit,
    private val onRestartVpn: () -> Unit
) {

    @Volatile
    var activeProviders: List<DnsProvider> = emptyList()

    @Volatile
    var activeResolutionMode: DnsResolutionMode = DnsResolutionMode.SINGLE

    @Volatile
    lateinit var activeDnsCachePolicy: DnsCachePolicy

    @Volatile
    var activeDnsLogMode: DnsLogMode = DnsLogMode.ALL

    @Volatile
    var activeLogRetentionDays: Int = 7

    @Volatile
    var activeBlockResponseMode: BlockResponseMode = BlockResponseMode.NXDOMAIN

    @Volatile
    var activeDynamicBlockResponseConfig: DynamicBlockResponseConfig = DynamicBlockResponseConfig()

    @Volatile
    var activeBootstrapEnabled: Boolean = false

    @Volatile
    var activeBootstrapIps: List<BootstrapIpEntry> = emptyList()

    @Volatile
    var activeDomainRulesEnabled: Boolean = true

    val dynamicBlockResponseTracker = DynamicBlockResponseTracker()

    fun loadInitialConfig() {
        activeLogRetentionDays = SystemSettingsStore.logRetentionDays(context)
        activeDnsCachePolicy = DnsCacheSettingsStore.getDnsCachePolicy(context)
        activeResolutionMode = ResolutionSettingsStore.getDnsResolutionMode(context)
        activeDnsLogMode = SystemSettingsStore.getDnsLogMode(context)
        activeBlockResponseMode = AppRulesSettingsStore.getBlockResponseMode(context)
        activeDynamicBlockResponseConfig = AppRulesSettingsStore.getDynamicBlockResponseConfig(context)
        activeBootstrapEnabled = BootstrapDnsSettingsStore.isBootstrapEnabled(context)
        activeBootstrapIps = BootstrapDnsSettingsStore.loadEnabledBootstrapIpEntries(context)
        activeDomainRulesEnabled = AppRulesSettingsStore.isDomainRulesEnabled(context)
    }

    fun refreshRuntimeConfig(reason: String) {
        if (tunnelManager.vpnInterface == null) {
            Log.d(TAG, "Skip runtime config refresh because VPN is not running: $reason")
            return
        }

        scope.launch {
            refreshMutex.withLock {
                val oldProviders = activeProviders
                val newCachePolicy = DnsCacheSettingsStore.getDnsCachePolicy(context)
                val newResolutionMode = ResolutionSettingsStore.getDnsResolutionMode(context)
                val newBootstrapEnabled = BootstrapDnsSettingsStore.isBootstrapEnabled(context)
                val newBootstrapIps = BootstrapDnsSettingsStore.loadEnabledBootstrapIpEntries(context)
                activeDomainRulesEnabled = AppRulesSettingsStore.isDomainRulesEnabled(context)
                activeDnsLogMode = SystemSettingsStore.getDnsLogMode(context)
                activeLogRetentionDays = SystemSettingsStore.logRetentionDays(context)
                val newBlockResponseMode = AppRulesSettingsStore.getBlockResponseMode(context)
                val newDynamicBlockResponseConfig = AppRulesSettingsStore.getDynamicBlockResponseConfig(context)
                val newProviders = runCatching { DnsVpnProviderResolver.resolveDnsProviders(context, null) }

                newProviders.fold(
                    onSuccess = { updatedProviders ->
                        val goSyncError = tunnelManager.goInspectionTunnel?.let { tunnel ->
                            runCatching {
                                tunnel.syncDnsConfig(
                                    providers = updatedProviders,
                                    resolutionMode = newResolutionMode,
                                    blockResponseMode = newBlockResponseMode,
                                    dynamicBlockResponseConfig = newDynamicBlockResponseConfig,
                                    cachePolicy = newCachePolicy,
                                    bootstrapEnabled = newBootstrapEnabled,
                                    bootstrapIps = newBootstrapIps
                                )
                                tunnel.pushRuleSnapshot()
                            }.exceptionOrNull()
                        }
                        activeDnsCachePolicy = newCachePolicy
                        activeBlockResponseMode = newBlockResponseMode
                        activeDynamicBlockResponseConfig = newDynamicBlockResponseConfig
                        activeBootstrapEnabled = newBootstrapEnabled
                        activeBootstrapIps = newBootstrapIps
                        dynamicBlockResponseTracker.clear()
                        dbComponents.dnsCache.updatePolicy(newCachePolicy)
                        if (goSyncError != null) {
                            onNotificationRefresh()
                            Log.w(TAG, "Failed to refresh Go DNS upstream; keeping current snapshot", goSyncError)
                            return@fold
                        }
                        activeResolutionMode = newResolutionMode
                        activeProviders = updatedProviders
                        onNotificationRefresh()
                        Log.i(
                            TAG,
                            "Runtime config refreshed: $reason, providers=${updatedProviders.size}"
                        )
                    },
                    onFailure = { error ->
                        runCatching {
                            tunnelManager.goInspectionTunnel?.syncDnsConfig(
                                providers = oldProviders,
                                resolutionMode = activeResolutionMode,
                                blockResponseMode = newBlockResponseMode,
                                dynamicBlockResponseConfig = newDynamicBlockResponseConfig,
                                cachePolicy = newCachePolicy,
                                bootstrapEnabled = newBootstrapEnabled,
                                bootstrapIps = newBootstrapIps
                            )
                        }.onFailure { syncError ->
                            Log.w(TAG, "Failed to sync Go DNS response policy", syncError)
                        }
                        activeDnsCachePolicy = newCachePolicy
                        activeResolutionMode = newResolutionMode
                        activeBlockResponseMode = newBlockResponseMode
                        activeDynamicBlockResponseConfig = newDynamicBlockResponseConfig
                        activeBootstrapEnabled = newBootstrapEnabled
                        activeBootstrapIps = newBootstrapIps
                        dynamicBlockResponseTracker.clear()
                        dbComponents.dnsCache.updatePolicy(newCachePolicy)
                        onNotificationRefresh()
                        Log.w(TAG, "Failed to refresh DNS resolvers; keeping current snapshot", error)
                    }
                )

                runCatching { dbComponents.blockListManager.refreshCache() }
                    .onFailure { Log.w(TAG, "Failed to refresh block list cache", it) }
                runCatching { dbComponents.allowListManager.refreshCache() }
                    .onFailure { Log.w(TAG, "Failed to refresh allow list cache", it) }
                runCatching { dbComponents.rewriteRuleManager.refreshCache() }
                    .onSuccess { tunnelManager.goInspectionTunnel?.updateRewriteRules() }
                    .onFailure { Log.w(TAG, "Failed to refresh rewrite rule cache", it) }
                tunnelManager.goInspectionTunnel?.pushRuleSnapshot()
            }
        }
    }

    fun refreshAppExclusions() {
        if (tunnelManager.vpnInterface == null) {
            Log.d(TAG, "Skip application exclusion refresh because VPN is not running")
            return
        }

        scope.launch {
            refreshMutex.withLock {
                onRestartVpn()
            }
        }
    }

    fun refreshAppAllowlist() {
        if (tunnelManager.vpnInterface == null) {
            Log.d(TAG, "Skip application allowlist refresh because VPN is not running")
            return
        }

        scope.launch {
            refreshMutex.withLock {
                buildAndSyncAppAllowlist()
            }
        }
    }

    /**
     * Build the merged app-allowlist rule map (computation only, no sync).
     *
     * An app enters network-layer default-deny mode when it has an enabled
     * full-block rule ("默认拦截全部外联", stored in block_rule as pattern='*'
     * with $app=scope, e.g. `*$app=com.example`). Such apps are always isolated
     * regardless of the master "启用单应用域名放行" switch, because the full-block
     * rule itself is the user's explicit intent to cut the app off.
     *
     * Merges two sources of allowed domains per app:
     *  1. Manually-added domains from SharedPreferences ("专属放行域名",
     *     kept for backward compatibility)
     *  2. Per-app allow rules from AllowRuleDao, including subscription-imported
     *     rules like `@@||fqnovel.com^$app=com.dragon.read.oversea.gp`
     *
     * Semantics of the master switch:
     *  - Switch ON: every app in the manual allowlist map is isolated too
     *    (original "单应用域名放行" behavior), and full-block apps are included
     *    with their merged allowlist (possibly empty = deny everything).
     *  - Switch OFF: only full-block apps are isolated; the manual-only apps
     *    are not touched.
     *
     * Only apps that are candidates are queried (full-block scopes + manual
     * map keys), so the entire allow-rule table is not scanned for apps the
     * user never configured.
     *
     * Shared by the VPN startup path (DnsVpnService) and the runtime refresh
     * path. Must be called from within a coroutine (DAO methods are suspend).
     */
    suspend fun buildMergedAppAllowlist(): Map<String, Set<String>> {
        val merged = AppRulesSettingsStore.getAppAllowlistRuleMap(context).toMutableMap()
        runCatching {
            val db = com.haoze.diting.data.AppDatabase.getInstance(context)
            val blockDao = db.blockRuleDao()
            val allowDao = db.allowRuleDao()
            // Global DNS whitelist rules (appScope empty, e.g. whitelist-tab
            // additions and subscription imports without `$app=`) apply to every
            // full-block app: whitelisted domains must stay reachable under
            // default-deny isolation.
            val globalAllow = allowDao.enabledGlobalPatterns().toSet()
            // Find every app that has an enabled full-block rule (`*$app=pkg`).
            // appScope can be "pkg" or a "pkg1|pkg2" combination, with an optional
            // "~" prefix meaning inverted, so split and normalize before probing.
            // (Loop instead of filter{} because hasEnabledFullBlockForApp is suspend.)
            val candidates = blockDao.appScopes()
                .flatMap { scope -> scope.split('|') }
                .map { it.trim().removePrefix("~") }
                .filter { it.isNotEmpty() }
                .distinct()
            val fullBlockPkgs = mutableSetOf<String>()
            for (pkg in candidates) {
                if (blockDao.hasEnabledFullBlockForApp(pkg)) {
                    fullBlockPkgs.add(pkg)
                }
            }
            for (pkg in fullBlockPkgs) {
                // Keep the key even when both sources are empty so the Go engine
                // sees the UID as selected and applies default-deny.
                val manual = merged[pkg] ?: emptySet()
                val dnsAllow = allowDao.allByAppScope(pkg)
                    .filter { it.enabled }
                    .map { it.pattern }
                    .toSet()
                merged[pkg] = manual + dnsAllow + globalAllow
            }
            // Master switch OFF: manual-only apps (no full-block rule) must not
            // be isolated. Full-block apps remain regardless of the switch.
            if (!AppRulesSettingsStore.isAppAllowlistEnabled(context)) {
                merged.keys.retainAll(fullBlockPkgs)
            }
        }.onFailure { Log.w(TAG, "Failed to merge AllowRuleDao into app allowlist", it) }
        return merged
    }

    /**
     * Build the merged app-allowlist rule map and push it to the Go engine.
     * See [buildMergedAppAllowlist] for the merge semantics.
     */
    private suspend fun buildAndSyncAppAllowlist() {
        val tunnel = tunnelManager.goInspectionTunnel ?: return
        tunnel.syncAppAllowlist(buildMergedAppAllowlist())
    }

    companion object {
        private const val TAG = "DnsVpnRuntimeConfigManager"
    }
}
