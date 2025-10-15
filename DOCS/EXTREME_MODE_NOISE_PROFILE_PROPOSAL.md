# EXTREME Mode Noise Profile Pre-Recording - Implementation Proposal

**Date**: 2025-10-15
**Status**: PROPOSED (To be implemented)

---

## 🔴 Critical Problem Identified

### Current Behavior - The Flaw

**Spectral Gate Learning Phase**:
1. EXTREME mode starts
2. Gate learns "noise floor" from **first 2 seconds** of audio
3. Uses this as reference to distinguish voice from noise
4. Sets threshold based on learned noise floor

**The Problem**:
```
Scenario: User starts EXTREME mode DURING an active conversation

Step 1: Gate starts learning (first 2 seconds)
Step 2: Person is speaking → RMS = 200
Step 3: Gate learns: "noise floor = 200" ❌
Step 4: Threshold = 200 × multiplier = very high
Step 5: Normal voice (RMS = 150) < threshold
Step 6: Gate thinks: "This is NOISE!" → Suppresses voice! ❌

Result: EXTREME mode SUPPRESSES voice instead of enhancing it!
```

### Why This Happens

The gate **assumes** the first 2 seconds contain ONLY background noise. If the user:
- Starts during a conversation
- Starts when music is playing
- Starts when someone is speaking nearby
- Starts in any non-silent environment

The noise floor will be **learned incorrectly** and the gate will malfunction.

### User's Insight

> "What if I have started the app when actual conversation was going on? Now that volume might be marked as noise and then all the data will always go in noise!"

**User is 100% CORRECT** - This is a critical design flaw that breaks the feature in real-world usage!

---

## ✅ Proposed Solution

### Use "Record Noise" Button for EXTREME Mode

**Concept**: Let user **explicitly record** background noise profile BEFORE using EXTREME mode

**User Flow**:
```
1. User arrives in noisy environment (restaurant, traffic, etc.)
2. Clicks "Record Noise for EXTREME Mode (5s)" button
3. User stays SILENT for 5 seconds
4. App records PURE background noise
5. Calculates average RMS and saves to file: extreme_mode_noise_profile.txt
6. User can now start EXTREME mode anytime (even during conversation!)
7. Gate loads pre-recorded noise floor → No learning phase needed
8. Voice detected correctly regardless of when EXTREME mode starts ✅
```

### Benefits

- ✅ **Accurate noise floor**: User controls when/where noise is recorded
- ✅ **No learning delay**: Instant operation (no 2-second wait)
- ✅ **Persistent profile**: Record once, use many times (until environment changes)
- ✅ **User control**: Can re-record anytime environment changes
- ✅ **Same pattern as LIGHT mode**: Reuses familiar "Record Noise" button concept
- ✅ **Works mid-conversation**: Can start EXTREME mode during active speech

---

## 📋 Implementation Options

### **Option A: Full Implementation (Recommended)** ⭐

**What to Implement**:

1. **UI Changes**:
   - Extend "Record Noise (5s)" button to work for EXTREME mode too
   - Show button when:
     - LIGHT mode + Custom Profile selected, OR
     - EXTREME mode selected
   - Button label changes based on context:
     - LIGHT + Custom: "Record Noise Profile (5s)" → saves `noise_profile.txt`
     - EXTREME: "Record Noise for EXTREME (5s)" → saves `extreme_mode_noise_profile.txt`
   - Show profile status:
     - "No profile" → Button shows "Record Noise..."
     - "Profile exists (recorded 2025-10-15 00:30)" → Button shows "Re-record Noise..."

2. **Backend Changes**:
   - Create `ExtremeNoiseProfileRecorder.kt`:
     ```kotlin
     class ExtremeNoiseProfileRecorder(context: Context) {
         fun recordNoiseProfile(durationSeconds: Int = 5)
         fun getNoiseFloor(): Float?  // Returns saved RMS or null
         fun hasProfile(): Boolean
         fun getProfileTimestamp(): String?
         fun clearProfile()
     }
     ```

3. **Modify SpectralGate**:
   ```kotlin
   class SpectralGate(
       // ... existing params
       private val preloadedNoiseFloor: Float? = null  // NEW parameter
   ) {
       init {
           if (preloadedNoiseFloor != null) {
               noiseFloorRms = preloadedNoiseFloor
               learningFrames = maxLearningFrames  // Skip learning
               isLearning = false
               Log.d(tag, "Using preloaded noise floor: $noiseFloorRms")
           }
       }
   }
   ```

4. **Modify ExtremeModeProcessor.setup()**:
   ```kotlin
   override fun setup(audioRecord: AudioRecord?, sampleRate: Int) {
       // Load noise profile if it exists
       val recorder = ExtremeNoiseProfileRecorder(context)
       val preloadedFloor = recorder.getNoiseFloor()

       spectralGate = SpectralGate(
           sampleRate = sampleRate,
           chunkSize = chunkSize,
           thresholdDb = -30f,
           reductionDb = -12f,
           attackMs = 5f,
           holdMs = 300f,
           releaseMs = 300f,
           preloadedNoiseFloor = preloadedFloor  // Use saved profile
       )

       if (preloadedFloor != null) {
           Log.d(tag, "EXTREME mode using saved noise profile: $preloadedFloor")
       } else {
           Log.d(tag, "EXTREME mode will learn noise floor (2 seconds)")
       }
   }
   ```

