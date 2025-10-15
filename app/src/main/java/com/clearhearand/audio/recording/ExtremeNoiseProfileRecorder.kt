package com.clearhearand.audio.recording

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records and manages noise profile for EXTREME mode's Spectral Gate strategy.
 *
 * This allows users to pre-record background noise in their environment,
 * which the Spectral Gate uses as a reference to distinguish voice from noise.
 *
 * **Why this is needed**:
 * - Spectral Gate normally learns noise floor from first 2 seconds
 * - If EXTREME mode is started during conversation, it learns voice as "noise"
 * - Pre-recording solves this by letting user explicitly capture ONLY background noise
 *
 * **User workflow**:
 * 1. User clicks "Record Noise for EXTREME (5s)"
 * 2. User stays SILENT for 5 seconds
 * 3. App records pure background noise
 * 4. Calculates average RMS and saves to file
 * 5. Spectral Gate loads this value and skips learning phase
 *
 * @see com.clearhearand.audio.processors.extrememode.SpectralGateStrategy
 */
class ExtremeNoiseProfileRecorder(private val context: Context) {

    private val tag = "ExtremeNoiseProfileRecorder"

    private val profileFile: File by lazy {
        val logsDir = File(context.getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) logsDir.mkdirs()
        File(logsDir, "extreme_mode_noise_profile.txt")
    }

    /**
     * Record background noise for the specified duration.
     *
     * This should be called when the user is in their typical noisy environment
     * but staying SILENT (no speaking).
     *
     * The recorded noise floor is saved to extreme_mode_noise_profile.txt
     * and will be used by Spectral Gate on next EXTREME mode start.
     *
     * @param audioRecord Active AudioRecord instance for recording
     * @param durationSeconds Duration to record (default 5 seconds)
     * @param sampleRate Sample rate (should match AudioProcessor, typically 48000)
     * @param chunkSize Samples per chunk (should match AudioProcessor, typically 4800)
     * @return Average noise floor RMS, or null if recording failed
     */
    fun recordNoiseProfile(
        audioRecord: android.media.AudioRecord,
        durationSeconds: Int = 5,
        sampleRate: Int = 48000,
        chunkSize: Int = 4800
    ): Float? {
        return try {
            val numChunks = (sampleRate * durationSeconds) / chunkSize
            val buffer = ShortArray(chunkSize)
            var totalRms = 0.0
            var validChunks = 0

            Log.d(tag, "Recording noise profile: $durationSeconds seconds ($numChunks chunks)")

            for (chunk in 0 until numChunks) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    // Calculate RMS for this chunk
                    var sum = 0.0
                    for (i in 0 until read) {
                        val sample = buffer[i].toInt()
                        sum += sample * sample
                    }
                    val chunkRms = kotlin.math.sqrt(sum / read)
                    totalRms += chunkRms
                    validChunks++
                }
            }

            val averageNoiseFloor = (totalRms / validChunks).toFloat()

            // Save to file
            saveNoiseProfile(averageNoiseFloor, sampleRate, validChunks * chunkSize)

            Log.d(tag, "Noise profile recorded: RMS=$averageNoiseFloor (from $validChunks chunks)")
            averageNoiseFloor

        } catch (e: Exception) {
            Log.e(tag, "Failed to record noise profile: ${e.message}")
            null
        }
    }

    /**
     * Save noise profile to file.
     *
     * File format:
     * ```
     * timestamp,noise_floor_rms,num_samples,sample_rate
     * 2025-10-15 12:30:45,0.002456,240000,48000
     * ```
     */
    private fun saveNoiseProfile(noiseFloorRms: Float, sampleRate: Int, numSamples: Int) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val content = "timestamp,noise_floor_rms,num_samples,sample_rate\n" +
                    "$timestamp,$noiseFloorRms,$numSamples,$sampleRate\n"

            profileFile.writeText(content)
            Log.d(tag, "Saved noise profile to: ${profileFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(tag, "Failed to save noise profile: ${e.message}")
        }
    }

    /**
     * Get the saved noise floor RMS value.
     *
     * @return Saved noise floor RMS, or null if no profile exists or reading failed
     */
    fun getNoiseFloor(): Float? {
        return try {
            if (!profileFile.exists()) {
                Log.d(tag, "No noise profile found")
                return null
            }

            val lines = profileFile.readLines()
            if (lines.size < 2) {
                Log.w(tag, "Invalid noise profile file (too few lines)")
                return null
            }

            // Parse second line (skip header)
            val data = lines[1].split(",")
            if (data.size < 2) {
                Log.w(tag, "Invalid noise profile format")
                return null
            }

            val noiseFloor = data[1].toFloatOrNull()
            if (noiseFloor != null) {
                Log.d(tag, "Loaded noise profile: RMS=$noiseFloor (from ${data[0]})")
            }
            noiseFloor

        } catch (e: Exception) {
            Log.e(tag, "Failed to read noise profile: ${e.message}")
            null
        }
    }

    /**
     * Check if a noise profile exists.
     *
     * @return True if profile file exists and is readable
     */
    fun hasProfile(): Boolean {
        return profileFile.exists() && getNoiseFloor() != null
    }

    /**
     * Get the timestamp when the profile was recorded.
     *
     * @return Timestamp string like "2025-10-15 12:30:45", or null if no profile
     */
    fun getProfileTimestamp(): String? {
        return try {
            if (!profileFile.exists()) return null

            val lines = profileFile.readLines()
            if (lines.size < 2) return null

            val data = lines[1].split(",")
            if (data.isEmpty()) return null

            data[0]  // First column is timestamp
        } catch (e: Exception) {
            Log.e(tag, "Failed to read profile timestamp: ${e.message}")
            null
        }
    }

    /**
     * Delete the saved noise profile.
     *
     * Called when user clicks "Clear Logs" or wants to re-record in a new environment.
     */
    fun clearProfile() {
        try {
            if (profileFile.exists()) {
                profileFile.delete()
                Log.d(tag, "Deleted noise profile")
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to delete noise profile: ${e.message}")
        }
    }
}
