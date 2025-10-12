package com.clearhearand.audio.processors

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * LIGHT Mode Processor - Android Built-in Effects Implementation
 * 
 * This processor provides mild noise reduction using Android's hardware-accelerated
 * audio effects. These effects run on the device's DSP chip (if available) for
 * efficient, low-latency processing.
 * 
 * Active effects:
 * - **NoiseSuppressor**: Reduces steady-state background noise (fan, AC, etc.)
 * - **AutomaticGainControl**: Normalizes volume levels automatically
 * - **AcousticEchoCanceler**: Reduces acoustic echo in speakerphone scenarios
 * 
 * These effects are applied automatically at the AudioRecord level, before we
 * receive the audio data. Our processing just applies the user's gain and volume.
 * 
 * Use cases:
 * - Office environments with moderate background noise
 * - Home use with AC, fan, or computer noise
 * - Daily conversations in typical indoor settings
 * - When natural sound quality is important
 * 
 * Processing pipeline:
 * ```
 * Input (microphone)
 *   ↓
 * Android NoiseSuppressor (hardware, automatic)
 *   ↓
 * Android AGC (hardware, automatic)
 *   ↓
 * Android AEC (hardware, automatic)
 *   ↓
 * Gain multiplication (user control)
 *   ↓
 * Volume multiplication (user control)
 *   ↓
 * Clamp to prevent overflow
 *   ↓
 * Output (speaker/headphones)
 * ```
 * 
 * @see IAudioModeProcessor
 */
class LightModeProcessor : IAudioModeProcessor {
    
    private val tag = "LightModeProcessor"
    
    // Android built-in audio effects (hardware accelerated)
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    
    /**
     * Process audio with Android effects and user controls.
     * 
     * Android effects are already applied at the AudioRecord level,
     * so we just apply the user's gain and volume.
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Android effects are applied automatically at AudioRecord level
        // We just apply user's gain and volume
        for (i in inChunk.indices) {
            val sample = inChunk[i].toInt()
            var v = (sample * gain * volume)
            
            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            
            outChunk[i] = v.toInt().toShort()
        }
    }
    
    /**
     * Returns description showing which Android effects are active.
     * 
     * Example: "LIGHT-Android[NS,AGC,AEC]" means all three effects are enabled.
     */
    override fun getDescription(): String {
        val effects = mutableListOf<String>()
        if (noiseSuppressor?.enabled == true) effects.add("NS")
        if (automaticGainControl?.enabled == true) effects.add("AGC")
        if (acousticEchoCanceler?.enabled == true) effects.add("AEC")
        return "LIGHT-Android[${effects.joinToString(",")}]"
    }
    
    /**
     * Setup Android built-in audio effects.
     * 
     * Attempts to enable all three effects on the audio session. If any effect
     * is not available on the device, it will be skipped gracefully.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        val sessionId = audioRecord?.audioSessionId ?: return
        
        // Enable NoiseSuppressor
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor?.release()
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(tag, "NoiseSuppressor enabled")
            } else {
                Log.w(tag, "NoiseSuppressor not available on this device")
            }
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor setup failed: ${e.message}")
        }
        
        // Enable AutomaticGainControl
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl?.release()
                automaticGainControl = AutomaticGainControl.create(sessionId)
                automaticGainControl?.enabled = true
                Log.d(tag, "AutomaticGainControl enabled")
            } else {
                Log.w(tag, "AutomaticGainControl not available on this device")
            }
        } catch (e: Exception) {
            Log.w(tag, "AutomaticGainControl setup failed: ${e.message}")
        }
        
        // Enable AcousticEchoCanceler
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                acousticEchoCanceler?.release()
                acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
                acousticEchoCanceler?.enabled = true
                Log.d(tag, "AcousticEchoCanceler enabled")
            } else {
                Log.w(tag, "AcousticEchoCanceler not available on this device")
            }
        } catch (e: Exception) {
            Log.w(tag, "AcousticEchoCanceler setup failed: ${e.message}")
        }
        
        Log.d(tag, "LIGHT mode setup complete: ${getDescription()}")
    }
    
    /**
     * Cleanup and release all Android audio effects.
     * 
     * This must be called when switching modes or stopping audio to prevent
     * resource leaks.
     */
    override fun cleanup() {
        // Disable and release NoiseSuppressor
        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
            noiseSuppressor = null
            Log.d(tag, "NoiseSuppressor released")
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor cleanup failed: ${e.message}")
        }
        
        // Disable and release AutomaticGainControl
        try {
            automaticGainControl?.enabled = false
            automaticGainControl?.release()
            automaticGainControl = null
            Log.d(tag, "AutomaticGainControl released")
        } catch (e: Exception) {
            Log.w(tag, "AutomaticGainControl cleanup failed: ${e.message}")
        }
        
        // Disable and release AcousticEchoCanceler
        try {
            acousticEchoCanceler?.enabled = false
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
            Log.d(tag, "AcousticEchoCanceler released")
        } catch (e: Exception) {
            Log.w(tag, "AcousticEchoCanceler cleanup failed: ${e.message}")
        }
        
        Log.d(tag, "LIGHT mode cleanup complete")
    }
}
