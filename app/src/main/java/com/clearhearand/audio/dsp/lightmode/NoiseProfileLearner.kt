package com.clearhearand.audio.dsp.lightmode

import android.content.Context
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * Learns noise characteristics from recorded noise profile files.
 * 
 * Processes noise_profile_*.txt files to extract:
 * - Average noise RMS
 * - Peak noise level
 * - Recommended gate threshold
 * 
 * This allows creating a custom filter tuned to the user's specific noise environment.
 */
class NoiseProfileLearner(private val context: Context) {
    
    data class NoiseCharacteristics(
        val avgRms: Float,          // Average RMS of noise
        val maxRms: Float,          // Maximum RMS of noise
        val avgPeak: Float,         // Average peak amplitude
        val maxPeak: Float,         // Maximum peak amplitude
        val recommendedThreshold: Float,  // Suggested gate threshold
        val samplesAnalyzed: Int    // Number of chunks analyzed
    )
    
    /**
     * Analyze all noise profile files and compute average noise characteristics.
     * 
     * @return NoiseCharacteristics or null if no files found
     */
    fun analyzeNoiseProfiles(): NoiseCharacteristics? {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) {
            Log.w("NoiseProfileLearner", "Logs directory not found")
            return null
        }
        
        // Find noise profile file (now using single file that gets overwritten)
        val noiseFiles = logsDir.listFiles { file ->
            file.name == "noise_profile.txt"
        }
        
        if (noiseFiles == null || noiseFiles.isEmpty()) {
            Log.w("NoiseProfileLearner", "No noise profile files found")
            return null
        }
        
        Log.d("NoiseProfileLearner", "Found ${noiseFiles.size} noise profile file(s)")
        
        // Aggregate statistics across all files
        val rmsList = mutableListOf<Float>()
        val peakList = mutableListOf<Float>()
        
        for (file in noiseFiles) {
            try {
                file.bufferedReader().use { reader ->
                    reader.lineSequence()
                        .filter { !it.startsWith("#") && it.isNotBlank() }  // Skip comments
                        .forEach { line ->
                            // Format: chunk_number,rms,peak,samples...
                            val parts = line.split(",")
                            if (parts.size >= 3) {
                                try {
                                    val rms = parts[1].toFloat()
                                    val peak = parts[2].toFloat()
                                    
                                    // Filter out silence (RMS = 0) and extreme spikes
                                    if (rms > 0 && rms < 300) {
                                        rmsList.add(rms)
                                        peakList.add(peak)
                                    }
                                } catch (e: NumberFormatException) {
                                    // Skip malformed lines
                                }
                            }
                        }
                }
            } catch (e: Throwable) {
                Log.w("NoiseProfileLearner", "Error reading ${file.name}: ${e.message}")
            }
        }
        
        if (rmsList.isEmpty()) {
            Log.w("NoiseProfileLearner", "No valid noise data found in files")
            return null
        }
        
        // Calculate statistics
        val avgRms = rmsList.average().toFloat()
        val maxRms = rmsList.maxOrNull() ?: 0f
        val avgPeak = peakList.average().toFloat()
        val maxPeak = peakList.maxOrNull() ?: 0f
        
        // Recommended threshold: 3x average noise RMS
        // This ensures we don't gate out quiet speech while removing noise
        val recommendedThreshold = avgRms * 3f
        
        val characteristics = NoiseCharacteristics(
            avgRms = avgRms,
            maxRms = maxRms,
            avgPeak = avgPeak,
            maxPeak = maxPeak,
            recommendedThreshold = recommendedThreshold,
            samplesAnalyzed = rmsList.size
        )
        
        Log.d("NoiseProfileLearner", """
            Noise Analysis:
            - Avg RMS: ${avgRms.toInt()}
            - Max RMS: ${maxRms.toInt()}
            - Avg Peak: ${avgPeak.toInt()}
            - Max Peak: ${maxPeak.toInt()}
            - Recommended Threshold: ${recommendedThreshold.toInt()}
            - Samples: ${rmsList.size}
        """.trimIndent())
        
        return characteristics
    }
    
    /**
     * Check if noise profile files exist.
     * 
     * @return true if at least one noise profile file exists
     */
    fun hasNoiseProfiles(): Boolean {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) return false
        
        val noiseFile = File(logsDir, "noise_profile.txt")
        return noiseFile.exists()
    }
}
