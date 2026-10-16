# Mumla Flic Integration Notes

## Overview

This document records findings from inspecting the Mumla codebase for Flic 2/Duo integration.

---

## Updated: `onTalkKeyDown()` and `onTalkKeyUp()` honours `PREF_PTT_TOGGLE`

**Confirmed.** From `app/src/main/java/se/lublin/mumla/service/MumlaService.java:611-634`:

```java
@Override
public void onTalkKeyDown() {
    if(isConnectionEstablished()
            && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
        if (!mSettings.isPushToTalkToggle() && !isTalking()) {
            setTalkingState(true); // Start talking
        }
    }
}

@Override
public void onTalkKeyUp() {
    if(isConnectionEstablished()
            && Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())) {
        if (mSettings.isPushToTalkToggle()) {
            setTalkingState(!isTalking()); // Toggle talk state
        } else if (isTalking()) {
            setTalkingState(false); // Stop talking
        }
    }
}
```

| `PREF_PTT_TOGGLE` | Behavior |
|-------------------|----------|
| `false` (default) | `onTalkKeyDown()` starts talking, `onTalkKeyUp()` stops talking (hold-to-talk) |
| `true` | `onTalkKeyUp()` toggles state (tap-to-toggle), `onTalkKeyDown()` does nothing |

**Flic integration impact:** The Flic button must call `onTalkKeyDown()`/`onTalkKeyUp()` for hold-to-talk to work correctly. If `PREF_PTT_TOGGLE=true`, the Flic must NOT use these methods directly but instead call `setTalkingState()` directly.

---

## Still Unknown (need for M1)

### 1. Foreground Service Type

**Current declaration** (`AndroidManifest.xml:89`):
```xml
<service
    android:name=".service.MumlaService"
    android:exported="true"
    android:enabled="true"
    android:foregroundServiceType="microphone" />
```

**To add for Flic:** `FOREGROUND_SERVICE_CONNECTED_DEVICE` with `connectedDevice` type.

### 2. Application Class

**Location:** `app/src/main/java/se/lublin/mumla/app/MumlaApplication.java`

**Registered as:** `android:name=".app.MumlaApplication"` in manifest

**Current use:** Theme switching and language settings. Needs `Flic2Manager.initAndGetInstance()` call in `onCreate()`.

### 3. Settings UI Structure

**Location:** `app/src/main/res/xml/`

- `preference_headers.xml` - Main settings screen headers
- `settings_general.xml` - General preferences (chat, auto-reconnect, etc.)
- `settings_audio.xml` - Audio settings (includes PTT toggle)
- `settings_authentication.xml` - Certificate settings
- `settings_appearance.xml` - Theme settings
- `settings_about.xml` - Version and credits

**Fragment class:** `SettingsActivity.RootPreferenceFragment` extends `MumlaPreferenceFragment`

### 4. Build Commands

**Gradle wrapper:** `/gradle/wrapper/gradle-wrapper.properties` points to Gradle 8.13

**Build commands:**
```bash
./gradlew assembleDebug
./gradlew assembleRelease
```

**Application ID:** `se.lublin.mumla` (default)
**Product flavors:** goog, foss, donation, beta
**minSdk:** 21, **targetSdk:** 36

### 5. Flic SDK Inspection

**Library:** `com.github.50ButtonsEach:flic2lib-android:2.+` from JitPack

**Classes to inspect:**
- `io.flic.flic2libandroid.Flic2Manager` - Singleton manager
- `io.flic.flic2libandroid.Flic2Button` - Button representation
- `io.flic.flic2libandroid.Flic2ButtonListener` - Event listener

**Unknown (need to add dependency first):**
- Exact `onButtonEvent()` signature and event model
- How to identify Flic 2 vs Flic Duo
- How Duo inputs are identified
- SDK call for forgetting/unpairing

### 6. `TalkBroadcastReceiver`

**Location:** `app/src/main/java/se/lublin/mumla/service/ipc/TalkBroadcastReceiver.java`

**Actions:** `se.lublin.mumla.action.TALK`

**Extra:** `status` with values: `on`, `off`, `toggle` (default)

**Not exported** (no intent filters in manifest)

---

## Files That Will Be Modified

| File | Purpose |
|------|---------|
| `app/build.gradle` | Add Flic dependency |
| `app/src/main/AndroidManifest.xml` | Add BLE permissions, foregroundServiceType |
| `app/src/main/java/se/lublin/mumla/app/MumlaApplication.java` | Initialize Flic2Manager |
| `app/src/main/java/se/lublin/mumla/service/MumlaService.java` | Attach/detach Flic controller |
| `app/src/main/java/se/lublin/mumla/flic/` (new) | FlicPttController, FlicManager |
| `app/src/main/res/xml/` (new) | Flic settings preference XML |
| `app/src/main/java/se/lublin/mumla/preference/` | Settings fragments |

---

## Known Issues/Notes

1. **PTT toggle logic**: The `onTalkKeyDown()` method has an unusual pattern - it only starts talking when NOT in toggle mode AND not already talking. This may need investigation.

2. **Service export**: `MumlaService` is exported (`android:exported="true"`). This may be for the broadcast receiver but should be noted.
