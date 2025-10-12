# Audio Processing Flow - Before vs After Changes

## FLOWCHART 1: BEFORE CHANGES (Original Implementation)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            MainActivity                                      │
│  ┌────────────────────────────────────────────────────────────────┐        │
│  │  UI Components:                                                 │        │
│  │  • Gain Input (EditText)                                        │        │
│  │  • Volume Input (EditText)                                      │        │
│  │  • Start/Stop Button                                            │        │
│  │                                                                  │        │
│  │  On Start Click:                                                │        │
│  │    - Read gain and volume values                                │        │
│  │    - Start AudioForegroundService with ACTION_START             │        │
│  └────────────────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
                    Intent with EXTRA_GAIN_100X, EXTRA_VOL_100X
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AudioForegroundService                                  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────┐          │
│  │  Service Configuration:                                       │          │
│  │  • Sample Rate: 48,000 Hz                                     │          │
│  │  • Chunk Size: 100 ms (4,800 samples)                         │          │
│  │  • Channel: MONO                                              │          │
│  │  • Encoding: PCM_16BIT                                        │          │
│  │  • Audio Source: VOICE_RECOGNITION                            │          │
│  └──────────────────────────────────────────────────────────────┘          │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  Audio Pipeline Setup:                                              │    │
│  │                                                                      │    │
│  │  1. Create AudioRecord                                              │    │
│  │     • Buffer: max(minBuffer, 4800 * 2 bytes)                        │    │
│  │                                                                      │    │
│  │  2. Create AudioTrack                                               │    │
│  │     • Usage: USAGE_MEDIA                                            │    │
│  │     • Content: CONTENT_TYPE_SPEECH                                  │    │
│  │     • Buffer: max(minBuffer, 4800 * 2 bytes)                        │    │
│  │                                                                      │    │
│  │  3. Create Queue (ArrayBlockingQueue<ShortArray>, capacity 8)      │    │
│  │                                                                      │    │
│  │  4. Start AudioTrack playback                                       │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────┐    ┌──────────────────────────────────┐  │
│  │   IO Thread (Record)        │    │   Process Thread (Playback)      │  │
│  │                             │    │                                  │  │
│  │  START                      │    │  START                           │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  audioRecord.startRecording │    │  LOOP while running:             │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  LOOP while running:        │    │  Take chunk from queue           │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  Read 4800 samples          │    │  FOR each sample:                │  │
│  │  into buffer                │    │    • sample = input[i].toInt()   │  │
│  │    ↓                        │    │    • v = sample * gain * volume  │  │
│  │  Copy buffer to new array   │    │    • Clamp v to Short range      │  │
│  │    ↓                        │    │    • output[i] = v.toShort()     │  │
│  │  Queue.offer(copy)          │    │    ↓                             │  │
│  │    ↓                        │──┼─│→ audioTrack.write(output)        │  │
│  │  [If queue full, drop]      │    │    ↓                             │  │
│  │    ↓                        │    │  [Loop back]                     │  │
│  │  [Loop back]                │    │                                  │  │
│  │                             │    │                                  │  │
│  └─────────────────────────────┘    └──────────────────────────────────┘  │
│                                                                              │
│  Processing Formula (Single Mode):                                          │
│    output = clamp(input.toInt() * gain * volume, Short.MIN..Short.MAX)     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
                        ┌───────────────────────┐
                        │  Speaker Output        │
                        │  Quality: Full Audio   │
                        │  Rate: 48 kHz          │
                        └───────────────────────┘
