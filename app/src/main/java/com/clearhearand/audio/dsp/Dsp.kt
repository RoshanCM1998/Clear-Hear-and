package com.clearhearand.audio.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

enum class FilterType { LOWPASS, HIGHPASS, PEAKING }

class Biquad(
    private val type: FilterType,
    private val sampleRate: Int,
    private val cutoffHz: Double,
    private val q: Double = 0.707,
    private var dBGain: Double = 0.0
) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var z1 = 0.0
    private var z2 = 0.0

    init { recalc() }

    fun setGain(newDbGain: Double) {
        dBGain = newDbGain
        recalc()
        reset()
    }

    private fun recalc() {
        val w0 = 2.0 * PI * (cutoffHz / sampleRate)
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        when (type) {
            FilterType.LOWPASS -> {
                val a0 = 1.0 + alpha
                val bb0 = (1.0 - c) / 2.0
                val bb1 = 1.0 - c
                val bb2 = (1.0 - c) / 2.0
                val aa1 = -2.0 * c
                val aa2 = 1.0 - alpha
                b0 = bb0 / a0; b1 = bb1 / a0; b2 = bb2 / a0
                a1 = aa1 / a0; a2 = aa2 / a0
            }
            FilterType.HIGHPASS -> {
                val a0 = 1.0 + alpha
                val bb0 = (1.0 + c) / 2.0
                val bb1 = -(1.0 + c)
                val bb2 = (1.0 + c) / 2.0
                val aa1 = -2.0 * c
                val aa2 = 1.0 - alpha
                b0 = bb0 / a0; b1 = bb1 / a0; b2 = bb2 / a0
                a1 = aa1 / a0; a2 = aa2 / a0
            }
            FilterType.PEAKING -> {
                val A = 10.0.pow(dBGain / 40.0)
                val alphaA = alpha * A
                val alphaOverA = alpha / A
                val a0 = 1.0 + alphaOverA
                b0 = (1.0 + alphaA) / a0
                b1 = (-2.0 * c) / a0
                b2 = (1.0 - alphaA) / a0
                a1 = (-2.0 * c) / a0
                a2 = (1.0 - alphaOverA) / a0
            }
        }
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0.0; z2 = 0.0 }
}

class SoftLimiter(private val threshold: Float = 0.9f) {
    fun process(sample: Float): Float {
        val t = threshold
        val a = abs(sample)
        if (a <= t) return sample
        val sign = if (sample >= 0f) 1f else -1f
        val excess = a - t
        val compressed = t + excess / (1f + 10f * excess)
        return sign * min(1f, compressed)
    }
}

class SimpleAgc(
    private val targetRms: Float = 0.1f,
    private val maxGain: Float = 1.5f,
    private val attack: Float = 0.05f,
    private val release: Float = 0.005f
) {
    private var gain = 1.0f

    fun process(frame: FloatArray) {
        var sum = 0.0
        for (v in frame) sum += (v * v)
        val rms = kotlin.math.sqrt(sum / frame.size).toFloat().coerceAtLeast(1e-6f)
        val desired = (targetRms / rms).coerceIn(0.1f, maxGain)
        val coeff = if (desired < gain) attack else release
        gain += coeff * (desired - gain)
        for (i in frame.indices) frame[i] *= gain
    }
}

fun pcm16ToFloat(input: ShortArray, out: FloatArray, gain: Float) {
    val safeGain = gain.coerceIn(0.5f, 1.5f)
    val scale = 1f / 32768f
    val n = input.size
    var i = 0
    while (i < n) {
        out[i] = input[i] * scale * safeGain
        i++
    }
}

fun floatToPcm16(input: FloatArray, out: ShortArray, limiter: SoftLimiter?) {
    val n = input.size
    var i = 0
    while (i < n) {
        var v = input[i]
        if (limiter != null) v = limiter.process(v)
        val s = (v * 32768f).toInt()
        out[i] = when {
            s > Short.MAX_VALUE -> Short.MAX_VALUE
            s < Short.MIN_VALUE -> Short.MIN_VALUE
            else -> s.toShort()
        }
        i++
    }
}
