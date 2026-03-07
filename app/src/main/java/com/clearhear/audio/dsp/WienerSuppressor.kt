package com.clearhear.audio.dsp

import kotlin.math.max

class WienerSuppressor(
    private val fftSize: Int = 512,
    private val alpha: Float = 0.95f, // noise floor smoothing
    private val oversub: Float = 1.0f // light suppression
) {
    private val noiseMag = FloatArray(fftSize)

    fun process(frame: FloatArray) {
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        Fft512.fftReal(frame, re, im)

        // Magnitude and noise floor update
        var i = 0
        while (i < fftSize) {
            val mag = kotlin.math.hypot(re[i].toDouble(), im[i].toDouble()).toFloat()
            noiseMag[i] = alpha * noiseMag[i] + (1f - alpha) * mag
            i++
        }

        // Gain computation (Wiener-like)
        i = 0
        while (i < fftSize) {
            val mag = kotlin.math.hypot(re[i].toDouble(), im[i].toDouble()).toFloat()
            val noise = noiseMag[i]
            val clean = max(0f, mag - oversub * noise)
            val gain = if (mag > 1e-6f) (clean / mag).coerceIn(0.2f, 1.0f) else 1.0f
            re[i] *= gain
            im[i] *= gain
            i++
        }

        val out = FloatArray(frame.size)
        Fft512.ifft(re, im, out)
        System.arraycopy(out, 0, frame, 0, frame.size)
    }
}
