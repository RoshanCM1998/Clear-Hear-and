package com.clearhearand.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.clearhearand.audio.AudioProcessor
import com.clearhearand.audio.NoiseMode
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class AudioForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.clearhearand.action.START"
        const val ACTION_STOP = "com.clearhearand.action.STOP"
        const val ACTION_SET_MODE = "com.clearhearand.action.SET_MODE"
        const val ACTION_SET_PARAMS = "com.clearhearand.action.SET_PARAMS"
        const val ACTION_SET_LIGHT_STRATEGY = "com.clearhearand.action.SET_LIGHT_STRATEGY"

        const val EXTRA_GAIN_100X = "extra_gain_100x"
        const val EXTRA_VOL_100X = "extra_vol_100x"
        const val EXTRA_MODE = "extra_noise_mode" // OFF | LIGHT | EXTREME
        const val EXTRA_LIGHT_STRATEGY = "extra_light_strategy" // android | highpass | adaptive | custom

        private const val NOTIF_CHANNEL_ID = "clear_hear_and_channel"
        private const val NOTIF_ID = 101
        private const val TAG = "ClearHearAnd"
    }

    private var processor: AudioProcessor? = null

    private lateinit var logFile: File
    private lateinit var logger: FileWriter

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        debugLog("Service created")
        createNotificationChannel()
        processor = AudioProcessor(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val gain100 = intent.getIntExtra(EXTRA_GAIN_100X, 100)
                val vol100 = intent.getIntExtra(EXTRA_VOL_100X, 100)
                val modeStr = intent.getStringExtra(EXTRA_MODE) ?: "LIGHT"
                val mode = runCatching { NoiseMode.valueOf(modeStr) }.getOrDefault(NoiseMode.LIGHT)
                startForeground(NOTIF_ID, buildNotification("Running: $mode"))
                processor?.start(mode, gain100, vol100)
            }
            ACTION_SET_MODE -> {
                val modeStr = intent.getStringExtra(EXTRA_MODE) ?: return START_STICKY
                val mode = runCatching { NoiseMode.valueOf(modeStr) }.getOrDefault(NoiseMode.LIGHT)
                debugLog("Switching mode to $mode")
                processor?.setNoiseMode(mode)
            }
            ACTION_SET_PARAMS -> {
                val gain100 = intent.getIntExtra(EXTRA_GAIN_100X, 100)
                val vol100 = intent.getIntExtra(EXTRA_VOL_100X, 100)
                debugLog("Updating params: gain=$gain100, volume=$vol100")
                processor?.setGainAndVolume(gain100, vol100)
            }
            ACTION_SET_LIGHT_STRATEGY -> {
                val strategy = intent.getStringExtra(EXTRA_LIGHT_STRATEGY) ?: "android"
                debugLog("Updating LIGHT mode strategy: $strategy")
                processor?.setLightModeStrategy(strategy)
            }
            ACTION_STOP -> {
                processor?.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        processor?.stop()
        try { logger.close() } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Clear Hear And", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: String): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("clear-hear-and")
            .setContentText("Audio processing: $state")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun setupLogging() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val logsDir = File(getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) logsDir.mkdirs()
        logFile = File(logsDir, "clear_hear_and_$ts.log")
        logger = FileWriter(logFile, true)
        debugLog("Logging to: ${logFile.absolutePath}")
    }

    private fun debugLog(msg: String) {
        Log.d(TAG, msg)
        try { logger.write("D ${System.currentTimeMillis()}: $msg\n"); logger.flush() } catch (_: Throwable) {}
    }

    private fun errorLog(msg: String) {
        Log.e(TAG, msg)
        try { logger.write("E ${System.currentTimeMillis()}: $msg\n"); logger.flush() } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val nm = NotificationManagerCompat.from(this)
            val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
                .setContentTitle("clear-hear-and error")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .build()
            nm.notify(NOTIF_ID + 1, notif)
        }
    }
}

