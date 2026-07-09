package com.example.tes.sound

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RawRes
import com.example.tes.R

class SoundManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    enum class BuiltinSound(@RawRes val resId: Int) {
        DEFAULT(R.raw.alarm_default),
        SCREAM(R.raw.alarm_scream),
        GENTLE(R.raw.alarm_gentle)
    }

    fun playBuiltin(sound: BuiltinSound = BuiltinSound.DEFAULT) {
        stop()
        mediaPlayer = MediaPlayer.create(context, sound.resId).apply {
            isLooping = true
            setVolume(1.0f, 1.0f)
            start()
        }
        vibrate()
    }

    fun playFile(path: String) {
        stop()
        mediaPlayer = MediaPlayer.create(context, Uri.parse(path)).apply {
            isLooping = true
            setVolume(1.0f, 1.0f)
            start()
        }
        vibrate()
    }

    fun stop() {
        mediaPlayer?.apply {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        vibrator.cancel()
    }

    val isPlaying: Boolean get() = mediaPlayer?.isPlaying == true

    private fun vibrate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 500, 300, 500, 300),
                    intArrayOf(0, 255, 0, 255, 0),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 500, 300, 500, 300), -1)
        }
    }

    fun release() {
        stop()
    }
}