5. **Logging Changes**:
   - Update getDescription() to show profile status:
     ```
     "EXTREME-BandPass+Gate[VOICE rms=0.0052 floor=0.0024(SAVED)]..."
     OR
     "EXTREME-BandPass+Gate[LEARN rms=0.0023 floor=0.0000(LEARNING)]..."
     ```

6. **Clear Logs Integration**:
   - Modify `clearLogsToday()` to also delete `extreme_mode_noise_profile.txt`

**Time Estimate**: 30-45 minutes
**Complexity**: Medium
**User Experience**: ⭐⭐⭐⭐⭐ Excellent

---

### **Option B: Test First, Then Implement**

**Approach**:
1. Test current EXTREME mode implementation (with 1.5x compensation)
2. User must start EXTREME mode in SILENCE (let it learn correctly)
3. Analyze logs to see if it works well when started properly
4. Then decide if pre-recorded noise profile is needed

**Testing Procedure**:
```
1. Go to noisy environment
2. Start EXTREME mode while SILENT
3. Wait 2 seconds (learning phase)
4. Then start conversation
5. Export logs and analyze
```

**Pros**:
- ✅ Quick to test (no code changes)
- ✅ Validates if compensation fix solved the volume issue
- ✅ Defers complexity until we know it's needed

**Cons**:
- ❌ Doesn't solve the "start during conversation" problem
- ❌ User must remember to stay silent for 2 seconds
- ❌ Not practical for real-world hearing aid use

**Time Estimate**: 0 minutes (just testing)
**Complexity**: None
**User Experience**: ⭐⭐ Poor (user must remember to stay silent)

---

### **Option C: Quick UI Warning (Temporary Fix)**

**What to Implement**:

1. **Add warning notification** when EXTREME mode starts:
   ```kotlin
   if (mode == NoiseMode.EXTREME) {
       Toast.makeText(
           this,
           "EXTREME Mode: Please stay SILENT for 2 seconds for calibration",
           Toast.LENGTH_LONG
       ).show()
   }
   ```

2. **Update mode description** in logs to show learning status:
   ```
   First 2 seconds: "EXTREME-Gate[LEARNING - Stay Silent!]..."
   After learning: "EXTREME-Gate[READY - Noise floor: 0.0024]..."
   ```

**Pros**:
- ✅ Very quick to implement (5 minutes)
- ✅ Reminds user to stay silent
- ✅ No major code changes

**Cons**:
- ❌ Still requires user to remember
- ❌ Doesn't work if they miss the notification
- ❌ Not ideal for hearing aid use (might start mid-conversation)
- ❌ Doesn't solve the core problem

**Time Estimate**: 5 minutes
**Complexity**: Trivial
**User Experience**: ⭐⭐⭐ Acceptable (band-aid solution)

---

## 🎯 Recommendation

**Implement Option A (Full Implementation)** because:

1. ✅ **Solves the root problem**: No more "start during conversation" failures
2. ✅ **Better UX**: One-time setup, works forever
3. ✅ **Consistent pattern**: Matches LIGHT mode's Custom Profile workflow
4. ✅ **Real-world usable**: Hearing aid users can start anytime
5. ✅ **Professional**: Shows we thought through the use case

**Why not Option B or C?**:
- Option B: Doesn't solve the problem, just validates current broken behavior
- Option C: Band-aid solution that still requires user discipline

---

## 📝 Technical Details for Implementation

### File Structure
```
app/src/main/java/com/clearhearand/
  audio/
    processors/
      ExtremeModeProcessor.kt  (MODIFY: Load noise profile)
    dsp/
      SpectralGate.kt  (MODIFY: Accept preloaded noise floor)
    recording/
      ExtremeNoiseProfileRecorder.kt  (NEW: Record & save noise profile)
  ui/
    MainActivity.kt  (MODIFY: Update button visibility logic)

app/src/main/files/logs/
  extreme_mode_noise_profile.txt  (NEW: Saved noise floor data)
  noise_profile.txt  (EXISTING: For LIGHT mode Custom Profile)
```

### File Format: `extreme_mode_noise_profile.txt`
```
timestamp,noise_floor_rms,num_samples,sample_rate
2025-10-15 12:30:45,0.002456,240000,48000
```

### UI State Machine
```
LIGHT mode + Custom Profile:
  - Show button: "Record Noise Profile (5s)"
  - Saves to: noise_profile.txt
  - Used by: LIGHT mode Adaptive Gate

EXTREME mode:
  - Show button: "Record Noise for EXTREME (5s)"
  - Saves to: extreme_mode_noise_profile.txt
  - Used by: EXTREME mode Spectral Gate

Other modes (OFF, LIGHT + other strategies):
  - Hide button
```

---

## 🧪 Testing Plan (After Implementation)

