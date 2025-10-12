package com.clearhearand

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var gainInput: EditText
    private lateinit var volumeInput: EditText
    private lateinit var startStopButton: Button
    private lateinit var modeGroup: RadioGroup

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

        val gainLabel = TextView(this).apply { text = "Gain" }
        gainInput = EditText(this).apply {
            hint = "100"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(4))
        }

        val volLabel = TextView(this).apply { text = "Master Volume" }
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

        startStopButton = Button(this).apply {
            text = "Start"
            setOnClickListener { onStartStopClicked() }
        }

        rootLayout.addView(gainLabel)
        rootLayout.addView(gainInput)
        rootLayout.addView(volLabel)
        rootLayout.addView(volumeInput)
        rootLayout.addView(modeLabel)
        rootLayout.addView(modeGroup)
        rootLayout.addView(startStopButton)

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
        } else {
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_STOP
            }
            startService(service)
            isRunning = false
            startStopButton.text = "Start"
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

