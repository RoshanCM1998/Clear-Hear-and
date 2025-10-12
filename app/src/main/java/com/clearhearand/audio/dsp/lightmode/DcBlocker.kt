package com.clearhearand.audio.dsp.lightmode

import kotlin.math.PI
import kotlin.math.cos

/**
 * DC Blocking Filter (High-Pass @ 20 Hz)
 * 
 * Removes DC offset and very low frequency drift from audio signal.
 * Uses a first-order IIR high-pass filter.
 * 
 * Transfer function: H(z) = (1 - z^-1) / (1 - R*z^-1)
 * where R = 1 - (2*pi*fc/fs)
 * 
 * @param sampleRate Sample rate in Hz
 * @param cutoffFreq Cutoff frequency in Hz (default: 20 Hz)
 */
class DcBlocker(
    private val sampleRate: Int,
    private val cutoffFreq: Float = 20f
) {
    // Filter coefficient
    private val R: Float = 1f - (2f * PI.toFloat() * cutoffFreq / sampleRate)
    
    // State variables
    private var x_prev: Float = 0f  // Previous input
    private var y_prev: Float = 0f  // Previous output
    
    /**
     * Process audio samples in-place.
     * 
     * @param samples Audio samples (16-bit PCM, modified in-place)
     */
    fun process(samples: ShortArray) {
        for (i in samples.indices) {
            val x = samples[i].toFloat()
            
            // DC blocking formula: y[n] = x[n] - x[n-1] + R * y[n-1]
            val y = x - x_prev + R * y_prev
            
            // Update state
            x_prev = x
            y_prev = y
            
            // Clamp to 16-bit range and write back
            samples[i] = y.coerceIn(-32768f, 32767f).toInt().toShort()
        }
    }
    
    /**
     * Reset filter state (call when starting new audio stream).
     */
    fun reset() {
        x_prev = 0f
        y_prev = 0f
    }
}
