package com.haoze.dnssr.ui

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haoze.dnssr.ui.agent.AgentAnalysisSheet
import com.haoze.dnssr.ui.agent.AnalysisTarget
import com.haoze.dnssr.ui.components.AppAlertDialog
import com.haoze.dnssr.ui.components.SettingsGroupTitle
import com.haoze.dnssr.ui.components.SettingsScaffold
import com.haoze.dnssr.ui.settings.AgentApiConfig
import com.haoze.dnssr.ui.settings.AgentApiPresetStore
import com.haoze.dnssr.ui.settings.AgentApiSettingsStore

enum class AgentApiSubPage {
    CREDENTIALS,
    PRESETS,
    PARAMS
}

/**
 * Agent API Settings Hub Screen.
 * Provides service authorization, status overview, categorized secondary sub-pages,
 * and live testing playground.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApiSettingsScreen(
    onBack: () -> Unit,
    title: String = "智能体 API",
    initialSubPage: AgentApiSubPage? = null
) {
    var activeSubPage by remember { mutableStateOf(initialSubPage) }

    // Intercept system Back button to return to the hub screen instead of exiting the activity
    BackHandler(enabled = activeSubPage != null) {
        activeSubPage = null
    }

    // When inside a secondary page, render it directly with dedicated back navigation
    when (activeSubPage) {
        AgentApiSubPage.CREDENTIALS -> {
            AgentApiCredentialsScreen(onBack = { activeSubPage = null })
            return
        }
        AgentApiSubPage.PRESETS -> {
            AgentApiPresetsScreen(onBack = { activeSubPage = null })
            return
        }
        AgentApiSubPage.PARAMS -> {
            AgentApiParamsScreen(onBack = { activeSubPage = null })
            return
        }
        null -> Unit
    }

    val context = LocalContext.current
    var config by remember { mutableStateOf(AgentApiSettingsStore.getAgentApiConfig(context)) }

    // Re-read latest configuration when returning from secondary sub-pages
    LaunchedEffect(activeSubPage) {
        if (activeSubPage == null) {
            config = AgentApiSettingsStore.getAgentApiConfig(context)
        }
    }
    val presets = remember(config) { AgentApiPresetStore.getOrderedPresets(context) }

    var showResetDialog by remember { mutableStateOf(false) }
    var activeAnalysisTarget by remember { mutableStateOf<AnalysisTarget?>(null) }

    fun updateConfig(newConfig: AgentApiConfig) {
        config = newConfig
        AgentApiSettingsStore.setAgentApiConfig(context, newConfig)
    }

    val activePreset = presets.firstOrNull {
        it.baseUrl.equals(config.baseUrl, ignoreCase = true) &&
                it.model.equals(config.model, ignoreCase = true)
    }

    SettingsScaffold(title = localizedText(title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Service Master Switch & Status Overview
            item { SettingsGroupTitle(localizedText("服务总控与状态")) }
            item {
                AgentApiStatusCard(
                    config = config,
                    activePreset = activePreset,
                    onEnabledChange = { enabled ->
                        updateConfig(config.copy(enabled = enabled))
                    }
                )
            }

            // 2. Secondary Settings Navigation Group
            item { SettingsGroupTitle(localizedText("配置分类导航")) }
            item {
                AgentApiNavigationGroup(
                    onNavigateToCredentials = { activeSubPage = AgentApiSubPage.CREDENTIALS },
                    onNavigateToPresets = { activeSubPage = AgentApiSubPage.PRESETS },
                    onNavigateToParams = { activeSubPage = AgentApiSubPage.PARAMS }
                )
            }

            // 3. Live Playground
            item { SettingsGroupTitle(localizedText("实战演练与体验")) }
            item {
                AgentApiPlaygroundCard(
                    onAnalyzeDomain = { domain ->
                        activeAnalysisTarget = AnalysisTarget.Domain(domain)
                    },
                    onAnalyzeTraffic = {
                        activeAnalysisTarget = AnalysisTarget.RecentTraffic()
                    }
                )
            }

            // 4. Reset & Maintenance
            item { SettingsGroupTitle(localizedText("重置与恢复")) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    OutlinedButton(
                        onClick = { showResetDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(localizedText("恢复出厂默认配置"))
                    }
                }
            }

            // 5. Informational notice
            item {
                AgentApiNoticeSection()
            }

            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    // Reset confirmation dialog
    if (showResetDialog) {
        AppAlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text(localizedText("恢复默认配置？")) },
            text = { Text(localizedText("此操作将把所有智能体服务参数、API Key 与系统提示词恢复为出厂默认设置。")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val defaultConfig = AgentApiConfig()
                        updateConfig(defaultConfig)
                        showResetDialog = false
                        Toast.makeText(context, "已恢复出厂配置", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(localizedText("确认恢复"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text(localizedText("取消"))
                }
            }
        )
    }

    // Agent Analysis Sheet for live playground
    AgentAnalysisSheet(
        target = activeAnalysisTarget,
        onDismiss = { activeAnalysisTarget = null },
        onNavigateToSettings = {
            activeSubPage = AgentApiSubPage.CREDENTIALS
        }
    )
}
