package com.clearhearand.audio.dsp.eq

import com.clearhearand.audio.dsp.SixBandEqualizer

class AdditiveEqualizer(sampleRate: Int = 48000) : IEqualizer {

    private val core = SixBandEqualizer(sampleRate)
    private val currentBands = FloatArray(6)

    override fun setBands(gains: FloatArray) {
        for (i in 0 until 6) {
            currentBands[i] = gains[i].coerceIn(SixBandEqualizer.MIN_DB, SixBandEqualizer.MAX_DB)
        }
        core.setBands(currentBands)
    }

    override fun process(buffer: ShortArray) = core.process(buffer)

    override fun isFlat(): Boolean = core.isFlat()

    override fun reset() = core.reset()

    override fun getDescription(): String =
        "EQ-Additive[${currentBands.joinToString(",") { "%.0f".format(it) }}]"
}
