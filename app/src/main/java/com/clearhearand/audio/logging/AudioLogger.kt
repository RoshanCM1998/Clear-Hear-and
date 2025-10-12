package com.clearhearand.audio.logging

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AudioLogger(context: Context) {
    private val logsDir: File
    private val writer: FileWriter
    private val frameCounter = AtomicInteger(0)

    init {
        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists()) dir.mkdirs()
        logsDir = dir
        val tsDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(dir, "hearing_log_${tsDay}.txt")
        val exists = file.exists()
        writer = FileWriter(file, true)
        if (!exists) {
            writer.write("timestamp,mode,in_rms,in_peak,after_gain_rms,after_gain_peak,after_vol_rms,after_vol_peak,params,flags\n")
            writer.flush()
        }
    }

    fun logFrame(
        mode: String,
        inRms: Float,
        inPeak: Float,
        afterGainRms: Float,
        afterGainPeak: Float,
        afterVolRms: Float,
        afterVolPeak: Float,
        params: String,
        flags: String
    ) {
        val n = frameCounter.incrementAndGet()
        if (n % 10 != 0) return // Log every 1s at 48kHz/100ms = 10 frames/sec
        
        // Human-readable timestamp: YYYY-MM-DD HH:mm:ss
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        
        try {
            writer.write("$timestamp,$mode,$inRms,$inPeak,$afterGainRms,$afterGainPeak,$afterVolRms,$afterVolPeak,${params.replace(',', ';')},${flags.replace(',', ';')}\n")
            writer.flush()
        } catch (_: Throwable) {}
    }

    fun getLogsDir(): File = logsDir

    fun close() { try { writer.close() } catch (_: Throwable) {} }
}
