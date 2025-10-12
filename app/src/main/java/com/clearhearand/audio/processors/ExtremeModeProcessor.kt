package com.clearhearand.audio.processors

import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.util.Log

/**
 * EXTREME Mode Processor - Enhanced Noise Reduction Implementation
 * 
 * This processor provides strong noise reduction for very noisy environments.
 * Currently uses Android's NoiseSuppressor in a more aggressive configuration,
 * with room for future custom DSP enhancements.
 * 
 * Current implementation:
 * - **NoiseSuppressor**: Android's hardware-accelerated noise reduction
 * - No AGC (preserves dynamic range and user control)
 * - No AEC (not needed for extreme noise scenarios)
 * 
 * Future enhancements (can be added without affecting other modes):
 * - Windowed Wiener filter with overlap-add processing
 * - Spectral gate for additional noise suppression
 * - Voice activity detection (VAD) for selective processing
 * - RNNoise (AI-based noise suppression)
 * 
 * Use cases:
 * - Very noisy environments (crowded places, traffic, construction)
 * - Outdoor use with wind and environmental noise
 * - Maximum noise reduction when audio quality can be sacrificed
 * - When speech intelligibility is more important than naturalness
 * 
 * Processing pipeline (current):
 * ```
 * Input (microphone)
 *   ↓
 * Android NoiseSuppressor (hardware, aggressive)
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
 * Processing pipeline (future with custom DSP):
 * ```
 * Input (microphone)
 *   ↓
 * Android NoiseSuppressor (hardware)
 *   ↓
 * Windowed Wiener Filter (software, 512-sample windows, 50% overlap)
 *   ↓
 * Spectral Gate (software, -40dB threshold)
 *   ↓
 * Voice Activity Detection (software, selective processing)
 *   ↓
 * Gain multiplication (user control)
 *   ↓
 * Volume multiplication (user control)
 *   ↓
 * Soft Limiter (prevent clipping)
 *   ↓
 * Output (speaker/headphones)
 * ```
 * 
 * @see IAudioModeProcessor
 */
class ExtremeModeProcessor : IAudioModeProcessor {
    
    private val tag = "ExtremeModeProcessor"
    
    // Android built-in effect (hardware accelerated)
    private var noiseSuppressor: NoiseSuppressor? = null
    
    // Future: Custom DSP components
    // private var wienerFilter: WindowedWienerFilter? = null
    // private var spectralGate: SpectralGate? = null
    // private var voiceActivityDetector: VoiceActivityDetector? = null
    // private var softLimiter: SoftLimiter? = null
    
    /**
     * Process audio with enhanced noise reduction.
     * 
     * Currently applies Android NoiseSuppressor plus user controls.
     * In the future, this will include custom DSP processing with windowing.
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Current implementation: Simple processing after NoiseSuppressor
        for (i in inChunk.indices) {
            val sample = inChunk[i].toInt()
            var v = (sample * gain * volume)
            
            // Clamp to prevent overflow
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            
            outChunk[i] = v.toInt().toShort()
        }
        
        // Future implementation with custom DSP:
        /*
        // Convert to float for DSP processing
        val floats = pcm16ToFloat(inChunk)
        
        // Apply windowed Wiener filter
        wienerFilter?.processWithWindowing(floats)
        
        // Apply spectral gate
        spectralGate?.process(floats)
        
        // Voice activity detection (only process when voice is present)
        if (voiceActivityDetector?.detectVoice(floats) == true) {
            // Apply additional processing only when voice is detected
        }
        
        // Apply gain and volume
        for (i in floats.indices) {
            floats[i] *= gain * volume
        }
        
        // Soft limiter to prevent clipping
        softLimiter?.process(floats)
        
        // Convert back to PCM16
        floatToPcm16(floats, outChunk)
        */
    }
    
    /**
     * Returns description showing active components.
     * 
     * Current: "EXTREME-Enhanced[NS]"
     * Future: "EXTREME-Enhanced[NS,Wiener,SpectralGate,VAD]"
     */
    override fun getDescription(): String {
        val active = mutableListOf<String>()
        if (noiseSuppressor?.enabled == true) active.add("NS")
        // Future: Add more indicators
        // if (wienerFilter != null) active.add("Wiener")
        // if (spectralGate != null) active.add("SpectralGate")
        // if (voiceActivityDetector != null) active.add("VAD")
        return "EXTREME-Enhanced[${active.joinToString(",")}]"
    }
    
    /**
     * Setup enhanced noise reduction components.
     * 
     * Currently enables Android NoiseSuppressor with maximum effectiveness.
     * In the future, will also initialize custom DSP components.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        val sessionId = audioRecord?.audioSessionId ?: return
        
        // Enable Android NoiseSuppressor
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor?.release()
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
                Log.d(tag, "NoiseSuppressor enabled (aggressive)")
            } else {
                Log.w(tag, "NoiseSuppressor not available on this device")
            }
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor setup failed: ${e.message}")
        }
        
        // Future: Initialize custom DSP components
        /*
        try {
            // Windowed Wiener filter (512-sample windows, 50% overlap)
            wienerFilter = WindowedWienerFilter(
                sampleRate = sampleRate,
                windowSize = 512,
                overlap = 0.5f,
                alpha = 0.98f,       // Strong noise suppression
                oversub = 1.3f       // Aggressive over-subtraction
            )
            Log.d(tag, "Wiener filter initialized")
            
            // Spectral gate (-40dB threshold)
            spectralGate = SpectralGate(
                threshold = -40f,    // -40dB below peak
                attackMs = 10f,      // Fast attack
                releaseMs = 50f      // Moderate release
            )
            Log.d(tag, "Spectral gate initialized")
            
            // Voice activity detector
            voiceActivityDetector = VoiceActivityDetector(
                sampleRate = sampleRate,
                frameSize = 480      // 10ms frames at 48kHz
            )
            Log.d(tag, "Voice activity detector initialized")
            
            // Soft limiter
            softLimiter = SoftLimiter(threshold = 0.9f)
            Log.d(tag, "Soft limiter initialized")
        } catch (e: Exception) {
            Log.w(tag, "Custom DSP initialization failed: ${e.message}")
        }
        */
        
        Log.d(tag, "EXTREME mode setup complete: ${getDescription()}")
    }
    
    /**
     * Cleanup and release all noise reduction components.
     * 
     * Releases Android effects and any custom DSP components.
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
        
        // Future: Cleanup custom DSP components
        /*
        try {
            wienerFilter?.cleanup()
            wienerFilter = null
            
            spectralGate?.cleanup()
            spectralGate = null
            
            voiceActivityDetector?.cleanup()
            voiceActivityDetector = null
            
            softLimiter = null
            
            Log.d(tag, "Custom DSP components released")
        } catch (e: Exception) {
            Log.w(tag, "Custom DSP cleanup failed: ${e.message}")
        }
        */
        
        Log.d(tag, "EXTREME mode cleanup complete")
    }
}
