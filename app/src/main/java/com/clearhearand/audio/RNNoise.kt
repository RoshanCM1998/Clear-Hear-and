package com.clearhearand.audio

/**
 * RNNoise JNI Wrapper
 *
 * Provides access to the RNNoise noise suppression library via JNI.
 * RNNoise uses a recurrent neural network for real-time audio denoising.
 *
 * Frame size: 480 samples at 48kHz (10ms)
 * Input/Output format: FloatArray with values in range [-1.0, 1.0]
 *
 * @see com.clearhearand.audio.processors.extrememode.RNNoiseStrategy
 */
object RNNoise {
    /**
     * Initialize RNNoise with default model.
     *
     * @return Handle to DenoiseState, or 0 on failure
     */
    external fun init(): Long

    /**
     * Process a 480-sample frame through RNNoise.
     *
     * @param handle Handle from init()
     * @param input Float array with exactly 480 samples in range [-1.0, 1.0]
     * @return Float array with 480 denoised samples, or null on error
     */
    external fun process(handle: Long, input: FloatArray): FloatArray?

    /**
     * Release RNNoise resources.
     *
     * @param handle Handle from init()
     */
    external fun release(handle: Long)

    init {
        try {
            System.loadLibrary("rnnoise")
        } catch (t: Throwable) {
            // Library not available; calls will fail. Caller must handle.
        }
    }
}