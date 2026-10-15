package com.haoze.diting.normal.ui

import com.haoze.diting.ui.*
import com.haoze.diting.ui.dashboard.*
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.haoze.diting.data.AppDatabase
import com.haoze.diting.data.dao.AppScopeCount
import com.haoze.diting.data.entity.AllowRuleEntity
import com.haoze.diting.data.entity.BlockRuleEntity
import com.haoze.diting.data.entity.RuleScope
import com.haoze.diting.ui.apprule.AppRuleItem
import com.haoze.diting.ui.settings.AppRulesSettingsStore
import com.haoze.diting.core.rule.AdGuardRuleParser
import com.haoze.diting.core.rule.AllowListManager
import com.haoze.diting.core.rule.BlockListManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppRuleViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val blockRuleDao = db.blockRuleDao()
    private val allowRuleDao = db.allowRuleDao()
    private val scope = RuleScope.DNS

    private val blockListManager = BlockListManager(blockRuleDao, scope = scope)
    private val allowListManager = AllowListManager(allowRuleDao, scope = scope)

    private val _selectedApp = MutableStateFlow<InstalledApp?>(null)
    val selectedApp: StateFlow<InstalledApp?> = _selectedApp.asStateFlow()

    private val _appRuleCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val appRuleCounts: StateFlow<Map<String, Int>> = _appRuleCounts.asStateFlow()

    private val _appAllowlistMap = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val appAllowlistMap: StateFlow<Map<String, Set<String>>> = _appAllowlistMap.asStateFlow()

    private val _isAppAllowlistMasterEnabled = MutableStateFlow(false)
    val isAppAllowlistMasterEnabled: StateFlow<Boolean> = _isAppAllowlistMasterEnabled.asStateFlow()

    private val _selectedAppAllowlistDomains = MutableStateFlow<Set<String>>(emptySet())
    val selectedAppAllowlistDomains: StateFlow<Set<String>> = _selectedAppAllowlistDomains.asStateFlow()

    private val _fullBlockEnabled = MutableStateFlow(false)
    val fullBlockEnabled: StateFlow<Boolean> = _fullBlockEnabled.asStateFlow()

    private val _blockRules = MutableStateFlow<List<AppRuleItem>>(emptyList())
    val blockRules: StateFlow<List<AppRuleItem>> = _blockRules.asStateFlow()

    private val _allowRules = MutableStateFlow<List<AppRuleItem>>(emptyList())
    val allowRules: StateFlow<List<AppRuleItem>> = _allowRules.asStateFlow()

    private val _blockTotalCount = MutableStateFlow(0)
    val blockTotalCount: StateFlow<Int> = _blockTotalCount.asStateFlow()

    private val _allowTotalCount = MutableStateFlow(0)
    val allowTotalCount: StateFlow<Int> = _allowTotalCount.asStateFlow()

    private val _blockHasMore = MutableStateFlow(false)
    val blockHasMore: StateFlow<Boolean> = _blockHasMore.asStateFlow()

    private val _allowHasMore = MutableStateFlow(false)
    val allowHasMore: StateFlow<Boolean> = _allowHasMore.asStateFlow()

    // Paging bookkeeping for the currently selected app. Guarded by the single
    // viewModelScope dispatcher usage, but the package check makes a late page
    // from the previous app harmless.
    private var loadedPackage: String? = null
    private var blockOffset = 0
    private var allowOffset = 0
    private var blockLoading = false
    private var allowLoading = false

    init {
        loadAllData()
    }

    fun loadAllData() {
        loadAppRuleCounts()
        loadAllowlistData()
    }

    fun loadAllowlistData() {
        val context = getApplication<Application>()
        _isAppAllowlistMasterEnabled.value = AppRulesSettingsStore.isAppAllowlistEnabled(context)
        _appAllowlistMap.value = AppRulesSettingsStore.getAppAllowlistRuleMap(context)
    }

    fun setMasterAllowlistEnabled(enabled: Boolean) {
        val context = getApplication<Application>()
        _isAppAllowlistMasterEnabled.value = enabled
        AppRulesSettingsStore.setAppAllowlistEnabled(context, enabled)
        RuntimeDnsSettingsRefresher.refreshAppExclusionsIfRunning(context)
    }

    fun selectApp(app: InstalledApp?) {
        _selectedApp.value = app
        if (app != null) {
            loadRulesForApp(app.packageName)
        } else {
            loadedPackage = null
            _blockRules.value = emptyList()
            _allowRules.value = emptyList()
            _blockTotalCount.value = 0
            _allowTotalCount.value = 0
            _blockHasMore.value = false
            _allowHasMore.value = false
            _selectedAppAllowlistDomains.value = emptySet()
            _fullBlockEnabled.value = false
            loadAllData()
        }
    }

    fun loadAppRuleCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            // One grouped scan per table instead of two full-table scans per
            // installed app (previously ~2N unindexable LIKE scans, which could
            // occupy the IO thread for minutes on a large rule set).
            val counts = mutableMapOf<String, Int>()
            fun accumulate(rows: List<AppScopeCount>) {
                for (row in rows) {
                    for (part in row.appScope.split('|')) {
                        val pkg = part.trim().removePrefix("~")
                        if (pkg.isNotEmpty()) {
                            counts[pkg] = (counts[pkg] ?: 0) + row.ruleCount
                        }
                    }
                }
            }
            accumulate(blockRuleDao.appScopeCounts())
            accumulate(allowRuleDao.appScopeCounts())

            withContext(Dispatchers.Main) {
                _appRuleCounts.value = counts
            }
        }
    }

    fun loadRulesForApp(packageName: String) {
        val context = getApplication<Application>()
        _selectedAppAllowlistDomains.value = AppRulesSettingsStore.getAppAllowlistDomainsForApp(context, packageName)

        // Reset paging for the newly selected app.
        loadedPackage = packageName
        blockOffset = 0
        allowOffset = 0

        viewModelScope.launch(Dispatchers.IO) {
            // Only the first page of each list is loaded here; further pages are
            // fetched on demand by loadMoreBlockRules / loadMoreAllowRules.
            val blocks = blockRuleDao.pagedByAppScope(packageName, PAGE_SIZE, 0)
            val allows = allowRuleDao.pagedByAppScope(packageName, PAGE_SIZE, 0)
            val blockTotal = blockRuleDao.countByAppScope(packageName)
            val allowTotal = allowRuleDao.countByAppScope(packageName)
            val hasFullBlock = blockRuleDao.hasEnabledFullBlockForApp(packageName)

            val blockItems = buildBlockItems(blocks)
            val allowItems = buildAllowItems(allows)
            // Legacy manual allowlist domains ("专属放行域名", SharedPreferences)
            // are kept for backward compatibility: they still take effect at the
            // network layer (merged by buildMergedAppAllowlist) and are shown at
            // the top of the unified whitelist so the user can see and remove them.
            val legacyManualItems = buildLegacyManualItems(_selectedAppAllowlistDomains.value)

            if (loadedPackage != packageName) return@launch
            blockOffset = blocks.size
            allowOffset = allows.size
            withContext(Dispatchers.Main) {
                if (loadedPackage != packageName) return@withContext
                _blockRules.value = blockItems
                _allowRules.value = legacyManualItems + allowItems
                _blockTotalCount.value = blockTotal
                _allowTotalCount.value = legacyManualItems.size + allowTotal
                _blockHasMore.value = blocks.size == PAGE_SIZE
                _allowHasMore.value = allows.size == PAGE_SIZE
                _fullBlockEnabled.value = hasFullBlock
            }
        }
    }

    fun loadMoreBlockRules() = loadMore(isAllow = false)

    fun loadMoreAllowRules() = loadMore(isAllow = true)

    private fun loadMore(isAllow: Boolean) {
        val pkg = loadedPackage ?: return
        if (isAllow) {
            if (allowLoading || !_allowHasMore.value) return
            allowLoading = true
        } else {
            if (blockLoading || !_blockHasMore.value) return
            blockLoading = true
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (isAllow) {
                    val page = allowRuleDao.pagedByAppScope(pkg, PAGE_SIZE, allowOffset)
                    val items = buildAllowItems(page)
                    if (loadedPackage != pkg) return@launch
                    withContext(Dispatchers.Main) {
                        if (loadedPackage != pkg) return@withContext
                        _allowRules.value = _allowRules.value + items
                        allowOffset += page.size
                        _allowHasMore.value = page.size == PAGE_SIZE
                    }
                } else {
                    val page = blockRuleDao.pagedByAppScope(pkg, PAGE_SIZE, blockOffset)
                    val items = buildBlockItems(page)
                    if (loadedPackage != pkg) return@launch
                    withContext(Dispatchers.Main) {
                        if (loadedPackage != pkg) return@withContext
                        _blockRules.value = _blockRules.value + items
                        blockOffset += page.size
                        _blockHasMore.value = page.size == PAGE_SIZE
                    }
                }
            } finally {
                if (isAllow) allowLoading = false else blockLoading = false
            }
        }
    }

    /**
     * Resolves the subscription-name lookups for a page's source rows. Only the
     * subscription ids actually referenced by this page are queried, instead of
     * loading the whole `subscription` table.
     */
    private suspend fun subscriptionNamesFor(sources: List<RuleSourceRow>): Map<Long, String> {
        val subIds = sources.mapNotNull { it.subscriptionId() }.distinct()
        if (subIds.isEmpty()) return emptyMap()
        return db.subscriptionDao().idNameByIds(subIds).associate { it.id to it.name }
    }

    /** Shared classification of a rule's sources into user / subscription, used by both lists. */
    private fun classify(sources: List<RuleSourceRow>, subscriptionNames: Map<Long, String>): SourceClassification {
        val isUser = sources.isEmpty() || sources.any { it.source == "useradd" || !it.source.startsWith("sub_") }
        val subSource = sources.firstOrNull { it.source.startsWith("sub_") }
        return SourceClassification(
            isUserRule = isUser,
            isSubscription = subSource != null,
            subscriptionName = subSource?.subscriptionId()?.let { subscriptionNames[it] }
        )
    }

    private suspend fun buildBlockItems(entities: List<BlockRuleEntity>): List<AppRuleItem> {
        if (entities.isEmpty()) return emptyList()
        val sources = blockRuleDao.sourcesForRuleIds(entities.map { it.id })
        val names = subscriptionNamesFor(sources.map { RuleSourceRow(it.ruleId, it.source) })
        val byRule = sources.groupBy { it.ruleId }
        return entities.map { e ->
            val cls = classify(byRule[e.id].orEmpty().map { RuleSourceRow(it.ruleId, it.source) }, names)
            AppRuleItem(
                id = e.id,
                pattern = e.pattern,
                rawLine = e.rawLine,
                enabled = e.enabled,
                important = e.important,
                isWildcard = e.isWildcard,
                isAllow = false,
                appInverted = e.appInverted,
                isUserRule = cls.isUserRule,
                isSubscription = cls.isSubscription,
                subscriptionName = cls.subscriptionName
            )
        }
    }

    private suspend fun buildAllowItems(entities: List<AllowRuleEntity>): List<AppRuleItem> {
        if (entities.isEmpty()) return emptyList()
        val sources = allowRuleDao.sourcesForRuleIds(entities.map { it.id })
        val names = subscriptionNamesFor(sources.map { RuleSourceRow(it.ruleId, it.source) })
        val byRule = sources.groupBy { it.ruleId }
        return entities.map { e ->
            val cls = classify(byRule[e.id].orEmpty().map { RuleSourceRow(it.ruleId, it.source) }, names)
            AppRuleItem(
                id = e.id,
                pattern = e.pattern,
                rawLine = e.rawLine,
                enabled = e.enabled,
                important = e.important,
                isWildcard = e.isWildcard,
                isAllow = true,
                appInverted = e.appInverted,
                isUserRule = cls.isUserRule,
                isSubscription = cls.isSubscription,
                subscriptionName = cls.subscriptionName
            )
        }
    }

    /**
     * Legacy manual allowlist domains (the old "专属放行域名" feature, stored in
     * SharedPreferences) as read-only whitelist rows. They are still merged by
     * buildMergedAppAllowlist at the network layer, so they keep working; the
     * negative ids mark them as non-DAO rows so toggle/delete are routed to the
     * SharedPreferences store instead of the allow-rule table.
     */
    private fun buildLegacyManualItems(domains: Set<String>): List<AppRuleItem> =
        domains.sorted().mapIndexed { index, domain ->
            AppRuleItem(
                id = -1L - index,
                pattern = domain,
                rawLine = domain,
                enabled = true,
                important = false,
                isWildcard = false,
                isAllow = true,
                appInverted = false,
                isUserRule = true,
                isSubscription = false,
                subscriptionName = null
            )
        }

    private data class SourceClassification(
        val isUserRule: Boolean,
        val isSubscription: Boolean,
        val subscriptionName: String?
    )

    private fun RuleSourceRow.subscriptionId(): Long? =
        if (source.startsWith("sub_")) source.removePrefix("sub_").toLongOrNull() else null

    fun removeAllowlistDomain(domain: String) {
        val app = _selectedApp.value ?: return
        val context = getApplication<Application>()
        val current = _selectedAppAllowlistDomains.value.toMutableSet()
        current.remove(domain)
        _selectedAppAllowlistDomains.value = current
        AppRulesSettingsStore.setAppAllowlistDomainsForApp(context, app.packageName, current)
        RuntimeDnsSettingsRefresher.refreshAppAllowlistIfRunning(context)
        loadAllowlistData()
        loadRulesForApp(app.packageName)
    }

    fun clearAllAllowlistRules() {
        val context = getApplication<Application>()
        AppRulesSettingsStore.setAppAllowlistRuleMap(context, emptyMap())
        _appAllowlistMap.value = emptyMap()
        _selectedAppAllowlistDomains.value = emptySet()
        RuntimeDnsSettingsRefresher.refreshAppAllowlistIfRunning(context)
    }

    fun toggleFullBlockTemplate(enabled: Boolean, onResult: (String) -> Unit = {}) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pkg = app.packageName
            val existingId = blockRuleDao.fullBlockRuleIdForApp(pkg)
            val context = getApplication<Application>()

            if (enabled) {
                if (existingId != null) {
                    blockRuleDao.setEnabled(existingId, true)
                    blockListManager.syncCachedPattern("*")
                } else {
                    val ruleStr = "*\$app=$pkg"
                    blockListManager.addRule(ruleStr)
                }
                RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "block", "*", scope)
                val currentAllowlist = AppRulesSettingsStore.getAppAllowlistDomainsForApp(context, pkg)
                AppRulesSettingsStore.setAppAllowlistDomainsForApp(context, pkg, currentAllowlist)
                RuntimeDnsSettingsRefresher.refreshAppAllowlistIfRunning(context)
            } else {
                if (existingId != null) {
                    blockRuleDao.deleteById(existingId)
                    blockListManager.syncCachedPattern("*")
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "block", "*", scope)
                }
                val currentAllowlist = AppRulesSettingsStore.getAppAllowlistDomainsForApp(context, pkg)
                if (currentAllowlist.isEmpty()) {
                    AppRulesSettingsStore.removeAppAllowlistForApp(context, pkg)
                    RuntimeDnsSettingsRefresher.refreshAppAllowlistIfRunning(context)
                }
            }
            loadRulesForApp(pkg)
            loadAppRuleCounts()
            loadAllowlistData()
            withContext(Dispatchers.Main) {
                onResult(if (enabled) "已开启全外联拦截模式" else "已关闭全外联拦截模式")
            }
        }
    }

    fun addAppRule(
        pattern: String,
        isAllow: Boolean,
        important: Boolean = false,
        isWildcard: Boolean = false,
        onResult: (String) -> Unit
    ) {
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val pkg = app.packageName
            val trimmed = pattern.trim()
            val cleanPattern = when {
                trimmed.startsWith("@@") -> trimmed.removePrefix("@@").trim()
                trimmed.startsWith("||") -> trimmed.removePrefix("||").trimEnd('^').trim()
                else -> trimmed.trimEnd('^').trim()
            }
            val ruleString = buildString {
                if (isAllow) append("@@")
                if (cleanPattern == "*") {
                    append("*")
                } else {
                    append("||").append(cleanPattern).append("^")
                }
                append("\$app=").append(pkg)
                if (important) append(",important")
            }

            val context = getApplication<Application>()
            val success = if (isAllow) {
                val added = allowListManager.addRule(ruleString)
                if (added) {
                    val parsed = AdGuardRuleParser.parseAllowLine(ruleString)
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "allow", parsed?.pattern ?: cleanPattern, scope)
                }
                added
            } else {
                val added = blockListManager.addRule(ruleString)
                if (added) {
                    val parsed = AdGuardRuleParser.parseLine(ruleString)
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "block", parsed?.pattern ?: cleanPattern, scope)
                }
                added
            }

            loadRulesForApp(pkg)
            loadAppRuleCounts()
            withContext(Dispatchers.Main) {
                onResult(if (success) "规则添加成功" else "规则格式无效或已存在")
            }
        }
    }

    fun toggleRule(id: Long, isAllow: Boolean, enabled: Boolean) {
        // Legacy manual allowlist rows (negative ids) are always enabled and
        // have no DAO backing; toggling them is a no-op.
        if (id < 0) return
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            if (isAllow) {
                val pattern = allowListManager.toggleRule(id, enabled)
                if (pattern != null) {
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "allow", pattern, scope)
                }
            } else {
                val pattern = blockListManager.toggleRule(id, enabled)
                if (pattern != null) {
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "block", pattern, scope)
                }
            }
            loadRulesForApp(app.packageName)
            loadAppRuleCounts()
        }
    }

    fun deleteRule(id: Long, isAllow: Boolean) {
        // Legacy manual allowlist rows (negative ids) live in SharedPreferences,
        // not in the allow-rule table; deleting them clears the old store entry.
        if (id < 0) {
            if (isAllow) {
                val domain = _allowRules.value.firstOrNull { it.id == id }?.pattern
                if (domain != null) removeAllowlistDomain(domain)
            }
            return
        }
        val app = _selectedApp.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val isSubscriptionOnly = if (isAllow) {
                val sources = allowRuleDao.sourcesForRuleIds(listOf(id))
                sources.isNotEmpty() && sources.all { it.source.startsWith("sub_") }
            } else {
                val sources = blockRuleDao.sourcesForRuleIds(listOf(id))
                sources.isNotEmpty() && sources.all { it.source.startsWith("sub_") }
            }
            if (isSubscriptionOnly) {
                return@launch
            }
            if (isAllow) {
                val pattern = allowListManager.deleteRule(id)
                if (pattern != null) {
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "allow", pattern, scope)
                }
            } else {
                val pattern = blockListManager.deleteRule(id)
                if (pattern != null) {
                    RuntimeDnsSettingsRefresher.syncRuleIfRunning(context, "block", pattern, scope)
                }
            }
            loadRulesForApp(app.packageName)
            loadAppRuleCounts()
        }
    }

    companion object {
        private const val PAGE_SIZE = 100
    }
}

/** Uniform view of a rule-source row (block vs allow) for shared classification. */
private data class RuleSourceRow(val ruleId: Long, val source: String)

