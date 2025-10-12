package com.clearhearand.audio.processors

import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.clearhearand.audio.dsp.BandPassFilter
import com.example.audio.RNNoise

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
    
    // RNNoise (ML-based noise suppression)
    private var rnHandle: Long = 0L
    private var rnAvailable = false
    
    // Band-pass filter for voice isolation (300-3400 Hz)
    private var bandPassFilter: BandPassFilter? = null
    
    // Future: Custom DSP components
    // private var wienerFilter: WindowedWienerFilter? = null
    // private var spectralGate: SpectralGate? = null
    // private var voiceActivityDetector: VoiceActivityDetector? = null
    // private var softLimiter: SoftLimiter? = null
    
    /**
     * Process audio with enhanced noise reduction.
     * 
     * Uses band-pass filter for voice isolation (RNNoise disabled for stability).
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Copy input to output
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)
        
        // DISABLED: RNNoise temporarily disabled due to stability issues
        // Will re-enable after LIGHT mode is perfected
        /*
        if (rnAvailable && rnHandle != 0L) {
            try {
                // RNNoise expects 480 samples (10ms at 48kHz)
                // Process in chunks of 480 samples
                val rnChunkSize = 480
                var offset = 0
                
                while (offset + rnChunkSize <= outChunk.size) {
                    // Convert PCM16 to float for RNNoise
                    val floats = FloatArray(rnChunkSize)
                    for (i in 0 until rnChunkSize) {
                        floats[i] = outChunk[offset + i] / 32768f
                    }
                    
                    // Process with RNNoise
                    val processed = RNNoise.process(rnHandle, floats)
                    
                    // Convert back to PCM16
                    for (i in 0 until rnChunkSize) {
                        var v = processed[i] * 32768f
                        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
                        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
                        outChunk[offset + i] = v.toInt().toShort()
                    }
                    
                    offset += rnChunkSize
                }
            } catch (e: Exception) {
                Log.w(tag, "RNNoise processing error: ${e.message}")
            }
        }
        */
        
        // Step 1: Apply gain first
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * gain)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }
        
        // Step 2: Apply band-pass filter (300-3400 Hz - human voice range) to AMPLIFIED signal
        bandPassFilter?.process(outChunk)
        
        // Step 3: Apply volume for final output
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
     * Returns description showing active components.
     * 
     * Example: "EXTREME-BandPass+Android[NS]" (RNNoise disabled for now)
     */
    override fun getDescription(): String {
        val active = mutableListOf<String>()
        // RNNoise disabled for stability
        // if (rnAvailable && rnHandle != 0L) active.add("RNNoise")
        if (bandPassFilter != null) active.add("BandPass(300-3400Hz)")
        if (noiseSuppressor?.enabled == true) active.add("Android[NS]")
        return "EXTREME [bandpass] ${active.joinToString("+")}"
    }
    
    /**
     * Setup enhanced noise reduction components.
     * 
     * Initializes band-pass filter and Android NoiseSuppressor (RNNoise disabled for now).
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        val sessionId = audioRecord?.audioSessionId ?: return
        
        // DISABLED: RNNoise temporarily disabled for stability
        // Will re-enable after LIGHT mode is perfected and debugged
        /*
        try {
            rnHandle = RNNoise.init()
            rnAvailable = (rnHandle != 0L)
            if (rnAvailable) {
                Log.d(tag, "RNNoise initialized successfully")
            } else {
                Log.w(tag, "RNNoise initialization failed")
            }
        } catch (e: Exception) {
            Log.w(tag, "RNNoise not available: ${e.message}")
            rnAvailable = false
        }
        */
        Log.d(tag, "RNNoise disabled (will re-enable after debugging)")
        
        // Initialize band-pass filter (300-3400 Hz for human voice)
        try {
            bandPassFilter = BandPassFilter(
                sampleRate = sampleRate,
                lowCutHz = 300f,   // Remove low-frequency noise
                highCutHz = 3400f  // Remove high-frequency noise
            )
            Log.d(tag, "BandPassFilter initialized (300-3400 Hz)")
        } catch (e: Exception) {
            Log.w(tag, "BandPassFilter setup failed: ${e.message}")
        }
        
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
        // Release RNNoise
        if (rnAvailable && rnHandle != 0L) {
            try {
                RNNoise.release(rnHandle)
                rnHandle = 0L
                rnAvailable = false
                Log.d(tag, "RNNoise released")
            } catch (e: Exception) {
                Log.w(tag, "RNNoise cleanup failed: ${e.message}")
            }
        }
        
        // Release band-pass filter
        try {
            bandPassFilter?.reset()
            bandPassFilter = null
            Log.d(tag, "BandPassFilter released")
        } catch (e: Exception) {
            Log.w(tag, "BandPassFilter cleanup failed: ${e.message}")
        }
        
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
