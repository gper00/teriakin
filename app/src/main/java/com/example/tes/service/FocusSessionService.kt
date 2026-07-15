package com.example.tes.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tes.MainActivity
import com.example.tes.R
import com.example.tes.data.AppPreferences
import com.example.tes.sound.SoundManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class FocusSessionService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var detector: DistractionDetector
    private lateinit var soundManager: SoundManager
    private lateinit var prefs: AppPreferences
    private lateinit var notificationManager: NotificationManager
    private var monitorJob: Job? = null
    @Volatile
    var isAlarmActive = false
        private set

    companion object {
        const val CHANNEL_ID_NORMAL = "focus_timer_channel"
        const val CHANNEL_ID_ALARM = "focus_alarm_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_STOP = "com.example.tes.STOP_SESSION"
        const val ACTION_DISMISS_ALARM = "com.example.tes.DISMISS_ALARM"
        const val ACTION_END_SESSION = "com.example.tes.END_SESSION"

        var onDistractionDetected: (() -> Unit)? = null
        var onAlarmDismissed: (() -> Unit)? = null
        var onSessionEndRequested: (() -> Unit)? = null
    }

    inner class LocalBinder : Binder() {
        fun getService(): FocusSessionService = this@FocusSessionService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        detector = DistractionDetector(this)
        soundManager = SoundManager(this)
        prefs = AppPreferences(this)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_DISMISS_ALARM -> {
                dismissAlarm()
                // Revert notification to normal
                updateNotification("Focus session is running...")
                // Open the app so user can continue the session
                val goBackOpenApp = Intent(this, MainActivity::class.java).apply {
                    this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                startActivity(goBackOpenApp)
                return START_STICKY
            }
            ACTION_END_SESSION -> {
                soundManager.stop()
                isAlarmActive = false
                onSessionEndRequested?.invoke()
                stopForeground(true)  // Remove notification
                stopSelf()
                return START_NOT_STICKY
            }
        }

        startForeground(NOTIFICATION_ID, createNormalNotification("Focus session is running..."))
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    private fun startMonitoring() {
        // Cancel any previous monitoring loop to prevent duplicates
        monitorJob?.cancel()
        monitorJob = serviceScope.launch {
            val distractingApps = prefs.distractingApps.first()
            Log.d("FocusSessionService", "Monitoring started. Distracting apps: $distractingApps")

            while (isActive) {
                if (!isAlarmActive) {
                    val foregroundPkg = detector.getForegroundPackage()
                    val isDist = detector.isDistracting(foregroundPkg, distractingApps)
                    if (foregroundPkg != null) {
                        Log.d("FocusSessionService",
                            "Foreground: $foregroundPkg | Distracting: $isDist | Allowed: $distractingApps")
                    }
                    if (isDist) {
                        val detectedPkg = foregroundPkg ?: continue
                        isAlarmActive = true
                        Log.d("FocusSessionService", "DISTRACTION DETECTED: $detectedPkg")

                        withContext(Dispatchers.Main) {
                            onDistractionDetected?.invoke()
                        }

                        // Update notification to heads-up alarm
                        updateAlarmNotification(detectedPkg)

                        val source = prefs.alarmSource.first()
                        when (source) {
                            AppPreferences.ALARM_SOURCE_FILE -> {
                                val path = prefs.alarmCustomPath.first()
                                if (path != null) soundManager.playFile(path)
                                else soundManager.playBuiltin()
                            }
                            else -> soundManager.playBuiltin()
                        }
                    }
                }
                delay(1000)
            }
        }
    }

    fun dismissAlarm() {
        soundManager.stop()
        isAlarmActive = false
        updateNotification("Focus session is running...")
        onAlarmDismissed?.invoke()
    }

    override fun onDestroy() {
        soundManager.release()
        monitorJob?.cancel()
        serviceScope.cancel()
        // Ensure notification is removed (some OEMs like ColorOS don't auto-remove)
        try {
            stopForeground(true)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    // ===== NOTIFICATIONS =====

    private fun createNotificationChannels() {
        // Low-importance channel for normal session notification (won't heads-up)
        NotificationChannel(
            CHANNEL_ID_NORMAL,
            "Focus Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows focus timer status"
        }.also { notificationManager.createNotificationChannel(it) }

        // High-importance channel for alarm heads-up notification
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID_ALARM,
                "Focus Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when a distracting app is opened"
                enableVibration(true)
                setSound(null, null)
            }.also { notificationManager.createNotificationChannel(it) }
        }
    }

    private fun createNormalNotification(content: String) =
        NotificationCompat.Builder(this, CHANNEL_ID_NORMAL)
            .setContentTitle("FocusBuddy")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()

    /** Updates the foreground notification to a heads-up alarm notification. */
    private fun updateAlarmNotification(distractingPackage: String) {
        // Content intent: tapping notification body opens FocusBuddy app
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 2, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Go Back" action: dismisses alarm AND opens app.
        // Uses SERVICE intent so the handler in onStartCommand can call dismissAlarm()
        // before opening MainActivity — preventing sound-from-notification bug.
        val goBackIntent = Intent(this, FocusSessionService::class.java).apply {
            action = ACTION_DISMISS_ALARM
        }
        val goBackPendingIntent = PendingIntent.getService(
            this, 0, goBackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "End Session" action: stops everything
        val endIntent = Intent(this, FocusSessionService::class.java).apply {
            action = ACTION_END_SESSION
        }
        val endPendingIntent = PendingIntent.getService(
            this, 1, endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALARM)
            .setContentTitle("\uD83D\uDEA8 Distraction Detected!")
            .setContentText("You opened $distractingPackage — get back to focus!")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_media_play, "Go Back", goBackPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Session", endPendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /** Reverts the foreground notification to normal after alarm is dismissed. */
    private fun updateNotification(content: String) {
        startForeground(NOTIFICATION_ID, createNormalNotification(content))
    }
}