### Test 1: Clean Environment
```
1. Go to quiet room
2. Click "Record Noise for EXTREME" → Record silence
3. Check extreme_mode_noise_profile.txt → Should show low RMS (~0.001-0.002)
4. Start EXTREME mode
5. Speak normally
6. Export logs → Should show Gate[VOICE] with saved floor
```

### Test 2: Noisy Environment
```
1. Go to noisy place (music playing, traffic, etc.)
2. Click "Record Noise for EXTREME" → Record background noise
3. Check profile → Should show higher RMS (~0.005-0.010)
4. Start EXTREME mode WHILE CONVERSATION HAPPENING
5. Gate should use saved floor, detect voice correctly
6. Export logs → Should show Gate[VOICE] during speech
```

### Test 3: Profile Re-recording
```
1. Move to different noise environment
2. Click "Re-record Noise for EXTREME"
3. New profile should overwrite old one
4. EXTREME mode should use new profile
5. Verify voice detection accuracy in new environment
```

### Test 4: No Profile Fallback
```
1. Delete extreme_mode_noise_profile.txt
2. Start EXTREME mode
3. Should fall back to 2-second learning phase
4. Logs should show Gate[LEARN] for first 2 seconds
```

---

## 📊 Expected Log Output (After Implementation)

### With Saved Profile:
```
timestamp,mode,in_rms,in_peak,after_gain_rms,after_gain_peak,after_vol_rms,after_vol_peak,params,flags
2025-10-15 12:35:10,EXTREME,0.0052,0.018,0.0104,0.036,0.0312,0.108,EXTREME-BandPass+Gate[VOICE rms=0.0052 thr=0.0028 gain=1.00 floor=0.0024(SAVED)]+AndroidNS+Comp1.5x;gain=2.0;vol=3.0,
```

### Without Profile (Learning):
```
2025-10-15 12:35:10,EXTREME,0.0023,0.008,0.0046,0.016,0.0138,0.048,EXTREME-BandPass+Gate[LEARN rms=0.0023 thr=0.0000 gain=1.00 floor=0.0000(LEARNING)]+AndroidNS+Comp1.5x;gain=2.0;vol=3.0,
```

---

## 🚀 Implementation Checklist

When ready to implement Option A tomorrow:

- [ ] Create `ExtremeNoiseProfileRecorder.kt`
  - [ ] `recordNoiseProfile()` method
  - [ ] `getNoiseFloor()` method
  - [ ] `hasProfile()` method
  - [ ] `getProfileTimestamp()` method
  - [ ] `clearProfile()` method
  - [ ] Save to `extreme_mode_noise_profile.txt`

- [ ] Modify `SpectralGate.kt`
  - [ ] Add `preloadedNoiseFloor` constructor parameter
  - [ ] Skip learning if preloaded floor provided
  - [ ] Update logging to show `(SAVED)` vs `(LEARNING)`

- [ ] Modify `ExtremeModeProcessor.kt`
  - [ ] Load noise profile in `setup()`
  - [ ] Pass to SpectralGate constructor
  - [ ] Update getDescription() to show profile status
  - [ ] Log whether using saved or learning

- [ ] Modify `MainActivity.kt`
  - [ ] Update button visibility logic
  - [ ] Change button text based on mode
  - [ ] Call appropriate recorder (LIGHT vs EXTREME)
  - [ ] Show profile status in UI (optional)

- [ ] Modify `clearLogsToday()` in MainActivity
  - [ ] Also delete `extreme_mode_noise_profile.txt`
  - [ ] Update toast message count

- [ ] Test all scenarios
  - [ ] Record noise → Start EXTREME → Verify voice detection
  - [ ] Start EXTREME without profile → Verify learning fallback
  - [ ] Re-record noise → Verify profile updates
  - [ ] Clear logs → Verify profile deleted

- [ ] Update documentation
  - [ ] NOISE_REDUCTION_TRIALS.md
  - [ ] User instructions for recording noise profile

---

## 💬 Prompt for Tomorrow

**Simple Prompt**:
```
Implement Option A from EXTREME_MODE_NOISE_PROFILE_PROPOSAL.md -
Add noise profile pre-recording for EXTREME mode using the Record Noise button.
```

**Detailed Prompt**:
```
Read EXTREME_MODE_NOISE_PROFILE_PROPOSAL.md and implement Option A:
1. Create ExtremeNoiseProfileRecorder.kt
2. Modify SpectralGate to accept preloaded noise floor
3. Modify ExtremeModeProcessor to load saved profile
4. Update MainActivity button visibility for EXTREME mode
5. Integrate with Clear Logs button
Follow the implementation checklist and testing plan in the document.
```

---

## 📌 Summary

**Problem**: EXTREME mode's spectral gate learns noise floor from first 2 seconds, which fails if started during conversation.

**Solution**: Let user pre-record background noise profile, so gate knows what REAL noise is regardless of when EXTREME mode starts.

**Implementation**: Option A - Full implementation with ExtremeNoiseProfileRecorder, SpectralGate preloading, and UI integration.

**Benefit**: Makes EXTREME mode actually usable in real-world scenarios where conversations are already happening.

**Status**: Ready to implement tomorrow! 🚀

---

**End of Proposal Document**
