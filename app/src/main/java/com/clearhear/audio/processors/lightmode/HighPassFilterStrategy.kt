package com.clearhear.audio.processors.lightmode

import android.util.Log
import com.clearhear.audio.dsp.lightmode.DcBlocker
import com.clearhear.audio.dsp.lightmode.HighPassFilter80Hz

/**
 * Strategy 2: DC Blocking + High-Pass Filter @ 80 Hz
 * 
 * Two-stage filtering:
 * 1. DC Blocker (20 Hz high-pass) - removes DC offset/drift
 * 2. High-Pass Filter (80 Hz) - removes low-frequency rumble
 * 
 * Targets the actual noise characteristics found in recordings:
 * - DC offset (+/- 200-400 bias)
 * - Low-frequency drift
 * - Sub-bass rumble
 * 
 * Pros:
 * - Removes root cause of noise (DC + low-freq)
 * - 60-80% noise reduction expected
 * - No learning phase
 * - Works consistently on all devices
 * - Minimal CPU usage
 * 
 * Cons:
 * - Slightly reduces bass (but voice is 300+ Hz, so minimal impact)
 */
class HighPassFilterStrategy : ILightModeStrategy {
    private var dcBlocker: DcBlocker? = null
    private var highPassFilter: HighPassFilter80Hz? = null
    
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
        cleanup()
        
        dcBlocker = DcBlocker(sampleRate, cutoffFreq = 20f)
        highPassFilter = HighPassFilter80Hz(sampleRate, cutoffFreq = 80f)
        
        Log.d("HighPassStrategy", "Initialized: DC blocker (20 Hz) + High-pass (80 Hz)")
    }
    
    override fun process(samples: ShortArray) {
        // Stage 1: Remove DC offset
        dcBlocker?.process(samples)
        
        // Stage 2: Remove low-frequency rumble
        highPassFilter?.process(samples)
        
        // DEBUG: Verify processing happened
        // Log.d("HighPassStrategy", "Processed ${samples.size} samples")
    }
    
    override fun cleanup() {
        dcBlocker?.reset()
        dcBlocker = null
        
        highPassFilter?.reset()
        highPassFilter = null
    }
    
    override fun getDescription(): String {
        return "HighPass(DC_Block_20Hz+HP_80Hz)"
    }
    
    override fun getDisplayName(): String = "High-Pass Filter"
}
