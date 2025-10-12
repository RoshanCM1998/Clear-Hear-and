package com.clearhearand.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.example.audio.RNNoise
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class NoiseMode { OFF, LIGHT, EXTREME }

class AudioProcessor(private val context: Context) {
    private val tag = "AudioProcessor"

    private val isRunning = AtomicBoolean(false)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val queue = ArrayBlockingQueue<ShortArray>(8)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null
    private var aec: AcousticEchoCanceler? = null

    @Volatile private var noiseMode: NoiseMode = NoiseMode.LIGHT

    @Volatile private var gainMultiplier: Float = 1.0f
    @Volatile private var volumeMultiplier: Float = 1.0f

    // RNNoise
    private var rnHandle: Long = 0L
    private var rnAvailable: Boolean = false

    // Audio format - KEEP at 48kHz and 100ms for original quality
    private val sampleRate = 48000  // Restored to match old code for better quality or 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    // DSP components - NULLABLE so they're only created when needed (LIGHT/EXTREME modes)
    private var hp300: Biquad? = null
    private var lp3400: Biquad? = null
    private var softLimiter: SoftLimiter? = null
    private var wienerLight: WienerSuppressor? = null
    private var wienerExtreme: WienerSuppressor? = null

    private var logger: AudioLogger? = null

    fun start(initialMode: NoiseMode = NoiseMode.LIGHT, gain100x: Int = 100, volume100x: Int = 100) {
        if (isRunning.getAndSet(true)) return
        noiseMode = initialMode
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f

        logger = AudioLogger(context)

        setupAudio()
        
        // Enable Android effects for LIGHT/EXTREME modes
        when (initialMode) {
            NoiseMode.LIGHT, NoiseMode.EXTREME -> {
                enableEffectsIfSupported()
                Log.d(tag, "Started in $initialMode mode with Android effects")
            }
            NoiseMode.OFF -> {
                Log.d(tag, "Started in OFF mode")
            }
        }
        
        startThreads()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        releaseEffects()
        destroyAllDsp()  // Clean up DSP components
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        queue.clear()
        if (rnHandle != 0L) {
            try { RNNoise.release(rnHandle) } catch (_: Throwable) {}
            rnHandle = 0L
        }
        logger?.close(); logger = null
    }

    fun setNoiseMode(mode: NoiseMode) {
        val oldMode = noiseMode
        noiseMode = mode
        if (!isRunning.get()) return

        // CRITICAL: When switching modes, properly clean up old mode and setup new mode
        when (mode) {
            NoiseMode.OFF -> {
                // OFF mode: Disable EVERYTHING
                disableEffects()
                releaseEffects()  // Actually release Android effects
                teardownRn()
                destroyAllDsp()  // Destroy ALL DSP components
                Log.d(tag, "Switched to OFF: All DSP and effects disabled")
            }
            NoiseMode.LIGHT -> {
                // LIGHT mode: Enable Android effects only (no custom DSP)
                teardownRn()  // No RNNoise
                destroyAllDsp()  // No custom DSP
                enableEffectsIfSupported()  // Android built-in effects
                Log.d(tag, "Switched to LIGHT: Android effects enabled")
            }
            NoiseMode.EXTREME -> {
                // EXTREME mode: Keep Android effects enabled (stronger settings if possible)
                // For now, same as LIGHT until we implement windowed DSP
                teardownRn()
                destroyAllDsp()
                enableEffectsIfSupported()  // Keep Android effects enabled
                Log.d(tag, "Switched to EXTREME: Android effects enabled (same as LIGHT for now)")
            }
        }
    }

    fun setGainAndVolume(gain100x: Int, volume100x: Int) {
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f
        Log.d(tag, "Updated gain=$gainMultiplier, volume=$volumeMultiplier")
    }

    private fun setupAudio() {
        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val chunkMs = 100 // Restored to 100ms to match old code behavior 20
        val samplesPerChunk = (sampleRate * chunkMs) / 1000
        val bytesPerSample = 2
        val recBufferSize = max(minRec, samplesPerChunk * bytesPerSample)
        val playBufferSize = max(minPlay, samplesPerChunk * bytesPerSample)

        audioRecord = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelIn)
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
                    .setEncoding(encoding)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(playBufferSize)
            .build()

