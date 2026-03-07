package com.clearhear.audio.processors.lightmode

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * Strategy 1: Android Hardware Effects Only
 * 
 * Uses built-in Android audio effects:
 * - NoiseSuppressor: Hardware-accelerated noise reduction
 * - AutomaticGainControl: Automatic volume leveling
 * - AcousticEchoCanceler: Echo cancellation
 * 
 * Pros:
 * - Hardware accelerated (low CPU usage)
 * - Works on any chunk size
 * - Very simple
 * 
 * Cons:
 * - Device dependent (may not be available)
 * - Effectiveness varies by device
 * - No control over algorithm
 */
class AndroidEffectsStrategy : ILightModeStrategy {
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
        cleanup()
        
        // Try to enable Android NoiseSuppressor
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId).apply {
                    enabled = true
                }
                Log.d("AndroidEffects", "NoiseSuppressor enabled")
            } catch (e: Throwable) {
                Log.w("AndroidEffects", "Failed to create NoiseSuppressor: ${e.message}")
            }
        }
        
        // Try to enable Android AutomaticGainControl
        if (AutomaticGainControl.isAvailable()) {
            try {
                automaticGainControl = AutomaticGainControl.create(audioSessionId).apply {
                    enabled = true
                }
                Log.d("AndroidEffects", "AutomaticGainControl enabled")
            } catch (e: Throwable) {
                Log.w("AndroidEffects", "Failed to create AutomaticGainControl: ${e.message}")
            }
        }
        
        // Try to enable Android AcousticEchoCanceler
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                acousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId).apply {
                    enabled = true
                }
                Log.d("AndroidEffects", "AcousticEchoCanceler enabled")
            } catch (e: Throwable) {
                Log.w("AndroidEffects", "Failed to create AcousticEchoCanceler: ${e.message}")
            }
        }
    }
    
    override fun process(samples: ShortArray) {
        // Android effects process audio automatically in the AudioRecord pipeline
        // No manual processing needed here - just pass through
    }
    
    override fun cleanup() {
        noiseSuppressor?.release()
        noiseSuppressor = null
        
        automaticGainControl?.release()
        automaticGainControl = null
        
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
    }
    
    override fun getDescription(): String {
        val effects = mutableListOf<String>()
        if (noiseSuppressor != null) effects.add("NS")
        if (automaticGainControl != null) effects.add("AGC")
        if (acousticEchoCanceler != null) effects.add("AEC")
        
        return if (effects.isNotEmpty()) {
            "AndroidEffects(${effects.joinToString("+")})"
        } else {
            "AndroidEffects(none_available)"
        }
    }
    
    override fun getDisplayName(): String = "Android Effects"
}
