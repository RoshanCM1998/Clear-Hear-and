## clear-hear: Creation Plan and Rationale

### Goals
- Simple hearing-aid app with Gain and Master Volume controls
- Foreground service for background audio record → process → play
- Low latency and chunked processing at 100 ms with parallelism
- Logging to file for easy diagnostics

### Architectural Choices
- Single Activity (`MainActivity`) for simple UI with two numeric inputs and a Start/Stop button
- Foreground `Service` (`AudioForegroundService`) to continue in background with notification
- Android platform audio APIs (`AudioRecord`/`AudioTrack`) for root-level latency and flexibility to extend to EQ per band later
- 48 kHz mono, PCM 16-bit for a good balance between quality and latency
- Two single-thread executors: one for recording (I/O) and one for processing/playback; connected via a bounded queue for backpressure
- Chunk size: 100 ms. While consuming chunk N, recorder fills chunk N+1 to avoid gaps

### Gain and Master Volume
- User enters values like 100, 125, 325; convert to multipliers 1.0, 1.25, 3.25 by dividing by 100
- Multiply the PCM samples by (gainMultiplier * volumeMultiplier), clamp to Int16 range

### Permissions and Background
- `RECORD_AUDIO` at runtime; `BLUETOOTH_CONNECT` on Android 12+
- Foreground service types: `microphone` and `mediaPlayback`
- `POST_NOTIFICATIONS` on Android 13+ recommended for notification visibility

### Device Routing (future-ready)
- Using core APIs allows attaching an `AudioDeviceCallback` later to choose input/output devices (BT SCO, A2DP, wired)

### Logging
- Write logs to `getExternalFilesDir(null)/logs/clear_hear_and_<timestamp>.log`
- Include debug and error entries; surface errors as notifications on Android 13+

### Files Kept Minimal
- Only two Kotlin files: `MainActivity.kt` and `AudioForegroundService.kt`
- Minimal resources: theme and launcher icons

### Future EQ per 7 bands
- Add a processor module that splits PCM into 7 bands (IIR/FIR or FFT bands) and applies per-band gain; the current pipeline supports inserting such a step in the processing thread

