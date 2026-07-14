package com.example.tes.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

class DistractionDetector(private val context: Context) {

    private val usageStatsManager: UsageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    /**
     * Returns the package name of the app currently in the foreground.
     *
     * Uses a two-method strategy:
     * 1. Primary: [queryEvents] — reads real-time MOVE_TO_FOREGROUND/MOVE_TO_BACKGROUND
     *    transition events from the last 5 seconds. This is responsive and accurate on
     *    most devices, including Android 12+.
     * 2. Fallback: [queryUsageStats] — checks lastTimeUsed within a 15-second window.
     *    Catches cases where OEMs delay event emission (common on OPPO/Realme/Xiaomi).
     *
     * Returns null if detection fails or permission is not granted.
     */
    fun getForegroundPackage(): String? {
        val now = System.currentTimeMillis()

        // ----- Method 1: queryEvents (real-time transitions) -----
        var packageName = getForegroundViaEvents(now)
        if (packageName != null) {
            Log.d("DistractionDetector", "queryEvents -> $packageName")
            return packageName
        }

        // ----- Method 2: queryUsageStats fallback (wider window) -----
        packageName = getForegroundViaUsageStats(now)
        if (packageName != null) {
            Log.d("DistractionDetector", "queryUsageStats fallback -> $packageName")
            return packageName
        }

        Log.d("DistractionDetector", "No foreground app detected (both methods returned null)")
        return null
    }

    /**
     * Uses [UsageStatsManager.queryEvents] to track foreground/background transitions
     * in the last 5 seconds. Returns the last known foreground package or null.
     *
     * This is the primary detection method because it:
     * - Processes individual transition events (not aggregated stats)
     * - Works in near-real-time on most devices
     * - Correctly handles rapid app switching
     */
    private fun getForegroundViaEvents(now: Long): String? {
        return try {
            val usageEvents = usageStatsManager.queryEvents(
                now - 5_000,
                now
            )

            var currentForeground: String? = null
            val event = UsageEvents.Event()

            while (usageEvents.getNextEvent(event)) {
                when (event.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND,
                    UsageEvents.Event.ACTIVITY_RESUMED -> {
                        currentForeground = event.packageName
                    }
                    UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                        // Only clear if the app going to background IS the one we
                        // think is in the foreground — prevents race conditions
                        // from out-of-order events on some OEMs.
                        if (event.packageName == currentForeground) {
                            currentForeground = null
                        }
                    }
                }
            }

            currentForeground
        } catch (e: SecurityException) {
            Log.w("DistractionDetector", "queryEvents permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w("DistractionDetector", "queryEvents error: ${e.message}")
            null
        }
    }

    /**
     * Fallback: uses [UsageStatsManager.queryUsageStats] with a 15-second
     * lastTimeUsed window. On some OEMs (OPPO, Realme, Xiaomi) events may
     * not fire promptly, but lastTimeUsed is eventually updated.
     *
     * Window is kept to 15s to minimize false positives from stale data.
     */
    private fun getForegroundViaUsageStats(now: Long): String? {
        return try {
            val stats = usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 60_000,
                now + 1_000
            )

            if (stats.isNullOrEmpty()) {
                Log.w("DistractionDetector", "queryUsageStats returned null/empty — permission may not be granted")
                return null
            }

            val recent = stats.filter { it.lastTimeUsed > now - 15_000 }
            if (recent.isEmpty()) {
                return null
            }

            recent.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: SecurityException) {
            Log.w("DistractionDetector", "queryUsageStats permission denied: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w("DistractionDetector", "queryUsageStats error: ${e.message}")
            null
        }
    }

    /**
     * Checks whether [currentPackage] is in the user's distracting-apps list.
     * Returns false for null, the app's own package, and the default launcher.
     */
    fun isDistracting(currentPackage: String?, distractingApps: Set<String>): Boolean {
        if (currentPackage == null) return false
        if (currentPackage == context.packageName) return false
        if (currentPackage == "com.android.launcher" ||
            currentPackage == "com.oppo.launcher" ||
            currentPackage == "com.oneplus.launcher" ||
            currentPackage == "com.miui.home") return false
        return distractingApps.contains(currentPackage)
    }
}
