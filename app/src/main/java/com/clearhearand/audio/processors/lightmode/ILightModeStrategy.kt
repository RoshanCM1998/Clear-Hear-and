package com.clearhearand.audio.processors.lightmode

/**
 * Strategy interface for different LIGHT mode filtering approaches.
 * 
 * Each strategy implements a different method of noise reduction:
 * - AndroidEffects: Uses built-in hardware effects
 * - HighPassFilter: DC blocking + 80Hz high-pass
 * - AdaptiveGate: DC blocking + high-pass + noise gating
 * - CustomProfile: Learned filter from recorded noise profile
 */
interface ILightModeStrategy {
    /**
     * Initialize the strategy with audio parameters.
     * 
     * @param audioSessionId Audio session ID for hardware effects
     * @param sampleRate Sample rate in Hz (typically 48000)
     * @param chunkSize Number of samples per processing chunk
     */
    fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int)
    
    /**
     * Process an audio chunk through this strategy.
     * 
     * @param samples Audio samples (16-bit PCM, modified in-place)
     */
    fun process(samples: ShortArray)
    
    /**
     * Clean up any resources used by this strategy.
     */
    fun cleanup()
    
    /**
     * Get a human-readable description of this strategy.
     * 
     * @return Description string for logging/UI
     */
    fun getDescription(): String
    
    /**
     * Get the display name for this strategy (shown in UI).
     * 
     * @return Short display name
     */
    fun getDisplayName(): String
}
