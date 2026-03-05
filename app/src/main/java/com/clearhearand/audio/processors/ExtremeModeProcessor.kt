package com.clearhearand.audio.processors

import android.content.Context
import android.media.AudioRecord
import android.util.Log
import com.rikorose.deepfilternet.NativeDeepFilterNet
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EXTREME Mode Processor - DeepFilterNet ML Noise Reduction
 *
 * Uses DeepFilterNet, a state-of-the-art open-source ML model specifically
 * designed for hearing aids (IEEE published). Natively supports 48kHz.
 *
 * Processing pipeline:
 * ```
 * Input (microphone, 48kHz mono PCM16)
 *   |
 * DeepFilterNet ML Processing (in frames)
 *   |
 * Gain multiplication (user control)
 *   |
 * Volume multiplication (user control)
 *   |
 * Clamp to prevent overflow
 *   |
 * Output (speaker/headphones)
 * ```
 */
class ExtremeModeProcessor(private val context: Context) : IAudioModeProcessor {

    private val tag = "ExtremeModeProcessor"

    private var dfn: NativeDeepFilterNet? = null
    private var frameSamples: Int = 0  // samples per DeepFilterNet frame

    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)

        val model = dfn
        if (model != null && frameSamples > 0) {
            try {
                val buf = ByteBuffer.allocateDirect(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
                var offset = 0

                while (offset + frameSamples <= outChunk.size) {
                    buf.clear()
                    for (i in 0 until frameSamples) {
                        buf.putShort(outChunk[offset + i])
                    }
                    buf.rewind()

                    model.processFrame(buf)

                    buf.rewind()
                    for (i in 0 until frameSamples) {
                        outChunk[offset + i] = buf.getShort()
                    }

                    offset += frameSamples
                }
            } catch (e: Exception) {
                Log.w(tag, "DeepFilterNet processing error: ${e.message}")
            }
        }

        // Apply gain
        for (i in outChunk.indices) {
            var v = (outChunk[i].toInt() * gain)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }

        // Apply volume and clamp
        for (i in outChunk.indices) {
            var v = (outChunk[i].toInt() * volume)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }
    }

    override fun getDescription(): String {
        val active = if (dfn != null) "DeepFilterNet" else "passthrough(DFN-unavailable)"
        return "EXTREME-$active"
    }

    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        try {
            val model = NativeDeepFilterNet(context)
            model.setAttenuationLimit(30f)

            val frameLen = model.frameLength.toInt()  // bytes per frame
            frameSamples = frameLen / 2  // 16-bit = 2 bytes per sample
            dfn = model

            Log.d(tag, "DeepFilterNet initialized: frameLength=$frameLen bytes, frameSamples=$frameSamples")
        } catch (e: Exception) {
            Log.e(tag, "DeepFilterNet initialization failed: ${e.message}")
            dfn = null
            frameSamples = 0
        }

        Log.d(tag, "EXTREME mode setup complete: ${getDescription()}")
    }

    override fun cleanup() {
        try {
            dfn?.release()
            dfn = null
            frameSamples = 0
            Log.d(tag, "DeepFilterNet released")
        } catch (e: Exception) {
            Log.w(tag, "DeepFilterNet cleanup failed: ${e.message}")
        }

        Log.d(tag, "EXTREME mode cleanup complete")
    }
}
