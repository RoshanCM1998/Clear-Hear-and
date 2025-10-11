package com.clearhearand

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.*
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AudioForegroundService : Service() {
    companion object {
        const val ACTION_START = "com.clearhearand.action.START"
        const val ACTION_STOP = "com.clearhearand.action.STOP"
        const val EXTRA_GAIN_100X = "extra_gain_100x"
        const val EXTRA_VOL_100X = "extra_vol_100x"

        private const val NOTIF_CHANNEL_ID = "clear_hear_and_channel"
        private const val NOTIF_ID = 101
        private const val TAG = "ClearHearAnd"
    }

    private val isRunning = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val queue = ArrayBlockingQueue<ShortArray>(8)

    private var gainMultiplier: Float = 1.0f
    private var volumeMultiplier: Float = 1.0f

    private lateinit var logFile: File
    private lateinit var logger: FileWriter

    override fun onCreate() {
        super.onCreate()
        setupLogging()
        debugLog("Service created")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isRunning.get()) return START_STICKY
                val gain100 = intent.getIntExtra(EXTRA_GAIN_100X, 100)
                val vol100 = intent.getIntExtra(EXTRA_VOL_100X, 100)
                gainMultiplier = (gain100 / 100.0f)
                volumeMultiplier = (vol100 / 100.0f)
                startForeground(NOTIF_ID, buildNotification("Running"))
                startAudio()
            }
            ACTION_STOP -> {
                stopAudio()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {}
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        try { logger.close() } catch (_: Throwable) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAudio() {
        isRunning.set(true)
        debugLog("Starting audio pipeline: gain=$gainMultiplier vol=$volumeMultiplier")
        val sampleRate = 48000 // low latency friendly
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, audioFormat)

        val chunkMs = 100
        val samplesPerChunk = (sampleRate * chunkMs) / 1000
        val bytesPerSample = 2
        val recBufferSize = maxOf(minRec, samplesPerChunk * bytesPerSample)
        val playBufferSize = maxOf(minPlay, samplesPerChunk * bytesPerSample)

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(recBufferSize)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(playBufferSize)
            .build()

        audioTrack?.play()

        ioExecutor.execute {
            try {
                audioRecord?.startRecording()
                val buffer = ShortArray(samplesPerChunk)
                while (isRunning.get()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        val copy = ShortArray(read)
                        System.arraycopy(buffer, 0, copy, 0, read)
                        if (!queue.offer(copy)) {
                            debugLog("Queue full, dropping chunk")
                        }
                    }
                }
            } catch (t: Throwable) {
                errorLog("Recorder error: ${t.message}")
            }
        }

        processExecutor.execute {
            try {
                val outBuffer = ShortArray(samplesPerChunk)
                while (isRunning.get()) {
                    val inChunk = queue.take()
                    val n = inChunk.size
                    for (i in 0 until n) {
                        val sample = inChunk[i].toInt()
                        var v = (sample * gainMultiplier * volumeMultiplier)
                        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
                        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
                        outBuffer[i] = v.toInt().toShort()
                    }
                    audioTrack?.write(outBuffer, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            } catch (t: Throwable) {
                errorLog("Playback error: ${t.message}")
            }
        }
    }

    private fun stopAudio() {
        if (!isRunning.getAndSet(false)) return
        debugLog("Stopping audio pipeline")
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        queue.clear()
    }

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

