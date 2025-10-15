package com.clearhearand.audio.processors.extrememode

import android.content.Context
import android.util.Log
import com.clearhearand.audio.dsp.BandPassFilter
import com.clearhearand.audio.dsp.SpectralGate
import com.clearhearand.audio.recording.ExtremeNoiseProfileRecorder

/**
 * Spectral Gate Strategy - Manual threshold-based voice isolation
 *
 * This strategy uses signal processing techniques to separate voice from noise:
 * 1. **Band-pass filter (300-3400 Hz)**: Removes all frequencies outside voice range
 * 2. **Spectral Gate**: RMS-based voice/noise discrimination with learning OR preloaded profile
 *
 * How it works:
 * - With pre-recorded profile: Instantly uses saved noise floor (no learning delay)
 * - Without profile: Learning phase (first 2 seconds) measures ambient noise floor
 * - Detection: Voice RMS should be 3x louder than noise floor
 * - Suppression: Reduces noise by 75% (-12dB) while preserving voice
 * - Hold time: 300ms to protect sentence endings
 *
 * Advantages:
 * - Fast processing (no ML inference)
 * - No external dependencies
 * - Predictable behavior
 * - Works even if started during conversation (with pre-recorded profile)
 *
 * Limitations:
 * - Requires quiet learning period (if no profile)
 * - May struggle with non-stationary noise
 * - Simple threshold-based detection
 *
 * @see IExtremeStrategy
 * @see BandPassFilter
 * @see SpectralGate
 * @see ExtremeNoiseProfileRecorder
 */
class SpectralGateStrategy(private val context: Context) : IExtremeStrategy {

    private val tag = "SpectralGateStrategy"

    // Band-pass filter for voice isolation (300-3400 Hz)
    private var bandPassFilter: BandPassFilter? = null

    // Spectral gate for noise suppression
    private var spectralGate: SpectralGate? = null

    // Noise profile recorder for loading saved profiles
    private val noiseProfileRecorder by lazy { ExtremeNoiseProfileRecorder(context) }

    /**
     * Initialize band-pass filter and spectral gate.
     *
     * Loads saved noise profile if available, otherwise spectral gate will learn in first 2 seconds.
     */
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
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

        // Load saved noise profile (if available)
        val preloadedNoiseFloor = noiseProfileRecorder.getNoiseFloor()

        // Initialize spectral gate (moderate noise suppression)
        try {
            spectralGate = SpectralGate(
                sampleRate = sampleRate,
                chunkSize = chunkSize,
                thresholdDb = -30f,      // Voice detection threshold (unused now, using 3x multiplier)
                reductionDb = -12f,      // 75% noise reduction (preserves voice volume)
                attackMs = 5f,           // Fast attack for speech
                holdMs = 300f,           // Protect sentence endings
                releaseMs = 300f,        // Smooth transitions
                preloadedNoiseFloor = preloadedNoiseFloor  // Use saved profile (or null = learn)
            )

            if (preloadedNoiseFloor != null) {
                val timestamp = noiseProfileRecorder.getProfileTimestamp() ?: "unknown"
                Log.d(tag, "SpectralGate using SAVED noise profile: RMS=$preloadedNoiseFloor (from $timestamp)")
            } else {
                Log.d(tag, "SpectralGate will LEARN noise floor (2 seconds, stay silent!)")
            }
        } catch (e: Exception) {
            Log.e(tag, "SpectralGate setup failed: ${e.message}")
        }

        Log.d(tag, "SpectralGateStrategy setup complete")
    }

    /**
     * Process audio through band-pass filter then spectral gate.
     */
    override fun process(samples: ShortArray) {
        // Step 1: Apply band-pass filter (300-3400 Hz - isolate voice frequencies)
        // This removes all low-frequency rumble and high-frequency hiss
        bandPassFilter?.process(samples)

        // Step 2: Apply spectral gate (moderate noise suppression within voice range)
        // This removes background sounds that fall within the voice frequency range
        spectralGate?.process(samples)
    }

    /**
     * Cleanup band-pass filter and spectral gate.
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

        Log.d(tag, "SpectralGateStrategy cleanup complete")
    }

    /**
     * Get detailed description showing current state.
     *
     * Includes:
     * - Active components (BandPass, Gate)
     * - Gate status (LEARN/VOICE/NOISE)
     * - RMS levels and threshold
     * - Applied gain
     * - Noise floor
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

        return "SpectralGate[${active.joinToString("+")}]"
    }

    /**
     * Get display name for UI.
     */
    override fun getDisplayName(): String = "Spectral Gate"

    /**
     * Check if ready to process audio.
     *
     * During learning phase (first 2 seconds), returns false.
     */
    override fun isReady(): Boolean {
        return spectralGate?.isLearning == false
    }
}
