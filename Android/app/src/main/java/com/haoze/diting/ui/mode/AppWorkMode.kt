package com.haoze.diting.ui.mode

import android.os.Build
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppWorkMode(
    val storageValue: String,
    val title: String,
    val summary: String,
    val badgeText: String,
    val minApiLevel: Int,
    val isAvailable: Boolean,
    val icon: ImageVector
) {
    NORMAL(
        storageValue = "normal",
        title = "普通模式",
        summary = "包含全量域名解析、黑白名单过滤、HTTPS 检查与网络管控能力",
        badgeText = "推荐",
        minApiLevel = 29,
        isAvailable = true,
        icon = Icons.Outlined.Layers
    );

    fun isSupportedOnCurrentDevice(): Boolean {
        if (Build.VERSION.SDK_INT < minApiLevel) return false
        return true
    }

    companion object {
        fun fromStorageValue(value: String?): AppWorkMode =
            entries.firstOrNull { it.storageValue == value } ?: NORMAL
    }
}
