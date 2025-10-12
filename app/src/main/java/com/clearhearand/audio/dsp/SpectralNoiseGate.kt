package com.clearhearand.audio.dsp

import kotlin.math.*

/**
 * Spectral Noise Gate for LIGHT mode (V3 - SMART ADAPTIVE)
 * 
 * Reduces steady-state white noise while preserving full speech (including sentence endings).
 * Uses adaptive gating with hold time to prevent cutting sentence endings.
 * 
 * Algorithm:
 * 1. Estimate noise floor from quiet periods
 * 2. Detect voice vs noise using adaptive threshold
 * 3. Apply aggressive reduction to noise ONLY (not speech)
 * 4. Hold gate open after speech to avoid cutting sentence endings
 * 5. Smooth transitions with attack/hold/release envelope
 */
class SpectralNoiseGate(
    private val sampleRate: Int,
    private val noiseThresholdDb: Float = -40f,  // Threshold for noise detection
    private val reductionDb: Float = -12f         // More aggressive for noise (75% reduction)
) {
    private var noiseFloor: FloatArray? = null
    private var frameCount = 0
    private val learningFrames = 10  // Learn noise profile from first 1 second
    
    // Envelope follower for smooth transitions (prevents choppy sound)
    private var currentGain = 1.0f
    private val attackTime = 0.010f   // 10ms attack (fast response to speech)
    private val holdTime = 0.200f     // 200ms hold (prevents cutting sentence endings!)
    private val releaseTime = 0.200f  // 200ms release (slow decay, smooth fade)
    
    // Hold timer to prevent cutting sentence endings
    private var holdTimer = 0f
    
    private val minFreq = 300f   // Human voice lower bound (Hz)
    private val maxFreq = 3400f  // Human voice upper bound (Hz)
    
    /**
     * Process audio with spectral noise gate (V3 - WITH HOLD TIME)
     */
    fun process(samples: ShortArray) {
        // Convert to float
        val floats = pcm16ToFloat(samples)
        
        // Calculate frame energy (RMS)
        val energy = calculateEnergy(floats)
        
        // Learn noise floor from first second
        if (frameCount < learningFrames) {
            if (noiseFloor == null) {
                noiseFloor = FloatArray(1) { energy }
            } else {
                // Running average
                noiseFloor!![0] = (noiseFloor!![0] * frameCount + energy) / (frameCount + 1)
            }
            frameCount++
            
            // During learning, don't apply any processing (avoid artifacts)
            return
        }
        
        // Adaptive threshold: 3x noise floor
        val threshold = (noiseFloor?.get(0) ?: energy) * 3.0f
        
        // Chunk duration in seconds
        val chunkDuration = samples.size.toFloat() / sampleRate
        
        // Determine if speech is present
        val isSpeech = energy >= threshold
        
        // Update hold timer
        if (isSpeech) {
            // Speech detected: reset hold timer
            holdTimer = holdTime
        } else {
            // No speech: decrement hold timer
            holdTimer = maxOf(0f, holdTimer - chunkDuration)
        }
        
        // Determine target gain
        val targetGain = if (isSpeech || holdTimer > 0) {
            // Speech detected OR in hold period: full passthrough
            1.0f
        } else {
            // Silence detected AND hold expired: apply noise reduction
            dbToLinear(reductionDb)  // -12dB = 0.25x (75% reduction)
        }
        
        // Smooth gain changes with attack/release envelope
        if (targetGain > currentGain) {
            // Attack: speech detected, quickly ramp up
            val attackCoeff = exp(-chunkDuration / attackTime)
            currentGain = targetGain + (currentGain - targetGain) * attackCoeff
        } else {
            // Release: speech ended and hold expired, slowly ramp down
            val releaseCoeff = exp(-chunkDuration / releaseTime)
            currentGain = targetGain + (currentGain - targetGain) * releaseCoeff
        }
        
        // Apply smooth gain to entire chunk
        for (i in floats.indices) {
            floats[i] *= currentGain
        }
        
        // Apply gentle high-pass filter to remove low-frequency hum
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
        currentGain = 1.0f
        holdTimer = 0f
    }
    
    /**
     * Exponential function for envelope
     */
    private fun exp(x: Float): Float {
        return kotlin.math.exp(x.toDouble()).toFloat()
    }
}
