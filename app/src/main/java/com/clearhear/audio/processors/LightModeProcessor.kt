package com.clearhear.audio.processors

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import com.clearhear.audio.processors.lightmode.*

/**
 * LIGHT Mode Processor - Strategy Pattern Implementation
 * 
 * This processor delegates to one of multiple filtering strategies:
 * 1. **Android Effects**: Hardware-accelerated (NS + AGC + AEC)
 * 2. **High-Pass Filter**: DC blocker + 80Hz high-pass
 * 3. **Adaptive Gate**: High-pass + noise gating
 * 4. **Custom Profile**: Learned from recorded noise files
 * 
 * The user can switch between strategies in real-time via the UI.
 * 
 * @see ILightModeStrategy
 * @see IAudioModeProcessor
 */
class LightModeProcessor(private val context: Context) : IAudioModeProcessor {
    
    private val tag = "LightModeProcessor"
    
    // Current active strategy
    private var currentStrategy: ILightModeStrategy? = null
    
    // Available strategies (lazy initialization)
    private val strategies = mapOf(
        "android" to lazy { AndroidEffectsStrategy() },
        "highpass" to lazy { HighPassFilterStrategy() },
        "adaptive" to lazy { AdaptiveGateStrategy() },
        "custom" to lazy { CustomProfileStrategy(context) }
    )
    
    // Current strategy key
    private var currentStrategyKey: String = "android"  // Default
    
    /**
     * Set the filtering strategy.
     * 
     * @param strategyKey Strategy identifier: "android", "highpass", "adaptive", or "custom"
     */
    fun setStrategy(strategyKey: String, audioRecord: AudioRecord?, sampleRate: Int) {
        if (strategyKey == currentStrategyKey && currentStrategy != null) {
            // Already using this strategy
            return
        }
        
        Log.d(tag, "Switching strategy: $currentStrategyKey → $strategyKey")
        
        // Cleanup old strategy
        currentStrategy?.cleanup()
        
        // Get new strategy
        val strategyLazy = strategies[strategyKey]
        if (strategyLazy == null) {
            Log.w(tag, "Unknown strategy: $strategyKey, falling back to android")
            currentStrategyKey = "android"
            currentStrategy = strategies["android"]?.value
        } else {
            currentStrategyKey = strategyKey
            currentStrategy = strategyLazy.value
        }
        
        // Setup new strategy
        val sessionId = audioRecord?.audioSessionId ?: 0
        val chunkSize = 4800  // 100ms at 48kHz
        currentStrategy?.setup(sessionId, sampleRate, chunkSize)
        
        Log.d(tag, "Strategy switched to: ${currentStrategy?.getDisplayName()} - ${currentStrategy?.getDescription()}")
    }
    
    /**
     * Process audio using the current strategy.
     * 
     * CRITICAL ORDER:
     * 1. Apply gain to amplify weak input
     * 2. Apply filtering to remove noise from amplified signal
     * 3. Apply volume for final output level
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Copy input to output
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)
        
        // DEBUG: Log strategy application
        if (currentStrategy == null) {
            Log.w(tag, "WARNING: currentStrategy is null! No filtering will be applied!")
        }
        
        // Step 1: Apply GAIN first to amplify weak signals
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * gain)
            
            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            
            outChunk[i] = v.toInt().toShort()
        }
        
        // Step 2: Apply filtering to the AMPLIFIED signal (much more effective!)
        currentStrategy?.process(outChunk)
        
        // Step 3: Apply volume for final output level
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * volume)
            
            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            
            outChunk[i] = v.toInt().toShort()
        }
    }
    
    /**
     * Get description of current strategy.
     */
    override fun getDescription(): String {
        val strategyDesc = currentStrategy?.getDescription() ?: "NoStrategy"
        return "LIGHT [$currentStrategyKey] $strategyDesc"
    }
    
    /**
     * Setup the default strategy.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        setStrategy(currentStrategyKey, audioRecord, sampleRate)
    }
    
    /**
     * Cleanup current strategy.
     */
    override fun cleanup() {
        currentStrategy?.cleanup()
        currentStrategy = null
        Log.d(tag, "LIGHT mode cleanup complete")
    }
}
