package com.haoze.diting.data

import android.content.Context
import androidx.room.RoomDatabase

/** Resolves the rule data sources for a dataset; the simplified build only has NORMAL. */
object RuleDatabases {
    fun forDataset(context: Context, dataset: RuleDataset): RuleDataSources = when (dataset) {
        RuleDataset.NORMAL -> AppDatabase.getInstance(context)
    }

    /** Same singleton as [forDataset], typed for Room transactions. */
    fun forDatasetDb(context: Context, dataset: RuleDataset): RoomDatabase = when (dataset) {
        RuleDataset.NORMAL -> AppDatabase.getInstance(context)
    }

    fun runtimeForDataset(context: Context, dataset: RuleDataset): DnsRuntimeDataSources = when (dataset) {
        RuleDataset.NORMAL -> AppDatabase.getInstance(context)
    }
}
