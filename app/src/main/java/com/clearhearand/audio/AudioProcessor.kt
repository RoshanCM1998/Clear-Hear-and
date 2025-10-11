package com.clearhearand.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.example.audio.RNNoise
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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

    // Audio format
    private val sampleRate = 16000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    fun start(initialMode: NoiseMode = NoiseMode.LIGHT, gain100x: Int = 100, volume100x: Int = 100) {
        if (isRunning.getAndSet(true)) return
        noiseMode = initialMode
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f
        setupAudio()
        startThreads()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        releaseEffects()
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        queue.clear()
        if (rnHandle != 0L) {
            try { RNNoise.release(rnHandle) } catch (_: Throwable) {}
            rnHandle = 0L
        }
    }

    fun setNoiseMode(mode: NoiseMode) {
        noiseMode = mode
        if (!isRunning.get()) return
        // Reconfigure effects / RNNoise on-the-fly
        when (mode) {
            NoiseMode.OFF -> {
                disableEffects()
                teardownRn()
            }
            NoiseMode.LIGHT -> {
                enableEffectsIfSupported()
                teardownRn()
            }
            NoiseMode.EXTREME -> {
                disableEffects()
                setupRn()
            }
        }
    }

    fun setGainAndVolume(gain100x: Int, volume100x: Int) {
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f
    }

    private fun setupAudio() {
        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val chunkMs = 20 // 20ms for low latency and effect friendliness
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

        when (noiseMode) {
            NoiseMode.LIGHT -> enableEffectsIfSupported()
            NoiseMode.EXTREME -> setupRn()
            else -> {}
        }

        audioTrack?.play()
    }

    private fun startThreads() {
        val rec = audioRecord ?: return
        ioExecutor.execute {
            try {
                rec.startRecording()
                val samplesPerChunk = (sampleRate * 20) / 1000
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
                val outBuffer = ShortArray((sampleRate * 20) / 1000)
                while (isRunning.get()) {
                    val inChunk = queue.take()
                    val n = inChunk.size
                    when (noiseMode) {
                        NoiseMode.OFF -> {
                            for (i in 0 until n) {
                                var v = inChunk[i] * gainMultiplier * volumeMultiplier
                                v = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), v))
                                outBuffer[i] = v.toInt().toShort()
                            }
                        }
                        NoiseMode.LIGHT -> {
                            // Built-in effects are applied directly to AudioRecord session; here we just scale
                            for (i in 0 until n) {
                                var v = inChunk[i] * gainMultiplier * volumeMultiplier
                                v = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), v))
                                outBuffer[i] = v.toInt().toShort()
                            }
                        }
                        NoiseMode.EXTREME -> {
                            if (rnHandle != 0L && rnAvailable) {
                                // Convert to float [-1,1]
                                val floats = FloatArray(n)
                                for (i in 0 until n) {
                                    floats[i] = (inChunk[i] / 32768.0f) * gainMultiplier * volumeMultiplier
                                }
                                // Simple frame-wise processing; JNI returns same length
                                val processed = try {
                                    RNNoise.process(rnHandle, floats)
                                } catch (t: Throwable) {
                                    // Fallback passthrough on failure
                                    floats
                                }
                                for (i in 0 until n) {
                                    var v = processed[i] * 32768.0f
                                    v = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), v))
                                    outBuffer[i] = v.toInt().toShort()
                                }
                            } else {
                                for (i in 0 until n) {
                                    var v = inChunk[i] * gainMultiplier * volumeMultiplier
                                    v = max(Short.MIN_VALUE.toFloat(), min(Short.MAX_VALUE.toFloat(), v))
                                    outBuffer[i] = v.toInt().toShort()
                                }
                            }
                        }
                    }
                    track.write(outBuffer, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            } catch (t: Throwable) {
                Log.e(tag, "Playback error: ${t.message}")
            }
        }
    }

    private fun enableEffectsIfSupported() {
        try {
            val sessionId = audioRecord?.audioSessionId ?: return
            if (NoiseSuppressor.isAvailable()) {
                ns?.release(); ns = null
                ns = NoiseSuppressor.create(sessionId)
                ns?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                agc?.release(); agc = null
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                aec?.release(); aec = null
                aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = true
            }
        } catch (t: Throwable) {
            Log.w(tag, "Effect enable failed: ${t.message}")
        }
    }

    private fun disableEffects() {
        try { ns?.enabled = false } catch (_: Throwable) {}
        try { agc?.enabled = false } catch (_: Throwable) {}
        try { aec?.enabled = false } catch (_: Throwable) {}
    }

    private fun releaseEffects() {
        try { ns?.release() } catch (_: Throwable) {}
        try { agc?.release() } catch (_: Throwable) {}
        try { aec?.release() } catch (_: Throwable) {}
        ns = null; agc = null; aec = null
    }

    private fun setupRn() {
        if (rnHandle != 0L) return
        rnAvailable = try {
            rnHandle = RNNoise.init()
            rnHandle != 0L
        } catch (t: Throwable) { false }
        if (!rnAvailable) {
            Log.w(tag, "RNNoise not available; falling back to passthrough")
        }
    }

    private fun teardownRn() {
        if (rnHandle != 0L) {
            try { RNNoise.release(rnHandle) } catch (_: Throwable) {}
            rnHandle = 0L
        }
        rnAvailable = false
    }
}