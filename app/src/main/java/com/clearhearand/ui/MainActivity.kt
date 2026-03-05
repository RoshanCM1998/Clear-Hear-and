package com.clearhearand.ui

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.clearhearand.R
import com.clearhearand.audio.NoiseMode
import com.clearhearand.services.AudioForegroundService
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var gainInput: EditText
    private lateinit var volumeInput: EditText
    private lateinit var startStopButton: Button
    private lateinit var modeGroup: RadioGroup
    private lateinit var applyParamsButton: Button
    private lateinit var exportButton: Button
    private lateinit var clearLogsButton: Button
    private lateinit var recordNoiseButton: Button
    private lateinit var lightStrategyGroup: RadioGroup
    private lateinit var lightStrategyLabel: TextView
    private lateinit var extremeStrategyGroup: RadioGroup
    private lateinit var extremeStrategyLabel: TextView

    private var isRunning: Boolean = false
    private var isRecordingNoise: Boolean = false

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // No-op; we check again on click
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val gainLabel = TextView(this).apply { text = "Gain (e.g., 100, 125, 325)" }
        gainInput = EditText(this).apply {
            hint = "100"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }

        val volLabel = TextView(this).apply { text = "Master Volume (e.g., 100, 125, 325)" }
        volumeInput = EditText(this).apply {
            hint = "100"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }

        val modeLabel = TextView(this).apply { text = "Noise Reduction Mode" }
        modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val off = RadioButton(this@MainActivity).apply { text = "Off"; id = View.generateViewId() }
            val light = RadioButton(this@MainActivity).apply { text = "Light"; id = View.generateViewId() }
            val extreme = RadioButton(this@MainActivity).apply { text = "Extreme"; id = View.generateViewId() }
            addView(off)
            addView(light)
            addView(extreme)
            check(light.id)
            setOnCheckedChangeListener { _, checkedId ->
                // Update strategy controls visibility based on mode
                val isLightMode = checkedId == light.id
                val isExtremeMode = checkedId == extreme.id

                lightStrategyLabel.visibility = if (isLightMode) View.VISIBLE else View.GONE
                lightStrategyGroup.visibility = if (isLightMode) View.VISIBLE else View.GONE

                extremeStrategyLabel.visibility = if (isExtremeMode) View.VISIBLE else View.GONE
                extremeStrategyGroup.visibility = if (isExtremeMode) View.VISIBLE else View.GONE

                // Hide Record Noise button when not in appropriate mode
                if (!isLightMode && !isExtremeMode) {
                    recordNoiseButton.visibility = View.GONE
                }

                if (!isRunning) return@setOnCheckedChangeListener
                val mode = when (checkedId) {
                    off.id -> "OFF"
                    light.id -> "LIGHT"
                    extreme.id -> "EXTREME"
                    else -> "LIGHT"
                }
                val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                    action = AudioForegroundService.ACTION_SET_MODE
                    putExtra(AudioForegroundService.EXTRA_MODE, mode)
                }
                startService(intent)
            }
        }

        // Strategy selector for LIGHT mode (4 filtering options)
        lightStrategyLabel = TextView(this).apply {
            text = "Filter Strategy (LIGHT mode only)"
            visibility = View.VISIBLE  // Visible since LIGHT is pre-selected
        }

        lightStrategyGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val android = RadioButton(this@MainActivity).apply {
                text = "Android Effects (Hardware)"
                id = View.generateViewId()
            }
            val highpass = RadioButton(this@MainActivity).apply {
                text = "High-Pass Filter (DC + 80Hz)"
                id = View.generateViewId()
            }
            val adaptive = RadioButton(this@MainActivity).apply {
                text = "Adaptive Gate (HP + Gating)"
                id = View.generateViewId()
            }
            val custom = RadioButton(this@MainActivity).apply {
                text = "Custom Profile (Learned)"
                id = View.generateViewId()
            }

            addView(android)
            addView(highpass)
            addView(adaptive)
            addView(custom)
            check(android.id)  // Default to Android effects
            visibility = View.VISIBLE  // Visible since LIGHT is pre-selected

            setOnCheckedChangeListener { _, checkedId ->
                // Update Record Noise button visibility when strategy changes
                val isCustomProfile = checkedId == custom.id
                recordNoiseButton.visibility = if (isCustomProfile) View.VISIBLE else View.GONE

                if (!isRunning) return@setOnCheckedChangeListener

                val strategy = when (checkedId) {
                    android.id -> "android"
                    highpass.id -> "highpass"
                    adaptive.id -> "adaptive"
                    custom.id -> "custom"
                    else -> "android"
                }

                val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                    action = AudioForegroundService.ACTION_SET_LIGHT_STRATEGY
                    putExtra(AudioForegroundService.EXTRA_LIGHT_STRATEGY, strategy)
                }
                startService(intent)
            }
        }

        // Strategy selector for EXTREME mode (2 voice isolation options)
        extremeStrategyLabel = TextView(this).apply {
            text = "Voice Isolation Strategy (EXTREME mode only)"
            visibility = View.GONE  // Hidden since LIGHT is pre-selected
        }

        extremeStrategyGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val spectral = RadioButton(this@MainActivity).apply {
                text = "Spectral Gate (Manual)"
                id = View.generateViewId()
            }
            val rnnoise = RadioButton(this@MainActivity).apply {
                text = "RNNoise (ML)"
                id = View.generateViewId()
            }

            addView(spectral)
            addView(rnnoise)
            check(spectral.id)  // Default to Spectral Gate
            visibility = View.GONE  // Hidden since LIGHT is pre-selected

            setOnCheckedChangeListener { _, checkedId ->
                // TODO: Update Record Noise button visibility when we implement noise profile pre-recording

                if (!isRunning) return@setOnCheckedChangeListener

                val strategy = when (checkedId) {
                    spectral.id -> "spectral"
                    rnnoise.id -> "rnnoise"
                    else -> "spectral"
                }

                val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                    action = AudioForegroundService.ACTION_SET_EXTREME_STRATEGY
                    putExtra(AudioForegroundService.EXTRA_EXTREME_STRATEGY, strategy)
                }
                startService(intent)
            }
        }

        applyParamsButton = Button(this).apply {
            text = "Apply Gain/Volume"
            isEnabled = false
            setOnClickListener { onApplyParamsClicked() }
        }

        startStopButton = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartStopClicked() }
            // Make button 1.5x height
            val buttonHeight = (48 * 1.5 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                buttonHeight
            )
            // Soft green color for start
            setBackgroundColor(0xFF90EE90.toInt())  // Light green
            setTextColor(0xFF000000.toInt())  // Black text
        }

        // Create horizontal layout for Export and Clear Logs buttons
        val logsButtonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        exportButton = Button(this).apply {
            text = "Export Logs"
            setOnClickListener { exportLogsToday() }
            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            params.setMargins(0, 0, 8, 0)  // 8dp right margin
            layoutParams = params
        }

        clearLogsButton = Button(this).apply {
            text = "Clear Logs"
            setOnClickListener { clearLogsToday() }
            val params = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
            params.setMargins(8, 0, 0, 0)  // 8dp left margin
            layoutParams = params
        }

        // Add both buttons to the horizontal row
        logsButtonsRow.addView(exportButton)
        logsButtonsRow.addView(clearLogsButton)

        // Record Noise button (shown only when LIGHT mode + Custom Profile selected)
        recordNoiseButton = Button(this).apply {
            text = "Record Noise (5s)"
            visibility = View.GONE  // Hidden by default
            setOnClickListener { recordNoiseProfile() }
        }

        // Add components with 16dp bottom margins for better spacing
        val marginDp = (16 * resources.displayMetrics.density).toInt()
        
        fun View.withMarginBottom(): View {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, marginDp)
            this.layoutParams = params
            return this
        }
        
        // Version footer
        val versionFooter = TextView(this).apply {
            try {
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageManager.getPackageInfo(packageName, 0).longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
                }
                text = "v$versionName (build $versionCode)"
            } catch (e: Exception) {
                text = "v1.0.0"
            }
            setTextColor(0xFF999999.toInt())  // Light gray
            textSize = 10f
            gravity = android.view.Gravity.CENTER
            setPadding(0, marginDp * 2, 0, 0)  // Extra top padding
        }

        rootLayout.addView(gainLabel)
        rootLayout.addView(gainInput.withMarginBottom())
        rootLayout.addView(volLabel)
        rootLayout.addView(volumeInput.withMarginBottom())
        rootLayout.addView(applyParamsButton.withMarginBottom())
        rootLayout.addView(modeLabel)
        rootLayout.addView(modeGroup.withMarginBottom())
        rootLayout.addView(lightStrategyLabel)  // Only visible in LIGHT mode
        rootLayout.addView(lightStrategyGroup.withMarginBottom())  // Only visible in LIGHT mode
        rootLayout.addView(extremeStrategyLabel)  // Only visible in EXTREME mode
        rootLayout.addView(extremeStrategyGroup.withMarginBottom())  // Only visible in EXTREME mode
        rootLayout.addView(startStopButton.withMarginBottom())
        rootLayout.addView(logsButtonsRow.withMarginBottom())
        rootLayout.addView(recordNoiseButton.withMarginBottom())  // Only visible when Custom Profile selected
        rootLayout.addView(versionFooter)

        setContentView(rootLayout)
    }

    private fun onStartStopClicked() {
        if (!isRunning) {
            if (!hasMicPermission()) {
                requestNeededPermissions()
                return
            }
            val gainValue = gainInput.text.toString().ifBlank { "100" }.toInt()
            val volValue = volumeInput.text.toString().ifBlank { "100" }.toInt()
            val selectedMode = when (modeGroup.checkedRadioButtonId) {
                -1 -> "LIGHT"
                else -> {
                    val btn = findViewById<RadioButton>(modeGroup.checkedRadioButtonId)
                    when (btn.text.toString()) {
                        "Off" -> "OFF"
                        "Extreme" -> "EXTREME"
                        else -> "LIGHT"
                    }
                }
            }
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_START
                putExtra(AudioForegroundService.EXTRA_GAIN_100X, gainValue)
                putExtra(AudioForegroundService.EXTRA_VOL_100X, volValue)
                putExtra(AudioForegroundService.EXTRA_MODE, selectedMode)
            }
            ContextCompat.startForegroundService(this, service)
            isRunning = true
            startStopButton.text = "Stop"
            startStopButton.setBackgroundColor(0xFFFF6347.toInt())  // Soft tomato red
            applyParamsButton.isEnabled = true
        } else {
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_STOP
            }
            startService(service)
            isRunning = false
            startStopButton.text = "Start"
            startStopButton.setBackgroundColor(0xFF90EE90.toInt())  // Light green
            applyParamsButton.isEnabled = false
        }
    }

    private fun onApplyParamsClicked() {
        if (!isRunning) return
        val gainValue = gainInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100
        val volValue = volumeInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100
        val intent = Intent(this, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_SET_PARAMS
            putExtra(AudioForegroundService.EXTRA_GAIN_100X, gainValue)
            putExtra(AudioForegroundService.EXTRA_VOL_100X, volValue)
        }
        startService(intent)
        Toast.makeText(this, "Updated: Gain=$gainValue%, Vol=$volValue%", Toast.LENGTH_SHORT).show()
    }

    private fun clearLogsToday() {
        val logsDir = File(getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) {
            Toast.makeText(this, "No logs directory found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            var deletedCount = 0

            // DELETE all hearing_log_*.txt files
            logsDir.listFiles { file -> file.name.startsWith("hearing_log_") && file.extension == "txt" }
                ?.forEach { file ->
                    try {
                        file.delete()
                        deletedCount++
                    } catch (e: Throwable) {
                        // Skip this file
                    }
                }

            // DELETE noise_profile.txt file
            val noiseFile = File(logsDir, "noise_profile.txt")
            if (noiseFile.exists()) {
                noiseFile.delete()
                deletedCount++
            }

            Toast.makeText(this, "Deleted $deletedCount log file(s) (hearing logs + noise profile)", Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Clear failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun recordNoiseProfile() {
        if (isRecordingNoise) {
            Toast.makeText(this, "Already recording noise profile", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!hasMicPermission()) {
            requestNeededPermissions()
            Toast.makeText(this, "Need microphone permission", Toast.LENGTH_SHORT).show()
            return
        }
        
        isRecordingNoise = true
        recordNoiseButton.isEnabled = false
        recordNoiseButton.text = "Recording..."
        
        // Record in background thread
        Thread {
            try {
                val logsDir = File(getExternalFilesDir(null), "logs")
                if (!logsDir.exists()) logsDir.mkdirs()
                
                // Use fixed filename to overwrite old recordings (no timestamp = single file)
                val noiseFile = File(logsDir, "noise_profile.txt")
                
                // Record 5 seconds of audio (48000 Hz, 100ms chunks = 50 chunks)
                val sampleRate = 48000
                val chunkSize = 4800  // 100ms at 48kHz
                val numChunks = 50    // 5 seconds
                
                val recorder = android.media.AudioRecord(
                    android.media.MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                    chunkSize * 4
                )
                
                recorder.startRecording()
                
                val writer = noiseFile.bufferedWriter()
                writer.write("# Noise Profile Recording\n")
                writer.write("# Sample Rate: $sampleRate Hz\n")
                writer.write("# Chunk Size: $chunkSize samples (100ms)\n")
                writer.write("# Format: chunk_number,rms,peak,samples...\n")
                writer.write("#\n")
                
                val buffer = ShortArray(chunkSize)
                
                for (chunk in 0 until numChunks) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        // Calculate RMS and peak
                        var sum = 0.0
                        var peak = 0
                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            sum += sample * sample
                            if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
                        }
                        val rms = kotlin.math.sqrt(sum / read)
                        
                        // Write chunk info
                        writer.write("$chunk,$rms,$peak")
                        
                        // Write first 100 samples for frequency analysis
                        for (i in 0 until minOf(100, read)) {
                            writer.write(",${buffer[i]}")
                        }
                        writer.write("\n")
                    }
                    
                    // Update progress on UI thread
                    val progress = ((chunk + 1) * 100 / numChunks)
                    runOnUiThread {
                        recordNoiseButton.text = "Recording... $progress%"
                    }
                }
                
                writer.close()
                recorder.stop()
                recorder.release()
                
                // Success!
                runOnUiThread {
                    recordNoiseButton.text = "Record Noise Profile (5s)"
                    recordNoiseButton.isEnabled = true
                    isRecordingNoise = false
                    Toast.makeText(this, "Noise profile saved: ${noiseFile.name}", Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Throwable) {
                runOnUiThread {
                    recordNoiseButton.text = "Record Noise Profile (5s)"
                    recordNoiseButton.isEnabled = true
                    isRecordingNoise = false
                    Toast.makeText(this, "Recording failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun exportLogsToday() {
        val srcDir = File(getExternalFilesDir(null), "logs")
        if (!srcDir.exists()) {
            Toast.makeText(this, "No logs directory found", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get all .txt files from logs directory
        val files = srcDir.listFiles { file -> file.extension == "txt" }
        if (files == null || files.isEmpty()) {
            Toast.makeText(this, "No log files found", Toast.LENGTH_SHORT).show()
            return
        }
        
        val resolver = contentResolver
        val destDirName = "HearingAidLogs"
        var exportCount = 0
        var errorCount = 0
        
        try {
            for (file in files) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/$destDirName")
                        }
                        val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { out ->
                                FileInputStream(file).use { it.copyTo(out) }
                            }
                            exportCount++
                        } else {
                            errorCount++
                        }
                    } else {
                        val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$destDirName/${file.name}")
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> FileInputStream(file).use { it.copyTo(out) } }
                        exportCount++
                    }
                } catch (e: Throwable) {
                    errorCount++
                }
            }
            
            val message = if (errorCount == 0) {
                "Exported $exportCount file(s) to Downloads/$destDirName"
            } else {
                "Exported $exportCount file(s), $errorCount failed"
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        } catch (e: Throwable) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermission.launch(perms.toTypedArray())
    }
}

