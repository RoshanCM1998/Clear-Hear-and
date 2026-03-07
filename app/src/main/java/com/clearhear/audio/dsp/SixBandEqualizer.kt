package com.clearhear.audio.dsp

class SixBandEqualizer(private val sampleRate: Int = 48000) {

    companion object {
        val CENTER_FREQUENCIES = doubleArrayOf(250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
        private const val Q = 1.4
        const val MIN_DB = -12f
        const val MAX_DB = 12f
    }

    private val bands = Array(6) { i ->
        Biquad(FilterType.PEAKING, sampleRate, CENTER_FREQUENCIES[i], Q, 0.0)
    }

    private val gains = FloatArray(6) // current dB gains

    fun setBands(newGains: FloatArray) {
        for (i in 0 until 6) {
            val g = newGains[i].coerceIn(MIN_DB, MAX_DB)
            if (g != gains[i]) {
                gains[i] = g
                bands[i].setGain(g.toDouble())
            }
        }
    }

    fun isFlat(): Boolean = gains.all { it == 0f }

    fun process(buffer: ShortArray) {
        for (i in buffer.indices) {
            var sample = buffer[i].toDouble()
            for (band in bands) {
                sample = band.process(sample)
            }
            // Clamp to 16-bit range
            val clamped = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            buffer[i] = clamped.toShort()
        }
    }

    fun reset() {
        for (band in bands) band.reset()
    }
}
