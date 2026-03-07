package com.clearhear.audio.processors

import android.media.AudioRecord

/**
 * OFF Mode Processor - Pure Passthrough Implementation
 * 
 * This processor implements pure passthrough behavior with NO audio processing:
 * - No noise suppression
 * - No Android effects
 * - No DSP components
 * - No filters
 * 
 * The audio flows directly from input to output with only user-controlled
 * gain and volume applied. This mode is identical to the original code before
 * the noise cancellation feature was added.
 * 
 * Use cases:
 * - Silent environments where no noise reduction is needed
 * - Testing and debugging
 * - Maximum audio quality with no processing artifacts
 * - When user wants completely unmodified audio
 * 
 * Processing pipeline:
 * ```
 * Input (microphone)
 *   ↓
 * Gain multiplication
 *   ↓
 * Volume multiplication
 *   ↓
 * Clamp to prevent overflow
 *   ↓
 * Output (speaker/headphones)
 * ```
 * 
 * @see IAudioModeProcessor
 */
class OffModeProcessor : IAudioModeProcessor {
    
    /**
     * Process audio with pure passthrough.
     * 
     * Each sample is multiplied by gain and volume, then clamped to prevent
     * integer overflow. This is the exact same processing as the original code.
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Process exactly like old code for consistency
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
     * Returns "OFF [passthrough only]" to indicate no processing.
     */
    override fun getDescription(): String = "OFF [passthrough only]"
    
    /**
     * No setup needed for OFF mode - pure passthrough requires no initialization.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        // No setup needed for pure passthrough
    }
    
    /**
     * No cleanup needed for OFF mode - no resources were allocated.
     */
    override fun cleanup() {
        // No cleanup needed for pure passthrough
    }
}
