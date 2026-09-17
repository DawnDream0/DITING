package com.haoze.dnssr.ui

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.haoze.dnssr.ui.components.SettingsInfoText
import com.haoze.dnssr.ui.components.SettingsSurfaceGroup
import com.haoze.dnssr.ui.components.SettingsSwitchItem
import com.haoze.dnssr.ui.settings.AgentApiConfig
import com.haoze.dnssr.ui.settings.AgentApiPresetStore
import com.haoze.dnssr.ui.settings.ModelPreset

/**
 * Authorization switch item for allowing plugins to call Agent API.
 */
@Composable
internal fun AgentApiAuthorizationCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    SettingsSurfaceGroup(
        content = listOf {
            SettingsSwitchItem(
                title = localizedText("允许插件调用智能体 API"),
                subtitle = localizedText("开启后，获得授权的外挂插件可直接复用软件配置的 API 密钥发起大模型分析与查询"),
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    )
}

/**
 * Provider presets selection chips and preset management entry buttons.
 */
@Composable
internal fun AgentApiPresetsCard(
    presets: List<ModelPreset>,
    activePresetId: String?,
    onPresetSelected: (ModelPreset) -> Unit,
    onManagePresets: () -> Unit,
    onAddPreset: () -> Unit
) {
    SettingsSurfaceGroup(
        content = listOf(
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Text(
                        text = localizedText("快速切换服务商"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            FilterChip(
                                selected = preset.id == activePresetId,
                                onClick = { onPresetSelected(preset) },
                                label = { Text(preset.name) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = localizedText("预设模板随官方模型下线节奏维护，点击右侧“管理预设”可增删改，或添加自己的私有网关。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = onManagePresets,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(localizedText("管理预设"))
                    }
                    OutlinedButton(
                        onClick = onAddPreset,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(localizedText("新增预设"))
                    }
                }
            }
        )
    )
}

/**
 * Credentials configuration form: API Key, Base URL, Model name, and Server Model Fetch.
 */
@Composable
internal fun AgentApiCredentialsCard(
    config: AgentApiConfig,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    onConfigChange: (AgentApiConfig) -> Unit,
    isFetchingModels: Boolean,
    modelFetchError: String?,
    onFetchModelsClick: () -> Unit
) {
    val context = LocalContext.current

    SettingsSurfaceGroup(
        content = listOf(
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = config.apiKey,
                        onValueChange = { input ->
                            val composite = AgentApiTextParser.parseCompositeUrlAndKey(input)
                            if (composite != null) {
                                val (extractedUrl, remaining) = composite
                                onConfigChange(config.copy(baseUrl = extractedUrl, apiKey = remaining))
                                Toast.makeText(context, "已自动识别并填入 Base URL 和 API Key", Toast.LENGTH_SHORT).show()
                                return@OutlinedTextField
                            }
                            val sanitized = AgentApiTextParser.sanitizeInput(input.removePrefix("Bearer "))
                            onConfigChange(config.copy(apiKey = sanitized))
                        },
                        label = { Text(localizedText("API Key (密钥)")) },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val pasteText = clip.getItemAt(0).text?.toString().orEmpty()
                                        if (pasteText.isNotBlank()) {
                                            val tip = AgentApiTextParser.parseAndApplyApiText(pasteText, config, onConfigChange)
                                            Toast.makeText(context, tip, Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                        }
                                    } else {
                                        Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴")
                                }
                                if (config.apiKey.isNotEmpty()) {
                                    IconButton(onClick = { onConfigChange(config.copy(apiKey = "")) }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                                    }
                                }
                                IconButton(onClick = onToggleApiKeyVisible) {
                                    Icon(
                                        if (apiKeyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (apiKeyVisible) "隐藏" else "显示"
                                    )
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = localizedText("凭据保存在本地私有安全存储中，不会上传到任何第三方服务器。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = config.baseUrl,
                        onValueChange = { input ->
                            val cleaned = AgentApiTextParser.sanitizeInput(input)
                            onConfigChange(config.copy(baseUrl = cleaned))
                        },
                        label = { Text(localizedText("服务地址 (Base URL)")) },
                        placeholder = { Text("https://api.deepseek.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        trailingIcon = {
                            Row {
                                if (config.baseUrl.isNotEmpty()) {
                                    IconButton(onClick = { onConfigChange(config.copy(baseUrl = "")) }) {
                                        Icon(Icons.Filled.Clear, contentDescription = "清空")
                                    }
                                }
                                IconButton(onClick = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = cm.primaryClip
                                    if (clip != null && clip.itemCount > 0) {
                                        val pasteText = clip.getItemAt(0).text?.toString().orEmpty()
                                        val cleaned = AgentApiTextParser.sanitizeInput(pasteText)
                                        if (cleaned.isNotBlank()) {
                                            onConfigChange(config.copy(baseUrl = cleaned))
                                            Toast.makeText(context, "已从剪贴板粘贴服务地址", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴")
                                }
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = localizedText("兼容 OpenAI 协议规范，端点支持自动适配补全 /chat/completions。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedTextField(
                        value = config.model,
                        onValueChange = { input ->
                            val cleaned = AgentApiTextParser.sanitizeInput(input)
                            onConfigChange(config.copy(model = cleaned))
                        },
                        label = { Text(localizedText("模型名称 (Model)")) },
                        placeholder = { Text(AgentApiConfig.DEFAULT_MODEL) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 2,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        AgentApiPresetStore.MODEL_SUGGESTIONS.forEach { m ->
                            SuggestionChip(
                                onClick = { onConfigChange(config.copy(model = m)) },
                                label = { Text(m, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }
            },
            {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    OutlinedButton(
                        onClick = onFetchModelsClick,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isFetchingModels
                    ) {
                        if (isFetchingModels) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(localizedText("正在拉取模型列表..."))
                        } else {
                            Icon(Icons.Filled.CloudDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(localizedText("从服务端拉取可用模型"))
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = modelFetchError
                            ?: localizedText("通过 OpenAI 兼容的 /models 端点获取该服务商当前真实提供的模型，避免手写模型名过期。"),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (modelFetchError != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        )
    )
}

/**
 * Advanced persona and system prompt configuration card.
 */
@Composable
internal fun AgentApiPromptCard(
    systemPrompt: String,
    onSystemPromptChange: (String) -> Unit
) {
    SettingsSurfaceGroup(
        content = listOf {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = onSystemPromptChange,
                    label = { Text(localizedText("全局 System Prompt")) },
                    placeholder = { Text("例如：你是一名网络安全与 DNS 威胁情报专家...") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = localizedText("当插件未指定系统提示词时，将自动注入此全局设定。"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

/**
 * Connectivity verification controls, live test result display card, and reset button.
 */
@Composable
internal fun AgentApiVerificationCard(
    isTesting: Boolean,
    testResultText: String?,
    testResultSuccess: Boolean?,
    onTestConnection: () -> Unit,
    onResetDefaults: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onTestConnection,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isTesting
        ) {
            if (isTesting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(localizedText("正在连线验证..."))
            } else {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text(localizedText("测试 API 连通性"))
            }
        }

        AnimatedVisibility(visible = testResultText != null) {
            val isSuccess = testResultSuccess == true
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuccess) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Error,
                        contentDescription = null,
                        tint = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = testResultText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSuccess) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        }
                    )
                }
            }
        }

        OutlinedButton(
            onClick = onResetDefaults,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text(localizedText("恢复默认配置"))
        }
    }
}

/**
 * Informational note regarding plugin permissions and AI API usage.
 */
@Composable
internal fun AgentApiNoticeSection() {
    SettingsInfoText(
        localizedText("提示：配置此智能体服务后，所有申请了 AI_AGENT 权限的外挂插件均可通过 ai.chat(...) 或 ai.analyzeDnsLogs(...) 无缝调用大模型进行域名安全评估与实时 DNS 日志深度审查。")
    )
}
