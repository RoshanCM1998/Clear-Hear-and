package com.clearhear.audio

import android.content.Context
import android.media.*
import android.util.Log
import com.clearhear.audio.dsp.eq.AdditiveEqualizer
import com.clearhear.audio.dsp.eq.IEqualizer
import com.clearhear.audio.dsp.eq.MultiplierEqualizer
import com.clearhear.audio.dsp.lightmode.AdaptiveNoiseGate
import com.clearhear.audio.dsp.lightmode.DcBlocker
import com.clearhear.audio.logging.AudioLogger
import com.clearhear.audio.processors.IAudioModeProcessor
import com.clearhear.audio.processors.ExtremeModeProcessor
import com.clearhear.audio.processors.LightModeProcessor
import com.clearhear.audio.processors.OffModeProcessor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private val generation = AtomicInteger(0)
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val processExecutor = Executors.newSingleThreadExecutor()
    private val queue = ArrayBlockingQueue<ShortArray>(8)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var noiseMode: NoiseMode = NoiseMode.OFF

    @Volatile private var gainMultiplier: Float = 1.0f
    @Volatile private var volumeMultiplier: Float = 1.0f

    val deviceManager = AudioDeviceManager(context)

    // Audio format
    private val sampleRate = 48000
    private val channelIn = AudioFormat.CHANNEL_IN_MONO
    private val channelOut = AudioFormat.CHANNEL_OUT_MONO
    private val encoding = AudioFormat.ENCODING_PCM_16BIT

    private var currentProcessor: IAudioModeProcessor = OffModeProcessor()

    private var logger: AudioLogger? = null

    private var equalizer: IEqualizer? = null
    @Volatile private var eqModeMultiplier: Boolean = false

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

        equalizer = if (eqModeMultiplier) MultiplierEqualizer(sampleRate) else AdditiveEqualizer(sampleRate)

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

        if (deviceManager.bluetoothScoActive) {
            deviceManager.stopBluetoothSco()
        }
    }

    fun setNoiseMode(mode: NoiseMode) {
        val oldMode = noiseMode
        noiseMode = mode
        if (!isRunning.get()) return

        Log.d(tag, "Switching from $oldMode to $mode")
        createProcessorForMode(mode)
    }

    private fun createProcessorForMode(mode: NoiseMode) {
        currentProcessor.cleanup()

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

    // ── Device Selection ──

    fun setInputDevice(deviceId: Int?) {
        deviceManager.setInputDeviceId(deviceId)

        // Always restart the pipeline when changing input device while running.
        // setPreferredDevice() alone is unreliable — recreating AudioRecord is the
        // only way to guarantee the new device is actually used.
        if (isRunning.get()) {
            Log.d(tag, "Input device changed while running — restarting audio pipeline")
            restartAudioPipeline()
        }
    }

    fun setOutputDevice(deviceId: Int?) {
        deviceManager.setOutputDeviceId(deviceId)

        // Output device changes work reliably with setPreferredDevice()
        if (isRunning.get()) {
            deviceManager.applyOutputDevice(audioTrack)
        }
    }

    private fun restartAudioPipeline() {
        if (!isRunning.get()) return
        Log.d(tag, "Restarting audio pipeline for device change")

        // Bump generation so old IO/process threads exit their loops
        val newGen = generation.incrementAndGet()
        Log.d(tag, "Pipeline generation bumped to $newGen")

        // Stop current audio hardware
        try { audioRecord?.stop() } catch (_: Throwable) {}
        try { audioTrack?.stop() } catch (_: Throwable) {}
        currentProcessor.cleanup()
        audioRecord?.release(); audioRecord = null
        audioTrack?.release(); audioTrack = null
        // Unblock the process thread if it's stuck on queue.take()
        queue.clear()
        queue.offer(ShortArray(0)) // sentinel to wake up queue.take()

        if (deviceManager.bluetoothScoActive) {
            deviceManager.stopBluetoothSco()
        }

        // Let old threads exit before reusing the executors
        Thread.sleep(150)

        // Recreate audio with new settings
        setupAudio()
        createProcessorForMode(noiseMode)
        startThreads()
        Log.d(tag, "Audio pipeline restarted (gen=$newGen)")
    }

    // ── Other Settings ──

    fun setPostFilterEnabled(enabled: Boolean) {
        postFilterEnabled = enabled
        postDcBlocker?.reset()
        postNoiseGate?.reset()
        Log.d(tag, "Post-filter ${if (enabled) "enabled" else "disabled"}")
    }

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

    // ── Audio Setup ──

    private fun setupAudio() {
        val pair = deviceManager.createAudioPair(sampleRate, channelIn, channelOut, encoding)
        audioRecord = pair.record
        audioTrack = pair.track

        audioTrack?.play()
        deviceManager.logRoutedDevices(audioRecord, audioTrack)
    }

    // ── Processing Threads ──

    private fun startThreads() {
        val rec = audioRecord ?: return
        val myGen = generation.get()
        Log.d(tag, "startThreads: gen=$myGen")

        ioExecutor.execute {
            try {
                rec.startRecording()
                val samplesPerChunk = (sampleRate * 100) / 1000
                val buffer = ShortArray(samplesPerChunk)
                while (isRunning.get() && generation.get() == myGen) {
                    val read = rec.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val copy = ShortArray(read)
                        System.arraycopy(buffer, 0, copy, 0, read)
                        if (!queue.offer(copy)) {
                            Log.d(tag, "Queue full, dropping chunk")
                        }
                    }
                }
                Log.d(tag, "IO thread exiting (gen=$myGen, current=${generation.get()})")
            } catch (t: Throwable) {
                Log.e(tag, "Recorder error (gen=$myGen): ${t.message}")
            }
        }

        processExecutor.execute {
            try {
                val track = audioTrack ?: return@execute
                val outBuffer = ShortArray((sampleRate * 100) / 1000)
                while (isRunning.get() && generation.get() == myGen) {
                    val inChunk = queue.take()
                    if (inChunk.isEmpty()) {
                        Log.d(tag, "Process thread got sentinel, exiting (gen=$myGen)")
                        break
                    }
                    val n = inChunk.size

                    val gain = gainMultiplier
                    val volume = volumeMultiplier
                    val processor = currentProcessor

                    val inRms = calcRms(inChunk)
                    val inPeak = calcPeak(inChunk)

                    processor.process(inChunk, outBuffer, gain, volume)

                    val eq = equalizer
                    if (eq != null && !eq.isFlat()) {
                        eq.process(outBuffer)
                    }

                    if (postFilterEnabled) {
                        postDcBlocker?.process(outBuffer)
                        postNoiseGate?.process(outBuffer)
                    }

                    val outRms = calcRms(outBuffer)
                    val outPeak = calcPeak(outBuffer)

                    val afterFilterRms: Float
                    val afterFilterPeak: Float
                    val flags: String
                    val routingFlags = deviceManager.getRoutingFlags(audioRecord, audioTrack)

                    if (processor is ExtremeModeProcessor) {
                        afterFilterRms = processor.lastFilteredRms
                        afterFilterPeak = processor.lastFilteredPeak
                        flags = "frameLen=${processor.rawFrameLength};snr=${processor.lastSnr};postFilter=$postFilterEnabled;$routingFlags"
                    } else {
                        afterFilterRms = inRms * gain
                        afterFilterPeak = inPeak * gain
                        flags = "postFilter=$postFilterEnabled;$routingFlags"
                    }

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
                Log.d(tag, "Process thread exiting (gen=$myGen, current=${generation.get()})")
            } catch (t: Throwable) {
                Log.e(tag, "Playback error (gen=$myGen): ${t.message}")
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