        audioTrack?.play()
    }

    /**
     * Initialize DSP components based on mode
     * CRITICAL: OFF mode gets NOTHING initialized
     * NOTE: Currently LIGHT and EXTREME use only Android built-in effects, no custom DSP
     */
    private fun initializeDspForMode(mode: NoiseMode) {
        when (mode) {
            NoiseMode.OFF -> {
                // OFF mode: NO DSP components at all
                destroyAllDsp()
                Log.d(tag, "OFF mode: No DSP")
            }
            NoiseMode.LIGHT -> {
                // LIGHT mode: Only Android built-in effects (no custom DSP for now)
                destroyAllDsp()
                Log.d(tag, "LIGHT mode: Android built-in effects only")
            }
            NoiseMode.EXTREME -> {
                // EXTREME mode: Only Android built-in effects (no custom DSP for now)
                // Note: Android effects disabled in EXTREME, so this is currently same as OFF
                destroyAllDsp()
                Log.d(tag, "EXTREME mode: Currently same as LIGHT (Android effects)")
            }
        }
    }

    /**
     * Destroy ALL DSP components - used when switching to OFF or between modes
     */
    private fun destroyAllDsp() {
        hp300 = null
        lp3400 = null
        softLimiter = null
        wienerLight = null
        wienerExtreme = null
    }

    private fun calcRmsPeak(buf: FloatArray): Pair<Float, Float> {
        var sum = 0.0
        var peak = 0f
        for (v in buf) {
            sum += (v * v)
            val a = abs(v)
            if (a > peak) peak = a
        }
        val rms = kotlin.math.sqrt(sum / buf.size).toFloat()
        return rms to peak
    }

    private fun startThreads() {
        val rec = audioRecord ?: return
        ioExecutor.execute {
            try {
                rec.startRecording()
                val samplesPerChunk = (sampleRate * 100) / 1000  // 100ms chunks to match old code
                val buffer = ShortArray(samplesPerChunk)
                while (isRunning.get()) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val copy = ShortArray(read)
                        System.arraycopy(buffer, 0, copy, 0, read)
                        if (!queue.offer(copy)) {
                            Log.d(tag, "Queue full, dropping chunk")
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(tag, "Recorder error: ${t.message}")
            }
        }

        processExecutor.execute {
            try {
                val track = audioTrack ?: return@execute
                val outBuffer = ShortArray((sampleRate * 100) / 1000)  // 100ms chunks to match old code
                val floatBuffer = FloatArray((sampleRate * 100) / 1000)
                while (isRunning.get()) {
                    val inChunk = queue.take()
                    val n = inChunk.size

                    // Read mode once for this frame to avoid race conditions
                    val currentMode = noiseMode
                    when (currentMode) {
                        NoiseMode.OFF -> processOff(inChunk, outBuffer)
                        NoiseMode.LIGHT -> processLight(inChunk, floatBuffer, outBuffer)
                        NoiseMode.EXTREME -> processExtreme(inChunk, floatBuffer, outBuffer)
                    }

                    track.write(outBuffer, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            } catch (t: Throwable) {
                Log.e(tag, "Playback error: ${t.message}")
            }
        }
    }

    /**
     * OFF Mode: Pure pass-through with NO filtering
     * - Directly route input → gain → master volume → output
     * - NO band-pass, NO noise suppression, NO AGC, NO limiter
     * - NO Android built-in effects
     * - Maintains same loudness as original pre-noise-cancel implementation
     * - Processes EXACTLY like the old code
     */
    private fun processOff(inChunk: ShortArray, outShorts: ShortArray) {
        val gain = gainMultiplier
        val volume = volumeMultiplier

        // Process exactly like old code for consistency
        for (i in inChunk.indices) {
            val sample = inChunk[i].toInt()
            var v = (sample * gain * volume)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outShorts[i] = v.toInt().toShort()
        }

        // Log OFF mode (minimal logging, no float conversion needed)
        if (logger != null && inChunk.isNotEmpty()) {
            val inSample = inChunk[0] / 32768f
            val outSample = outShorts[0] / 32768f
            logger?.logFrame(
                "OFF",
                abs(inSample), abs(inSample),
                abs(inSample) * gain, abs(inSample) * gain,
                abs(outSample), abs(outSample),
                "passthrough;gain=$gain;vol=$volume",
                ""
            )
        }
    }

    /**
     * LIGHT Mode: Mild noise reduction with preserved loudness
     * - Rely on Android built-in effects (NoiseSuppressor, AGC, AEC)
     * - NO custom DSP (Wiener filter doesn't work with 100ms/48kHz chunks)
     * - Simple gain/volume control
     */
    private fun processLight(inChunk: ShortArray, floats: FloatArray, outShorts: ShortArray) {
        val gain = gainMultiplier
        val volume = volumeMultiplier

        // Android built-in effects are already applied to audioRecord
        // Just do simple gain/volume processing like OFF mode
        for (i in inChunk.indices) {
            val sample = inChunk[i].toInt()
            var v = (sample * gain * volume)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outShorts[i] = v.toInt().toShort()
        }

        // Log for verification
        if (inChunk.isNotEmpty()) {
            val inSample = inChunk[0] / 32768f
            val outSample = outShorts[0] / 32768f
            logger?.logFrame(
                "LIGHT",
                abs(inSample), abs(inSample),
                abs(inSample) * gain, abs(inSample) * gain,
                abs(outSample), abs(outSample),
                "android_effects;gain=$gain;vol=$volume",
                ""
            )
        }
    }

    /**
     * EXTREME Mode: Currently same as LIGHT mode
     * - Android built-in effects provide the noise suppression
     * - Custom DSP (Wiener/RNNoise) doesn't work with 100ms/48kHz chunks
     * - TODO: Implement proper windowed processing if needed
     */
    private fun processExtreme(inChunk: ShortArray, floats: FloatArray, outShorts: ShortArray) {
        val gain = gainMultiplier
        val volume = volumeMultiplier

        // For now, same as LIGHT mode - rely on Android effects
        // In future, could add custom DSP with proper windowing
        for (i in inChunk.indices) {
            val sample = inChunk[i].toInt()
            var v = (sample * gain * volume)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outShorts[i] = v.toInt().toShort()
        }

        // Log for verification
        if (inChunk.isNotEmpty()) {
            val inSample = inChunk[0] / 32768f
            val outSample = outShorts[0] / 32768f
            logger?.logFrame(
                "EXTREME",
                abs(inSample), abs(inSample),
                abs(inSample) * gain, abs(inSample) * gain,
                abs(outSample), abs(outSample),
                "android_effects;gain=$gain;vol=$volume",
                ""
            )
        }
    }

    private fun enableEffectsIfSupported() {
        try {
            val sessionId = audioRecord?.audioSessionId ?: return
            if (NoiseSuppressor.isAvailable()) {
                ns?.release(); ns = null
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
                Log.d(tag, "NoiseSuppressor enabled")
            }
            if (AutomaticGainControl.isAvailable()) {
                agc?.release(); agc = null
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
                Log.d(tag, "AutomaticGainControl enabled")
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec?.release(); aec = null
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
                Log.d(tag, "AcousticEchoCanceler enabled")
            }
        } catch (t: Throwable) {
            Log.w(tag, "Effect enable failed: ${t.message}")
        }
    }

    private fun disableEffects() {
        try { ns?.enabled = false; Log.d(tag, "NoiseSuppressor disabled") } catch (_: Throwable) {}
        try { agc?.enabled = false; Log.d(tag, "AGC disabled") } catch (_: Throwable) {}
        try { aec?.enabled = false; Log.d(tag, "AEC disabled") } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        try { ns?.release(); Log.d(tag, "NoiseSuppressor released") } catch (_: Throwable) {}
        try { agc?.release(); Log.d(tag, "AGC released") } catch (_: Throwable) {}
        try { aec?.release(); Log.d(tag, "AEC released") } catch (_: Throwable) {}
        ns = null; agc = null; aec = null
    }

    private fun setupRn() {
        if (rnHandle != 0L) return
        rnAvailable = try {
            rnHandle = RNNoise.init()
            val success = rnHandle != 0L
            if (success) Log.d(tag, "RNNoise initialized")
            success
        } catch (t: Throwable) {
            Log.w(tag, "RNNoise init failed: ${t.message}")
            false
        }
        if (!rnAvailable) {
            Log.w(tag, "RNNoise not available; falling back to Wiener")
        }
    }

    private fun teardownRn() {
        if (rnHandle != 0L) {
            try {
                RNNoise.release(rnHandle)
                Log.d(tag, "RNNoise released")
            } catch (_: Throwable) {}
            rnHandle = 0L
        }
        rnAvailable = false
    }
}
