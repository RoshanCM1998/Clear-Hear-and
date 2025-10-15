package com.clearhearand.audio.processors

import android.content.Context
import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import com.clearhearand.audio.processors.extrememode.*

/**
 * EXTREME Mode Processor - Strategy Pattern Implementation
 *
 * This processor provides strong noise reduction for very noisy environments
 * with the goal of isolating human voice from background sounds while maintaining
 * loud, clear output for hearing aid users.
 *
 * This processor delegates to one of multiple voice isolation strategies:
 * 1. **Spectral Gate**: Manual threshold-based voice/noise discrimination with learning
 * 2. **RNNoise (ML)**: Deep learning-based noise suppression (STUB - not yet implemented)
 *
 * The user can switch between strategies in real-time via the UI.
 *
 * Processing pipeline:
 * ```
 * Input (microphone)
 *   ↓
 * Strategy (Spectral Gate or RNNoise)
 *   ↓
 * Gain amplification with 1.5x compensation (hearing aid boost)
 *   ↓
 * Volume multiplication (final level control)
 *   ↓
 * Clamp to prevent overflow
 *   ↓
 * Output (speaker/headphones - LOUD and CLEAR)
 * ```
 *
 * Use cases:
 * - Noisy environments with music (isolate voice from background music)
 * - Crowded places (restaurants, cafes, parties)
 * - Traffic and outdoor noise
 * - Maximum voice clarity while preserving volume
 * - Hearing aid use where loud output is critical
 *
 * @see IAudioModeProcessor
 * @see IExtremeStrategy
 */
class ExtremeModeProcessor(private val context: Context) : IAudioModeProcessor {

    private val tag = "ExtremeModeProcessor"

    // Current active strategy
    private var currentStrategy: IExtremeStrategy? = null

    // Available strategies (lazy initialization)
    private val strategies = mapOf(
        "spectral" to lazy { SpectralGateStrategy(context) },
        "rnnoise" to lazy { RNNoiseStrategy() }
    )

    // Current strategy key
    private var currentStrategyKey: String = "spectral"  // Default

    // Android built-in effect (hardware accelerated) - optional, can be used with any strategy
    private var noiseSuppressor: NoiseSuppressor? = null

    /**
     * Set the voice isolation strategy.
     *
     * @param strategyKey Strategy identifier: "spectral" or "rnnoise"
     */
    fun setStrategy(strategyKey: String, audioRecord: AudioRecord?, sampleRate: Int) {
        if (strategyKey == currentStrategyKey && currentStrategy != null) {
            // Already using this strategy
            return
        }

        Log.d(tag, "Switching strategy: $currentStrategyKey → $strategyKey")

        // Cleanup old strategy
        currentStrategy?.cleanup()

        // Get new strategy
        val strategyLazy = strategies[strategyKey]
        if (strategyLazy == null) {
            Log.w(tag, "Unknown strategy: $strategyKey, falling back to spectral")
            currentStrategyKey = "spectral"
            currentStrategy = strategies["spectral"]?.value
        } else {
            currentStrategyKey = strategyKey
            currentStrategy = strategyLazy.value
        }

        // Setup new strategy
        val sessionId = audioRecord?.audioSessionId ?: 0
        val chunkSize = 4800  // 100ms at 48kHz
        currentStrategy?.setup(sessionId, sampleRate, chunkSize)

        Log.d(tag, "Strategy switched to: ${currentStrategy?.getDisplayName()} - ${currentStrategy?.getDescription()}")
    }

    /**
     * Process audio with multi-stage voice isolation.
     *
     * Pipeline: Strategy Processing → Compensated Gain → Volume
     *
     * This ensures maximum voice clarity by:
     * 1. Applying selected strategy (Spectral Gate or RNNoise)
     * 2. Amplifying the cleaned signal with compensation for filter losses
     * 3. Final volume adjustment for output
     *
     * NOTE: Gain is applied AFTER filtering with 1.5x compensation to ensure loud output
     */
    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        // Copy input to output
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)

        // Step 1: Apply strategy processing (Spectral Gate or RNNoise)
        currentStrategy?.process(outChunk)

        // Step 2: Apply gain amplification with filter compensation
        // COMPENSATION: 1.5x multiplier compensates for ~33% signal loss from filtering
        // This ensures EXTREME mode is LOUDER than OFF mode, suitable for hearing aid use
        val FILTER_COMPENSATION = 1.5f
        for (i in outChunk.indices) {
            val sample = outChunk[i].toInt()
            var v = (sample * gain * FILTER_COMPENSATION)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }

        // Step 3: Apply volume for final output level
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
     * Get description of current strategy.
     */
    override fun getDescription(): String {
        val strategyDesc = currentStrategy?.getDescription() ?: "NoStrategy"
        val ns = if (noiseSuppressor?.enabled == true) "+AndroidNS" else ""
        return "EXTREME [$currentStrategyKey] $strategyDesc${ns}+Comp1.5x"
    }
    
    /**
     * Setup the default strategy.
     */
    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        setStrategy(currentStrategyKey, audioRecord, sampleRate)

        // Optionally enable Android NoiseSuppressor (hardware acceleration)
        // This can be used alongside any strategy for additional noise reduction
        val sessionId = audioRecord?.audioSessionId
        if (sessionId != null) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor?.release()
                    noiseSuppressor = NoiseSuppressor.create(sessionId)
                    noiseSuppressor?.enabled = false  // Disabled by default, can be toggled in UI
                    Log.d(tag, "NoiseSuppressor available (currently disabled)")
                } else {
                    Log.w(tag, "NoiseSuppressor not available on this device")
                }
            } catch (e: Exception) {
                Log.w(tag, "NoiseSuppressor setup failed: ${e.message}")
            }
        }

        Log.d(tag, "EXTREME mode setup complete: ${getDescription()}")
    }
    
    /**
     * Cleanup current strategy.
     */
    override fun cleanup() {
        currentStrategy?.cleanup()
        currentStrategy = null

        // Disable and release NoiseSuppressor
        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
            noiseSuppressor = null
            Log.d(tag, "NoiseSuppressor released")
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor cleanup failed: ${e.message}")
        }

        Log.d(tag, "EXTREME mode cleanup complete")
    }
}
