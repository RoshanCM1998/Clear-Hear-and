package com.clearhearand.audio

import android.content.Context
import android.media.*
import android.media.AudioDeviceInfo
import android.util.Log
import com.clearhearand.audio.dsp.eq.AdditiveEqualizer
import com.clearhearand.audio.dsp.eq.IEqualizer
import com.clearhearand.audio.dsp.eq.MultiplierEqualizer
import com.clearhearand.audio.dsp.lightmode.AdaptiveNoiseGate
import com.clearhearand.audio.dsp.lightmode.DcBlocker
import com.clearhearand.audio.logging.AudioLogger
import com.clearhearand.audio.processors.IAudioModeProcessor
import com.clearhearand.audio.processors.ExtremeModeProcessor
import com.clearhearand.audio.processors.LightModeProcessor
import com.clearhearand.audio.processors.OffModeProcessor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Noise cancellation modes available to the user.
 *
 * Each mode has a corresponding processor implementation in the processors package.
 */
enum class NoiseMode {
    /** No noise cancellation - pure passthrough */
    OFF,

    /** Mild noise reduction using Android built-in effects */
    LIGHT,

    /** Strong noise reduction for very noisy environments */
    EXTREME
}

class AudioProcessor(private val context: Context) {
    private val tag = "AudioProcessor"

    private val isRunning = AtomicBoolean(false)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val queue = ArrayBlockingQueue<ShortArray>(8)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var noiseMode: NoiseMode = NoiseMode.OFF

    @Volatile private var gainMultiplier: Float = 1.0f
    @Volatile private var volumeMultiplier: Float = 1.0f

    // Preferred audio devices (null = system default)
    @Volatile private var preferredInputDeviceId: Int? = null
    @Volatile private var preferredOutputDeviceId: Int? = null

    // Audio format - KEEP at 48kHz and 100ms for original quality
    private val sampleRate = 48000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    // Strategy pattern: Different processor for each mode
    private var currentProcessor: IAudioModeProcessor = OffModeProcessor()

    private var logger: AudioLogger? = null

    // 6-band parametric EQ applied after processor, before post-filter
    private var equalizer: IEqualizer? = null
    @Volatile private var eqModeMultiplier: Boolean = false

    // Post-filter: DC Block + Noise Gate applied after volume (catches residual noise)
    @Volatile private var postFilterEnabled: Boolean = false
    private var postDcBlocker: DcBlocker? = null
    private var postNoiseGate: AdaptiveNoiseGate? = null

    fun start(initialMode: NoiseMode = NoiseMode.OFF, gain100x: Int = 100, volume100x: Int = 100, postFilter: Boolean = false) {
        if (isRunning.getAndSet(true)) return
        noiseMode = initialMode
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f
        postFilterEnabled = postFilter

        logger = AudioLogger(context)

        // Initialize EQ based on current mode
        equalizer = if (eqModeMultiplier) MultiplierEqualizer(sampleRate) else AdditiveEqualizer(sampleRate)

        // Initialize post-filter DSP components
        postDcBlocker = DcBlocker(sampleRate, cutoffFreq = 20f)
        postNoiseGate = AdaptiveNoiseGate(
            sampleRate = sampleRate,
            threshold = 50f,
            attackTime = 0.002f,
            releaseTime = 0.050f,
            holdTime = 0.150f,
            reductionDb = -18f
        )

        setupAudio()

        // Create processor for initial mode
        createProcessorForMode(initialMode)

        startThreads()
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        currentProcessor.cleanup()
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        queue.clear()
        logger?.close(); logger = null
        equalizer?.reset(); equalizer = null
        postDcBlocker = null
        postNoiseGate = null
    }

    fun setNoiseMode(mode: NoiseMode) {
        val oldMode = noiseMode
        noiseMode = mode
        if (!isRunning.get()) return

        // Strategy pattern: Switch to different processor
        Log.d(tag, "Switching from $oldMode to $mode")
        createProcessorForMode(mode)
    }

    /**
     * Create and setup processor for the specified mode
     * Cleans up old processor first
     */
    private fun createProcessorForMode(mode: NoiseMode) {
        // Cleanup old processor
        currentProcessor.cleanup()

        // Create new processor based on mode
        currentProcessor = when (mode) {
            NoiseMode.OFF -> {
                Log.d(tag, "Created OffModeProcessor")
                OffModeProcessor()
            }
            NoiseMode.LIGHT -> {
                Log.d(tag, "Created LightModeProcessor")
                LightModeProcessor(context)
            }
            NoiseMode.EXTREME -> {
                Log.d(tag, "Created ExtremeModeProcessor")
                ExtremeModeProcessor(context)
            }
        }

        // Setup new processor
        currentProcessor.setup(audioRecord, sampleRate)
        Log.d(tag, "Active processor: ${currentProcessor.getDescription()}")
    }

    fun setGainAndVolume(gain100x: Int, volume100x: Int) {
        gainMultiplier = gain100x / 100.0f
        volumeMultiplier = volume100x / 100.0f
        Log.d(tag, "Updated gain=$gainMultiplier, volume=$volumeMultiplier")
    }

    fun setEqBands(bands: FloatArray) {
        equalizer?.setBands(bands)
        Log.d(tag, "EQ bands updated: ${bands.joinToString()}")
    }

