package com.haoze.diting.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.haoze.diting.data.RuleDataset
import com.haoze.diting.normal.ui.MainScreen
import com.haoze.diting.ui.MainViewModel
import com.haoze.diting.ui.Routes

/**
 * Dispatches the main screen content. The simplified build only supports
 * Normal mode, so this directly hosts the Normal-mode main screen.
 */
@Composable
fun MainModeDispatcher(
    mainViewModel: MainViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onNavigateToSettings: (String, RuleDataset?) -> Unit,
    onNavigateToLogs: (RuleDataset?) -> Unit,
    onNavigateToLogRoute: (String, RuleDataset?) -> Unit,
    bottomBarRefreshRequested: Boolean,
    onBottomBarRefreshConsumed: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        MainScreen(
            onToggle = onToggleVpn,
            onNavigateToSettings = { onNavigateToSettings(Routes.SETTINGS, null) },
            onNavigateToLogs = { onNavigateToLogs(null) },
            onNavigateToProviderManagement = { onNavigateToSettings(Routes.PROVIDER_MANAGEMENT, null) },
            onNavigateToBootstrapSettings = { onNavigateToSettings(Routes.BOOTSTRAP_SETTINGS, null) },
            onNavigateToHomeProviderVisibility = { onNavigateToSettings(Routes.HOME_PROVIDER_VISIBILITY, null) },
            onNavigateToRaceModeSettings = { onNavigateToSettings(Routes.RACE_MODE_PROVIDERS, null) },
            onNavigateToBlockedApps = { onNavigateToSettings(Routes.BLOCKED_APPS, null) },
            onNavigateToAppAllowlist = { onNavigateToSettings(Routes.APP_ALLOWLIST, null) },
            onNavigateToExcludedApps = { onNavigateToSettings(Routes.EXCLUDED_APPS, null) },
            onNavigateToAppearanceSettings = { onNavigateToSettings(Routes.APPEARANCE_SETTINGS, null) },
            onNavigateToRuleControl = { onNavigateToSettings(Routes.RULE_CONTROL, null) },
            onNavigateToBlacklist = { onNavigateToSettings(Routes.BLACKLIST_MANAGEMENT, null) },
            onNavigateToWhitelist = { onNavigateToSettings(Routes.WHITELIST_MANAGEMENT, null) },
            onNavigateToRewriteList = { onNavigateToSettings(Routes.REWRITELIST_MANAGEMENT, null) },
            onNavigateToAppRules = { onNavigateToSettings(Routes.APP_RULE_MANAGEMENT, null) },
            onNavigateToHttpInspection = { onNavigateToSettings(Routes.HTTP_INSPECTION_SETTINGS, null) },
            onNavigateToLogRetentionSettings = { onNavigateToSettings(Routes.LOG_RETENTION_SETTINGS, null) },
            onNavigateToNetworkTools = { onNavigateToSettings(Routes.NETWORK_TOOLS, null) },
            onNavigateToHomeProviderVisibilityFromFeatureHub = { onNavigateToSettings(Routes.HOME_PROVIDER_VISIBILITY, null) },
            onNavigateToAbout = { onNavigateToSettings(Routes.ABOUT, null) },
            onNavigateToSponsor = { onNavigateToSettings(Routes.SPONSOR, null) },
            onNavigateToSponsorList = { onNavigateToSettings(Routes.SPONSOR_LIST, null) },
            onNavigateToCoBuilderList = { onNavigateToSettings(Routes.CO_BUILDER_LIST, null) },
            onNavigateToAppUpdate = { onNavigateToSettings(Routes.APP_UPDATE, null) },
            onNavigateToDataManagement = { onNavigateToSettings(Routes.CONFIG_TRANSFER, null) },
            onNavigateToTrafficStats = { onNavigateToSettings(Routes.APP_TRAFFIC_STATS, null) },
            onNavigateToHiddenFeatures = { onNavigateToSettings(Routes.HIDDEN_FEATURES, null) },
            onNavigateToCacheSettings = { onNavigateToSettings(Routes.CACHE_SETTINGS, null) },
            onNavigateToOutboundProxy = { onNavigateToSettings(Routes.OUTBOUND_PROXY_SETTINGS, null) },
            onNavigateToDataCleanup = { onNavigateToSettings(Routes.DATA_CLEANUP, null) },
            onNavigateToAgentApiSettings = { onNavigateToSettings(Routes.AGENT_API_SETTINGS, null) },
            onNavigateToLogRoute = { r -> onNavigateToLogRoute(r, null) },
            onNavigateToSettingsRoute = { r -> onNavigateToSettings(r, null) },
            bottomBarRefreshRequested = bottomBarRefreshRequested,
            onBottomBarRefreshConsumed = onBottomBarRefreshConsumed,
            viewModel = mainViewModel,
            modifier = Modifier.fillMaxSize()
        )
    }
}
