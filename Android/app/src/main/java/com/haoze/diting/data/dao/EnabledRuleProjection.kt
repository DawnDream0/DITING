package com.haoze.diting.data.dao

/** Projection of an enabled rule (rule + source + modifier fields) shared by the allow/block rule caches. */
data class EnabledRule(
    val pattern: String,
    val source: String,
    val important: Boolean = false,
    val appScope: String? = null,
    val appInverted: Boolean = false,
    val isWildcard: Boolean = false,
    val denyallow: String? = null,
    val isRegex: Boolean = false,
    val dnsType: String? = null
)

/** Raw `appScope` value with the number of rules carrying it, used to build per-app rule counts in one pass. */
data class AppScopeCount(
    val appScope: String,
    val ruleCount: Int
)

data class EnabledRuleKeyset(
    val id: Long,
    val pattern: String,
    val source: String,
    val important: Boolean = false,
    val appScope: String? = null,
    val appInverted: Boolean = false,
    val isWildcard: Boolean = false,
    val denyallow: String? = null,
    val isRegex: Boolean = false,
    val dnsType: String? = null
) {
    fun toEnabledRule(): EnabledRule = EnabledRule(
        pattern = pattern,
        source = source,
        important = important,
        appScope = appScope,
        appInverted = appInverted,
        isWildcard = isWildcard,
        denyallow = denyallow,
        isRegex = isRegex,
        dnsType = dnsType
    )
}
