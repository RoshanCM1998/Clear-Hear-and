package com.clearhear.audio.dsp.eq

interface IEqualizer {
    fun setBands(gains: FloatArray)
    fun process(buffer: ShortArray)
    fun isFlat(): Boolean
    fun reset()
    fun getDescription(): String
}
