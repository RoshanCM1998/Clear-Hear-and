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

    private var isRunning: Boolean = false

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

        applyParamsButton = Button(this).apply {
            text = "Apply Gain/Volume"
            isEnabled = false
            setOnClickListener { onApplyParamsClicked() }
        }

        startStopButton = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartStopClicked() }
        }

        exportButton = Button(this).apply {
            text = "Export Logs"
            setOnClickListener { exportLogsToday() }
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
        
        rootLayout.addView(gainLabel)
        rootLayout.addView(gainInput.withMarginBottom())
        rootLayout.addView(volLabel)
        rootLayout.addView(volumeInput.withMarginBottom())
        rootLayout.addView(applyParamsButton.withMarginBottom())
        rootLayout.addView(modeLabel)
        rootLayout.addView(modeGroup.withMarginBottom())
        rootLayout.addView(startStopButton.withMarginBottom())
        rootLayout.addView(exportButton)

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
            applyParamsButton.isEnabled = true
        } else {
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_STOP
            }
            startService(service)
            isRunning = false
            startStopButton.text = "Start"
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

    private fun exportLogsToday() {
        val srcDir = File(getExternalFilesDir(null), "logs")
        if (!srcDir.exists()) {
            Toast.makeText(this, "No logs directory found", Toast.LENGTH_SHORT).show()
            return
        }
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val file = File(srcDir, "hearing_log_${today}.txt")
        if (!file.exists()) {
            Toast.makeText(this, "No log for today", Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = contentResolver
        val destDirName = "HearingAidLogs"
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
                }
            } else {
                val dest = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$destDirName/${file.name}")
                dest.parentFile?.mkdirs()
                dest.outputStream().use { out -> FileInputStream(file).use { it.copyTo(out) } }
            }
            Toast.makeText(this, "Exported today's log to Downloads/$destDirName", Toast.LENGTH_SHORT).show()
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

