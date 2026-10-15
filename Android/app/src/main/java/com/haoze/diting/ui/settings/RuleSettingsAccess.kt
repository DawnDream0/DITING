package com.haoze.diting.ui.settings

import android.content.Context
import com.haoze.diting.data.RuleDataset
import com.haoze.diting.core.rule.BlockResponseMode
import com.haoze.diting.core.rule.DynamicBlockResponseConfig
import com.haoze.diting.core.rule.SubscriptionAutoUpdateSettings

/**
 * Dispatches rule-management settings reads/writes for the NORMAL dataset,
 * delegating to the original AppRulesSettingsStore / SubscriptionAutoUpdateSettings.
 */
class RuleSettingsAccess(private val dataset: RuleDataset) {

    /** Master switch of domain rule filtering for the dataset. */
    fun isMasterEnabled(context: Context): Boolean {
        return AppRulesSettingsStore.isDomainRulesEnabled(context, dataset)
    }

    fun setMasterEnabled(context: Context, enabled: Boolean) {
        AppRulesSettingsStore.setDomainRulesEnabled(context, enabled, dataset)
    }

    /**
     * Whether URL rules can take effect. URL rules only matter for the VPN
     * mode's traffic inspection.
     */
    fun isAddressRulesOperational(context: Context): Boolean {
        return AppRulesSettingsStore.isAddressRulesFullyOperational(context)
    }

    fun isAllowEditDefaultWhitelist(context: Context): Boolean {
        return AppRulesSettingsStore.isAllowEditDefaultWhitelist(context, dataset)
    }

    fun setAllowEditDefaultWhitelist(context: Context, enabled: Boolean) {
        AppRulesSettingsStore.setAllowEditDefaultWhitelist(context, enabled, dataset)
    }

    fun blockResponseMode(context: Context): BlockResponseMode {
        return AppRulesSettingsStore.getBlockResponseMode(context, dataset)
    }

    fun setBlockResponseMode(context: Context, mode: BlockResponseMode) {
        AppRulesSettingsStore.setBlockResponseMode(context, mode, dataset)
    }

    fun dynamicBlockResponseConfig(context: Context): DynamicBlockResponseConfig {
        return AppRulesSettingsStore.getDynamicBlockResponseConfig(context, dataset)
    }

    fun setDynamicBlockResponseConfig(context: Context, config: DynamicBlockResponseConfig) {
        AppRulesSettingsStore.setDynamicBlockResponseConfig(context, config, dataset)
    }

    fun autoUpdateEnabled(context: Context): Boolean {
        return SubscriptionAutoUpdateSettings.isEnabled(context, dataset)
    }

    fun autoUpdateIntervalHours(context: Context): Int {
        return SubscriptionAutoUpdateSettings.intervalHours(context, dataset)
    }

    fun saveAutoUpdate(context: Context, enabled: Boolean, intervalHours: Int) {
        SubscriptionAutoUpdateSettings.save(context, enabled, intervalHours, dataset)
    }
}
