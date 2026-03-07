package com.clearhear.audio.dsp

import kotlin.math.*

/**
 * Spectral Noise Suppressor for LIGHT mode (TRIAL 2)
 * 
 * Uses spectral subtraction to remove white noise while preserving voice.
 * Unlike volume gating, this targets SPECIFIC FREQUENCIES of noise.
 * 
 * Algorithm:
 * 1. Learn noise spectrum during initialization (frequency profile)
 * 2. Apply FFT to convert audio to frequency domain
 * 3. Subtract learned noise spectrum from signal spectrum
 * 4. Apply iFFT to convert back to time domain
 * 5. Voice frequencies remain untouched
 * 
 * This is the CORRECT approach for hearing aids - we remove noise frequencies,
 * not reduce overall volume!
 */
class SpectralNoiseSuppressor(
    private val sampleRate: Int,
    private val fftSize: Int = 512  // FFT window size
) {
    // Noise spectrum (learned during initialization)
    private var noiseSpectrum: FloatArray? = null
    private var learningFrameCount = 0
    private val learningFrames = 20  // Learn from first 2 seconds
    
    // Overlap-add buffers for smooth transitions
    private val hopSize = fftSize / 2  // 50% overlap
    private var prevOutput = FloatArray(fftSize) { 0f }
    private var outputBuffer = FloatArray(fftSize) { 0f }
    
    // Hanning window for FFT
    private val window: FloatArray = FloatArray(fftSize) { i ->
        0.5f * (1 - cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    
    // FFT helper
    private val fft = SimpleFFT(fftSize)
    
    // Spectral floor (minimum gain to prevent artifacts)
    private val spectralFloor = 0.1f  // -20dB
    
    /**
     * Process audio with spectral subtraction
     */
    fun process(samples: ShortArray) {
        // During learning phase, collect noise spectrum but pass through audio unchanged
        if (learningFrameCount < learningFrames) {
            // Convert to float for learning
            val floats = pcm16ToFloat(samples)
            learnNoiseSpectrum(floats)
            learningFrameCount++
            // Pass through unchanged (audio already in samples)
            return
        }
        
        // Convert to float
        val floats = pcm16ToFloat(samples)
        
        // Process with overlap-add for smooth transitions
        processWithOverlapAdd(floats)
        
        // Convert back to PCM16
        floatToPcm16(floats, samples)
    }
    
    /**
     * Learn noise spectrum from quiet periods
     */
    private fun learnNoiseSpectrum(samples: FloatArray) {
        // Apply window
        val windowed = FloatArray(fftSize)
        for (i in 0 until min(samples.size, fftSize)) {
            windowed[i] = samples[i] * window[i]
        }
        
        // Compute FFT
        val spectrum = fft.forward(windowed)
        
        // Accumulate magnitude spectrum
        if (noiseSpectrum == null) {
            noiseSpectrum = FloatArray(spectrum.size / 2) { i ->
                val re = spectrum[2 * i]
                val im = spectrum[2 * i + 1]
                sqrt(re * re + im * im)
            }
        } else {
            // Running average
            for (i in noiseSpectrum!!.indices) {
                val re = spectrum[2 * i]
                val im = spectrum[2 * i + 1]
                val mag = sqrt(re * re + im * im)
                noiseSpectrum!![i] = (noiseSpectrum!![i] * learningFrameCount + mag) / (learningFrameCount + 1)
            }
        }
    }
    
    /**
     * Process with overlap-add method
     */
    private fun processWithOverlapAdd(samples: FloatArray) {
        val noise = noiseSpectrum ?: return
        
        // Process in overlapping windows
        var offset = 0
        val output = FloatArray(samples.size)
        
        while (offset + fftSize <= samples.size) {
            // Extract and window frame
            val frame = FloatArray(fftSize)
            for (i in 0 until fftSize) {
                frame[i] = samples[offset + i] * window[i]
            }
            
            // Apply FFT
            val spectrum = fft.forward(frame)
            
            // Apply spectral subtraction
            for (i in 0 until noise.size) {
                val re = spectrum[2 * i]
                val im = spectrum[2 * i + 1]
                val mag = sqrt(re * re + im * im)
                val phase = atan2(im, re)
                
                // Subtract noise spectrum (over-subtraction factor = 1.5 for better removal)
                val cleanMag = max(mag - 1.5f * noise[i], spectralFloor * mag)
                
                // Convert back to complex
                spectrum[2 * i] = cleanMag * cos(phase)
                spectrum[2 * i + 1] = cleanMag * sin(phase)
            }
            
            // Apply iFFT
            val cleaned = fft.inverse(spectrum)
            
            // Overlap-add
            for (i in 0 until fftSize) {
                if (offset + i < output.size) {
                    output[offset + i] += cleaned[i] * window[i]
                }
            }
            
            offset += hopSize
        }
        
        // Copy to input array
        for (i in output.indices) {
            if (i < samples.size) {
                samples[i] = output[i]
            }
        }
    }
    
    /**
     * Convert PCM16 to float [-1, 1]
     */
    private fun pcm16ToFloat(pcm: ShortArray): FloatArray {
        return FloatArray(pcm.size) { pcm[it] / 32768f }
    }
    
    /**
     * Convert float [-1, 1] to PCM16
     */
    private fun floatToPcm16(floats: FloatArray, out: ShortArray) {
        for (i in floats.indices) {
            var v = floats[i] * 32768f
            if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
            if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
            out[i] = v.toInt().toShort()
        }
    }
    
    /**
     * Reset learned noise spectrum
     */
    fun reset() {
        noiseSpectrum = null
        learningFrameCount = 0
        prevOutput.fill(0f)
        outputBuffer.fill(0f)
    }
}

/**
 * Simple FFT implementation for spectral processing
 */
class SimpleFFT(private val n: Int) {
    init {
        require(n and (n - 1) == 0) { "FFT size must be power of 2" }
    }
    
    /**
     * Forward FFT (real to complex)
     */
    fun forward(input: FloatArray): FloatArray {
        val complex = FloatArray(n * 2)  // Interleaved re, im
        for (i in 0 until min(input.size, n)) {
            complex[2 * i] = input[i]
            complex[2 * i + 1] = 0f
        }
        fft(complex, false)
        return complex
    }
    
    /**
     * Inverse FFT (complex to real)
     */
    fun inverse(input: FloatArray): FloatArray {
        val complex = input.copyOf()
        fft(complex, true)
        val output = FloatArray(n)
        for (i in 0 until n) {
            output[i] = complex[2 * i] / n  // Normalize
        }
        return output
    }
    
    /**
     * In-place FFT (Cooley-Tukey algorithm)
     */
    private fun fft(x: FloatArray, inverse: Boolean) {
        // Bit reversal
        var j = 0
        for (i in 0 until n) {
            if (i < j) {
                // Swap
                var temp = x[2 * i]
                x[2 * i] = x[2 * j]
                x[2 * j] = temp
                temp = x[2 * i + 1]
                x[2 * i + 1] = x[2 * j + 1]
                x[2 * j + 1] = temp
            }
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        
        // FFT
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len * (if (inverse) 1 else -1)
            val wlen_re = cos(angle).toFloat()
            val wlen_im = sin(angle).toFloat()
            
            var i = 0
            while (i < n) {
                var w_re = 1f
                var w_im = 0f
                for (j in 0 until len / 2) {
                    val u_re = x[2 * (i + j)]
                    val u_im = x[2 * (i + j) + 1]
                    val v_re = x[2 * (i + j + len / 2)]
                    val v_im = x[2 * (i + j + len / 2) + 1]
                    
                    val t_re = w_re * v_re - w_im * v_im
                    val t_im = w_re * v_im + w_im * v_re
                    
                    x[2 * (i + j)] = u_re + t_re
                    x[2 * (i + j) + 1] = u_im + t_im
                    x[2 * (i + j + len / 2)] = u_re - t_re
                    x[2 * (i + j + len / 2) + 1] = u_im - t_im
                    
                    val temp = w_re
                    w_re = w_re * wlen_re - w_im * wlen_im
                    w_im = temp * wlen_im + w_im * wlen_re
                }
                i += len
            }
            len *= 2
        }
    }
}
