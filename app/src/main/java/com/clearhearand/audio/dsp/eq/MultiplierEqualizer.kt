package com.clearhearand.audio.dsp.eq

import com.clearhearand.audio.dsp.SixBandEqualizer
import kotlin.math.log10

class MultiplierEqualizer(sampleRate: Int = 48000) : IEqualizer {

    private val core = SixBandEqualizer(sampleRate)
    private val currentBands = FloatArray(6) { 100f }

    override fun setBands(gains: FloatArray) {
        val dbGains = FloatArray(6)
        for (i in 0 until 6) {
            val multiplier = gains[i].coerceIn(1f, 500f)
            currentBands[i] = multiplier
            // Convert multiplier percentage to dB: dB = 20 * log10(multiplier / 100)
            dbGains[i] = (20.0 * log10(multiplier / 100.0)).toFloat()
                .coerceIn(SixBandEqualizer.MIN_DB, SixBandEqualizer.MAX_DB)
        }
        core.setBands(dbGains)
    }

    override fun process(buffer: ShortArray) = core.process(buffer)

    override fun isFlat(): Boolean = currentBands.all { it == 100f }

    override fun reset() {
        for (i in currentBands.indices) currentBands[i] = 100f
        core.reset()
    }

    override fun getDescription(): String =
        "EQ-Multiplier[${currentBands.joinToString(",") { "%.0f".format(it) }}]"
}
