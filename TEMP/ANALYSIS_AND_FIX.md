# Audio Processing Changes Analysis

## Critical Changes Made

### 1. **Architecture Refactoring**
- **Before**: All audio processing logic was in `AudioForegroundService`
- **After**: Audio processing moved to separate `AudioProcessor` class
- **Impact**: Better separation of concerns, but introduced configuration differences

### 2. **Sample Rate Change** ⚠️ **ROOT CAUSE OF FAINTNESS**
- **Before**: 48,000 Hz (standard high-quality audio)
- **After**: 16,000 Hz (telephony quality, chosen for noise cancellation compatibility)
- **Impact**: Lower audio quality and potential volume/frequency response issues

### 3. **Chunk Size Change**
- **Before**: 100 ms chunks (4,800 samples @ 48kHz)
- **After**: 20 ms chunks (320 samples @ 16kHz)
- **Impact**: Lower latency but more frequent processing

### 4. **New Features Added**
- Three noise cancellation modes: OFF, LIGHT, EXTREME
- Built-in Android audio effects (NoiseSuppressor, AGC, AEC) for LIGHT mode
- RNNoise JNI integration for EXTREME mode
- Runtime mode switching without service restart

## Audio Processing Flow Comparison

### BEFORE (Old Code)
```
┌─────────────────────────────────────────────────────────────┐
│                  AudioForegroundService                      │
│                                                              │
│  AudioRecord (48kHz, 100ms chunks)                          │
│         ↓                                                    │
│  IO Thread: Record audio → Queue                            │
│         ↓                                                    │
│  Process Thread:                                            │
│    - Read from queue                                        │
│    - sample.toInt()                                         │
│    - sample * gain * volume                                 │
│    - Clamp to Short range                                   │
│         ↓                                                    │
│  AudioTrack (48kHz) → Speaker output                        │
└─────────────────────────────────────────────────────────────┘
```

### AFTER (New Code)
```
┌─────────────────────────────────────────────────────────────┐
│              AudioForegroundService                          │
│                     ↓                                        │
│              AudioProcessor                                  │
│                                                              │
│  AudioRecord (16kHz, 20ms chunks) ⚠️                        │
│         ↓                                                    │
│  IO Thread: Record audio → Queue                            │
│         ↓                                                    │
│  Process Thread (Mode-dependent):                           │
│                                                              │
│  ┌─ OFF Mode ────────────────────────┐                     │
│  │  - sample * gain * volume          │                     │
│  │  - Clamp to Short range            │                     │
│  └────────────────────────────────────┘                     │
│                                                              │
│  ┌─ LIGHT Mode ──────────────────────┐                     │
│  │  - NoiseSuppressor enabled         │                     │
│  │  - AutomaticGainControl enabled    │                     │
│  │  - AcousticEchoCanceler enabled    │                     │
│  │  - sample * gain * volume          │                     │
│  │  - Clamp to Short range            │                     │
│  └────────────────────────────────────┘                     │
│                                                              │
│  ┌─ EXTREME Mode ────────────────────┐                     │
│  │  - Convert Short → Float [-1,1]    │                     │
│  │  - Apply gain * volume in float    │                     │
│  │  - RNNoise.process(float[])        │                     │
│  │  - Convert Float → Short           │                     │
│  │  - Clamp to Short range            │                     │
│  └────────────────────────────────────┘                     │
│         ↓                                                    │
│  AudioTrack (16kHz) → Speaker output ⚠️                     │
└─────────────────────────────────────────────────────────────┘
```

## Root Cause of Faint Audio

### Issue 1: Sample Rate Mismatch (16kHz vs 48kHz)
The new code uses 16,000 Hz instead of 48,000 Hz. This causes:
- **Lower frequency range**: 16kHz can only reproduce frequencies up to 8kHz (Nyquist), vs 24kHz for 48kHz
- **Potential resampling artifacts**: If the device speaker expects different rates, Android resamples internally
- **Quality degradation**: Telephony quality vs full audio quality

### Issue 2: Float Conversion in EXTREME Mode
In EXTREME mode, the gain/volume is applied DURING the float conversion:
```kotlin
floats[i] = (inChunk[i] / 32768.0f) * gainMultiplier * volumeMultiplier
```
Then converted back:
```kotlin
var v = processed[i] * 32768.0f
```

This is different from OFF/LIGHT modes where the Short value is directly multiplied.

## Requested Fix

**User Requirement**: "When noise cancellation is OFF, it should have output like old code so it should persist old flow"

### Solution Strategy
1. **Restore 48kHz sample rate as default** to match old behavior
2. **Restore 100ms chunks** for consistency with old code
3. **Keep the new architecture** but with old audio parameters
4. **Ensure OFF mode** perfectly matches the old processing flow

---

## Changes to Implement

### In `AudioProcessor.kt`:
1. Change `sampleRate` from `16000` to `48000`
2. Change `chunkMs` from `20` to `100`
3. Ensure processing logic in OFF mode matches old code exactly

This will restore the original audio quality while keeping the new noise cancellation features available in LIGHT and EXTREME modes.
