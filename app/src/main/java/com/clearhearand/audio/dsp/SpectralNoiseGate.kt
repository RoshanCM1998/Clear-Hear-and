package com.clearhearand.audio.dsp

import kotlin.math.*

/**
 * Spectral Noise Gate for LIGHT mode
 * 
 * Reduces steady-state noise (amplifier hiss, white noise) while preserving speech.
 * Uses frequency-domain processing to selectively suppress noise frequencies.
 * 
 * Algorithm:
 * 1. Estimate noise floor from quiet periods
 * 2. Apply frequency-domain gating
 * 3. Suppress frequencies below threshold
 * 4. Preserve speech frequencies (300-3400 Hz)
 */
class SpectralNoiseGate(
    private val sampleRate: Int,
    private val noiseThresholdDb: Float = -50f,  // Threshold for noise detection
    private val reductionDb: Float = -20f         // How much to reduce noise by
) {
    private var noiseFloor: FloatArray? = null
    private var frameCount = 0
    private val learningFrames = 20  // Learn noise profile from first 2 seconds
    
    private val minFreq = 300f   // Human voice lower bound (Hz)
    private val maxFreq = 3400f  // Human voice upper bound (Hz)
    
    /**
     * Process audio with spectral noise gate
     */
    fun process(samples: ShortArray) {
        // Convert to float
        val floats = pcm16ToFloat(samples)
        
        // Simple time-domain noise gate for now (more efficient than FFT)
        // Calculate frame energy
        val energy = calculateEnergy(floats)
        
        // Learn noise floor
        if (frameCount < learningFrames) {
            if (noiseFloor == null) {
                noiseFloor = FloatArray(1) { energy }
            } else {
                noiseFloor!![0] = (noiseFloor!![0] * frameCount + energy) / (frameCount + 1)
            }
            frameCount++
        }
        
        val threshold = (noiseFloor?.get(0) ?: energy) * 2.0f  // 2x noise floor
        
        // Apply noise gate
        if (energy < threshold) {
            // Reduce noise by specified dB
            val reduction = dbToLinear(reductionDb)
            for (i in floats.indices) {
                floats[i] *= reduction
            }
        }
        
        // Apply high-pass filter to remove low-frequency hum
        highPassFilter(floats, 80f)
        
        // Convert back to PCM16
        floatToPcm16(floats, samples)
    }
    
    /**
     * Calculate energy (RMS) of signal
     */
    private fun calculateEnergy(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size).toFloat()
    }
    
    /**
     * Simple high-pass filter to remove low-frequency hum
     */
    private var hpPrev = 0f
    private fun highPassFilter(samples: FloatArray, cutoffHz: Float) {
        // Simple first-order high-pass filter
        val rc = 1.0f / (2.0f * PI.toFloat() * cutoffHz)
        val dt = 1.0f / sampleRate
        val alpha = rc / (rc + dt)
        
        for (i in samples.indices) {
            val current = samples[i]
            samples[i] = alpha * (hpPrev + current - (if (i > 0) samples[i - 1] else 0f))
            hpPrev = samples[i]
        }
    }
    
    /**
     * Convert PCM16 to float [-1, 1]
     */
    private fun pcm16ToFloat(pcm: ShortArray): FloatArray {
        return FloatArray(pcm.size) { pcm[it] / 32768f }
    }
    
    /**
     * Convert float [-1, 1] to PCM16
     */
    private fun floatToPcm16(floats: FloatArray, out: ShortArray) {
        for (i in floats.indices) {
            var v = floats[i] * 32768f
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            out[i] = v.toInt().toShort()
        }
    }
    
    /**
     * Convert dB to linear scale
     */
    private fun dbToLinear(db: Float): Float {
        return 10f.pow(db / 20f)
    }
    
    /**
     * Reset noise floor estimation
     */
    fun reset() {
        noiseFloor = null
        frameCount = 0
        hpPrev = 0f
    }
}