    fun setEqMode(isMultiplier: Boolean) {
        if (eqModeMultiplier == isMultiplier) return
        eqModeMultiplier = isMultiplier
        val oldEq = equalizer
        equalizer = if (isMultiplier) MultiplierEqualizer(sampleRate) else AdditiveEqualizer(sampleRate)
        oldEq?.reset()
        Log.d(tag, "EQ mode switched to ${if (isMultiplier) "Multiplier" else "Additive"}")
    }

    fun setInputDevice(deviceId: Int?) {
        preferredInputDeviceId = deviceId
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceInfo = if (deviceId != null) {
            am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
        } else null
        audioRecord?.setPreferredDevice(deviceInfo)
        Log.d(tag, "Input device set to: ${deviceInfo?.productName ?: "Default"}")
    }

    fun setOutputDevice(deviceId: Int?) {
        preferredOutputDeviceId = deviceId
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceInfo = if (deviceId != null) {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == deviceId }
        } else null
        audioTrack?.setPreferredDevice(deviceInfo)
        Log.d(tag, "Output device set to: ${deviceInfo?.productName ?: "Default"}")
    }

    fun setPostFilterEnabled(enabled: Boolean) {
        postFilterEnabled = enabled
        // Reset filter state when toggling to avoid artifacts
        postDcBlocker?.reset()
        postNoiseGate?.reset()
        Log.d(tag, "Post-filter ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Set the filtering strategy for LIGHT mode.
     * Only applies if currently in LIGHT mode.
     *
     * @param strategyKey Strategy identifier: "android", "highpass", "adaptive", or "custom"
     */
    fun setLightModeStrategy(strategyKey: String) {
        if (noiseMode != NoiseMode.LIGHT) {
            Log.w(tag, "Cannot set strategy - not in LIGHT mode")
            return
        }

        val processor = currentProcessor
        if (processor is LightModeProcessor) {
            processor.setStrategy(strategyKey, audioRecord, sampleRate)
            Log.d(tag, "LIGHT mode strategy updated: $strategyKey")
        }
    }

    private fun setupAudio() {
        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val chunkMs = 100
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

        // Apply preferred devices if set
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        preferredInputDeviceId?.let { id ->
            val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            audioRecord?.setPreferredDevice(dev)
        }
        preferredOutputDeviceId?.let { id ->
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
            audioTrack?.setPreferredDevice(dev)
        }

        audioTrack?.play()
    }


    private fun startThreads() {
        val rec = audioRecord ?: return
        ioExecutor.execute {
            try {
                rec.startRecording()
                val samplesPerChunk = (sampleRate * 100) / 1000
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
                val outBuffer = ShortArray((sampleRate * 100) / 1000)
                while (isRunning.get()) {
                    val inChunk = queue.take()
                    val n = inChunk.size

                    val gain = gainMultiplier
                    val volume = volumeMultiplier
                    val processor = currentProcessor

                    // Calculate input levels for logging
                    val inRms = calcRms(inChunk)
                    val inPeak = calcPeak(inChunk)

                    // Main processing: Gain → Filter → Volume (handled by each processor)
                    processor.process(inChunk, outBuffer, gain, volume)

                    // EQ applied on cleaned signal — preserves all frequency shaping
                    val eq = equalizer
                    if (eq != null && !eq.isFlat()) {
                        eq.process(outBuffer)
                    }

                    // Post-filter: DC Block + Noise Gate (catches volume-amplified residual)
                    if (postFilterEnabled) {
                        postDcBlocker?.process(outBuffer)
                        postNoiseGate?.process(outBuffer)
                    }

                    // Calculate output levels for logging
                    val outRms = calcRms(outBuffer)
                    val outPeak = calcPeak(outBuffer)

                    // Get post-filter metrics and DFN diagnostics
                    val afterFilterRms: Float
                    val afterFilterPeak: Float
                    val flags: String
                    if (processor is ExtremeModeProcessor) {
                        afterFilterRms = processor.lastFilteredRms
                        afterFilterPeak = processor.lastFilteredPeak
                        flags = "frameLen=${processor.rawFrameLength};snr=${processor.lastSnr};postFilter=$postFilterEnabled"
                    } else {
                        afterFilterRms = inRms * gain
                        afterFilterPeak = inPeak * gain
                        flags = "postFilter=$postFilterEnabled"
                    }

                    // Log with processor description
                    logger?.logFrame(
                        noiseMode.name,
                        inRms, inPeak,
                        afterFilterRms, afterFilterPeak,
                        outRms, outPeak,
                        "${processor.getDescription()};gain=$gain;vol=$volume",
                        flags
                    )

                    track.write(outBuffer, 0, n, AudioTrack.WRITE_BLOCKING)
                }
            } catch (t: Throwable) {
                Log.e(tag, "Playback error: ${t.message}")
            }
        }
    }

    private fun calcRms(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        var sum = 0.0
        for (sample in buffer) {
            val normalized = sample / 32768f
            sum += (normalized * normalized)
        }
        return kotlin.math.sqrt(sum / buffer.size).toFloat()
    }

    private fun calcPeak(buffer: ShortArray): Float {
        if (buffer.isEmpty()) return 0f
        var peak = 0f
        for (sample in buffer) {
            val abs = kotlin.math.abs(sample / 32768f)
            if (abs > peak) peak = abs
        }
        return peak
    }

}
