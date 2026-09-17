package com.haoze.dnssr.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.haoze.dnssr.ui.components.SettingsGroupTitle
import com.haoze.dnssr.ui.components.SettingsScaffold
import com.haoze.dnssr.ui.settings.AgentApiClient
import com.haoze.dnssr.ui.settings.AgentApiConfig
import com.haoze.dnssr.ui.settings.AgentApiPresetStore
import com.haoze.dnssr.ui.settings.AgentApiSettingsStore
import com.haoze.dnssr.ui.settings.ModelPreset
import kotlinx.coroutines.launch

/**
 * Agent API Settings Screen: allows users to configure AI agent model endpoints,
 * API credentials, provider presets, and test connectivity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentApiSettingsScreen(onBack: () -> Unit, title: String = "智能体 API") {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var config by remember { mutableStateOf(AgentApiSettingsStore.getAgentApiConfig(context)) }
    var presets by remember { mutableStateOf(AgentApiPresetStore.getOrderedPresets(context)) }
    var apiKeyVisible by remember { mutableStateOf(false) }

    var isTesting by remember { mutableStateOf(false) }
    var testResultText by remember { mutableStateOf<String?>(null) }
    var testResultSuccess by remember { mutableStateOf<Boolean?>(null) }

    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModelPicker by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    var showPresetManager by remember { mutableStateOf(false) }
    var editingPreset by remember { mutableStateOf<ModelPreset?>(null) }
    var isCreatingPreset by remember { mutableStateOf(false) }

    fun updateConfig(newConfig: AgentApiConfig) {
        config = newConfig
        AgentApiSettingsStore.setAgentApiConfig(context, newConfig)
    }

    fun reloadPresets() {
        presets = AgentApiPresetStore.getOrderedPresets(context)
    }

    /** Which preset the current config matches (matched on both baseUrl and model) */
    val activePresetId = presets.firstOrNull {
        it.baseUrl.equals(config.baseUrl, ignoreCase = true) &&
                it.model.equals(config.model, ignoreCase = true)
    }?.id

    SettingsScaffold(title = localizedText(title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Basic switch
            item { SettingsGroupTitle(localizedText("功能授权")) }
            item {
                AgentApiAuthorizationCard(
                    enabled = config.enabled,
                    onEnabledChange = { enabled ->
                        updateConfig(config.copy(enabled = enabled))
                    }
                )
            }

            // 2. Provider presets
            item { SettingsGroupTitle(localizedText("服务提供方预设")) }
            item {
                AgentApiPresetsCard(
                    presets = presets,
                    activePresetId = activePresetId,
                    onPresetSelected = { preset ->
                        updateConfig(
                            config.copy(
                                baseUrl = preset.baseUrl,
                                model = preset.model
                            )
                        )
                        Toast.makeText(
                            context,
                            "已切换至 ${preset.name}",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onManagePresets = { showPresetManager = true },
                    onAddPreset = { isCreatingPreset = true }
                )
            }

            // 3. API service and credentials
            item { SettingsGroupTitle(localizedText("服务提供方配置")) }
            item {
                AgentApiCredentialsCard(
                    config = config,
                    apiKeyVisible = apiKeyVisible,
                    onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                    onConfigChange = ::updateConfig,
                    isFetchingModels = isFetchingModels,
                    modelFetchError = modelFetchError,
                    onFetchModelsClick = {
                        if (config.apiKey.isBlank()) {
                            Toast.makeText(context, "请先输入 API Key", Toast.LENGTH_SHORT).show()
                            return@AgentApiCredentialsCard
                        }
                        isFetchingModels = true
                        modelFetchError = null
                        coroutineScope.launch {
                            val result = AgentApiClient.fetchModels(config)
                            isFetchingModels = false
                            result.onSuccess { models ->
                                if (models.isEmpty()) {
                                    modelFetchError = "服务端返回的模型列表为空"
                                } else {
                                    fetchedModels = models
                                    showModelPicker = true
                                }
                            }.onFailure { error ->
                                modelFetchError = error.message ?: "拉取失败"
                            }
                        }
                    }
                )
            }

            // 4. Advanced role/persona settings
            item { SettingsGroupTitle(localizedText("高级角色提示词 (可选)")) }
            item {
                AgentApiPromptCard(
                    systemPrompt = config.systemPrompt,
                    onSystemPromptChange = { updateConfig(config.copy(systemPrompt = it)) }
                )
            }

            // 5. Connectivity test and actions
            item { SettingsGroupTitle(localizedText("配置验证")) }
            item {
                AgentApiVerificationCard(
                    isTesting = isTesting,
                    testResultText = testResultText,
                    testResultSuccess = testResultSuccess,
                    onTestConnection = {
                        if (config.apiKey.isBlank()) {
                            Toast.makeText(context, "请先输入 API Key", Toast.LENGTH_SHORT).show()
                            return@AgentApiVerificationCard
                        }
                        isTesting = true
                        testResultText = null
                        testResultSuccess = null
                        coroutineScope.launch {
                            val startTime = System.currentTimeMillis()
                            val res = AgentApiClient.testConnection(config)
                            val elapsed = System.currentTimeMillis() - startTime
                            isTesting = false
                            res.onSuccess { chatResult ->
                                testResultSuccess = true
                                testResultText = "【连接正常】耗时 ${elapsed}ms | 模型: ${chatResult.model}\n" +
                                        "响应: ${chatResult.content.trim()}\n" +
                                        "消耗 Token: ${chatResult.totalTokens}"
                            }.onFailure { error ->
                                testResultSuccess = false
                                testResultText = "【连接失败】${error.message}"
                            }
                        }
                    },
                    onResetDefaults = {
                        val defaultConfig = AgentApiConfig()
                        updateConfig(defaultConfig)
                        testResultText = null
                        testResultSuccess = null
                        modelFetchError = null
                        Toast.makeText(context, "已恢复默认配置", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            // 6. Informational notice
            item {
                AgentApiNoticeSection()
            }
            item { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }

    if (showModelPicker) {
        ServerModelPickerDialog(
            models = fetchedModels,
            currentModel = config.model,
            onDismiss = { showModelPicker = false },
            onPick = { model ->
                updateConfig(config.copy(model = model))
                showModelPicker = false
                Toast.makeText(context, "已选择模型 $model", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showPresetManager) {
        PresetManagerDialog(
            presets = presets,
            activePresetId = activePresetId,
            onDismiss = { showPresetManager = false },
            onEdit = { preset ->
                showPresetManager = false
                isCreatingPreset = false
                editingPreset = preset
            },
            onDelete = { preset ->
                presets = AgentApiPresetStore.deleteCustomPreset(context, preset.id)
                Toast.makeText(context, "已删除预设 ${preset.name}", Toast.LENGTH_SHORT).show()
            },
            onAdd = {
                showPresetManager = false
                editingPreset = null
                isCreatingPreset = true
            },
            onReset = {
                AgentApiPresetStore.resetToDefault(context)
                reloadPresets()
                Toast.makeText(context, "已恢复出厂预设", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (isCreatingPreset || editingPreset != null) {
        PresetEditDialog(
            initial = editingPreset,
            onDismiss = {
                isCreatingPreset = false
                editingPreset = null
            },
            onSave = { preset ->
                AgentApiPresetStore.upsertCustomPreset(context, preset)
                reloadPresets()
                isCreatingPreset = false
                editingPreset = null
                Toast.makeText(context, "预设已保存", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
