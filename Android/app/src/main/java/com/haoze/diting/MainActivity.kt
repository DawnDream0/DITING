package com.haoze.diting

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.haoze.diting.main.MainModeDispatcher
import com.haoze.diting.main.MainNavigationCoordinator
import com.haoze.diting.main.MainStartupCoordinator
import com.haoze.diting.main.MainVpnController
import com.haoze.diting.permission.BatteryOptimizationHelper
import com.haoze.diting.ui.AppLanguageManager
import com.haoze.diting.ui.AppLanguageMode
import com.haoze.diting.ui.AppThemeSurface
import com.haoze.diting.ui.AppUpdateDialog
import com.haoze.diting.ui.InitialAgreementDialog
import com.haoze.diting.ui.MainViewModel
import com.haoze.diting.ui.PermissionDisclosureDialog
import com.haoze.diting.ui.RecentsPrivacyController
import com.haoze.diting.ui.background.CustomBackgroundManager
import com.haoze.diting.ui.mode.AppWorkMode
import com.haoze.diting.ui.mode.WorkModeStore
import com.haoze.diting.ui.settings.AppearanceSettingsStore
import com.haoze.diting.ui.settings.SystemSettingsStore
import com.haoze.diting.update.AppUpdateHost

class MainActivity : AppLocalizedActivity() {
    private var languageModeAtCreate = AppLanguageMode.SYSTEM
    private val appUpdateHost = AppUpdateHost(this)
    private val mainViewModel: MainViewModel by viewModels()

    private var mainThemeRefreshRequested by mutableStateOf(false)
    private var backgroundRefreshRequested by mutableStateOf(false)
    private var bottomBarRefreshRequested by mutableStateOf(false)

    private val startupCoordinator: MainStartupCoordinator = MainStartupCoordinator()

    private val vpnController: MainVpnController = MainVpnController(
        activity = this,
        getCurrentWorkMode = { AppWorkMode.NORMAL },
        onRefreshStatus = { mainViewModel.refreshStatus() }
    )

