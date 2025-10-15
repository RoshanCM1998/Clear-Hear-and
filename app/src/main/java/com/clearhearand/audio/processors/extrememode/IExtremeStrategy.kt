package com.clearhearand.audio.processors.extrememode

/**
 * Strategy interface for different EXTREME mode noise cancellation approaches.
 *
 * Each strategy implements a different method of aggressive voice isolation:
 * - SpectralGate: Manual threshold-based voice/noise discrimination with learning
 * - RNNoise: ML-based recurrent neural network noise suppression
 *
 * All strategies are designed to separate human voice from background sounds
 * (music, conversations, traffic, etc.) in very noisy environments while
 * maintaining loud, clear output for hearing aid users.
 */
interface IExtremeStrategy {
    /**
     * Initialize the strategy with audio parameters.
     *
     * @param audioSessionId Audio session ID for hardware effects
     * @param sampleRate Sample rate in Hz (typically 48000)
     * @param chunkSize Number of samples per processing chunk (typically 4800 = 100ms)
     */
    fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int)

    /**
     * Process an audio chunk through this strategy.
     *
     * The strategy should:
     * 1. Remove all non-voice frequencies (if applicable)
     * 2. Suppress background noise within voice range
     * 3. Preserve voice clarity and volume
     *
     * @param samples Audio samples (16-bit PCM, modified in-place)
     */
    fun process(samples: ShortArray)

    /**
     * Clean up any resources used by this strategy.
     *
     * Called when switching strategies or stopping EXTREME mode.
     * Should release all native handles, buffers, and effects.
     */
    fun cleanup()

    /**
     * Get a human-readable description of this strategy's current state.
     *
     * Should include:
     * - Active components (filters, gates, etc.)
     * - Current processing status (learning, voice detected, etc.)
     * - Relevant metrics (RMS, threshold, gain, etc.)
     *
     * Examples:
     * - "SpectralGate[BandPass(300-3400Hz)+Gate[VOICE rms=0.005 thr=0.003]]"
     * - "RNNoise[ML-based suppression, 480 samples]"
     *
     * @return Description string for logging and debugging
     */
    fun getDescription(): String

    /**
     * Get the display name for this strategy (shown in UI).
     *
     * Examples:
     * - "Spectral Gate"
     * - "RNNoise (ML)"
     *
     * @return Short display name for UI selection
     */
    fun getDisplayName(): String

    /**
     * Check if this strategy is ready to process audio.
     *
     * May return false if:
     * - Still learning noise profile (Spectral Gate)
     * - Waiting for noise recording (Spectral Gate with pre-recording)
     * - ML model not loaded (RNNoise)
     * - Native library not available (RNNoise)
     *
     * @return True if ready to process, false if still initializing
     */
    fun isReady(): Boolean
}
