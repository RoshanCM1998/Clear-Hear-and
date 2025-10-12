package com.clearhearand.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object Fft512 {
    private const val N = 512

    fun fftReal(input: FloatArray, realOut: FloatArray, imagOut: FloatArray) {
        // input length may be <= 512; zero-pad
        for (i in 0 until N) {
            val v = if (i < input.size) input[i] else 0f
            realOut[i] = v
            imagOut[i] = 0f
        }
        fft(realOut, imagOut)
    }

    fun ifft(real: FloatArray, imag: FloatArray, out: FloatArray) {
        // Conjugate
        for (i in 0 until N) imag[i] = -imag[i]
        fft(real, imag)
        // Conjugate again and scale
        for (i in 0 until N) {
            imag[i] = -imag[i]
            val v = real[i] / N
            if (i < out.size) out[i] = v
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = N
        var j = 0
        for (i in 1 until n - 1) {
            var bit = n shr 1
            while (j >= bit) { j -= bit; bit = bit shr 1 }
            j += bit
            if (i < j) {
                var tmp = real[i]; real[i] = real[j]; real[j] = tmp
                tmp = imag[i]; imag[i] = imag[j]; imag[j] = tmp
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wlenCos = cos(ang).toFloat()
            val wlenSin = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wCos = 1f
                var wSin = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2]
                    val vIm = imag[i + k + len / 2]
                    val tRe = vRe * wCos - vIm * wSin
                    val tIm = vRe * wSin + vIm * wCos
                    real[i + k] = uRe + tRe
                    imag[i + k] = uIm + tIm
                    real[i + k + len / 2] = uRe - tRe
                    imag[i + k + len / 2] = uIm - tIm
                    val nextCos = wCos * wlenCos - wSin * wlenSin
                    val nextSin = wCos * wlenSin + wSin * wlenCos
                    wCos = nextCos
                    wSin = nextSin
                }
                i += len
            }
            len = len shl 1
        }
    }
}
