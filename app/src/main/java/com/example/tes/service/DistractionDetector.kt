package com.example.tes.service

import android.app.usage.UsageStatsManager
import android.content.Context

class DistractionDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Returns the package name of the app currently in the foreground.
     * Uses UsageStatsManager.queryUsageStats() with a 2-second window.
     * Returns null if can't determine or permission not granted.
     */
    fun getForegroundPackage(): String? {
        val now = System.currentTimeMillis()
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            now - 2000,
            now
        ) ?: return null

        return stats
            .filter { it.lastTimeUsed > now - 3000 }
            .maxByOrNull { it.lastTimeUsed }
            ?.packageName
    }

    fun isDistracting(currentPackage: String?, distractingApps: Set<String>): Boolean {
        if (currentPackage == null) return false
        if (currentPackage == context.packageName) return false
        if (currentPackage == "com.android.launcher") return false
        return distractingApps.contains(currentPackage)
    }
}
