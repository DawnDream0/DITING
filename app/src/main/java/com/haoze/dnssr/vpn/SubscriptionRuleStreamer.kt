package com.haoze.dnssr.vpn

import com.haoze.dnssr.data.entity.SubscriptionKind
import java.io.BufferedReader

/**
 * Streaming importer for categorized rules (block/allow/rewrite), shared by
 * subscription imports and local file imports: parses line by line, flushes
 * to the database every [chunkSize] rules, reports import progress, and
 * finally returns a [RuleImportSummary].
 *
 * The caller declares a subscription kind: a `domain` import keeps block and
 * allow rules and drops rewriting rules, a `hosts` import does the opposite.
 * Dropped rules are counted in [RuleImportSummary.typeSkippedCount] so the UI
 * can tell "this source holds nothing of the requested type" apart from
 * "the file is empty or malformed".
 */
internal class CategorizedRuleStreamImporter(
    private val blockListManager: BlockListManager,
    private val allowListManager: AllowListManager,
    private val rewriteRuleManager: RewriteRuleManager,
    private val chunkSize: Int = CHUNK_SIZE
) {
    companion object {
        const val CHUNK_SIZE = 1000
    }

    /**
     * [onEmpty] is invoked when there is not a single importable valid rule;
     * its argument tells whether the source did contain rules of the other
     * type. The caller decides which exception to throw.
     */
    suspend fun import(
        reader: BufferedReader,
        source: String,
        kind: String,
        enabled: Boolean,
        refreshCache: Boolean = false,
        onEmpty: (typeMismatchOnly: Boolean) -> Nothing,
        onProgress: (suspend (processed: Int) -> Unit)? = null
    ): RuleImportSummary {
        // Accept all categorized rules across all subscription kinds:
        // - Adblock domain lists can include $dnsrewrite= rules.
        // - Hosts files predominantly contain 0.0.0.0 / 127.0.0.1 sinkholes (parsed into blockRules).
        // This ensures zero valid rules are discarded due to arbitrary type boundaries.
        val acceptsDomain = true
        val acceptsHosts = true

        val blockBatch = ArrayList<AdGuardRuleParser.ParsedRule>(chunkSize)
        val allowBatch = ArrayList<AdGuardRuleParser.ParsedRule>(chunkSize)
        val rewriteBatch = ArrayList<RewriteRule>(chunkSize)
        var insertedBlock = 0
        var insertedAllow = 0
        var insertedRewrite = 0
        var parsedRules = 0
        var invalid = 0
        var unsupported = 0
        var typeSkipped = 0
        var processed = 0

        suspend fun flushBlock() {
            if (blockBatch.isEmpty()) return
            val inserted = blockListManager.addRulesBatch(blockBatch, source, chunkSize, enabled, refreshCache)
            insertedBlock += inserted
            processed += inserted
            blockBatch.clear()
            onProgress?.invoke(processed)
        }

        suspend fun flushAllow() {
            if (allowBatch.isEmpty()) return
            val inserted = allowListManager.addRulesBatch(allowBatch, source, chunkSize, enabled, refreshCache)
            insertedAllow += inserted
            processed += inserted
            allowBatch.clear()
            onProgress?.invoke(processed)
        }

        suspend fun flushRewrite() {
            if (rewriteBatch.isEmpty()) return
            val inserted = rewriteRuleManager.addRules(rewriteBatch, source, enabled, chunkSize, refreshCache)
            insertedRewrite += inserted
            processed += inserted
            rewriteBatch.clear()
            onProgress?.invoke(processed)
        }

        reader.useLines { lines ->
            lines.forEach { line ->
                val parsed = AdGuardRuleParser.parseCategorizedLine(line)
                invalid += parsed.invalidCount
                unsupported += parsed.unsupportedCount

                val lineRuleCount = parsed.blockRules.size + parsed.allowRules.size + parsed.rewriteRules.size
                parsedRules += lineRuleCount
                for (rule in parsed.blockRules) {
                    blockBatch += rule
                    if (blockBatch.size == chunkSize) flushBlock()
                }
                for (rule in parsed.allowRules) {
                    allowBatch += rule
                    if (allowBatch.size == chunkSize) flushAllow()
                }
                for (rule in parsed.rewriteRules) {
                    rewriteBatch += rule
                    if (rewriteBatch.size == chunkSize) flushRewrite()
                }
            }
        }
        flushBlock()
        flushAllow()
        flushRewrite()

        if (parsedRules == 0) onEmpty(typeSkipped > 0)
        val totalInserted = insertedBlock + insertedAllow + insertedRewrite
        return RuleImportSummary(
            blockCount = insertedBlock,
            allowCount = insertedAllow,
            rewriteCount = insertedRewrite,
            duplicateCount = (parsedRules - totalInserted).coerceAtLeast(0),
            invalidCount = invalid,
            unsupportedCount = unsupported,
            typeSkippedCount = typeSkipped
        )
    }
}