    private val navigationCoordinator: MainNavigationCoordinator = MainNavigationCoordinator(
        activity = this,
        onRefreshRuntimeDns = {
            mainViewModel.loadProviders()
            mainViewModel.refreshRuntimeConfigIfRunning()
        },
        onThemeChanged = { mainThemeRefreshRequested = true },
        onBackgroundChanged = { backgroundRefreshRequested = true },
        onBottomBarChanged = { bottomBarRefreshRequested = true }
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        languageModeAtCreate = AppLanguageManager.getMode(this)
        enableEdgeToEdge()
        startupCoordinator.performStartupChecks(this)
        applyRecentsPrivacySetting()

        // Simplified build always runs Normal mode.
        WorkModeStore.setAppWorkMode(this, AppWorkMode.NORMAL)
        WorkModeStore.setWorkModeSelected(this, true)

        setContent {
            var initialAgreementAccepted by remember {
                mutableStateOf(SystemSettingsStore.isInitialAgreementAccepted(this))
            }
            var themeMode by remember { mutableStateOf(AppearanceSettingsStore.getAppThemeMode(this)) }
            var colorStyle by remember { mutableStateOf(AppearanceSettingsStore.getThemeColorStyle(this)) }
            var backgroundEnabled by remember { mutableStateOf(AppearanceSettingsStore.isCustomBackgroundEnabled(this)) }
            var backgroundUri by remember { mutableStateOf(AppearanceSettingsStore.getCustomBackgroundUri(this)) }

            LaunchedEffect(mainThemeRefreshRequested, backgroundRefreshRequested) {
                if (mainThemeRefreshRequested) {
                    themeMode = AppearanceSettingsStore.getAppThemeMode(this@MainActivity)
                    colorStyle = AppearanceSettingsStore.getThemeColorStyle(this@MainActivity)
                    CustomBackgroundManager.applyWindowBackground(this@MainActivity)
                    mainThemeRefreshRequested = false
                }
                if (backgroundRefreshRequested) {
                    backgroundEnabled = AppearanceSettingsStore.isCustomBackgroundEnabled(this@MainActivity)
                    backgroundUri = AppearanceSettingsStore.getCustomBackgroundUri(this@MainActivity)
                    CustomBackgroundManager.applyWindowBackground(this@MainActivity)
                    backgroundRefreshRequested = false
                }
            }

            AppThemeSurface(
                themeMode = themeMode,
                colorStyle = colorStyle,
                backgroundEnabled = backgroundEnabled,
                backgroundUri = backgroundUri,
                modifier = Modifier.fillMaxSize()
            ) {
                if (!initialAgreementAccepted) {
                    InitialAgreementDialog(
                        onAccept = {
                            SystemSettingsStore.setInitialAgreementAccepted(this@MainActivity)
                            initialAgreementAccepted = true
                            initializeAcceptedExperience()
                        },
                        onDecline = {
                            vpnController.permissionDisclosure = null
                            startupCoordinator.declineInitialAgreement(this@MainActivity) {
                                vpnController.stopVpnService()
                            }
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainModeDispatcher(
                            mainViewModel = mainViewModel,
                            onToggleVpn = { isRunning -> vpnController.onToggleVpn(isRunning) },
                            onNavigateToSettings = { route, dataset ->
                                navigationCoordinator.launchSettings(route, dataset)
                            },
                            onNavigateToLogs = { dataset ->
                                navigationCoordinator.launchLogs(dataset)
                            },
                            onNavigateToLogRoute = { route, dataset ->
                                navigationCoordinator.launchLogRoute(route, dataset)
                            },
                            bottomBarRefreshRequested = bottomBarRefreshRequested,
                            onBottomBarRefreshConsumed = { bottomBarRefreshRequested = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                vpnController.permissionDisclosure?.let { disclosure ->
                    PermissionDisclosureDialog(
                        disclosure = disclosure,
                        onContinue = { vpnController.continuePermissionRequest(disclosure) },
                        onDismiss = { vpnController.dismissPermissionRequest(disclosure) }
                    )
                }

                appUpdateHost.state.availableUpdate?.let { update ->
                    if (appUpdateHost.dismissedVersion != update.version) {
                        AppUpdateDialog(
                            update = update,
                            downloadState = appUpdateHost.state.downloadState,
                            onDismiss = { appUpdateHost.dismissedVersion = update.version },
                            onDownload = { appUpdateHost.downloadUpdate() }
                        )
                    }
                }
            }
        }

        if (SystemSettingsStore.isInitialAgreementAccepted(this)) {
            initializeAcceptedExperience()
        }
    }

    private fun initializeAcceptedExperience() {
        startupCoordinator.initializeAcceptedExperience(
            activity = this,
            lifecycleScope = lifecycleScope,
            appUpdateHost = appUpdateHost,
            onAutoStartVpn = { vpnController.handleAutoStartIfNeeded(intent) }
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (SystemSettingsStore.isInitialAgreementAccepted(this)) {
            vpnController.handleAutoStartIfNeeded(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        val currentLanguageMode = AppLanguageManager.getMode(this)
        if (currentLanguageMode != languageModeAtCreate) {
            languageModeAtCreate = currentLanguageMode
            recreate()
            return
        }
        BatteryOptimizationHelper.invalidateCache()
        mainViewModel.refreshStatus()
        applyRecentsPrivacySetting()
        appUpdateHost.refreshDownloadState()
    }

    override fun onStop() {
        appUpdateHost.cancelActiveDownload()
        super.onStop()
    }

    private fun applyRecentsPrivacySetting() =
        RecentsPrivacyController.apply(this, SystemSettingsStore.isHideFromRecentsEnabled(this))

    companion object {
        const val EXTRA_AUTO_START_VPN = "auto_start_vpn"
    }
}
