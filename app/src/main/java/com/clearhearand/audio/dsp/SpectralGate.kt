package com.clearhearand.audio.dsp

import kotlin.math.*

/**
 * Spectral Gate - Voice-focused noise suppression for EXTREME mode
 *
 * This gate analyzes RMS energy to distinguish voice from background noise,
 * providing moderate suppression while preserving voice clarity and volume.
 *
 * Key features:
 * - Energy-based voice detection (RMS threshold)
 * - Moderate suppression (-12dB = 75% reduction) to preserve voice quality
 * - Fast attack (5ms) to catch speech quickly
 * - Long hold (300ms) to protect sentence endings
 * - Slow release (300ms) for smooth transitions
 * - Learns ambient noise floor in first 2 seconds
 *
 * Use case: Separating human voice from music and background noise in extreme environments
 */
class SpectralGate(
    private val sampleRate: Int,
    private val chunkSize: Int,
    private val thresholdDb: Float = -30f,      // Voice detection threshold (less strict)
    private val reductionDb: Float = -12f,      // Noise reduction amount (75%)
    private val attackMs: Float = 5f,           // Fast attack for speech
    private val holdMs: Float = 300f,           // Long hold for sentence endings
    private val releaseMs: Float = 300f         // Smooth release
) {
    private val tag = "SpectralGate"

    // Convert params to sample counts
    private val chunkDurationS = chunkSize.toFloat() / sampleRate
    private val holdSamples = (holdMs / 1000f / chunkDurationS).toInt()

    // Noise floor learning
    private var learningFrames = 0
    private val maxLearningFrames = 20  // 2 seconds at 100ms chunks
    private var noiseFloorRms = 0f
    private var sumSquaredNoise = 0.0
    private var noiseSampleCount = 0

    // State tracking
    private var holdCounter = 0
    private var currentGain = 1f

    // Gain targets
    private val speechGain = 1f
    private val noiseGain = 10f.pow(reductionDb / 20f)  // -12dB = 0.25x (75% reduction)

    // Attack/release coefficients
    private val attackCoef = exp(-chunkDurationS / (attackMs / 1000f)).toFloat()
    private val releaseCoef = exp(-chunkDurationS / (releaseMs / 1000f)).toFloat()

    /**
     * Process audio through spectral gate
     */
    fun process(samples: ShortArray) {
        // Calculate RMS energy
        var sum = 0.0
        for (sample in samples) {
            val s = sample.toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / samples.size)

        // Learning phase: measure noise floor
        if (learningFrames < maxLearningFrames) {
            sumSquaredNoise += sum
            noiseSampleCount += samples.size
            learningFrames++

            if (learningFrames == maxLearningFrames) {
                noiseFloorRms = sqrt(sumSquaredNoise / noiseSampleCount).toFloat()
                android.util.Log.d(tag, "Learned noise floor: RMS=$noiseFloorRms")
            }
            return  // Pass through during learning
        }

        // Convert threshold from dB to linear
        val thresholdLinear = noiseFloorRms * 10f.pow(thresholdDb / 20f).toFloat()

        // Detect voice vs noise
        val isVoice = rms >= thresholdLinear

        // Update hold counter
        if (isVoice) {
            holdCounter = holdSamples  // Reset hold timer
        } else if (holdCounter > 0) {
            holdCounter--
        }

        // Determine target gain
        val targetGain = if (isVoice || holdCounter > 0) {
            speechGain  // Full volume for voice and hold period
        } else {
            noiseGain   // Reduced volume for noise
        }

        // Smooth gain transitions with attack/release envelope
        currentGain = if (targetGain > currentGain) {
            // Attack (moving towards speech)
            targetGain + (currentGain - targetGain) * attackCoef
        } else {
            // Release (moving towards noise reduction)
            targetGain + (currentGain - targetGain) * releaseCoef
        }

        // Apply gain to samples
        for (i in samples.indices) {
            val sample = samples[i].toInt()
            var v = (sample * currentGain)

            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()

            samples[i] = v.toInt().toShort()
        }
    }

    /**
     * Reset gate state
     */
    fun reset() {
        learningFrames = 0
        noiseFloorRms = 0f
        sumSquaredNoise = 0.0
        noiseSampleCount = 0
        holdCounter = 0
        currentGain = 1f
    }

    /**
     * Get current noise floor (for debugging)
     */
    fun getNoiseFloor(): Float = noiseFloorRms
}
