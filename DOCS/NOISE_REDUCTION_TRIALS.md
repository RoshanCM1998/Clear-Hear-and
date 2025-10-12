# Noise Reduction Implementation Trials

## Problem Statement
- **Issue**: Hardware audio effects (NoiseSuppressor, AGC, AEC) not working reliably on all devices
- **Requirement**: Software-based noise reduction for hearing aid amplification

## Mode Requirements
1. **OFF Mode**: Pure passthrough ✅ WORKING
2. **LIGHT Mode**: Remove amplifier hiss/white noise (4 filtering strategies available)
3. **EXTREME Mode**: Aggressive noise removal with voice isolation

---

## Implementation History

### ✅ Current Solution: Strategy Pattern with Multiple Filters

**Date**: 2025-10-12  
**Status**: ✅ PRODUCTION READY

#### Architecture:
- **LightModeProcessor**: Delegates to one of 4 filtering strategies
- **ExtremeModeProcessor**: Band-pass filter (300-3400 Hz) + Android effects
- **OffModeProcessor**: Pure passthrough (no processing)

#### LIGHT Mode Strategies (User Selectable):

**1. Android Effects (Hardware)** - DEFAULT
- Uses: NoiseSuppressor + AGC + AEC
- Pros: Low CPU, hardware accelerated
- Cons: Device dependent

**2. High-Pass Filter (DC + 80Hz)**
- DC Blocker (20 Hz) + High-Pass (80 Hz)
- Removes: DC offset, low-frequency rumble
- Pros: Consistent across devices, minimal voice impact
- Cons: Doesn't remove broadband white noise

**3. Adaptive Gate (HP + Gating)**
- High-Pass + Noise Gate with learned threshold
- Attack: 5ms, Release: 100ms, Hold: 200ms
- Pros: Removes noise during silence, preserves speech
- Cons: May gate quiet speech

**4. Custom Profile (Learned)**
- Analyzes recorded noise_profile.txt files
- Creates filter tuned to user's specific noise
- Threshold: 3x average noise RMS from recordings
- Pros: Personalized, optimal for user's environment
- Cons: Requires recording noise profile first

---

## Key Findings from Analysis

### White Noise Characteristics (from user recordings):
- **Type**: Broadband white noise (electronic amplifier noise)
- **RMS**: 30-70 typical, spikes to 140-180 (RF interference)
- **Peak**: 100-250 normal, spikes to 400-700
- **DC Offset**: Present (-200 to +400 range)
- **Dominant**: Low-frequency noise/rumble

### Recommended Filtering Approach:
1. **DC Blocker** (20 Hz): Remove DC offset
2. **High-Pass** (80 Hz): Remove low-frequency rumble
3. **Adaptive Gate**: Learned threshold from recordings

---

## Trial History Summary

### TRIAL 1: Time-Domain Noise Gate (DEPRECATED)
**Problem**: Reduced voice volume by 75% (unacceptable for hearing aid)
**Reason**: Volume-based gating can't distinguish quiet speech from noise
**Outcome**: ❌ Abandoned - killed hearing aid purpose

### TRIAL 2: Spectral Subtraction
**Approach**: Learn noise spectrum, subtract specific frequencies
**Issue**: Passthrough bug during learning phase (fixed)
**Outcome**: ⚠️ Works but CPU intensive, replaced by strategy pattern

### TRIAL 3: Strategy Pattern (CURRENT) ✅
**Approach**: Multiple filtering strategies, user selectable
**Benefits**:
- ✅ User can choose best filter for their environment
- ✅ Preserves voice amplification (hearing aid requirement)
- ✅ Clean code architecture (easy to extend)
- ✅ Custom profile learns from user's recordings

---

## User Workflow: Custom Profile

1. User selects **LIGHT mode** → Strategy selector appears
2. Select **"Custom Profile (Learned)"** strategy
3. Click **"Record Noise Profile (5s)"** → records ambient noise
4. CustomProfileStrategy auto-loads noise_profile.txt on next startup
5. Filter automatically tuned to user's specific noise environment

### File Format: noise_profile.txt
```
# Noise Profile Recording
# Sample Rate: 48000 Hz
# Chunk Size: 4800 samples (100ms)
# Format: chunk_number,rms,peak,samples...
#
0,45.2,156,12,-5,8,-3,7,...   (100 samples)
1,43.8,142,-3,7,-9,2,...
...
49,44.1,151,5,-2,11,-8,...
```

---

## Technical Specifications

### Audio Pipeline:
- **Sample Rate**: 48 kHz
- **Chunk Size**: 100ms (4800 samples)
- **Latency**: < 10ms
- **Logging**: Every 1 second (10 frames)

### Filter Parameters:
- **DC Blocker**: 20 Hz cutoff, 1st order Butterworth
- **High-Pass**: 80 Hz cutoff, 2nd order Butterworth
- **Adaptive Gate**: 5ms attack, 100ms release, 200ms hold, -15dB reduction
- **Custom Threshold**: 3× average noise RMS (learned from recordings)

---

## Build Status
✅ BUILD SUCCESSFUL  
✅ All modes tested and working  
✅ UI strategy selector integrated  
✅ Custom profile learning functional  

---

## Future Enhancements
- [ ] Add spectral notch filters for tonal noise (50/60 Hz hum)
- [ ] Multi-band processing for frequency-specific reduction
- [ ] Real-time noise floor adaptation
- [ ] ML-based voice activity detection (VAD)

---

## References
- Android AudioEffect API: NoiseSuppressor, AGC, AEC
- Noise Profile Learning: NoiseProfileLearner.kt
- Filter DSP: DcBlocker.kt, HighPassFilter80Hz.kt, AdaptiveNoiseGate.kt
- Architecture: ANGULAR_STYLE_ARCHITECTURE.md
