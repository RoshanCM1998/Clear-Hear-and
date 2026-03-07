## How to Run clear-hear

### Prerequisites
- Android Studio Hedgehog or later
- Android SDK 34, JDK 17

### Build and Install
1. Open the project in Android Studio
2. Select a physical device (recommended) or emulator with microphone support
3. Build and run the `app` module

### Using the App
1. On first launch, grant microphone permission (and notifications on Android 13+)
2. Enter numeric values for Gain and Master Volume (e.g., 100, 125, 325)
3. Tap Start to begin background processing; the button toggles to Stop
4. Tap Stop to end

### Logs
- Logs are written to: App external files directory under `logs`
- Path example: `Android/data/com.clearhear/files/logs/clear_hear_and_<timestamp>.log`
- You can pull logs via `Device File Explorer` in Android Studio or via `adb pull`

### Notes
- Processing runs in a foreground service for continuity in background
- 100 ms chunks with parallel record and playback to avoid dropouts
- 48 kHz mono PCM 16-bit for low latency and future DSP expansion

