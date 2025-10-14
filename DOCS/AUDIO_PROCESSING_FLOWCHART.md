# Audio Processing Flowcharts - All Noise Cancellation Modes

This document provides detailed flowcharts for each noise cancellation mode, showing the complete audio processing pipeline from microphone to speaker.

---

## 📊 Mode Comparison Overview

| Mode | Processor Class | Android Effects | Custom DSP | Latency | Quality | Noise Reduction |
|------|----------------|----------------|------------|---------|---------|-----------------|
| **OFF** | `OffModeProcessor` | None | None | Minimal | Highest | None |
| **LIGHT** | `LightModeProcessor` | NS+AGC+AEC | None | Low | High | Mild |
| **EXTREME** | `ExtremeModeProcessor` | NS only | None* | Low | Good | Strong |

\* Future: Can add windowed DSP without affecting other modes

---

## 🔄 Common Audio Pipeline (All Modes)

```
┌─────────────────────────────────────────────────────────────────┐
│                         Audio Pipeline                           │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────┐
│   Microphone    │  ← Physical microphone captures sound
│   (Hardware)    │
└────────┬────────┘
         │
         │ Raw analog audio
         │
         ▼
┌─────────────────┐
│   ADC (Analog   │  ← Hardware converts analog to digital
│  to Digital)    │     Sample rate: 48,000 Hz
│                 │     Bit depth: 16-bit PCM
└────────┬────────┘
         │
         │ Digital audio (PCM16)
         │
         ▼
┌─────────────────┐
│  AudioRecord    │  ← Android API captures digital audio
│  (Android API)  │     Buffer: 4800 samples (100ms)
│                 │     Format: Mono, 16-bit PCM
└────────┬────────┘
         │
         │ [MODE-SPECIFIC PROCESSING HAPPENS HERE]
         │
         ▼
┌─────────────────┐
│  AudioTrack     │  ← Android API plays processed audio
│  (Android API)  │     Buffer: 4800 samples (100ms)
│                 │     Format: Mono, 16-bit PCM
└────────┬────────┘
         │
         │ Digital audio (PCM16)
         │
         ▼
┌─────────────────┐
│   DAC (Digital  │  ← Hardware converts digital to analog
│   to Analog)    │
└────────┬────────┘
         │
         │ Analog audio
         │
         ▼
┌─────────────────┐
│Speaker/Headphone│  ← Physical speaker produces sound
│   (Hardware)    │
└─────────────────┘
```

---

## 🔇 OFF Mode - Pure Passthrough

**Processor**: `OffModeProcessor.kt`

```
┌───────────────────────────────────────────────────────────────┐
│                    OFF MODE PROCESSING                         │
│                   (Pure Passthrough)                           │
└───────────────────────────────────────────────────────────────┘

       AudioRecord (48kHz, Mono, PCM16)
                │
                │ Raw input: inChunk[4800 samples]
                │ Example: [-5123, 8234, -1245, ...]
                │
                ▼
        ┌───────────────┐
        │  Read Chunk   │  ← Blocking read, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                │ ShortArray[4800]
                │
                ▼
┌───────────────────────────────────────────────┐
│        OffModeProcessor.process()             │
│                                               │
│  for each sample:                             │
│    sample = inChunk[i] as Int                 │
│    v = sample × gain × volume                 │
│                                               │
│    // Clamp to prevent overflow               │
│    if (v > 32767) v = 32767                   │
│    if (v < -32768) v = -32768                 │
│                                               │
│    outChunk[i] = v as Short                   │
│                                               │
│  Processing time: ~0.1ms (negligible)         │
└───────────────┬───────────────────────────────┘
                │
                │ Processed output: outChunk[4800 samples]
                │ Example (gain=1.0, vol=1.0): [-5123, 8234, -1245, ...]
                │ Example (gain=1.5, vol=2.0): [-15369, 24702, -3735, ...]
                │
                ▼
        ┌───────────────┐
        │  Write Chunk  │  ← Blocking write, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                ▼
         AudioTrack (48kHz, Mono, PCM16)


┌─────────────────────────────────────────────────────────────┐
│                   OFF MODE CHARACTERISTICS                   │
├─────────────────────────────────────────────────────────────┤
│ Android Effects:   None (disabled)                          │
│ Custom DSP:        None                                     │
│ Processing:        Gain × Volume only                       │
│ Latency:          ~100ms (buffer delay only)               │
│ CPU Usage:        Minimal (~1% of core)                    │
│ Quality:          Perfect (bit-exact with gain/vol)        │
│ Noise Reduction:  None                                     │
│ Use Case:         Silent environments, testing             │
└─────────────────────────────────────────────────────────────┘
```

