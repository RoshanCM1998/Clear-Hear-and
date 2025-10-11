package com.example.audio

object RNNoise {
    external fun init(): Long
    external fun process(handle: Long, input: FloatArray): FloatArray
    external fun release(handle: Long)

    init {
        try {
            System.loadLibrary("rnnoise")
        } catch (t: Throwable) {
            // Library not available; calls will fail. Caller must handle.
        }
    }
}