```

---

## FLOWCHART 2: AFTER CHANGES (New Implementation with Fixes Applied)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            MainActivity                                      │
│  ┌────────────────────────────────────────────────────────────────┐        │
│  │  UI Components:                                                 │        │
│  │  • Gain Input (EditText)                                        │        │
│  │  • Volume Input (EditText)                                      │        │
│  │  • Noise Mode (RadioGroup): Off / Light / Extreme  ◄── NEW     │        │
│  │  • Start/Stop Button                                            │        │
│  │                                                                  │        │
│  │  On Start Click:                                                │        │
│  │    - Read gain, volume, and selected mode                       │        │
│  │    - Start AudioForegroundService with ACTION_START             │        │
│  │                                                                  │        │
│  │  On Mode Change (while running):                                │        │
│  │    - Send ACTION_SET_MODE to service  ◄── NEW                   │        │
│  └────────────────────────────────────────────────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
          Intent with EXTRA_GAIN_100X, EXTRA_VOL_100X, EXTRA_MODE
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                      AudioForegroundService                                  │
│  ┌──────────────────────────────────────────────────────────┐              │
│  │  Delegates to AudioProcessor  ◄── NEW ARCHITECTURE        │              │
│  │  • processor.start(mode, gain, volume)                    │              │
│  │  • processor.setNoiseMode(mode)  [on mode change]         │              │
│  │  • processor.stop()                                       │              │
│  └──────────────────────────────────────────────────────────┘              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
┌─────────────────────────────────────────────────────────────────────────────┐
│                         AudioProcessor  ◄── NEW CLASS                        │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────┐          │
│  │  Audio Configuration: ◄── RESTORED TO MATCH OLD CODE          │          │
│  │  • Sample Rate: 48,000 Hz  [Was 16kHz, now fixed]            │          │
│  │  • Chunk Size: 100 ms (4,800 samples)  [Was 20ms, now fixed] │          │
│  │  • Channel: MONO                                              │          │
│  │  • Encoding: PCM_16BIT                                        │          │
│  │  • Audio Source: VOICE_RECOGNITION                            │          │
│  └──────────────────────────────────────────────────────────────┘          │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  Audio Pipeline Setup:                                              │    │
│  │                                                                      │    │
│  │  1. Create AudioRecord (same as before)                             │    │
│  │  2. Create AudioTrack (same as before)                              │    │
│  │  3. Create Queue (ArrayBlockingQueue<ShortArray>, capacity 8)      │    │
│  │  4. Setup mode-specific effects:                                    │    │
│  │     • OFF mode: No effects                                          │    │
│  │     • LIGHT mode: Enable NoiseSuppressor, AGC, AEC  ◄── NEW        │    │
│  │     • EXTREME mode: Initialize RNNoise  ◄── NEW                     │    │
│  │  5. Start AudioTrack playback                                       │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ┌─────────────────────────────┐    ┌──────────────────────────────────┐  │
│  │   IO Thread (Record)        │    │   Process Thread (Playback)      │  │
│  │                             │    │                                  │  │
│  │  START                      │    │  START                           │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  audioRecord.startRecording │    │  LOOP while running:             │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  LOOP while running:        │    │  Take chunk from queue           │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  Read 4800 samples          │    │  ┌────────────────────────────┐ │  │
│  │  into buffer                │    │  │ SWITCH (noiseMode):        │ │  │
│  │    ↓                        │    │  └────────────────────────────┘ │  │
│  │  [LIGHT mode: built-in      │    │           ↓                      │  │
│  │   effects auto-process]     │    │    ┌──────┴──────┬──────────┐   │  │
│  │    ↓                        │    │    ↓             ↓          ↓   │  │
│  │  Copy buffer to new array   │    │  ┌─────┐    ┌───────┐  ┌────────┐│
│  │    ↓                        │    │  │ OFF │    │ LIGHT │  │EXTREME ││
│  │  Queue.offer(copy)          │    │  └─────┘    └───────┘  └────────┘│
│  │    ↓                        │──┼─│→    ↓             ↓          ↓   │  │
│  │  [If queue full, drop]      │    │  [Mode-specific processing]      │  │
│  │    ↓                        │    │    ↓                             │  │
│  │  [Loop back]                │    │  audioTrack.write(output)        │  │
│  │                             │    │    ↓                             │  │
│  └─────────────────────────────┘    └──────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
                                    ↓
                        ┌───────────────────────┐
                        │  Speaker Output        │
                        │  Quality: Full Audio   │
                        │  Rate: 48 kHz          │
                        └───────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    DETAILED MODE PROCESSING FLOWS                            │
└─────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│  OFF MODE Processing: ◄── MATCHES OLD CODE EXACTLY                       │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  FOR each sample in chunk:                                        │   │
│  │    ↓                                                              │   │
│  │  1. sample = input[i].toInt()                                     │   │
│  │  2. v = sample * gainMultiplier * volumeMultiplier                │   │
│  │  3. IF v > Short.MAX_VALUE: v = Short.MAX_VALUE                   │   │
│  │  4. IF v < Short.MIN_VALUE: v = Short.MIN_VALUE                   │   │
│  │  5. output[i] = v.toInt().toShort()                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                           │
│  Result: Pure amplification, no noise reduction                          │
│  Audio Quality: Full 48kHz fidelity                                      │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│  LIGHT MODE Processing: ◄── NEW                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  Built-in Android Effects (Applied at AudioRecord level):        │   │
│  │  • NoiseSuppressor (if available on device)                       │   │
│  │  • AutomaticGainControl (if available on device)                  │   │
│  │  • AcousticEchoCanceler (if available on device)                  │   │
│  │                                                                    │   │
│  │  FOR each sample in chunk:                                        │   │
│  │    ↓                                                              │   │
│  │  1. sample = input[i].toInt()  [Already processed by effects]    │   │
│  │  2. v = sample * gainMultiplier * volumeMultiplier                │   │
│  │  3. IF v > Short.MAX_VALUE: v = Short.MAX_VALUE                   │   │
│  │  4. IF v < Short.MIN_VALUE: v = Short.MIN_VALUE                   │   │
│  │  5. output[i] = v.toInt().toShort()                               │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                           │
│  Result: Mild noise reduction + gain control + echo cancellation         │
│  Audio Quality: Natural sounding, minimal processing artifacts           │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│  EXTREME MODE Processing: ◄── NEW (RNNoise AI-based)                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │  IF RNNoise available:                                            │   │
│  │    ↓                                                              │   │
│  │  1. Convert chunk to Float array:                                 │   │
│  │     FOR each sample:                                              │   │
│  │       floats[i] = input[i] / 32768.0f  [Range: -1.0 to 1.0]      │   │
│  │    ↓                                                              │   │
│  │  2. processed = RNNoise.process(rnHandle, floats)                 │   │
│  │     [AI-based noise suppression in native code]                   │   │
│  │    ↓                                                              │   │
│  │  3. Convert back and apply gain/volume:                           │   │
│  │     FOR each sample:                                              │   │
│  │       sample = (processed[i] * 32768.0f).toInt()                  │   │
│  │       v = sample * gainMultiplier * volumeMultiplier              │   │
│  │       IF v > Short.MAX_VALUE: v = Short.MAX_VALUE                 │   │
│  │       IF v < Short.MIN_VALUE: v = Short.MIN_VALUE                 │   │
│  │       output[i] = v.toInt().toShort()                             │   │
│  │                                                                    │   │
│  │  ELSE (RNNoise not available):                                    │   │
│  │    Fallback to OFF mode processing                                │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                           │
│  Result: Aggressive AI-based noise suppression                           │
│  Audio Quality: Maximum noise reduction, may affect voice naturalness    │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## KEY DIFFERENCES SUMMARY

### Architecture Changes:
1. ✅ **Separation of Concerns**: Audio logic moved to `AudioProcessor` class
2. ✅ **Mode Switching**: Can change noise reduction modes without restart
3. ✅ **Dynamic Effects**: Effects enabled/disabled based on mode selection

### Audio Parameters (FIXED):
| Parameter    | Before   | After (Initial) | After (FIXED) | Status |
|--------------|----------|-----------------|---------------|--------|
| Sample Rate  | 48 kHz   | 16 kHz ❌       | 48 kHz ✅     | RESTORED |
| Chunk Size   | 100 ms   | 20 ms ❌        | 100 ms ✅     | RESTORED |
| Processing   | Direct   | Mode-based      | Mode-based    | ENHANCED |

### Processing Modes:
- **OFF**: Identical to old code (48kHz, 100ms, pure amplification)
- **LIGHT**: Same parameters + Android built-in noise suppression
- **EXTREME**: Same parameters + RNNoise AI processing

### Fix Applied:
✅ Restored 48,000 Hz sample rate (was incorrectly set to 16,000 Hz)
✅ Restored 100 ms chunks (was incorrectly set to 20 ms)
✅ Fixed OFF mode to process exactly like old code
✅ Fixed gain/volume application timing in EXTREME mode

**Result**: OFF mode now provides identical audio quality to original code, while LIGHT and EXTREME modes offer optional noise reduction features.
