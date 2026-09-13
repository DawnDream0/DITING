package com.haoze.dnssr.ui

import android.content.Context
import com.haoze.dnssr.ui.settings.StartupSelfCheck

/**
 * Entry point for the startup self-check and config repair orchestration, which
 * composes multiple setting stores under [com.haoze.dnssr.ui.settings].
 *
 * The former pass-through facade members were dismantled: call sites now use the
 * individual store objects ([com.haoze.dnssr.ui.settings.AppearanceSettingsStore],
 * [com.haoze.dnssr.ui.settings.SystemSettingsStore],
 * [com.haoze.dnssr.ui.settings.AppRulesSettingsStore],
 * [com.haoze.dnssr.ui.settings.ResolutionSettingsStore],
 * [com.haoze.dnssr.ui.settings.BootstrapDnsSettingsStore],
 * [com.haoze.dnssr.ui.settings.DnsCacheSettingsStore],
 * [com.haoze.dnssr.ui.settings.OutboundProxySettingsStore],
 * [com.haoze.dnssr.ui.settings.AgentApiSettingsStore]) directly.
 */
object AppSettings {
    fun performStartupSelfCheck(context: Context) = StartupSelfCheck.performStartupSelfCheck(context)
}
