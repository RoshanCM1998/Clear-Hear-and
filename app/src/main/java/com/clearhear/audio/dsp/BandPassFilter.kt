package com.clearhear.audio.dsp

import kotlin.math.*

/**
 * Band-pass filter for isolating human voice frequencies
 * 
 * Passes frequencies between 300-3400 Hz (telephone quality)
 * This range contains most of the information needed for speech intelligibility.
 * 
 * Used in EXTREME mode to remove all non-voice frequencies.
 */
class BandPassFilter(
    private val sampleRate: Int,
    private val lowCutHz: Float = 300f,   // Low cutoff (remove low-frequency noise)
    private val highCutHz: Float = 3400f   // High cutoff (remove high-frequency noise)
) {
    // Biquad filter coefficients for high-pass stage
    private var hpB0 = 0f
    private var hpB1 = 0f
    private var hpB2 = 0f
    private var hpA1 = 0f
    private var hpA2 = 0f
    
    // Biquad filter coefficients for low-pass stage
    private var lpB0 = 0f
    private var lpB1 = 0f
    private var lpB2 = 0f
    private var lpA1 = 0f
    private var lpA2 = 0f
    
    // Filter state (for high-pass)
    private var hpX1 = 0f
    private var hpX2 = 0f
    private var hpY1 = 0f
    private var hpY2 = 0f
    
    // Filter state (for low-pass)
    private var lpX1 = 0f
    private var lpX2 = 0f
    private var lpY1 = 0f
    private var lpY2 = 0f
    
    init {
        calculateCoefficients()
    }
    
    /**
     * Calculate biquad filter coefficients
     */
    private fun calculateCoefficients() {
        // High-pass filter (removes low frequencies < 300 Hz)
        val hpFreq = 2.0 * PI * lowCutHz / sampleRate
        val hpQ = 0.707  // Butterworth response
        val hpAlpha = sin(hpFreq) / (2.0 * hpQ)
        val hpCos = cos(hpFreq)
        
        val hpA0 = (1.0 + hpAlpha).toFloat()
        hpB0 = ((1.0 + hpCos) / 2.0 / hpA0).toFloat()
        hpB1 = (-(1.0 + hpCos) / hpA0).toFloat()
        hpB2 = ((1.0 + hpCos) / 2.0 / hpA0).toFloat()
        hpA1 = (-2.0 * hpCos / hpA0).toFloat()
        hpA2 = ((1.0 - hpAlpha) / hpA0).toFloat()
        
        // Low-pass filter (removes high frequencies > 3400 Hz)
        val lpFreq = 2.0 * PI * highCutHz / sampleRate
        val lpQ = 0.707  // Butterworth response
        val lpAlpha = sin(lpFreq) / (2.0 * lpQ)
        val lpCos = cos(lpFreq)
        
        val lpA0 = (1.0 + lpAlpha).toFloat()
        lpB0 = ((1.0 - lpCos) / 2.0 / lpA0).toFloat()
        lpB1 = ((1.0 - lpCos) / lpA0).toFloat()
        lpB2 = ((1.0 - lpCos) / 2.0 / lpA0).toFloat()
        lpA1 = (-2.0 * lpCos / lpA0).toFloat()
        lpA2 = ((1.0 - lpAlpha) / lpA0).toFloat()
    }
    
    /**
     * Process audio through band-pass filter
     */
    fun process(samples: ShortArray) {
        for (i in samples.indices) {
            val x = samples[i] / 32768f
            
            // High-pass stage (remove low frequencies)
            val hp = hpB0 * x + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2
            hpX2 = hpX1
            hpX1 = x
            hpY2 = hpY1
            hpY1 = hp
            
            // Low-pass stage (remove high frequencies)
            val lp = lpB0 * hp + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2
            lpX2 = lpX1
            lpX1 = hp
            lpY2 = lpY1
            lpY1 = lp
            
            // Clamp and write back
            var out = lp * 32768f
            if (out > Short.MAX_VALUE) out = Short.MAX_VALUE.toFloat()
            if (out < Short.MIN_VALUE) out = Short.MIN_VALUE.toFloat()
            samples[i] = out.toInt().toShort()
        }
    }
    
    /**
     * Reset filter state
     */
    fun reset() {
        hpX1 = 0f
        hpX2 = 0f
        hpY1 = 0f
        hpY2 = 0f
        lpX1 = 0f
        lpX2 = 0f
        lpY1 = 0f
        lpY2 = 0f
    }
}
