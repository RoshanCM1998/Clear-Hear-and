package com.clearhearand.audio.processors.extrememode

import android.util.Log
import com.clearhearand.audio.RNNoise

/**
 * RNNoise Strategy - ML-based voice isolation using Recurrent Neural Networks
 *
 * This strategy uses RNNoise, a recurrent neural network trained on thousands of hours
 * of noisy speech to separate voice from noise in real-time.
 *
 * How it works:
 * 1. Buffer incoming PCM16 samples (4800 samples @ 48kHz = 100ms chunks)
 * 2. Split into 480-sample frames (10ms @ 48kHz) - RNNoise's required frame size
 * 3. Convert each frame from PCM16 to float32 (-1.0 to 1.0)
 * 4. Process through RNNoise ML model via JNI
 * 5. Convert back to PCM16
 * 6. Return processed audio
 *
 * Advantages:
 * - Superior noise suppression using deep learning
 * - No learning period required (pre-trained model)
 * - Handles non-stationary noise (music, conversations, traffic)
 * - Works well in complex acoustic environments
 *
 * Performance:
 * - Frame size: 480 samples (10ms at 48kHz)
 * - Processing latency: ~1-2ms per frame on modern ARM devices
 * - Total latency: <10ms for 4800-sample chunks
 * - CPU usage: Low (optimized C implementation with NEON on ARM)
 *
 * Limitations:
 * - Slightly slower than spectral gate (ML inference overhead)
 * - Fixed frame size (480 samples) requires buffering
 * - May introduce minor artifacts on very quiet speech
 *
 * @see IExtremeStrategy
 * @see RNNoise
 * @see com.clearhearand.audio.processors.ExtremeModeProcessor
 */
class RNNoiseStrategy : IExtremeStrategy {

    private val tag = "RNNoiseStrategy"

    // RNNoise handle (native pointer to DenoiseState)
    private var rnHandle: Long = 0L
    private var rnAvailable = false

    // Frame buffering: RNNoise needs 480 samples, we receive 4800
    // Strategy: Process in 10 frames of 480 samples each
    private val FRAME_SIZE = 480  // 10ms at 48kHz
    private val frameBufferFloat = FloatArray(FRAME_SIZE)
    private val outputBufferFloat = FloatArray(FRAME_SIZE)

    /**
     * Initialize RNNoise with default pre-trained model.
     *
     * The model is compiled into the native library (rnnoise_tables.c).
     * No external files needed - fully offline.
     */
    override fun setup(audioSessionId: Int, sampleRate: Int, chunkSize: Int) {
        try {
            // Verify sample rate
            if (sampleRate != 48000) {
                Log.w(tag, "RNNoise expects 48kHz, got ${sampleRate}Hz - may not work correctly!")
            }

            // Initialize RNNoise via JNI
            rnHandle = RNNoise.init()
            rnAvailable = (rnHandle != 0L)

            if (rnAvailable) {
                Log.d(tag, "RNNoise initialized successfully")
                Log.d(tag, "  Handle: $rnHandle")
                Log.d(tag, "  Frame size: $FRAME_SIZE samples (10ms @ 48kHz)")
                Log.d(tag, "  Chunk size: $chunkSize samples (${chunkSize/48}ms @ 48kHz)")
                Log.d(tag, "  Frames per chunk: ${chunkSize / FRAME_SIZE}")
            } else {
                Log.e(tag, "RNNoise initialization failed - handle is 0")
            }
        } catch (e: Exception) {
            Log.e(tag, "RNNoise setup failed: ${e.message}", e)
            rnAvailable = false
        }
    }

    /**
     * Process audio through RNNoise.
     *
     * Since RNNoise requires 480-sample frames and we receive 4800-sample chunks,
     * we process 10 frames sequentially.
     *
     * Processing pipeline:
     * 1. Split 4800 samples into 10 frames of 480 samples
     * 2. For each frame:
     *    a. Convert Short[] to Float[] (PCM16 → [-1.0, 1.0])
     *    b. Process through RNNoise via JNI
     *    c. Convert Float[] back to Short[]
     * 3. In-place modification of input array
     */
    override fun process(samples: ShortArray) {
        if (!rnAvailable) {
            // Library not available, pass through unchanged
            return
        }

        try {
            val totalSamples = samples.size
            val numFrames = totalSamples / FRAME_SIZE

            if (totalSamples % FRAME_SIZE != 0) {
                Log.w(tag, "Sample count ($totalSamples) not divisible by frame size ($FRAME_SIZE)")
            }

            // Process each 480-sample frame
            for (frameIdx in 0 until numFrames) {
                val offset = frameIdx * FRAME_SIZE

                // Convert PCM16 to float32 [-1.0, 1.0]
                for (i in 0 until FRAME_SIZE) {
                    frameBufferFloat[i] = samples[offset + i] / 32768f
                }

                // Process frame through RNNoise
                val processed = RNNoise.process(rnHandle, frameBufferFloat)

                if (processed != null) {
                    // Convert float32 back to PCM16
                    for (i in 0 until FRAME_SIZE) {
                        var v = processed[i] * 32768f
                        // Clamp to prevent overflow
                        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
                        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
                        samples[offset + i] = v.toInt().toShort()
                    }
                } else {
                    // Processing failed, leave frame unchanged
                    Log.w(tag, "Frame $frameIdx processing returned null")
                }
            }

        } catch (e: Exception) {
            Log.e(tag, "RNNoise processing failed: ${e.message}", e)
            // On error, leave samples unchanged (fail gracefully)
        }
    }

    /**
     * Cleanup RNNoise resources.
     *
     * Releases the native DenoiseState.
     */
    override fun cleanup() {
        if (rnAvailable && rnHandle != 0L) {
            try {
                RNNoise.release(rnHandle)
                Log.d(tag, "RNNoise released")
            } catch (e: Exception) {
                Log.w(tag, "RNNoise cleanup failed: ${e.message}", e)
            }
        }
        rnHandle = 0L
        rnAvailable = false
    }

    /**
     * Get technical description for logging.
     */
    override fun getDescription(): String {
        return if (rnAvailable) {
            "RNNoise[ML handle=$rnHandle frames=${FRAME_SIZE}smp/10ms]"
        } else {
            "RNNoise[NOT_AVAILABLE]"
        }
    }

    /**
     * Get display name for UI.
     */
    override fun getDisplayName(): String = "RNNoise (ML)"

    /**
     * Check if ready to process audio.
     */
    override fun isReady(): Boolean = rnAvailable
}
