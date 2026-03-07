package com.clearhear.audio.dsp.lightmode

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Adaptive Noise Gate with Hold Time
 * 
 * Gates out (reduces volume of) audio below a certain RMS threshold.
 * Prevents amplifying pure noise when no speech is present.
 * 
 * Features:
 * - Adaptive threshold based on noise floor
 * - Smooth attack/release envelopes
 * - Hold time to prevent cutting sentence endings
 * - Gradual reduction (not hard on/off)
 * 
 * @param sampleRate Sample rate in Hz
 * @param threshold RMS threshold (audio below this is considered noise)
 * @param attackTime Attack time in seconds (how fast gate opens)
 * @param releaseTime Release time in seconds (how fast gate closes)
 * @param holdTime Hold time in seconds (delay before closing after speech)
 * @param reductionDb Amount to reduce noise by (in dB, negative value)
 */
class AdaptiveNoiseGate(
    private val sampleRate: Int,
    private val threshold: Float = 50f,        // RMS threshold (16-bit samples)
    private val attackTime: Float = 0.005f,    // 5ms attack
    private val releaseTime: Float = 0.100f,   // 100ms release
    private val holdTime: Float = 0.200f,      // 200ms hold
    private val reductionDb: Float = -12f      // 12dB reduction (75% = 0.25x)
) {
    // Convert dB to linear gain
    private val reductionGain: Float = dbToGain(reductionDb)
    
    // Current gain (smoothly interpolated)
    private var currentGain: Float = 1.0f
    
    // Hold timer (in samples)
    private var holdTimer: Float = 0f
    private val holdSamples: Float = holdTime * sampleRate
    
    // Attack/release coefficients
    private val attackCoeff: Float
    private val releaseCoeff: Float
    
    init {
        // Calculate exponential smoothing coefficients
        attackCoeff = exp(-1.0 / (attackTime * sampleRate)).toFloat()
        releaseCoeff = exp(-1.0 / (releaseTime * sampleRate)).toFloat()
    }
    
    /**
     * Process audio samples in-place.
     * 
     * @param samples Audio samples (16-bit PCM, modified in-place)
     */
    fun process(samples: ShortArray) {
        // Calculate RMS of this chunk
        val rms = calculateRms(samples)
        
        // Determine if speech is present
        val isSpeech = rms >= threshold
        
        // Update hold timer
        if (isSpeech) {
            holdTimer = holdSamples  // Reset hold timer
        } else if (holdTimer > 0) {
            holdTimer -= samples.size  // Decrement by chunk size
        }
        
        // Determine target gain
        val targetGain = if (isSpeech || holdTimer > 0) {
            1.0f  // Full volume (speech or hold period)
        } else {
            reductionGain  // Reduce noise
        }
        
        // Smooth gain transitions (per-sample for smoothness)
        for (i in samples.indices) {
            // Apply exponential smoothing
            val coeff = if (targetGain > currentGain) attackCoeff else releaseCoeff
            currentGain = coeff * currentGain + (1f - coeff) * targetGain
            
            // Apply gain
            val sample = samples[i].toFloat() * currentGain
            samples[i] = sample.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }
    
    /**
     * Calculate RMS (root mean square) of audio samples.
     * 
     * @param samples Audio samples
     * @return RMS value
     */
    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        
        return sqrt(sum / samples.size).toFloat()
    }
    
    /**
     * Convert decibels to linear gain.
     * 
     * @param db Decibels (can be negative)
     * @return Linear gain (0.0 to 1.0+)
     */
    private fun dbToGain(db: Float): Float {
        return exp((db / 20.0) * kotlin.math.ln(10.0)).toFloat()
    }
    
    /**
     * Reset gate state (call when starting new audio stream).
     */
    fun reset() {
        currentGain = 1.0f
        holdTimer = 0f
    }
}
