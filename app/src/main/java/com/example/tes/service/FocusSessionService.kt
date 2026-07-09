package com.example.tes.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
    private var monitorJob: Job? = null
    private var isAlarmActive = false

    companion object {
        const val CHANNEL_ID = "focus_timer_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.example.tes.STOP_SESSION"

        var onDistractionDetected: (() -> Unit)? = null
        var onAlarmDismissed: (() -> Unit)? = null
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
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, createNotification("Focus session is running..."))
        startMonitoring()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = binder

    private fun startMonitoring() {
        monitorJob = serviceScope.launch {
            val distractingApps = prefs.distractingApps.first()

            while (isActive) {
                if (!isAlarmActive) {
                    val foregroundPkg = detector.getForegroundPackage()
                    if (detector.isDistracting(foregroundPkg, distractingApps)) {
                        isAlarmActive = true
                        withContext(Dispatchers.Main) {
                            onDistractionDetected?.invoke()
                        }

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
        onAlarmDismissed?.invoke()
    }

    override fun onDestroy() {
        soundManager.release()
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus Timer",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows focus timer status"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("FocusBuddy")
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()
}
