package com.clearhear.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.max

/**
 * Manages audio device selection, Bluetooth SCO, and AudioRecord/AudioTrack creation.
 *
 * Separated from AudioProcessor to keep device routing logic independent of
 * the processing pipeline.
 */
class AudioDeviceManager(private val context: Context) {
    private val tag = "AudioDeviceManager"

    @Volatile var preferredInputDeviceId: Int? = null
        private set
    @Volatile var preferredOutputDeviceId: Int? = null
        private set
    @Volatile var bluetoothScoActive: Boolean = false
        private set

    val audioManager: AudioManager
        get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // ── Device Selection ──

    fun setInputDeviceId(deviceId: Int?) {
        preferredInputDeviceId = deviceId
        Log.d(tag, "setInputDeviceId: $deviceId")
    }

    fun setOutputDeviceId(deviceId: Int?) {
        preferredOutputDeviceId = deviceId
        Log.d(tag, "setOutputDeviceId: $deviceId")
    }

    /**
     * Returns true if the selected input device requires Bluetooth SCO.
     */
    fun needsBluetoothSco(): Boolean {
        val id = preferredInputDeviceId ?: return false
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == id } ?: return false
        return isBluetoothDevice(device.type)
    }

    // ── AudioRecord / AudioTrack Creation ──

    data class AudioPair(
        val record: AudioRecord,
        val track: AudioTrack
    )

    /**
     * Creates and configures AudioRecord + AudioTrack with the correct audio source,
     * attributes, and preferred devices. Starts BT SCO if needed.
     */
    fun createAudioPair(
        sampleRate: Int,
        channelIn: Int = AudioFormat.CHANNEL_IN_MONO,
        channelOut: Int = AudioFormat.CHANNEL_OUT_MONO,
        encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
        chunkMs: Int = 100
    ): AudioPair {
        val am = audioManager
        val needsBtSco = needsBluetoothSco()

        if (needsBtSco) {
            startBluetoothSco()
        }

        logAvailableDevices()

        val minRec = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val minPlay = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val samplesPerChunk = (sampleRate * chunkMs) / 1000
        val bytesPerSample = 2
        val recBufferSize = max(minRec, samplesPerChunk * bytesPerSample)
        val playBufferSize = max(minPlay, samplesPerChunk * bytesPerSample)

        Log.d(tag, "createAudioPair: sampleRate=$sampleRate, needsBtSco=$needsBtSco, recBuf=$recBufferSize, playBuf=$playBufferSize")

        // Use VOICE_COMMUNICATION for Bluetooth SCO, VOICE_RECOGNITION otherwise
        val audioSource = if (needsBtSco) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        Log.d(tag, "AudioSource: ${if (needsBtSco) "VOICE_COMMUNICATION" else "VOICE_RECOGNITION"}")

        val record = AudioRecord.Builder()
            .setAudioSource(audioSource)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelIn)
                    .build()
            )
            .setBufferSizeInBytes(recBufferSize)
            .build()

        Log.d(tag, "AudioRecord state=${record.state}, session=${record.audioSessionId}")

        // Use VOICE_COMMUNICATION usage when BT SCO is active for consistent routing
        val audioUsage = if (needsBtSco) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_MEDIA
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(audioUsage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(channelOut)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(playBufferSize)
            .build()

        Log.d(tag, "AudioTrack state=${track.state}")

        // Apply preferred devices
        applyPreferredDevices(record, track)

        return AudioPair(record, track)
    }

    /**
     * Applies the stored preferred device settings to existing AudioRecord/AudioTrack.
     */
    fun applyPreferredDevices(record: AudioRecord?, track: AudioTrack?) {
        val am = audioManager

        preferredInputDeviceId?.let { id ->
            val dev = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
            val result = record?.setPreferredDevice(dev)
            Log.d(tag, "setPreferredDevice(input): dev=${dev?.productName}, result=$result")
        }
        preferredOutputDeviceId?.let { id ->
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            var dev = outputs.firstOrNull { it.id == id }
            // If preferred output is BT SCO but SCO isn't active, fall back to A2DP
            if (dev != null && dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !bluetoothScoActive) {
                val a2dpDev = outputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                    it.productName == dev!!.productName
                }
                Log.d(tag, "SCO output without active SCO — falling back to ${if (a2dpDev != null) "A2DP" else "system default"}")
                dev = a2dpDev  // null = system default if no A2DP found
            }
            val result = track?.setPreferredDevice(dev)
            Log.d(tag, "setPreferredDevice(output): dev=${dev?.productName}, result=$result")
        }
    }

    /**
     * Applies preferred output device to an existing AudioTrack (for output-only changes).
     */
    fun applyOutputDevice(track: AudioTrack?) {
        val am = audioManager
        var deviceInfo = preferredOutputDeviceId?.let { id ->
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
        }
        // If preferred output is BT SCO but SCO isn't active, fall back to A2DP
        if (deviceInfo != null && deviceInfo.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && !bluetoothScoActive) {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val a2dpDev = outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP &&
                it.productName == deviceInfo!!.productName
            }
            Log.d(tag, "SCO output without active SCO — falling back to ${if (a2dpDev != null) "A2DP" else "system default"}")
            deviceInfo = a2dpDev
        }
        val result = track?.setPreferredDevice(deviceInfo)
        Log.d(tag, "setPreferredDevice(output): dev=${deviceInfo?.productName}, result=$result")

        val actual = track?.routedDevice
        Log.d(tag, "Output routed to: ${actual?.productName} (type=${actual?.type}, id=${actual?.id})")
    }

    // ── Bluetooth SCO ──

    @Suppress("DEPRECATION")
    fun startBluetoothSco() {
        if (bluetoothScoActive) return
        val am = audioManager
        Log.d(tag, "Starting Bluetooth SCO, current mode=${am.mode}")
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.startBluetoothSco()
        am.isBluetoothScoOn = true
        bluetoothScoActive = true
        Log.d(tag, "Bluetooth SCO started, mode=${am.mode}, scoOn=${am.isBluetoothScoOn}")
    }

    @Suppress("DEPRECATION")
    fun stopBluetoothSco() {
        if (!bluetoothScoActive) return
        val am = audioManager
        Log.d(tag, "Stopping Bluetooth SCO")
        am.isBluetoothScoOn = false
        am.stopBluetoothSco()
        am.mode = AudioManager.MODE_NORMAL
        bluetoothScoActive = false
        Log.d(tag, "Bluetooth SCO stopped, mode=${am.mode}")
    }

    // ── Logging ──

    fun logRoutedDevices(record: AudioRecord?, track: AudioTrack?) {
        val inDev = record?.routedDevice
        val outDev = track?.routedDevice
        Log.d(tag, "Routed input: ${inDev?.productName} (type=${inDev?.type})")
        Log.d(tag, "Routed output: ${outDev?.productName} (type=${outDev?.type})")
    }

    fun getRoutingFlags(record: AudioRecord?, track: AudioTrack?): String {
        val inDev = record?.routedDevice
        val outDev = track?.routedDevice
        return "inDev=${inDev?.productName}(${inDev?.type});outDev=${outDev?.productName}(${outDev?.type});btSco=$bluetoothScoActive"
    }

    private fun logAvailableDevices() {
        val am = audioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        Log.d(tag, "Available inputs: ${inputs.joinToString { "${it.productName}(id=${it.id},type=${it.type})" }}")
        Log.d(tag, "Available outputs: ${outputs.joinToString { "${it.productName}(id=${it.id},type=${it.type})" }}")
    }

    companion object {
        fun isBluetoothDevice(type: Int): Boolean {
            return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                   type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                   (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                    (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }
}
