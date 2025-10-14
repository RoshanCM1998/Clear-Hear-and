package com.clearhearand.audio.processors

import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.clearhearand.audio.dsp.BandPassFilter
import com.clearhearand.audio.dsp.SpectralGate
import com.example.audio.RNNoise

/**
 * EXTREME Mode Processor - Multi-Stage Voice Isolation
 *
 * This processor provides strong noise reduction for very noisy environments
 * with the goal of isolating human voice from background sounds while maintaining
 * loud, clear output for hearing aid users.
 *
 * Multi-stage processing pipeline:
 * 1. **Band-pass filter (300-3400 Hz)**: Removes all frequencies outside voice range
 * 2. **Spectral Gate (moderate)**: Suppresses background noise within voice range (-12dB)
 * 3. **Gain amplification**: User-controlled hearing aid amplification (applied AFTER filtering)
 * 4. **Volume control**: Final output level adjustment
 *
 * The combination of these stages ensures clear voice with maximum volume for hearing aid users,
 * even in noisy environments like crowded restaurants, with music playing, traffic, or construction.
 *
 * Use cases:
 * - Noisy environments with music (isolate voice from background music)
 * - Crowded places (restaurants, cafes, parties)
 * - Traffic and outdoor noise
 * - Maximum voice clarity while preserving volume
 * - Hearing aid use where loud output is critical
 *
 * Processing pipeline:
 * ```
 * Input (microphone)
 *   ↓
 * Band-Pass Filter (300-3400 Hz - isolate voice frequencies)
 *   ↓
 * Spectral Gate (-12dB reduction, 300ms hold - moderate noise suppression)
 *   ↓
 * Gain amplification (hearing aid boost - AFTER filtering for loud output)
 *   ↓
 * Volume multiplication (final level control)
 *   ↓
 * Clamp to prevent overflow
 *   ↓
 * Output (speaker/headphones - LOUD and CLEAR)
 * ```
 *
 * Technical details:
 * - Band-pass: 300-3400 Hz (telephone quality, optimized for speech intelligibility)
 * - Spectral gate: -30dB threshold, -12dB reduction (75% noise suppression, preserves voice volume)
 * - Hold time: 300ms (protects sentence endings)
 * - Attack/Release: 5ms/300ms (smooth transitions)
 * - Learning phase: 2 seconds (adapts to ambient noise floor)
 * - Gain applied AFTER filtering (ensures loud output)
 *
 * @see IAudioModeProcessor
 */
class ExtremeModeProcessor : IAudioModeProcessor {
    
    private val tag = "ExtremeModeProcessor"

    // Android built-in effect (hardware accelerated)
    private var noiseSuppressor: NoiseSuppressor? = null

    // Band-pass filter for voice isolation (300-3400 Hz)
    private var bandPassFilter: BandPassFilter? = null

    // Spectral gate for aggressive noise suppression
    private var spectralGate: SpectralGate? = null

    // RNNoise (ML-based noise suppression) - DISABLED for stability
    // Will be re-enabled in future updates after thorough testing
    private var rnHandle: Long = 0L
    private var rnAvailable = false
    
