package com.haoze.diting.ui.apprule

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.accompanist.drawablepainter.rememberDrawablePainter
import com.haoze.diting.data.entity.AllowRuleEntity
import com.haoze.diting.data.entity.BlockRuleEntity
import com.haoze.diting.ui.InstalledApp
import com.haoze.diting.ui.components.SettingsCornerShape
import com.haoze.diting.ui.components.SettingsItemSpacing
import com.haoze.diting.ui.components.SettingsScaffold
import com.haoze.diting.ui.components.SettingsSurfaceGroup
import com.haoze.diting.ui.components.SettingsSurfaceItem
import com.haoze.diting.ui.components.SettingsSwitchItem
import com.haoze.diting.ui.components.SettingsCardMargin
import com.haoze.diting.ui.localizedText

@Composable
internal fun SingleAppRulePanel(
    app: InstalledApp,
    allowlistDomains: Set<String>,
    fullBlockEnabled: Boolean,
    blockRules: List<AppRuleItem>,
    allowRules: List<AppRuleItem>,
    blockTotalCount: Int,
    allowTotalCount: Int,
    blockHasMore: Boolean,
    allowHasMore: Boolean,
    onLoadMoreBlockRules: () -> Unit,
    onLoadMoreAllowRules: () -> Unit,
    onBack: () -> Unit,
    onToggleFullBlock: (Boolean) -> Unit,
    onAddRule: (pattern: String, isAllow: Boolean, important: Boolean, isWildcard: Boolean) -> Unit,
    onToggleRule: (id: Long, isAllow: Boolean, enabled: Boolean) -> Unit,
    onDeleteRule: (id: Long, isAllow: Boolean) -> Unit
) {
    var showFullBlockConfirmDialog by remember { mutableStateOf(false) }

    var showAddDnsDialog by remember { mutableStateOf(false) }
    var addDialogIsAllow by remember { mutableStateOf(false) }

    fun openAddDnsDialog(isAllow: Boolean) {
        addDialogIsAllow = isAllow
        showAddDnsDialog = true
    }

    SettingsScaffold(
        titleContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    contentAlignment = Alignment.Center
                ) {
                    app.icon?.let { icon ->
                        Image(
                            painter = rememberDrawablePainter(drawable = icon),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().scale(1.06f)
                        )
                    } ?: Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        },
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsItemSpacing)
        ) {
            /* Section 1: outbound access and isolation modes */
            item(key = "strategy_title") {
                AppSectionHeader(title = "外联与隔离模式")
            }

            item(key = "strategy_group") {
                SettingsSurfaceGroup(
                    content = listOf {
                        SettingsSwitchItem(
                            title = localizedText("默认拦截全部外联"),
                            subtitle = localizedText("开启后阻断该应用的所有网络连接，仅放行白名单"),
                            checked = fullBlockEnabled,
                            onCheckedChange = { next ->
                                if (next) {
                                    showFullBlockConfirmDialog = true
                                } else {
                                    onToggleFullBlock(false)
                                }
                            }
                        )
                    }
                )
            }

            if (fullBlockEnabled) {
                item(key = "fullblock_warning") {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = SettingsCardMargin, vertical = 4.dp),
                        shape = SettingsCornerShape,
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = localizedText("已开启全阻断模式：请确保已添加必要的放行白名单，否则应用可能无法联网。"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            /* Section 2: unified whitelist — DNS allow rules plus legacy manual
               allowlist domains (merged by buildMergedAppAllowlist) */
            item(key = "allow_rules_title") {
                AppSectionHeader(
                    title = "放行白名单 ($allowTotalCount)",
                    onAddClick = { openAddDnsDialog(isAllow = true) },
                    addText = "添加放行域名"
                )
            }

            if (allowRules.isEmpty()) {
                item(key = "allow_rules_empty") {
                    SettingsSurfaceGroup(
                        content = listOf {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = localizedText("未配置放行白名单"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    )
                }
            } else {
                itemsIndexed(allowRules, key = { _, r -> "allow_${r.id}" }) { index, rule ->
                    SettingsSurfaceItem(
                        index = index,
                        itemCount = allowRules.size,
                        modifier = Modifier.padding(horizontal = SettingsCardMargin)
                    ) {
                        RuleEntityRow(
                            item = rule,
                            onToggle = { onToggleRule(rule.id, true, it) },
                            onDelete = { onDeleteRule(rule.id, true) }
                        )
                    }
                }
                if (allowHasMore) {
                    item(key = "allow_load_more") {
                        // Composed only when scrolled into view, so this doubles
                        // as a scroll-triggered incremental page loader.
                        LaunchedEffect(allowRules.size) { onLoadMoreAllowRules() }
                        LoadMoreFooter()
                    }
                }
            }

            /* Section 3: DNS-specific block rules */
            item(key = "block_rules_title") {
                AppSectionHeader(
                    title = "DNS 专属拦截 ($blockTotalCount)",
                    onAddClick = { openAddDnsDialog(isAllow = false) },
                    addText = "添加 DNS 拦截"
                )
            }

            if (blockRules.isEmpty()) {
                item(key = "block_rules_empty") {
                    SettingsSurfaceGroup(
                        content = listOf {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                Text(
                                    text = localizedText("未配置 DNS 拦截规则"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                )
                            }
                        }
                    )
                }
            } else {
                itemsIndexed(blockRules, key = { _, r -> "block_${r.id}" }) { index, rule ->
                    SettingsSurfaceItem(
                        index = index,
                        itemCount = blockRules.size,
                        modifier = Modifier.padding(horizontal = SettingsCardMargin)
                    ) {
                        RuleEntityRow(
                            item = rule,
                            onToggle = { onToggleRule(rule.id, false, it) },
                            onDelete = { onDeleteRule(rule.id, false) }
                        )
                    }
                }
                if (blockHasMore) {
                    item(key = "block_load_more") {
                        LaunchedEffect(blockRules.size) { onLoadMoreBlockRules() }
                        LoadMoreFooter()
                    }
                }
            }
        }
    }

    /* Confirmation dialog for the risks of enabling full outbound blocking */
    if (showFullBlockConfirmDialog) {
        FullBlockConfirmDialog(
            onConfirm = { onToggleFullBlock(true) },
            onDismiss = { showFullBlockConfirmDialog = false }
        )
    }

    /* Dialog for adding a DNS rule */
    if (showAddDnsDialog) {
        AddDnsRuleDialog(
            app = app,
            isAllow = addDialogIsAllow,
            onDismiss = { showAddDnsDialog = false },
            onConfirm = onAddRule
        )
    }
}

@Composable
private fun LoadMoreFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}
