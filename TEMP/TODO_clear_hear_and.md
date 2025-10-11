# To-Do for clear-hear-and

1. Initialize minimal Android app project structure
   - Reason: Base scaffolding for app, build, and manifest
   - Behavior change: New project creation

2. Implement UI with Gain, Master Volume, Start/Stop
   - Reason: Inputs and control per requirements
   - Behavior change: Adds editable fields and toggle control

3. Create ForegroundService for audio record/process/play 100ms chunks
   - Reason: Background processing and continuity
   - Behavior change: Service lifecycle and notification

4. Apply gain and master volume multipliers per spec
   - Reason: Core hearing-aid processing
   - Behavior change: Audio amplitude scaled by gain*volume

5. Handle permissions and background/BT/wired device access basics
   - Reason: Required runtime permissions and future device routing
   - Behavior change: Prompts user for RECORD_AUDIO and BT connect (S+)

6. Implement low-latency pipeline with AudioRecord/AudioTrack and parallelism
   - Reason: Prevent data loss between chunks
   - Behavior change: Concurrent read and play using queues

7. Implement file logging and retrieval path
   - Reason: Diagnostics for Windows/Android
   - Behavior change: Log file stored in app external files dir /logs

8. Write Creation Plan doc with reasoning
   - Reason: Documentation per request
   - Behavior change: None (docs only)

9. Write How To Run doc
   - Reason: Developer/QA guidance
   - Behavior change: None (docs only)

10. Verify and remove any #ToDo comments encountered as part of changes
    - Reason: Keep code clean and requirements satisfied
    - Behavior change: None (cleanup)

11. Fix build: add android:exported to launcher activity
    - Reason: Required on Android 12+ for activities with intent-filters
    - Behavior change: None (manifest metadata only)

12. Enable AndroidX and Jetifier in gradle.properties
    - Reason: Build error requires android.useAndroidX=true; Jetifier for legacy transitive deps
    - Behavior change: None, build system flags only

13. Add AudioProcessor with 16kHz mic-to-speaker pipeline and NoiseMode
    - Reason: Encapsulate audio logic and mode switching for noise reduction
    - Behavior change: Audio processing moved from service into processor class

14. Implement LIGHT mode using NoiseSuppressor/AGC/AEC bound to AudioRecord
    - Reason: Built-in effects for mild, natural noise cleanup
    - Behavior change: Enables supported effects dynamically; no effect if unsupported

15. Integrate RNNoise JNI wrapper and CMake with graceful stub fallback
    - Reason: Extreme AI-based noise reduction on-device via NDK
    - Behavior change: New native library load at runtime; fallback to passthrough if unavailable

16. Wire AudioForegroundService to use AudioProcessor and support ACTION_SET_MODE
    - Reason: Runtime mode switching from UI without restarting service
    - Behavior change: Service delegates processing and can change modes on the fly

17. Add SegmentedButton UI in MainActivity to switch modes (default LIGHT)
    - Reason: User control for Off/Light/Extreme
    - Behavior change: Selecting a mode updates processing immediately

18. Update Gradle for externalNativeBuild and ABI filters
    - Reason: Build native rnnoise .so with CMake for supported ABIs
    - Behavior change: NDK/CMake configs added; app size may change per ABI splits

19. Ensure resource cleanup and error handling across modes
    - Reason: Prevent leaks, handle unsupported effects/devices and missing natives
    - Behavior change: Effects and native handles are created/released on mode switch and stop

