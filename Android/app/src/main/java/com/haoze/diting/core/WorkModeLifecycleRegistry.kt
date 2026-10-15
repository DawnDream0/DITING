package com.haoze.diting.core

import android.content.Context
import android.content.Intent

/**
 * Registry for coordinating lifecycle operations for the Normal-mode VPN service.
 */
object WorkModeLifecycleRegistry {
    @Volatile
    var startNormalMode: (Context) -> Unit = { ctx ->
        val intent = Intent().setClassName(ctx.packageName, "${ctx.packageName}.normal.DnsVpnService")
        runCatching { androidx.core.content.ContextCompat.startForegroundService(ctx, intent) }
    }

    @Volatile
    var stopNormalMode: (Context) -> Unit = { ctx ->
        if (VpnStateRegistry.isNormalRunning(ctx)) {
            val intent = Intent().setClassName(ctx.packageName, "${ctx.packageName}.normal.DnsVpnService")
                .setAction("com.haoze.diting.STOP_VPN")
            runCatching { ctx.startService(intent) }
        }
    }

    @Volatile
    var updateNormalFloatingLogAppState: (Context, Boolean) -> Unit = { ctx, fg ->
        val sp = ctx.getSharedPreferences("system_settings", Context.MODE_PRIVATE)
        sp.edit().putBoolean("main_activity_foreground", fg).apply()
        if (VpnStateRegistry.isNormalRunning(ctx)) {
            val intent = Intent().setClassName(ctx.packageName, "${ctx.packageName}.normal.DnsVpnService")
                .setAction("com.haoze.diting.FLOATING_LOG_APP_STATE")
                .putExtra("app_foreground", fg)
            runCatching { ctx.startService(intent) }
        }
    }

    fun refreshFloatingLogOverlay(context: Context) {
        if (VpnStateRegistry.isNormalRunning(context)) {
            val intent = Intent().setClassName(context.packageName, "${context.packageName}.normal.DnsVpnService")
                .setAction("com.haoze.diting.REFRESH_FLOATING_LOG")
            runCatching { context.startService(intent) }
        }
    }
}