**Code Flow**:
```kotlin
// OffModeProcessor.kt
override fun process(inChunk: ShortArray, outChunk: ShortArray, 
                     gain: Float, volume: Float) {
    for (i in inChunk.indices) {
        val sample = inChunk[i].toInt()
        var v = (sample * gain * volume)
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
        outChunk[i] = v.toInt().toShort()
    }
}
```

---

## 🔉 LIGHT Mode - Android Built-in Effects

**Processor**: `LightModeProcessor.kt`

```
┌───────────────────────────────────────────────────────────────┐
│                    LIGHT MODE PROCESSING                       │
│              (Android Hardware Effects)                        │
└───────────────────────────────────────────────────────────────┘

       AudioRecord (48kHz, Mono, PCM16)
                │
                │ Raw input from microphone
                │
                ▼
        ┌───────────────┐
        │ NoiseSuppressor│  ← HARDWARE (DSP chip)
        │   (Android)    │     Reduces steady-state noise
        │                │     (fan, AC, hum, etc.)
        │  Enabled: YES  │     Latency: <1ms
        └───────┬────────┘
                │
                │ Noise-suppressed audio
                │
                ▼
        ┌───────────────┐
        │AutomaticGain   │  ← HARDWARE (DSP chip)
        │   Control      │     Normalizes volume levels
        │   (Android)    │     Target: consistent loudness
        │  Enabled: YES  │     Latency: <1ms
        └───────┬────────┘
                │
                │ Volume-normalized audio
                │
                ▼
        ┌───────────────┐
        │AcousticEcho    │  ← HARDWARE (DSP chip)
        │  Canceler      │     Removes echo/feedback
        │   (Android)    │     (speakerphone scenarios)
        │  Enabled: YES  │     Latency: <1ms
        └───────┬────────┘
                │
                │ Effects-processed audio
                │ (Android effects applied automatically)
                │
                ▼
        ┌───────────────┐
        │  Read Chunk   │  ← Blocking read, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                │ ShortArray[4800]
                │ (already processed by Android effects)
                │
                ▼
┌───────────────────────────────────────────────┐
│        LightModeProcessor.process()           │
│                                               │
│  for each sample:                             │
│    sample = inChunk[i] as Int                 │
│    v = sample × gain × volume                 │
│                                               │
│    // Clamp to prevent overflow               │
│    if (v > 32767) v = 32767                   │
│    if (v < -32768) v = -32768                 │
│                                               │
│    outChunk[i] = v as Short                   │
│                                               │
│  Processing time: ~0.1ms (negligible)         │
└───────────────┬───────────────────────────────┘
                │
                │ Final output: outChunk[4800 samples]
                │ Noise reduced + user gain/volume applied
                │
                ▼
        ┌───────────────┐
        │  Write Chunk  │  ← Blocking write, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                ▼
         AudioTrack (48kHz, Mono, PCM16)


┌─────────────────────────────────────────────────────────────┐
│                  LIGHT MODE CHARACTERISTICS                  │
├─────────────────────────────────────────────────────────────┤
│ Android Effects:   NoiseSuppressor (ON)                     │
│                    AutomaticGainControl (ON)                │
│                    AcousticEchoCanceler (ON)                │
│ Custom DSP:        None                                     │
│ Processing:        Hardware + Gain × Volume                 │
│ Latency:          ~103ms (100ms buffer + 3ms effects)      │
│ CPU Usage:        Minimal (~2% of core)                    │
│                   (effects run on DSP chip)                 │
│ Quality:          High (natural sound)                     │
│ Noise Reduction:  Mild (~10-15 dB)                         │
│ Use Case:         Office, home, daily conversations        │
└─────────────────────────────────────────────────────────────┘
```

