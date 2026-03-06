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
 * Pipeline: Gain → DeepFilterNet → Volume
 */
class ExtremeModeProcessor(private val context: Context) : IAudioModeProcessor {

    private val tag = "ExtremeModeProcessor"

    private var dfn: NativeDeepFilterNet? = null
    @Volatile private var frameSamples: Int = 0

    // Diagnostics exposed for CSV logging
    @Volatile var lastFilteredRms: Float = 0f; private set
    @Volatile var lastFilteredPeak: Float = 0f; private set
    @Volatile var lastSnr: Float = -1f; private set
    @Volatile var rawFrameLength: Int = -1; private set

    override fun process(inChunk: ShortArray, outChunk: ShortArray, gain: Float, volume: Float) {
        System.arraycopy(inChunk, 0, outChunk, 0, inChunk.size)

        // Step 1: Apply gain FIRST (filter works on amplified signal)
        for (i in outChunk.indices) {
            var v = (outChunk[i].toInt() * gain)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }

        // Step 2: DeepFilterNet on gain-amplified signal
        val model = dfn
        val fs = frameSamples
        if (model != null && fs > 0) {
            try {
                val buf = ByteBuffer.allocateDirect(fs * 2).order(ByteOrder.LITTLE_ENDIAN)
                var offset = 0
                var snr = -1f

                while (offset + fs <= outChunk.size) {
                    buf.clear()
                    for (i in 0 until fs) {
                        buf.putShort(outChunk[offset + i])
                    }
                    buf.rewind()

                    snr = model.processFrame(buf)

                    buf.rewind()
                    for (i in 0 until fs) {
                        outChunk[offset + i] = buf.getShort()
                    }

                    offset += fs
                }
                lastSnr = snr
            } catch (e: Exception) {
                Log.w(tag, "DeepFilterNet processing error: ${e.message}")
            }
        }

        // Measure post-DFN levels (after DFN, before volume)
        var sumSq = 0.0
        var peak = 0f
        for (i in outChunk.indices) {
            val n = outChunk[i] / 32768f
            sumSq += (n * n)
            val a = kotlin.math.abs(n)
            if (a > peak) peak = a
        }
        lastFilteredRms = kotlin.math.sqrt(sumSq / outChunk.size).toFloat()
        lastFilteredPeak = peak

        // Step 3: Apply volume LAST
        for (i in outChunk.indices) {
            var v = (outChunk[i].toInt() * volume)
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            outChunk[i] = v.toInt().toShort()
        }
    }

    override fun getDescription(): String {
        val active = when {
            dfn != null && frameSamples > 0 -> "DeepFilterNet(snr=${lastSnr})"
            dfn != null -> "DeepFilterNet(loading)"
            else -> "passthrough(DFN-unavailable)"
        }
        return "EXTREME-$active"
    }

    override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
        try {
            val model = NativeDeepFilterNet(context)
            dfn = model

            model.onModelLoaded { loadedModel ->
                loadedModel.setAttenuationLimit(30f)

                val frameLen = loadedModel.frameLength.toInt()
                rawFrameLength = frameLen
                Log.d(tag, "DeepFilterNet model loaded: raw frameLength=$frameLen")

                // frameLength is the ByteBuffer byte count; PCM16 = 2 bytes/sample
                frameSamples = frameLen / 2

                Log.d(tag, "DeepFilterNet ready: frameBytes=$frameLen, frameSamples=$frameSamples")
                if (frameSamples < 100 || frameSamples > 4800) {
                    Log.w(tag, "WARNING: frameSamples=$frameSamples seems unusual, expected ~480 or ~960")
                }
            }

            Log.d(tag, "DeepFilterNet created, waiting for model to load...")
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