    /**
     * Process audio with multi-stage voice isolation.
     *
     * Pipeline: Band-pass (300-3400Hz) → Spectral Gate → Compensated Gain → Volume
     *
     * This ensures maximum voice clarity by:
     * 1. Removing all non-voice frequencies (300-3400 Hz band-pass)
     * 2. Suppressing background noise within voice range (spectral gate)
     * 3. Amplifying the cleaned signal with compensation for filter losses
     * 4. Final volume adjustment for output
     *
     * NOTE: Gain is applied AFTER filtering with 1.5x compensation to ensure loud output
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Copy input to output
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)

        // Step 1: Apply band-pass filter (300-3400 Hz - isolate voice frequencies)
        // This removes all low-frequency rumble and high-frequency hiss
        bandPassFilter?.process(outChunk)

        // Step 2: Apply spectral gate (moderate noise suppression within voice range)
        // This removes background sounds that fall within the voice frequency range
        spectralGate?.process(outChunk)

        // Step 3: Apply gain amplification with filter compensation
        // COMPENSATION: 1.5x multiplier compensates for ~33% signal loss from filtering
        // This ensures EXTREME mode is LOUDER than OFF mode, suitable for hearing aid use
        val FILTER_COMPENSATION = 1.5f
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * gain * FILTER_COMPENSATION)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }

        // Step 4: Apply volume for final output level
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * volume)

            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()

            outChunk[i] = v.toInt().toShort()
        }
    }
    
    /**
     * Returns description showing active components with detailed metrics.
     *
     * Includes spectral gate metrics for analysis:
     * - Gate status (learning/voice/noise)
     * - RMS levels and threshold
     * - Applied gain
     * - Compensation multiplier (1.5x)
     *
     * Example: "EXTREME-BandPass+SpectralGate[VOICE rms=0.005 thr=0.003 gain=1.0]+Comp1.5x"
     */
    override fun getDescription(): String {
        val active = mutableListOf<String>()

        if (bandPassFilter != null) {
            active.add("BandPass(300-3400Hz)")
        }

        if (spectralGate != null) {
            val gate = spectralGate!!
            val status = when {
                gate.isLearning -> "LEARN"
                gate.lastDetectedAsVoice -> "VOICE"
                else -> "NOISE"
            }
            val rms = "%.4f".format(gate.lastRms)
            val thr = "%.4f".format(gate.lastThreshold)
            val gain = "%.2f".format(gate.lastAppliedGain)
            val noiseFloor = "%.4f".format(gate.getNoiseFloor())
            active.add("Gate[$status rms=$rms thr=$thr gain=$gain floor=$noiseFloor]")
        }

        if (noiseSuppressor?.enabled == true) {
            active.add("AndroidNS")
        }

        active.add("Comp1.5x")  // Compensation multiplier

        return "EXTREME-${active.joinToString("+")}"
    }
    
    /**
     * Setup multi-stage voice isolation components.
     *
     * Initializes band-pass filter, spectral gate, and Android NoiseSuppressor.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        val sessionId = audioRecord?.audioSessionId ?: return
        val chunkSize = 4800  // 100ms at 48kHz

        // Initialize band-pass filter (300-3400 Hz for human voice)
        try {
            bandPassFilter = BandPassFilter(
                sampleRate = sampleRate,
                lowCutHz = 300f,   // Remove low-frequency noise/rumble
                highCutHz = 3400f  // Remove high-frequency hiss
            )
            Log.d(tag, "BandPassFilter initialized (300-3400 Hz voice isolation)")
        } catch (e: Exception) {
            Log.e(tag, "BandPassFilter setup failed: ${e.message}")
        }

        // Initialize spectral gate (moderate noise suppression)
        try {
            spectralGate = SpectralGate(
                sampleRate = sampleRate,
                chunkSize = chunkSize,
                thresholdDb = -30f,      // Voice detection threshold (less strict for music)
                reductionDb = -12f,      // 75% noise reduction (preserves voice volume)
                attackMs = 5f,           // Fast attack for speech
                holdMs = 300f,           // Protect sentence endings
                releaseMs = 300f         // Smooth transitions
            )
            Log.d(tag, "SpectralGate initialized (-12dB reduction, 300ms hold)")
        } catch (e: Exception) {
            Log.e(tag, "SpectralGate setup failed: ${e.message}")
        }

        // Enable Android NoiseSuppressor (hardware acceleration)
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor?.release()
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(tag, "NoiseSuppressor enabled (hardware accelerated)")
            } else {
                Log.w(tag, "NoiseSuppressor not available on this device")
            }
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor setup failed: ${e.message}")
        }

        Log.d(tag, "EXTREME mode setup complete: ${getDescription()}")
    }
    
    /**
     * Cleanup and release all voice isolation components.
     *
     * Releases band-pass filter, spectral gate, and Android effects.
     */
    override fun cleanup() {
        // Release band-pass filter
        try {
            bandPassFilter?.reset()
            bandPassFilter = null
            Log.d(tag, "BandPassFilter released")
        } catch (e: Exception) {
            Log.w(tag, "BandPassFilter cleanup failed: ${e.message}")
        }

        // Release spectral gate
        try {
            spectralGate?.reset()
            spectralGate = null
            Log.d(tag, "SpectralGate released")
        } catch (e: Exception) {
            Log.w(tag, "SpectralGate cleanup failed: ${e.message}")
        }

        // Disable and release NoiseSuppressor
        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
            noiseSuppressor = null
            Log.d(tag, "NoiseSuppressor released")
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor cleanup failed: ${e.message}")
        }

        Log.d(tag, "EXTREME mode cleanup complete")
    }
}
