package com.haoze.dnssr.vpn

import android.util.Log
import com.haoze.dnssr.data.dao.BlockRuleDao
import com.haoze.dnssr.data.dao.EnabledRule
import com.haoze.dnssr.data.entity.RuleScope
import com.haoze.dnssr.util.forEachKeysetPage
import java.io.File

/**
 * Result of loading all block rules (custom and subscription).
 */
internal data class BlockRuleReloadResult(
    val customRules: Map<String, String>,
    val importantCustomRules: Map<String, String>,
    val customWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val importantCustomWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val customAppBuckets: Map<String, BlockAppRuleBucket>,
    val invertedCustomRules: List<InvertedBlockRule>,
    val subscriptionFallback: Map<String, String>,
    val importantSubscriptionFallback: Map<String, String>,
    val subscriptionWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val importantSubscriptionWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val subscriptionAppBuckets: Map<String, BlockAppRuleBucket>,
    val invertedSubscriptionRules: List<InvertedBlockRule>,
    val subscriptionIndex: MappedSubscriptionRuleIndex?,
    val importantSubscriptionIndex: MappedSubscriptionRuleIndex?
)

/**
 * Result of reloading only custom block rules.
 */
internal data class CustomRuleReloadResult(
    val customRules: Map<String, String>,
    val importantCustomRules: Map<String, String>,
    val customWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val importantCustomWildcards: List<Pair<AdGuardRuleParser.WildcardPattern, String>>,
    val customAppBuckets: Map<String, BlockAppRuleBucket>,
    val invertedCustomRules: List<InvertedBlockRule>
)

/**
 * Loads block rules from the database and compiles/loads the mmap index.
 */
internal object BlockRuleCacheLoader {
    private const val TAG = "BlockRuleCacheLoader"
    private const val INDEX_PAGE_SIZE = 2_000

