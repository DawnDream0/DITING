package com.haoze.dnssr.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.haoze.dnssr.ui.components.SettingsGroupTitle
import com.haoze.dnssr.ui.components.SettingsInfoText
import com.haoze.dnssr.ui.components.SettingsScaffold
import com.haoze.dnssr.ui.components.SettingsSurfaceGroup
import com.haoze.dnssr.ui.settings.AppearanceSettingsStore

@Composable
fun BottomBarCustomizationScreen(
    onBack: () -> Unit,
    onBottomBarChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    var selectedItems by remember {
        mutableStateOf(AppearanceSettingsStore.getBottomBarDestinations(context))
    }

    val saveAndNotify = { newItems: List<BottomBarDestination> ->
        selectedItems = newItems
        AppearanceSettingsStore.setBottomBarDestinations(context, newItems)
        onBottomBarChanged()
    }

    val availableItems = remember(selectedItems) {
        BottomBarDestination.entries.filterNot { it in selectedItems }
    }

    SettingsScaffold(
        title = localizedText("底栏自定义"),
        onBack = onBack,
        actions = {
            TextButton(
                onClick = {
                    saveAndNotify(BottomBarDestination.DEFAULT_DESTINATIONS)
                    Toast.makeText(context, localizedText(context, "已恢复默认底栏配置"), Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(localizedText("恢复默认"))
            }
        }
    ) { innerPadding ->
        val selectedGroupItems = buildList<@Composable () -> Unit> {
            selectedItems.forEachIndexed { index, item ->
                add {
                    SelectedBottomBarItemRow(
                        index = index,
                        totalCount = selectedItems.size,
                        destination = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < selectedItems.size - 1,
                        canRemove = selectedItems.size > BottomBarDestination.MIN_COUNT,
                        onMoveUp = {
                            if (index > 0) {
                                val mutable = selectedItems.toMutableList()
                                val temp = mutable[index]
                                mutable[index] = mutable[index - 1]
                                mutable[index - 1] = temp
                                saveAndNotify(mutable)
                            }
                        },
                        onMoveDown = {
                            if (index < selectedItems.size - 1) {
                                val mutable = selectedItems.toMutableList()
                                val temp = mutable[index]
                                mutable[index] = mutable[index + 1]
                                mutable[index + 1] = temp
                                saveAndNotify(mutable)
                            }
                        },
                        onRemove = {
                            if (selectedItems.size <= BottomBarDestination.MIN_COUNT) {
                                Toast.makeText(
                                    context,
                                    localizedText(context, "底栏至少保留 2 个按钮"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                val mutable = selectedItems.toMutableList()
                                mutable.removeAt(index)
                                saveAndNotify(mutable)
                            }
                        }
                    )
                }
            }
        }

        val availableGroupItems = buildList<@Composable () -> Unit> {
            availableItems.forEach { item ->
                add {
                    AvailableBottomBarItemRow(
                        destination = item,
                        canAdd = selectedItems.size < BottomBarDestination.MAX_COUNT,
                        onAdd = {
                            if (selectedItems.size >= BottomBarDestination.MAX_COUNT) {
                                Toast.makeText(
                                    context,
                                    localizedText(context, "底栏最多添加 4 个按钮"),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                saveAndNotify(selectedItems + item)
                            }
                        }
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsInfoText(
                    localizedText("底栏按钮数量限制在 2～4 个。您可在此自由添加、移除或调整底栏按钮的显示顺序。")
                )
            }

            item {
                SettingsGroupTitle(
                    text = localizedText("已选底栏按钮") + " (${selectedItems.size}/${BottomBarDestination.MAX_COUNT})"
                )
            }

            item {
                SettingsSurfaceGroup(content = selectedGroupItems)
            }

            item {
                SettingsGroupTitle(localizedText("可添加的页面"))
            }

            if (availableItems.isEmpty()) {
                item {
                    SettingsInfoText(localizedText("暂无更多可添加的页面"))
                }
            } else {
                item {
                    SettingsSurfaceGroup(content = availableGroupItems)
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SelectedBottomBarItemRow(
    index: Int,
    totalCount: Int,
    destination: BottomBarDestination,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Index badge
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Destination Icon
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Title and description
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = localizedText(destination.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = localizedText(destination.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Action buttons
        IconButton(
            onClick = onMoveUp,
            enabled = canMoveUp,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowUpward,
                contentDescription = localizedText("向上移动"),
                tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onMoveDown,
            enabled = canMoveDown,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = localizedText("向下移动"),
                tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f),
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.DeleteOutline,
                contentDescription = localizedText("移除"),
                tint = if (canRemove) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.error.copy(alpha = 0.32f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun AvailableBottomBarItemRow(
    destination: BottomBarDestination,
    canAdd: Boolean,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = destination.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = localizedText(destination.title),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = localizedText(destination.description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        IconButton(
            onClick = onAdd,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.AddCircleOutline,
                contentDescription = localizedText("添加"),
                tint = if (canAdd) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.32f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