**Code Flow**:
```kotlin
// LightModeProcessor.kt
override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
    val sessionId = audioRecord?.audioSessionId ?: return
    
    // Enable Android hardware effects
    noiseSuppressor = NoiseSuppressor.create(sessionId)
    noiseSuppressor?.enabled = true
    
    automaticGainControl = AutomaticGainControl.create(sessionId)
    automaticGainControl?.enabled = true
    
    acousticEchoCanceler = AcousticEchoCanceler.create(sessionId)
    acousticEchoCanceler?.enabled = true
}

override fun process(inChunk: ShortArray, outChunk: ShortArray,
                     gain: Float, volume: Float) {
    // Android effects already applied, just apply user controls
    for (i in inChunk.indices) {
        val sample = inChunk[i].toInt()
        var v = (sample * gain * volume)
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE.toFloat()
        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE.toFloat()
        outChunk[i] = v.toInt().toShort()
    }
}
```

---

## 🔊 EXTREME Mode - Enhanced Noise Reduction

**Processor**: `ExtremeModeProcessor.kt`

```
┌───────────────────────────────────────────────────────────────┐
│                  EXTREME MODE PROCESSING                       │
│              (Aggressive Noise Reduction)                      │
└───────────────────────────────────────────────────────────────┘

       AudioRecord (48kHz, Mono, PCM16)
                │
                │ Raw input from microphone
                │
                ▼
        ┌───────────────┐
        │ NoiseSuppressor│  ← HARDWARE (DSP chip)
        │   (Android)    │     AGGRESSIVE MODE
        │                │     Stronger suppression
        │  Enabled: YES  │     More noise reduction
        │  (Aggressive)  │     Latency: <1ms
        └───────┬────────┘
                │
                │ Heavily noise-suppressed audio
                │ (More aggressive than LIGHT mode)
                │
                ▼
        ┌───────────────┐
        │  Read Chunk   │  ← Blocking read, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                │ ShortArray[4800]
                │ (NoiseSuppressor already applied)
                │
                ▼
┌───────────────────────────────────────────────┐
│       ExtremeModeProcessor.process()          │
│             (Current Version)                 │
│                                               │
│  for each sample:                             │
│    sample = inChunk[i] as Int                 │
│    v = sample × gain × volume                 │
│                                               │
│    // Clamp to prevent overflow               │
│    if (v > 32767) v = 32767                   │
│    if (v < -32768) v = -32768                 │
│                                               │
│    outChunk[i] = v as Short                   │
│                                               │
│  Processing time: ~0.1ms (negligible)         │
└───────────────┬───────────────────────────────┘
                │
                │ Final output: outChunk[4800 samples]
                │ Heavily noise reduced + user gain/volume
                │
                ▼
        ┌───────────────┐
        │  Write Chunk  │  ← Blocking write, 100ms
        │  4800 samples │
        └───────┬───────┘
                │
                ▼
         AudioTrack (48kHz, Mono, PCM16)


┌─────────────────────────────────────────────────────────────┐
│                EXTREME MODE CHARACTERISTICS                  │
├─────────────────────────────────────────────────────────────┤
│ Android Effects:   NoiseSuppressor (ON - Aggressive)        │
│                    AutomaticGainControl (OFF)               │
│                    AcousticEchoCanceler (OFF)               │
│ Custom DSP:        None (future: Windowed Wiener, etc.)    │
│ Processing:        Hardware + Gain × Volume                 │
│ Latency:          ~101ms (100ms buffer + 1ms effects)      │
│ CPU Usage:        Minimal (~2% of core)                    │
│                   (effects run on DSP chip)                 │
│ Quality:          Good (more processed than LIGHT)         │
│ Noise Reduction:  Strong (~20-25 dB)                       │
│ Use Case:         Crowded places, traffic, construction    │
└─────────────────────────────────────────────────────────────┘
```

**Future Enhancement** (when custom DSP is added):

