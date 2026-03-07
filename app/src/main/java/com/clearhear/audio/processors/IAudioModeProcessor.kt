package com.clearhear.audio.processors

import android.media.AudioRecord

/**
 * Strategy interface for different noise cancellation modes.
 * 
 * Each mode has its own implementation with complete control over:
 * - Audio processing pipeline
 * - Effect management
 * - DSP components
 * - Lifecycle (setup/cleanup)
 * 
 * This follows the Strategy Pattern (also known as Policy Pattern) for clean
 * separation of concerns and easy extensibility.
 * 
 * Naming Convention: Interface name starts with "I" (C#/.NET convention)
 * 
 * @see OffModeProcessor Pure passthrough implementation
 * @see LightModeProcessor Android built-in effects implementation
 * @see ExtremeModeProcessor Enhanced noise reduction implementation
 */
interface IAudioModeProcessor {
    /**
     * Process one chunk of audio.
     * 
     * This method is called for each audio frame (typically 100ms at 48kHz = 4800 samples).
     * The implementation should process the input chunk and write results to the output chunk.
     * 
     * @param inChunk Input PCM16 samples (mono)
     * @param outChunk Output PCM16 samples (mono, same size as input)
     * @param gain Gain multiplier (1.0 = 100%, 1.5 = 150%, etc.)
     * @param volume Volume multiplier (1.0 = 100%, 2.0 = 200%, etc.)
     */
    fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float)
    
    /**
     * Get a human-readable description of this mode for logging.
     * 
     * This should describe what processing is being applied, including any active
     * effects or DSP components.
     * 
     * Examples:
     * - "OFF-passthrough"
     * - "LIGHT-Android[NS,AGC,AEC]"
     * - "EXTREME-Enhanced[NS,Wiener,SpectralGate]"
     * 
     * @return Description string for logs
     */
    fun getDescription(): String
    
    /**
     * Setup any effects or DSP components.
     * 
     * Called when:
     * - The mode is activated (user switches to this mode)
     * - The app starts with this mode
     * 
     * This is where you should:
     * - Enable Android audio effects (NoiseSuppressor, AGC, AEC)
     * - Initialize DSP components (filters, FFT, etc.)
     * - Allocate buffers
     * 
     * @param audioRecord The AudioRecord instance to attach effects to
     * @param sampleRate The audio sample rate (typically 48000 Hz)
     */
    fun setup(audioRecord: AudioRecord?, sampleRate: Int)
    
    /**
     * Cleanup any effects or DSP components.
     * 
     * Called when:
     * - The mode is deactivated (user switches to another mode)
     * - The app stops audio processing
     * 
     * This is where you should:
     * - Disable and release Android audio effects
     * - Cleanup DSP components
     * - Free buffers
     * 
     * Always call this before switching modes to prevent resource leaks.
     */
    fun cleanup()
}
