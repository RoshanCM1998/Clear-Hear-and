package com.clearhear.audio.processors.lightmode

import android.util.Log
import com.clearhear.audio.dsp.lightmode.AdaptiveNoiseGate
import com.clearhear.audio.dsp.lightmode.DcBlocker
import com.clearhear.audio.dsp.lightmode.HighPassFilter80Hz

/**
 * Strategy 3: DC Blocking + High-Pass + Adaptive Noise Gate
 * 
 * Three-stage filtering:
 * 1. DC Blocker (20 Hz) - removes DC offset
 * 2. High-Pass Filter (80 Hz) - removes low-frequency rumble
 * 3. Adaptive Noise Gate - only amplifies when RMS > threshold
 * 
 * Most aggressive noise reduction. Gates out pure noise when not speaking.
 * 
 * Pros:
 * - Best noise reduction (70-90%)
 * - Handles RF spikes
 * - No learning phase
 * - Hold time prevents cutting sentence endings
 * 
 * Cons:
 * - More CPU usage
 * - May need threshold tuning
 * - Slight delay before gate opens (5ms attack)
 */
class AdaptiveGateStrategy : ILightModeStrategy {
    private var dcBlocker: DcBlocker? = null
    private var highPassFilter: HighPassFilter80Hz? = null
    private var noiseGate: AdaptiveNoiseGate? = null
    
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
        cleanup()
        
        dcBlocker = DcBlocker(sampleRate, cutoffFreq = 20f)
        highPassFilter = HighPassFilter80Hz(sampleRate, cutoffFreq = 80f)
        
        // Gate threshold: RMS < 150 is considered noise
        // (From logs: white noise RMS = 78-98, voice RMS = 327-950+ in raw PCM)
        noiseGate = AdaptiveNoiseGate(
            sampleRate = sampleRate,
            threshold = 150f,          // RMS threshold (updated from 50 to 150)
            attackTime = 0.005f,       // 5ms attack (fast response)
            releaseTime = 0.100f,      // 100ms release (smooth fade)
            holdTime = 0.200f,         // 200ms hold (protects sentence endings)
            reductionDb = -12f         // 12dB = 75% reduction
        )
        
        Log.d("AdaptiveGateStrategy", "Initialized: DC blocker + High-pass + Noise gate (threshold=150)")
    }
    
    override fun process(samples: ShortArray) {
        // Stage 1: Remove DC offset
        dcBlocker?.process(samples)
        
        // Stage 2: Remove low-frequency rumble
        highPassFilter?.process(samples)
        
        // Stage 3: Gate out pure noise
        noiseGate?.process(samples)
        
        // DEBUG: Verify processing happened
        // Log.d("AdaptiveGateStrategy", "Processed ${samples.size} samples with gate")
    }
    
    override fun cleanup() {
        dcBlocker?.reset()
        dcBlocker = null
        
        highPassFilter?.reset()
        highPassFilter = null
        
        noiseGate?.reset()
        noiseGate = null
    }
    
    override fun getDescription(): String {
        return "AdaptiveGate(DC+HP+Gate_threshold150)"
    }
    
    override fun getDisplayName(): String = "Adaptive Gate"
}