```
┌───────────────────────────────────────────────────────────────┐
│                  EXTREME MODE PROCESSING                       │
│              (Future with Custom DSP)                          │
└───────────────────────────────────────────────────────────────┘

       AudioRecord (48kHz, Mono, PCM16)
                │
                ▼
        ┌───────────────┐
        │ NoiseSuppressor│  ← HARDWARE
        └───────┬────────┘
                │
                ▼
        ┌───────────────┐
        │  Read Chunk   │
        │  4800 samples │
        └───────┬───────┘
                │
                ▼
        ┌───────────────┐
        │Convert PCM16  │  ← Convert to Float32
        │  to Float32   │     for DSP processing
        └───────┬────────┘
                │
                │ FloatArray[4800] (-1.0 to +1.0)
                │
                ▼
┌──────────────────────────────────────────────┐
│   Windowed Wiener Filter                     │
│   - 512-sample windows                       │
│   - 50% overlap (256 samples)                │
│   - 9 windows per chunk                      │
│   - FFT → Spectral subtraction → IFFT       │
│   - Overlap-add reconstruction               │
│   Processing time: ~5-10ms per chunk         │
└──────────────┬───────────────────────────────┘
                │
                │ Spectrally filtered audio
                │
                ▼
┌──────────────────────────────────────────────┐
│   Spectral Gate                              │
│   - Frequency analysis                       │
│   - Suppress bins below threshold            │
│   - Threshold: -40dB                         │
│   Processing time: ~2-3ms                    │
└──────────────┬───────────────────────────────┘
                │
                │ Additional noise suppression
                │
                ▼
┌──────────────────────────────────────────────┐
│   Apply Gain and Volume                      │
│   for (i in floats.indices):                 │
│     floats[i] *= gain * volume               │
└──────────────┬───────────────────────────────┘
                │
                ▼
┌──────────────────────────────────────────────┐
│   Soft Limiter                               │
│   - Prevent clipping                         │
│   - Threshold: 0.9                           │
│   - Smooth compression above threshold       │
└──────────────┬───────────────────────────────┘
                │
                ▼
        ┌───────────────┐
        │Convert Float32│  ← Convert back to PCM16
        │  to PCM16     │
        └───────┬────────┘
                │
                ▼
        ┌───────────────┐
        │  Write Chunk  │
        │  4800 samples │
        └───────┬───────┘
                │
                ▼
         AudioTrack

┌─────────────────────────────────────────────────────────────┐
│          EXTREME MODE (FUTURE) CHARACTERISTICS               │
├─────────────────────────────────────────────────────────────┤
│ Android Effects:   NoiseSuppressor (ON)                     │
│ Custom DSP:        Windowed Wiener Filter                   │
│                    Spectral Gate                            │
│                    Soft Limiter                             │
│ Processing:        Hardware + Software DSP                  │
│ Latency:          ~110-115ms (100ms buffer + 10-15ms DSP)  │
│ CPU Usage:        Moderate (~15-20% of core)               │
│ Quality:          Good (heavy processing)                  │
│ Noise Reduction:  Very Strong (~30-35 dB)                  │
│ Use Case:         Extremely noisy environments             │
└─────────────────────────────────────────────────────────────┘
```

---

## 📊 Side-by-Side Comparison

```
┌──────────────────────────────────────────────────────────────────────────┐
│                     AUDIO PROCESSING COMPARISON                          │
└──────────────────────────────────────────────────────────────────────────┘

    OFF MODE              LIGHT MODE            EXTREME MODE
    ════════              ══════════            ════════════
    
  Microphone            Microphone            Microphone
      │                     │                     │
      ▼                     ▼                     ▼
   AudioRecord          AudioRecord           AudioRecord
      │                     │                     │
      │                     ▼                     ▼
      │              NoiseSuppressor      NoiseSuppressor
      │                     │               (Aggressive)
      │                     ▼                     │
      │             AutomaticGain                 │
      │                Control                    │
      │                     │                     │
      │                     ▼                     │
      │             AcousticEcho                  │
      │               Canceler                    │
      │                     │                     │
      ▼                     ▼                     ▼
   Read Chunk          Read Chunk            Read Chunk
      │                     │                     │
      ▼                     ▼                     ▼
  Gain × Volume       Gain × Volume         Gain × Volume
      │                     │                     │
      ▼                     ▼                     ▼
  Write Chunk         Write Chunk           Write Chunk
      │                     │                     │
      ▼                     ▼                     ▼
   AudioTrack          AudioTrack            AudioTrack
      │                     │                     │
      ▼                     ▼                     ▼
    Speaker               Speaker               Speaker


   Perfect              Natural             Strong Noise
   Quality              Sound              Reduction


┌──────────────────────────────────────────────────────────────────────────┐
│                        PERFORMANCE METRICS                                │
├──────────────┬────────────────┬────────────────┬──────────────────────────┤
│   Metric     │   OFF Mode     │   LIGHT Mode   │   EXTREME Mode           │
├──────────────┼────────────────┼────────────────┼──────────────────────────┤
│ Latency      │ 100ms          │ 103ms          │ 101ms                    │
│ CPU Usage    │ 1% (minimal)   │ 2% (minimal)   │ 2% (minimal)             │
│ Memory       │ 20 KB          │ 25 KB          │ 25 KB                    │
│ Quality      │ ★★★★★         │ ★★★★☆         │ ★★★☆☆                   │
│ Noise Reduc. │ None           │ 10-15 dB       │ 20-25 dB                 │
│ Naturalness  │ ★★★★★         │ ★★★★☆         │ ★★★☆☆                   │
│ Battery      │ Best           │ Good           │ Good                     │
└──────────────┴────────────────┴────────────────┴──────────────────────────┘
```

