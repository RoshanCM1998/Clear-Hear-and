package com.clearhearand.ui

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.clearhearand.R
import com.clearhearand.audio.NoiseMode
import com.clearhearand.services.AudioForegroundService
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var gainSlider: Slider
    private lateinit var gainValueInput: TextInputEditText
    private lateinit var volumeSlider: Slider
    private lateinit var volumeValueInput: TextInputEditText
    private lateinit var startStopFab: MaterialButton
    private lateinit var modeToggleGroup: MaterialButtonToggleGroup
    private lateinit var exportButton: MaterialButton
    private lateinit var clearLogsButton: MaterialButton
    private lateinit var recordNoiseButton: MaterialButton
    private lateinit var strategyCard: MaterialCardView
    private lateinit var postFilterSwitch: MaterialSwitch
    private lateinit var recordNoiseCard: MaterialCardView

    private var modeOffId = View.generateViewId()
    private var modeLightId = View.generateViewId()
    private var modeExtremeId = View.generateViewId()

    private var strategyHardwareId = View.generateViewId()
    private var strategyHighPassId = View.generateViewId()
    private var strategyAdaptiveId = View.generateViewId()
    private var strategyCustomId = View.generateViewId()

    private var isRunning: Boolean = false
    private var isRecordingNoise: Boolean = false
    private var updatingGainFromSlider = false
    private var updatingVolumeFromSlider = false

    // Profiles & EQ
    private lateinit var prefs: SharedPreferences
    private var activeProfile = 1
    private val profileButtons = arrayOfNulls<MaterialButton>(5)
    private val eqSliders = arrayOfNulls<Slider>(6)
    private val eqLabels = arrayOfNulls<TextView>(6)
    private val eqFreqNames = arrayOf("250", "500", "1k", "2k", "4k", "8k")
    private var updatingFromProfile = false

    // Dual-mode EQ
    private val eqAdditiveBands = FloatArray(6)   // dB values, default 0
    private val eqMultiplierBands = FloatArray(6) { 100f } // multiplier %, default 100
    private var eqModeMultiplier = false           // false = additive/dB, true = multiplier/%
    private lateinit var eqModeSwitch: MaterialSwitch
    private lateinit var eqModeSwitchLabel: TextView

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    // Colors
    private val surfaceDark = 0xFF121212.toInt()
    private val surfaceCard = 0xFF1E1E1E.toInt()
    private val accentTeal = 0xFF7A9BA5.toInt()
    private val textPrimary = 0xFFFFFFFF.toInt()
    private val textSecondary = 0xB3FFFFFF.toInt()
    private val startGreen = 0xFF5B8C5A.toInt()
    private val stopRed = 0xFF9E5555.toInt()

    // Profile button colors
    private val profileColors = intArrayOf(
        0xFF5B9EA6.toInt(), // teal
        0xFFB8965A.toInt(), // amber
        0xFFA85A5A.toInt(), // coral
        0xFF8A7EB8.toInt(), // lavender
        0xFF5B8C5A.toInt()  // green
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SharedPreferences
        prefs = getSharedPreferences("clear_hear_prefs", Context.MODE_PRIVATE)
        activeProfile = prefs.getInt("active_profile", 1)

        // Edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun dpf(value: Int) = value * density

        // ── Root FrameLayout ──
        val root = FrameLayout(this).apply {
            setBackgroundColor(surfaceDark)
        }

        // ── ScrollView ──
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isVerticalScrollBarEnabled = false
        }

        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(100)) // bottom padding for FAB clearance
        }

        // Apply system bar insets
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentLayout.setPadding(dp(16), dp(16) + systemBars.top, dp(16), dp(100) + systemBars.bottom)
            insets
        }

        // ── Header ──
        val header = TextView(this).apply {
            text = "Clear Hear"
            setTextColor(textPrimary)
            textSize = 28f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }

        val subtitle = TextView(this).apply {
            text = "Hearing Enhancement"
            setTextColor(accentTeal)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        }

        // ── Helper: create a MaterialCardView ──
        fun createCard(): MaterialCardView {
            return MaterialCardView(this).apply {
                radius = dpf(12)
                cardElevation = dpf(2)
                setCardBackgroundColor(surfaceCard)
                strokeWidth = 0
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, dp(12))
                layoutParams = params
            }
        }

        // ── Helper: card inner padding layout ──
        fun createCardContent(): LinearLayout {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }
        }

        // ── Helper: card title ──
        fun createCardTitle(title: String): TextView {
            return TextView(this).apply {
                text = title
                setTextColor(accentTeal)
                textSize = 13f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, dp(4))
            }
        }

        // ── Helper: slider + input row ──
        fun createSliderRow(
            defaultValue: Float,
            onSliderChange: (Float) -> Unit,
            onTextChange: (Int) -> Unit
        ): Triple<LinearLayout, Slider, TextInputEditText> {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val slider = Slider(this).apply {
                valueFrom = 0f
                valueTo = 500f
                value = defaultValue
                stepSize = 1f
                val sliderParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                sliderParams.setMargins(0, 0, dp(8), 0)
                layoutParams = sliderParams
                trackActiveTintList = ColorStateList.valueOf(accentTeal)
                thumbTintList = ColorStateList.valueOf(accentTeal)
                trackInactiveTintList = ColorStateList.valueOf(0xFF333333.toInt())
            }

            val inputLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
                boxStrokeColor = accentTeal
                hintTextColor = ColorStateList.valueOf(textSecondary)
            }

            val input = TextInputEditText(inputLayout.context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                filters = arrayOf(InputFilter.LengthFilter(3))
                setText(defaultValue.toInt().toString())
                setTextColor(textPrimary)
                textSize = 14f
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            inputLayout.addView(input)
            row.addView(slider)
            row.addView(inputLayout)

            return Triple(row, slider, input)
        }

        // ── Load saved values from active profile ──
        val savedGain = prefs.getInt("last_gain", prefs.getInt("profile_${activeProfile}_gain", 100)).toFloat()
        val savedVolume = prefs.getInt("last_volume", prefs.getInt("profile_${activeProfile}_volume", 100)).toFloat()

        // Migrate old EQ keys and load dual-mode bands
        migrateEqKeys()
        loadEqBandsFromPrefs(activeProfile)

        // ── Audio Gain Card ──
        val gainCard = createCard()
        val gainContent = createCardContent()
        gainContent.addView(createCardTitle("AUDIO GAIN"))

        val (gainRow, gSlider, gInput) = createSliderRow(savedGain, {}, {})
        gainSlider = gSlider
        gainValueInput = gInput

        // Bidirectional sync + auto-apply
        gainSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updatingGainFromSlider = true
                gainValueInput.setText(value.toInt().toString())
                updatingGainFromSlider = false
                autoApplyParams()
            }
        }
        gainValueInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updatingGainFromSlider) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v in 0f..500f) {
                    gainSlider.value = v
                    autoApplyParams()
                }
            }
        })

        gainContent.addView(gainRow)
        gainCard.addView(gainContent)

        // ── Master Volume Card ──
        val volumeCard = createCard()
        val volumeContent = createCardContent()
        volumeContent.addView(createCardTitle("MASTER VOLUME"))

        val (volumeRow, vSlider, vInput) = createSliderRow(savedVolume, {}, {})
        volumeSlider = vSlider
        volumeValueInput = vInput

        volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                updatingVolumeFromSlider = true
                volumeValueInput.setText(value.toInt().toString())
                updatingVolumeFromSlider = false
                autoApplyParams()
            }
        }
        volumeValueInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (updatingVolumeFromSlider) return
                val v = s?.toString()?.toFloatOrNull() ?: return
                if (v in 0f..500f) {
                    volumeSlider.value = v
                    autoApplyParams()
                }
            }
        })

        volumeContent.addView(volumeRow)
        volumeCard.addView(volumeContent)

        // ── Noise Reduction Card ──
        val modeCard = createCard()
        val modeContent = createCardContent()
        modeContent.addView(createCardTitle("NOISE REDUCTION"))

        modeToggleGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val modeOff = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = modeOffId
            text = "Off"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        val modeLight = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = modeLightId
            text = "Light"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        val modeExtreme = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = modeExtremeId
            text = "Extreme"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }

        modeToggleGroup.addView(modeOff)
        modeToggleGroup.addView(modeLight)
        modeToggleGroup.addView(modeExtreme)
        modeToggleGroup.check(modeLightId)

        modeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val isLightMode = checkedId == modeLightId
            strategyCard.visibility = if (isLightMode) View.VISIBLE else View.GONE

            if (!isLightMode) {
                recordNoiseCard.visibility = View.GONE
            }

            if (!isRunning) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                modeOffId -> "OFF"
                modeLightId -> "LIGHT"
                modeExtremeId -> "EXTREME"
                else -> "LIGHT"
            }
            val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_SET_MODE
                putExtra(AudioForegroundService.EXTRA_MODE, mode)
            }
            startService(intent)
        }

        modeContent.addView(modeToggleGroup)
        modeCard.addView(modeContent)

        // ── Filter Strategy Card ──
        strategyCard = createCard().apply {
            visibility = View.VISIBLE // Light is pre-selected
        }
        val strategyContent = createCardContent()
        strategyContent.addView(createCardTitle("FILTER STRATEGY"))

        // 2x2 grid for strategy buttons
        val strategyGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val strategyRow1 = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val strategyRow2 = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            isSelectionRequired = false
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, dp(4), 0, 0)
            layoutParams = params
        }

        val btnHardware = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = strategyHardwareId
            text = "Hardware"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        val btnHighPass = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = strategyHighPassId
            text = "High-Pass"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        val btnAdaptive = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = strategyAdaptiveId
            text = "Adaptive"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }
        val btnCustom = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = strategyCustomId
            text = "Custom"
            isCheckable = true
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = params
        }

        strategyRow1.addView(btnHardware)
        strategyRow1.addView(btnHighPass)
        strategyRow2.addView(btnAdaptive)
        strategyRow2.addView(btnCustom)
        strategyRow1.check(strategyHardwareId)

        // Cross-group single selection: selecting in one row clears the other
        fun onStrategySelected(checkedId: Int) {
            val isCustomProfile = checkedId == strategyCustomId
            recordNoiseCard.visibility = if (isCustomProfile) View.VISIBLE else View.GONE

            if (!isRunning) return
            val strategy = when (checkedId) {
                strategyHardwareId -> "android"
                strategyHighPassId -> "highpass"
                strategyAdaptiveId -> "adaptive"
                strategyCustomId -> "custom"
                else -> "android"
            }
            val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_SET_LIGHT_STRATEGY
                putExtra(AudioForegroundService.EXTRA_LIGHT_STRATEGY, strategy)
            }
            startService(intent)
        }

        var suppressCrossGroupClear = false
        strategyRow1.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCrossGroupClear) return@addOnButtonCheckedListener
            suppressCrossGroupClear = true
            strategyRow2.clearChecked()
            suppressCrossGroupClear = false
            onStrategySelected(checkedId)
        }
        strategyRow2.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCrossGroupClear) return@addOnButtonCheckedListener
            suppressCrossGroupClear = true
            strategyRow1.clearChecked()
            suppressCrossGroupClear = false
            onStrategySelected(checkedId)
        }

        strategyGrid.addView(strategyRow1)
        strategyGrid.addView(strategyRow2)
        strategyContent.addView(strategyGrid)
        strategyCard.addView(strategyContent)

        // ── Post-Processing Switch Row ──
        val postFilterCard = createCard()
        val postFilterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val postFilterLabel = TextView(this).apply {
            text = "Post-Processing"
            setTextColor(textPrimary)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        postFilterSwitch = MaterialSwitch(this).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                if (!isRunning) return@setOnCheckedChangeListener
                val intent = Intent(this@MainActivity, AudioForegroundService::class.java).apply {
                    action = AudioForegroundService.ACTION_SET_POST_FILTER
                    putExtra(AudioForegroundService.EXTRA_POST_FILTER_ENABLED, isChecked)
                }
                startService(intent)
            }
        }
        postFilterRow.addView(postFilterLabel)
        postFilterRow.addView(postFilterSwitch)
        postFilterCard.addView(postFilterRow)

        // ── Record Noise Card ──
        recordNoiseCard = createCard().apply {
            visibility = View.GONE
        }
        val recordContent = createCardContent()
        recordContent.addView(createCardTitle("NOISE PROFILE"))
        recordNoiseButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Record Noise (5s)"
            setTextColor(accentTeal)
            strokeColor = ColorStateList.valueOf(accentTeal)
            cornerRadius = dp(20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { recordNoiseProfile() }
        }
        recordContent.addView(recordNoiseButton)
        recordNoiseCard.addView(recordContent)

        // ── Profiles Card ──
        val profileCard = createCard()
        val profileContent = createCardContent()
        profileContent.addView(createCardTitle("PROFILES"))

        val profileRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (i in 0 until 5) {
            val profileNum = i + 1
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "$profileNum"
                isCheckable = false
                val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                params.setMargins(if (i > 0) dp(4) else 0, 0, 0, 0)
                layoutParams = params
                setOnClickListener { switchProfile(profileNum) }
            }
            profileButtons[i] = btn
            profileRow.addView(btn)
        }
        updateProfileButtonStyles()
        profileContent.addView(profileRow)
        profileCard.addView(profileContent)

        // ── Equalizer Card ──
        val eqCard = createCard()
        val eqContent = createCardContent()

        // EQ header row: title + mode toggle
        val eqHeaderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        eqHeaderRow.addView(createCardTitle("EQUALIZER").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        eqModeSwitchLabel = TextView(this).apply {
            text = if (eqModeMultiplier) "x%" else "dB"
            setTextColor(accentTeal)
            textSize = 12f
            setPadding(0, 0, dp(6), 0)
        }
        eqModeSwitch = MaterialSwitch(this).apply {
            isChecked = eqModeMultiplier
            setOnCheckedChangeListener { _, isChecked ->
                onEqModeToggled(isChecked)
            }
        }
        eqHeaderRow.addView(eqModeSwitchLabel)
        eqHeaderRow.addView(eqModeSwitch)
        eqContent.addView(eqHeaderRow)

        // Current active bands for initial slider values
        val activeBands = if (eqModeMultiplier) eqMultiplierBands else eqAdditiveBands

        val eqRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (i in 0 until 6) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            // Value label
            val valLabel = TextView(this).apply {
                text = formatEqLabel(activeBands[i])
                setTextColor(textPrimary)
                textSize = 11f
                gravity = Gravity.CENTER
            }
            eqLabels[i] = valLabel

            // Vertical slider: rotation on FrameLayout container, NOT on Slider
            val sliderFrame = FrameLayout(this).apply {
                rotation = 270f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(180)
                )
            }
            val slider = Slider(this).apply {
                if (eqModeMultiplier) {
                    valueFrom = 0f; valueTo = 500f; stepSize = 5f
                } else {
                    valueFrom = -12f; valueTo = 12f; stepSize = 1f
                }
                value = activeBands[i]
                trackActiveTintList = ColorStateList.valueOf(accentTeal)
                thumbTintList = ColorStateList.valueOf(accentTeal)
                trackInactiveTintList = ColorStateList.valueOf(0xFF333333.toInt())
                layoutParams = FrameLayout.LayoutParams(
                    dp(180),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            }
            val bandIndex = i
            slider.addOnChangeListener { _, value, fromUser ->
                if (fromUser) {
                    if (eqModeMultiplier) {
                        eqMultiplierBands[bandIndex] = value
                    } else {
                        eqAdditiveBands[bandIndex] = value
                    }
                    valLabel.text = formatEqLabel(value)
                    saveEqToProfile()
                    autoApplyEq()
                }
            }
            eqSliders[i] = slider
            sliderFrame.addView(slider)

            // Frequency label
            val freqLabel = TextView(this).apply {
                text = eqFreqNames[i]
                setTextColor(textSecondary)
                textSize = 11f
                gravity = Gravity.CENTER
            }

            col.addView(valLabel)
            col.addView(sliderFrame)
            col.addView(freqLabel)
            eqRow.addView(col)
        }
        eqContent.addView(eqRow)

        // Reset EQ button
        val resetEqButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reset EQ"
            setTextColor(textSecondary)
            strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
            cornerRadius = dp(20)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, 0) }
            setOnClickListener { resetEq() }
        }
        eqContent.addView(resetEqButton)
        eqCard.addView(eqContent)

        // ── Log Buttons Row ──
        val logsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 0, 0, dp(12))
            layoutParams = params
        }
        exportButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Export Logs"
            setTextColor(textSecondary)
            strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
            cornerRadius = dp(20)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(0, 0, dp(6), 0)
            layoutParams = params
            setOnClickListener { exportLogsToday() }
        }
        clearLogsButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Clear Logs"
            setTextColor(textSecondary)
            strokeColor = ColorStateList.valueOf(0xFF444444.toInt())
            cornerRadius = dp(20)
            val params = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            params.setMargins(dp(6), 0, 0, 0)
            layoutParams = params
            setOnClickListener { clearLogsToday() }
        }
        logsRow.addView(exportButton)
        logsRow.addView(clearLogsButton)

        // ── Version Footer ──
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
            setTextColor(0xFF666666.toInt())
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        // ── Assemble content ──
        contentLayout.addView(header)
        contentLayout.addView(subtitle)
        contentLayout.addView(gainCard)
        contentLayout.addView(volumeCard)
        contentLayout.addView(modeCard)
        contentLayout.addView(strategyCard)
        contentLayout.addView(postFilterCard)
        contentLayout.addView(recordNoiseCard)
        contentLayout.addView(profileCard)
        contentLayout.addView(eqCard)
        contentLayout.addView(logsRow)
        contentLayout.addView(versionFooter)

        scrollView.addView(contentLayout)
        root.addView(scrollView)

        // ── Floating Start/Stop Button ──
        startStopFab = MaterialButton(this).apply {
            text = "Start"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            backgroundTintList = ColorStateList.valueOf(startGreen)
            cornerRadius = dp(36)
            elevation = dpf(8)
            val size = dp(72)
            val fabParams = FrameLayout.LayoutParams(dp(200), size)
            fabParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            fabParams.setMargins(0, 0, 0, dp(24))
            layoutParams = fabParams
            insetTop = 0
            insetBottom = 0
            setOnClickListener { onStartStopClicked() }
        }

        // Apply bottom inset to FAB
        ViewCompat.setOnApplyWindowInsetsListener(startStopFab) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val fabParams = v.layoutParams as FrameLayout.LayoutParams
            fabParams.setMargins(0, 0, 0, dp(24) + systemBars.bottom)
            v.layoutParams = fabParams
            insets
        }

        root.addView(startStopFab)

        setContentView(root)
    }

    private fun onStartStopClicked() {
        if (!isRunning) {
            if (!hasMicPermission()) {
                requestNeededPermissions()
                return
            }
            val gainValue = gainValueInput.text.toString().ifBlank { "100" }.toInt()
            val volValue = volumeValueInput.text.toString().ifBlank { "100" }.toInt()
            val selectedMode = when (modeToggleGroup.checkedButtonId) {
                modeOffId -> "OFF"
                modeExtremeId -> "EXTREME"
                else -> "LIGHT"
            }
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_START
                putExtra(AudioForegroundService.EXTRA_GAIN_100X, gainValue)
                putExtra(AudioForegroundService.EXTRA_VOL_100X, volValue)
                putExtra(AudioForegroundService.EXTRA_MODE, selectedMode)
                putExtra(AudioForegroundService.EXTRA_POST_FILTER_ENABLED, postFilterSwitch.isChecked)
                putExtra(AudioForegroundService.EXTRA_EQ_MODE_MULTIPLIER, eqModeMultiplier)
                putExtra(AudioForegroundService.EXTRA_EQ_BANDS, currentEqBands().clone())
            }
            ContextCompat.startForegroundService(this, service)
            isRunning = true
            startStopFab.text = "Stop"
            startStopFab.backgroundTintList = ColorStateList.valueOf(stopRed)
        } else {
            val service = Intent(this, AudioForegroundService::class.java).apply {
                action = AudioForegroundService.ACTION_STOP
            }
            startService(service)
            isRunning = false
            startStopFab.text = "Start"
            startStopFab.backgroundTintList = ColorStateList.valueOf(startGreen)
        }
    }

    private fun autoApplyParams() {
        val gainValue = gainValueInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100
        val volValue = volumeValueInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100

        // Persist last-used values and active profile
        if (!updatingFromProfile) {
            prefs.edit()
                .putInt("last_gain", gainValue)
                .putInt("last_volume", volValue)
                .putInt("profile_${activeProfile}_gain", gainValue)
                .putInt("profile_${activeProfile}_volume", volValue)
                .apply()
        }

        if (!isRunning) return
        val intent = Intent(this, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_SET_PARAMS
            putExtra(AudioForegroundService.EXTRA_GAIN_100X, gainValue)
            putExtra(AudioForegroundService.EXTRA_VOL_100X, volValue)
        }
        startService(intent)
    }

    private fun formatEqLabel(value: Float): String {
        return if (eqModeMultiplier) {
            "${value.toInt()}%"
        } else {
            val v = value.toInt()
            if (v > 0) "+$v" else "$v"
        }
    }

    private fun currentEqBands(): FloatArray =
        if (eqModeMultiplier) eqMultiplierBands else eqAdditiveBands

    private fun updateProfileButtonStyles() {
        for (i in 0 until 5) {
            val btn = profileButtons[i] ?: continue
            val color = profileColors[i]
            if (i + 1 == activeProfile) {
                btn.backgroundTintList = ColorStateList.valueOf(color)
                btn.setTextColor(Color.WHITE)
                btn.strokeWidth = 0
            } else {
                btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                btn.setTextColor(color)
                btn.strokeColor = ColorStateList.valueOf(color)
                btn.strokeWidth = (2 * resources.displayMetrics.density).toInt()
            }
        }
    }

    private fun switchProfile(newProfile: Int) {
        if (newProfile == activeProfile) return

        // Save current values to current profile
        val gainValue = gainValueInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100
        val volValue = volumeValueInput.text.toString().ifBlank { "100" }.toIntOrNull() ?: 100
        val editor = prefs.edit()
        editor.putInt("profile_${activeProfile}_gain", gainValue)
        editor.putInt("profile_${activeProfile}_volume", volValue)
        saveEqBandsToEditor(editor, activeProfile)

        // Switch active profile
        activeProfile = newProfile
        editor.putInt("active_profile", newProfile)
        editor.apply()

        // Load new profile values
        loadProfile(newProfile)
    }

    private fun loadProfile(profile: Int) {
        updatingFromProfile = true

        val gain = prefs.getInt("profile_${profile}_gain", 100).toFloat()
        val volume = prefs.getInt("profile_${profile}_volume", 100).toFloat()

        gainSlider.value = gain.coerceIn(0f, 500f)
        gainValueInput.setText(gain.toInt().toString())
        volumeSlider.value = volume.coerceIn(0f, 500f)
        volumeValueInput.setText(volume.toInt().toString())

        // Load EQ state
        loadEqBandsFromPrefs(profile)
        eqModeSwitch.isChecked = eqModeMultiplier
        eqModeSwitchLabel.text = if (eqModeMultiplier) "x%" else "dB"
        updateEqSliders()

        // Save as last-used
        prefs.edit()
            .putInt("last_gain", gain.toInt())
            .putInt("last_volume", volume.toInt())
            .apply()

        updateProfileButtonStyles()
        updatingFromProfile = false

        // Apply to running service
        autoApplyParams()
        sendEqModeToService()
        autoApplyEq()
    }

    private fun saveEqToProfile() {
        val editor = prefs.edit()
        saveEqBandsToEditor(editor, activeProfile)
        editor.apply()
    }

    private fun saveEqBandsToEditor(editor: SharedPreferences.Editor, profile: Int) {
        for (i in 0 until 6) {
            editor.putFloat("profile_${profile}_eq_additive_$i", eqAdditiveBands[i])
            editor.putFloat("profile_${profile}_eq_multiplier_$i", eqMultiplierBands[i])
        }
        editor.putBoolean("profile_${profile}_eq_mode", eqModeMultiplier)
    }

    private fun loadEqBandsFromPrefs(profile: Int) {
        eqModeMultiplier = prefs.getBoolean("profile_${profile}_eq_mode", false)
        for (i in 0 until 6) {
            eqAdditiveBands[i] = prefs.getFloat("profile_${profile}_eq_additive_$i", 0f)
            eqMultiplierBands[i] = prefs.getFloat("profile_${profile}_eq_multiplier_$i", 100f)
        }
    }

    private fun migrateEqKeys() {
        // Migrate old profile_{n}_eq_{i} keys to profile_{n}_eq_additive_{i}
        for (p in 1..5) {
            val oldKey = "profile_${p}_eq_0"
            if (prefs.contains(oldKey) && !prefs.contains("profile_${p}_eq_additive_0")) {
                val editor = prefs.edit()
                for (i in 0 until 6) {
                    val value = prefs.getFloat("profile_${p}_eq_$i", 0f)
                    editor.putFloat("profile_${p}_eq_additive_$i", value)
                    editor.putFloat("profile_${p}_eq_multiplier_$i", 100f)
                    editor.remove("profile_${p}_eq_$i")
                }
                editor.putBoolean("profile_${p}_eq_mode", false)
                editor.apply()
            }
        }
    }

    private fun autoApplyEq() {
        if (!isRunning) return
        val intent = Intent(this, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_SET_EQ_BANDS
            putExtra(AudioForegroundService.EXTRA_EQ_BANDS, currentEqBands().clone())
        }
        startService(intent)
    }

    private fun sendEqModeToService() {
        if (!isRunning) return
        val intent = Intent(this, AudioForegroundService::class.java).apply {
            action = AudioForegroundService.ACTION_SET_EQ_MODE
            putExtra(AudioForegroundService.EXTRA_EQ_MODE_MULTIPLIER, eqModeMultiplier)
        }
        startService(intent)
    }

    private fun onEqModeToggled(isMultiplier: Boolean) {
        eqModeMultiplier = isMultiplier
        eqModeSwitchLabel.text = if (isMultiplier) "x%" else "dB"
        updateEqSliders()
        saveEqToProfile()
        sendEqModeToService()
        autoApplyEq()
    }

    private fun updateEqSliders() {
        val bands = currentEqBands()
        for (i in 0 until 6) {
            val slider = eqSliders[i] ?: continue
            if (eqModeMultiplier) {
                slider.valueFrom = 0f
                slider.valueTo = 500f
                slider.stepSize = 5f
            } else {
                slider.valueFrom = -12f
                slider.valueTo = 12f
                slider.stepSize = 1f
            }
            slider.value = bands[i]
            eqLabels[i]?.text = formatEqLabel(bands[i])
        }
    }

    private fun resetEq() {
        if (eqModeMultiplier) {
            for (i in 0 until 6) {
                eqMultiplierBands[i] = 100f
                eqSliders[i]?.value = 100f
                eqLabels[i]?.text = formatEqLabel(100f)
            }
        } else {
            for (i in 0 until 6) {
                eqAdditiveBands[i] = 0f
                eqSliders[i]?.value = 0f
                eqLabels[i]?.text = formatEqLabel(0f)
            }
        }
        saveEqToProfile()
        autoApplyEq()
    }

    private fun clearLogsToday() {
        val logsDir = File(getExternalFilesDir(null), "logs")
        if (!logsDir.exists()) {
            Toast.makeText(this, "No logs directory found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            var deletedCount = 0

            logsDir.listFiles { file -> file.name.startsWith("hearing_log_") && file.extension == "txt" }
                ?.forEach { file ->
                    try {
                        file.delete()
                        deletedCount++
                    } catch (e: Throwable) { }
                }

            val noiseFile = File(logsDir, "noise_profile.txt")
            if (noiseFile.exists()) {
                noiseFile.delete()
                deletedCount++
            }

            Toast.makeText(this, "Deleted $deletedCount log file(s)", Toast.LENGTH_LONG).show()
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

        Thread {
            try {
                val logsDir = File(getExternalFilesDir(null), "logs")
                if (!logsDir.exists()) logsDir.mkdirs()

                val noiseFile = File(logsDir, "noise_profile.txt")

                val sampleRate = 48000
                val chunkSize = 4800
                val numChunks = 50

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
                        var sum = 0.0
                        var peak = 0
                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            sum += sample * sample
                            if (kotlin.math.abs(sample) > peak) peak = kotlin.math.abs(sample)
                        }
                        val rms = kotlin.math.sqrt(sum / read)

                        writer.write("$chunk,$rms,$peak")
                        for (i in 0 until minOf(100, read)) {
                            writer.write(",${buffer[i]}")
                        }
                        writer.write("\n")
                    }

                    val progress = ((chunk + 1) * 100 / numChunks)
                    runOnUiThread {
                        recordNoiseButton.text = "Recording... $progress%"
                    }
                }

                writer.close()
                recorder.stop()
                recorder.release()

                runOnUiThread {
                    recordNoiseButton.text = "Record Noise (5s)"
                    recordNoiseButton.isEnabled = true
                    isRecordingNoise = false
                    Toast.makeText(this, "Noise profile saved: ${noiseFile.name}", Toast.LENGTH_LONG).show()
                }

            } catch (e: Throwable) {
                runOnUiThread {
                    recordNoiseButton.text = "Record Noise (5s)"
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