    suspend fun loadAll(
        dao: BlockRuleDao,
        scope: RuleScope = RuleScope.DNS,
        forceRebuild: Boolean = false,
        indexFile: File? = null,
        importantIndexFile: File? = null
    ): BlockRuleReloadResult {
        val customResult = loadCustomRules(dao)

        val targetFile = indexFile
        val importantFile = importantIndexFile

        var mapped = targetFile?.let { file ->
            if (!forceRebuild && file.exists() && file.length() > 0) {
                runCatching { MappedSubscriptionRuleIndex.load(file) }
                    .onFailure { Log.w(TAG, "Existing subscription block index invalid, will recompile", it) }
                    .getOrNull()
            } else null
        }

        var importantMapped = importantFile?.let { file ->
            if (!forceRebuild && file.exists() && file.length() > 0) {
                runCatching { MappedSubscriptionRuleIndex.load(file) }
                    .onFailure { Log.w(TAG, "Existing important subscription block index invalid, will recompile", it) }
                    .getOrNull()
            } else null
        }

        if (mapped == null) {
            mapped = targetFile?.let { file ->
                runCatching {
                    MappedSubscriptionRuleIndex.compileAndLoad(file) { consume ->
                        dao.forEachSubscriptionRulePage(important = false) { rule ->
                            if (!rule.isWildcard && !rule.pattern.contains('*') && rule.appScope.isNullOrEmpty() && !rule.appInverted) {
                                consume(rule)
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Failed to compile subscription block index (${file.name})", e)
                }.getOrNull()
            }
        }

        if (importantMapped == null) {
            importantMapped = importantFile?.let { file ->
                runCatching {
                    MappedSubscriptionRuleIndex.compileAndLoad(file) { consume ->
                        dao.forEachSubscriptionRulePage(important = true) { rule ->
                            if (!rule.isWildcard && !rule.pattern.contains('*') && rule.appScope.isNullOrEmpty() && !rule.appInverted) {
                                consume(rule)
                            }
                        }
                    }
                }.onFailure { e ->
                    Log.e(TAG, "Failed to compile important subscription block index (${file.name})", e)
                }.getOrNull()
            }
        }

        val subFallback = HashMap<String, String>()
        val importantSubFallback = HashMap<String, String>()
        val subWc = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()
        val importantSubWc = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()
        val subBucketsMap = HashMap<String, MutableAppBucket>()
        val subInvertedList = mutableListOf<InvertedBlockRule>()

        fun processSubscriptionRule(entry: EnabledRule) {
            val isWc = entry.isWildcard || entry.pattern.contains('*')
            val wcPattern = if (isWc) AdGuardRuleParser.WildcardPattern(entry.pattern) else null

            if (entry.appInverted && !entry.appScope.isNullOrEmpty()) {
                val excluded = entry.appScope.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                subInvertedList.add(InvertedBlockRule(entry.pattern, entry.source, entry.important, excluded, wcPattern))
            } else if (!entry.appScope.isNullOrEmpty()) {
                val pkgs = entry.appScope.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                for (pkg in pkgs) {
                    val bucket = subBucketsMap.getOrPut(pkg) { MutableAppBucket() }
                    if (isWc && wcPattern != null) {
                        if (entry.important) bucket.importantWildcards.add(wcPattern to entry.source)
                        else bucket.wildcards.add(wcPattern to entry.source)
                    } else {
                        if (entry.important) bucket.importantExact[entry.pattern] = entry.source
                        else bucket.exact[entry.pattern] = entry.source
                    }
                }
            } else if (isWc && wcPattern != null) {
                if (entry.important) importantSubWc.add(wcPattern to entry.source)
                else subWc.add(wcPattern to entry.source)
            } else {
                if (entry.important) {
                    if (importantMapped == null) importantSubFallback[entry.pattern] = entry.source
                } else {
                    if (mapped == null) subFallback[entry.pattern] = entry.source
                }
            }
        }

        if (mapped != null && importantMapped != null) {
            dao.enabledSpecialSubscriptionRules().forEach(::processSubscriptionRule)
        } else {
            dao.forEachSubscriptionRulePage(important = false, ::processSubscriptionRule)
            dao.forEachSubscriptionRulePage(important = true, ::processSubscriptionRule)
        }

        return BlockRuleReloadResult(
            customRules = customResult.customRules,
            importantCustomRules = customResult.importantCustomRules,
            customWildcards = customResult.customWildcards,
            importantCustomWildcards = customResult.importantCustomWildcards,
            customAppBuckets = customResult.customAppBuckets,
            invertedCustomRules = customResult.invertedCustomRules,
            subscriptionFallback = subFallback,
            importantSubscriptionFallback = importantSubFallback,
            subscriptionWildcards = subWc,
            importantSubscriptionWildcards = importantSubWc,
            subscriptionAppBuckets = subBucketsMap.mapValues { it.value.toImmutable() },
            invertedSubscriptionRules = subInvertedList,
            subscriptionIndex = mapped,
            importantSubscriptionIndex = importantMapped
        )
    }

    suspend fun loadCustomRules(dao: BlockRuleDao): CustomRuleReloadResult {
        val customRuleEntries = dao.enabledCustomRules()

        val custom = HashMap<String, String>()
        val importantCustom = HashMap<String, String>()
        val customWc = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()
        val importantCustomWc = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()
        val customBucketsMap = HashMap<String, MutableAppBucket>()
        val customInvertedList = mutableListOf<InvertedBlockRule>()

        for (entry in customRuleEntries) {
            val isWc = entry.isWildcard || entry.pattern.contains('*')
            val wcPattern = if (isWc) AdGuardRuleParser.WildcardPattern(entry.pattern) else null

            if (entry.appInverted && !entry.appScope.isNullOrEmpty()) {
                val excluded = entry.appScope.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
                customInvertedList.add(InvertedBlockRule(entry.pattern, entry.source, entry.important, excluded, wcPattern))
            } else if (!entry.appScope.isNullOrEmpty()) {
                val pkgs = entry.appScope.split('|').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                for (pkg in pkgs) {
                    val bucket = customBucketsMap.getOrPut(pkg) { MutableAppBucket() }
                    if (isWc && wcPattern != null) {
                        if (entry.important) bucket.importantWildcards.add(wcPattern to entry.source)
                        else bucket.wildcards.add(wcPattern to entry.source)
                    } else {
                        if (entry.important) bucket.importantExact[entry.pattern] = entry.source
                        else bucket.exact[entry.pattern] = entry.source
                    }
                }
            } else {
                if (isWc && wcPattern != null) {
                    if (entry.important) importantCustomWc.add(wcPattern to entry.source)
                    else customWc.add(wcPattern to entry.source)
                } else {
                    if (entry.important) importantCustom[entry.pattern] = entry.source
                    else custom[entry.pattern] = entry.source
                }
            }
        }

        return CustomRuleReloadResult(
            customRules = custom,
            importantCustomRules = importantCustom,
            customWildcards = customWc,
            importantCustomWildcards = importantCustomWc,
            customAppBuckets = customBucketsMap.mapValues { it.value.toImmutable() },
            invertedCustomRules = customInvertedList
        )
    }

    private suspend fun BlockRuleDao.forEachSubscriptionRulePage(
        important: Boolean,
        consume: (EnabledRule) -> Unit
    ) = forEachKeysetPage(
        INDEX_PAGE_SIZE,
        { lastId, limit -> enabledSubscriptionRulesPageKeyset(important, limit, lastId) },
        { it.id },
        { consume(it.toEnabledRule()) }
    )
}

internal class MutableAppBucket {
    val exact = HashMap<String, String>()
    val importantExact = HashMap<String, String>()
    val wildcards = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()
    val importantWildcards = mutableListOf<Pair<AdGuardRuleParser.WildcardPattern, String>>()

    fun toImmutable(): BlockAppRuleBucket = BlockAppRuleBucket(
        exactRules = exact,
        importantExactRules = importantExact,
        wildcardRules = wildcards,
        importantWildcardRules = importantWildcards
    )
}
