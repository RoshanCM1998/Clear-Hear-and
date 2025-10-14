package com.clearhearand.audio.dsp.lightmode

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * High-Pass Filter @ 80 Hz (2nd order Butterworth)
 * 
 * Removes low-frequency rumble and noise below 80 Hz.
 * Voice starts at ~300 Hz, so this doesn't affect speech quality.
 * 
 * Uses a 2nd-order Butterworth IIR filter for:
 * - Flat passband (no ripple)
 * - Maximally flat frequency response
 * - Good phase characteristics
 * 
 * @param sampleRate Sample rate in Hz
 * @param cutoffFreq Cutoff frequency in Hz (default: 80 Hz)
 */
class HighPassFilter80Hz(
    private val sampleRate: Int,
    private val cutoffFreq: Float = 80f
) {
    // Filter coefficients (calculated in init)
    private val a0: Float
    private val a1: Float
    private val a2: Float
    private val b1: Float
    private val b2: Float
    
    // State variables (2nd order = 2 delay elements)
    private var x1: Float = 0f  // x[n-1]
    private var x2: Float = 0f  // x[n-2]
    private var y1: Float = 0f  // y[n-1]
    private var y2: Float = 0f  // y[n-2]
    
    init {
        // Calculate Butterworth high-pass coefficients
        val omega = 2f * PI.toFloat() * cutoffFreq / sampleRate
        val cosOmega = cos(omega.toDouble()).toFloat()
        val alpha = kotlin.math.sin(omega.toDouble()).toFloat() / sqrt(2f)
        
        // Normalize coefficients
        val b0 = 1f + alpha
        a0 = (1f + cosOmega) / 2f / b0
        a1 = -(1f + cosOmega) / b0
        a2 = (1f + cosOmega) / 2f / b0
        b1 = -2f * cosOmega / b0
        b2 = (1f - alpha) / b0
    }
    
    /**
     * Process audio samples in-place.
     * 
     * @param samples Audio samples (16-bit PCM, modified in-place)
     */
    fun process(samples: ShortArray) {
        for (i in samples.indices) {
            val x0 = samples[i].toFloat()
            
            // 2nd order IIR: y[n] = a0*x[n] + a1*x[n-1] + a2*x[n-2] - b1*y[n-1] - b2*y[n-2]
            val y0 = a0 * x0 + a1 * x1 + a2 * x2 - b1 * y1 - b2 * y2
            
            // Update state
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
            
            // Clamp to 16-bit range and write back
            samples[i] = y0.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }
    
    /**
     * Reset filter state (call when starting new audio stream).
     */
    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
    }
}