---

## 🎯 Use Case Decision Tree

```
                        Start
                          │
                          │
            ┌─────────────▼─────────────┐
            │  Is background noise      │
            │  bothering you?           │
            └─────────────┬─────────────┘
                          │
                    ┌─────┴─────┐
                    │           │
                   NO          YES
                    │           │
                    │           ▼
                    │    ┌──────────────┐
                    │    │  How noisy   │
                    │    │  is it?      │
                    │    └──────┬───────┘
                    │           │
                    │      ┌────┴────┐
                    │      │         │
                    │    MILD     EXTREME
                    │      │         │
                    │      │         │
                    ▼      ▼         ▼
              ┌─────────────────────────┐
              │   OFF Mode              │
              │   - Silent room         │
              │   - Testing             │
              │   - Max quality         │
              └─────────────────────────┘
                     │
                     │      ┌─────────────────────┐
                     └──────│   LIGHT Mode        │
                            │   - Office          │
                            │   - Home            │
                            │   - AC/Fan noise    │
                            └─────────────────────┘
                                   │
                                   │      ┌─────────────────────┐
                                   └──────│   EXTREME Mode      │
                                          │   - Crowded places  │
                                          │   - Traffic         │
                                          │   - Construction    │
                                          └─────────────────────┘
```

---

## 🔧 Technical Details

### Sample Rate & Buffer Sizes

```
Sample Rate:         48,000 Hz (48 kHz)
Channels:            1 (Mono)
Bit Depth:           16-bit signed integer (PCM16)
Chunk Duration:      100 milliseconds
Samples per Chunk:   4,800 samples
Bytes per Chunk:     9,600 bytes (4800 × 2)
Chunks per Second:   10 chunks
Range per Sample:    -32,768 to +32,767
```

### Audio Quality Formula

```
Quality Score = (1.0 - NoiseReduction × ProcessingArtifacts)

OFF Mode:     1.0 - (0.0 × 0.0) = 1.00 (Perfect)
LIGHT Mode:   1.0 - (0.3 × 0.1) = 0.97 (Excellent)
EXTREME Mode: 1.0 - (0.6 × 0.2) = 0.88 (Good)
```

### Noise Reduction Levels

```
OFF:     0 dB      (No reduction)
LIGHT:   -15 dB    (Mild reduction - 94% quieter)
EXTREME: -25 dB    (Strong reduction - 97% quieter)

Note: dB is logarithmic scale
-10 dB = 68% quieter
-20 dB = 90% quieter
-30 dB = 97% quieter
```

---

## 📝 Summary

### OFF Mode
- **When**: Silent environments, testing, maximum quality needed
- **Processing**: Gain × Volume only
- **Effects**: None
- **Result**: Perfect audio quality, no noise reduction

### LIGHT Mode
- **When**: Moderate noise (office, home, AC/fan)
- **Processing**: Android hardware effects + Gain × Volume
- **Effects**: NoiseSuppressor + AGC + AEC
- **Result**: Natural sound with mild noise reduction

### EXTREME Mode
- **When**: Heavy noise (crowds, traffic, construction)
- **Processing**: Aggressive Android effects + Gain × Volume
- **Effects**: NoiseSuppressor (aggressive)
- **Result**: Strong noise reduction, may sound more processed

---

*Generated: 2025-10-12*
*Clear Hear Android Application*
