package com.clearhear.audio.processors.lightmode

import android.content.Context
import android.util.Log
import com.clearhear.audio.dsp.lightmode.AdaptiveNoiseGate
import com.clearhear.audio.dsp.lightmode.DcBlocker
import com.clearhear.audio.dsp.lightmode.HighPassFilter80Hz
import com.clearhear.audio.dsp.lightmode.NoiseProfileLearner

/**
 * Strategy 4: Custom Profile (Learned from Recorded Noise)
 * 
 * Analyzes recorded noise_profile_*.txt files and creates a custom filter
 * tuned to the user's specific noise environment.
 * 
 * Three-stage filtering:
 * 1. DC Blocker (20 Hz) - removes DC offset
 * 2. High-Pass Filter (80 Hz) - removes low-frequency rumble
 * 3. Adaptive Noise Gate - threshold learned from recordings
 * 
 * The gate threshold is automatically set to 3x the average noise RMS,
 * ensuring it only gates out actual noise while preserving all speech.
 * 
 * Pros:
 * - Tuned to YOUR specific noise
 * - Learns from multiple recordings
 * - Adaptive to your environment
 * - Same benefits as Adaptive Gate but optimized threshold
 * 
 * Cons:
 * - Requires recording noise profile first
 * - Threshold fixed after learning (doesn't adapt to changes)
 */
class CustomProfileStrategy(private val context: Context) : ILightModeStrategy {
    private var dcBlocker: DcBlocker? = null
    private var highPassFilter: HighPassFilter80Hz? = null
    private var noiseGate: AdaptiveNoiseGate? = null
    
    private var learnedThreshold: Float = 50f  // Default, updated during setup
    private var noiseCharacteristics: NoiseProfileLearner.NoiseCharacteristics? = null
    
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
        cleanup()
        
        dcBlocker = DcBlocker(sampleRate, cutoffFreq = 20f)
        highPassFilter = HighPassFilter80Hz(sampleRate, cutoffFreq = 80f)
        
        // Learn noise characteristics from recorded files
        val learner = NoiseProfileLearner(context)
        noiseCharacteristics = learner.analyzeNoiseProfiles()
        
        if (noiseCharacteristics != null) {
            learnedThreshold = noiseCharacteristics!!.recommendedThreshold
            val samples = noiseCharacteristics!!.samplesAnalyzed
            Log.d("CustomProfile", "✓ Learned threshold from $samples samples: $learnedThreshold")
            android.widget.Toast.makeText(context, "Custom filter loaded: threshold=$learnedThreshold from $samples samples", android.widget.Toast.LENGTH_LONG).show()
        } else {
            // Initialize with pre-analyzed data from user's recordings
            // Average noise RMS was ~50, so threshold = 3x = 150
            learnedThreshold = 150f
            Log.w("CustomProfile", "⚠ No noise profile found, using pre-analyzed threshold: $learnedThreshold (from previous analysis: avg RMS ~50)")
            android.widget.Toast.makeText(context, "No noise profile found. Using default threshold: $learnedThreshold. Please record noise profile first.", android.widget.Toast.LENGTH_LONG).show()
        }
        
        // Create adaptive gate with learned threshold
        noiseGate = AdaptiveNoiseGate(
            sampleRate = sampleRate,
            threshold = learnedThreshold,  // LEARNED from recordings!
            attackTime = 0.005f,           // 5ms attack
            releaseTime = 0.100f,          // 100ms release
            holdTime = 0.200f,             // 200ms hold
            reductionDb = -15f             // 15dB = 82% reduction (more aggressive)
        )
        
        Log.d("CustomProfile", "Initialized with learned threshold: ${learnedThreshold.toInt()}")
    }
    
    override fun process(samples: ShortArray) {
        // Stage 1: Remove DC offset
        dcBlocker?.process(samples)
        
        // Stage 2: Remove low-frequency rumble
        highPassFilter?.process(samples)
        
        // Stage 3: Gate out noise (using learned threshold)
        noiseGate?.process(samples)
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
        val threshold = noiseCharacteristics?.recommendedThreshold?.toInt() ?: learnedThreshold.toInt()
        val samples = noiseCharacteristics?.samplesAnalyzed ?: 0
        return "CustomProfile(threshold=$threshold,samples=$samples)"
    }
    
    override fun getDisplayName(): String = "Custom Profile"
}
