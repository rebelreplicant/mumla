# SPEC: Flic push-to-talk in a Mumla fork

Audience: Claude Code CLI working inside a clone of the Mumla repository.
Owner: Rebel. Last updated: 2026-10-14.

## How to use this file

1. Put this file at the repo root as `SPEC.md`.
2. Start Claude Code in the repo and say: `Read SPEC.md. Start at M0 and stop at the first HUMAN CHECK.`
3. Work one milestone at a time. Commit at the end of each milestone with the message `M<n>: <title>`.
4. Never mark a milestone done on the strength of compiling alone. Where a milestone says **HUMAN CHECK**, stop, tell the user exactly what to test on a real phone with a real Flic, and wait.

## Goal

Add native Flic 2 and Flic Duo support to a fork of Mumla (Android Mumble client) so a Flic button works as hold-to-talk push-to-talk. Hold = transmit, release = stop, including with the screen off and Mumla backgrounded.

## Decisions already made (do not reopen)

- The fork uses a **different application id** from stock Mumla (suggested: `se.lublin.mumla.flic`) and a distinguishable app name, so it installs beside stock Mumla.
- **Hold to talk only.** No tap-to-toggle, click or double-click behaviour.
- **One active button at a time.** A Flic 2 and a Flic Duo may both be paired (one of each type). The user picks the active device in settings. Only the active device is connected and drives PTT.
- On a **Flic Duo**, the user picks which input is PTT. Other inputs are ignored.
- **Fork for personal sideloaded use. It will not be distributed.** Do not spend time on SDK licence analysis. Keep GPL-3.0 notices intact anyway.
- Reuse Mumla's existing foreground service. Do **not** add a second service.

## Non-goals

- Other button brands, other gestures, multi-button simultaneous PTT.
- Changes to Mumla's audio, protocol or UI beyond the Flic feature and the identity change.
- Upstreaming.

## Facts to rely on, and facts to verify

Known (from the library README and Mumla's public pages):

- Mumla: GPL-3.0-only, stock application id `se.lublin.mumla`, version 3.7.1 seen, maintained fork of Plumble. Has a Push-to-talk transmit mode.
- Flic library: `com.github.50ButtonsEach:flic2lib-android:2.+` from JitPack (`https://jitpack.io`). Package `io.flic.flic2libandroid`. Classes: `Flic2Manager` (singleton), `Flic2Button`, `Flic2ScanCallback`, `Flic2ButtonListener`.
- `Flic2Manager.initAndGetInstance(Context, Handler)` once at app start; the Handler fixes the thread for **all** callbacks. Later use `Flic2Manager.getInstance()`.
- `startScan(Flic2ScanCallback)` scans, connects and pairs. Callbacks: `onDiscoveredAlreadyPairedButton(button)`, `onDiscovered(bdAddr)`, `onConnected()`, `onAskToAcceptPairRequest()`, `onComplete(result, subCode, button)`; success is `Flic2ScanCallback.RESULT_SUCCESS`.
- `manager.getButtons()` returns paired buttons. Pairings persist on disk. After process start they are disconnected until `connect()` is called. A newly paired button is already connected.
- `button.addListener(Flic2ButtonListener)`, `removeListener`, `connect()`, `disconnectOrAbortPendingConnection()`, `getReadyTimestamp()`.
- Listener callbacks: `onButtonUpOrDown(button, wasQueued, lastQueued, timestamp, isUp, isDown)`, `onConnected`, `onReady`, `onDisconnected`, `onUnpaired`.
- **For Flic Duo support, implement `onButtonEvent` ONLY** and do not mix it with the individual click/up-down callbacks. All events then arrive there. Since both devices must be supported, use `onButtonEvent` for both.
- Stale events: after reconnect the library replays queued presses. Drop an event when `wasQueued && button.getReadyTimestamp() - timestamp > 1000`.
- Permissions: `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+) or `ACCESS_FINE_LOCATION` (older) are runtime permissions. Missing permission makes `startScan` and `connect` throw `SecurityException`. The library merges its own permissions into the manifest. If the app uses fine location for other reasons, or scans Bluetooth for location, the README says to add `tools:remove` overrides (needs `xmlns:tools`).
- Javadoc (may be pinned to an older version, so also inspect the real classes after Gradle sync): `https://javadoc.jitpack.io/com/github/50ButtonsEach/flic2lib-android/2dab0989dc8aba76a47006c4673f415d43bc445e/javadoc/`

Unknown, so verify and record in `docs/flic-notes.md` (do not guess):

1. The exact signature of `onButtonEvent` and the event model it delivers.
2. How to tell a Flic 2 from a Flic Duo on a `Flic2Button`, and how a Duo's inputs are identified.
3. The SDK call to forget/unpair a button.
4. `TalkBroadcastReceiver`: whether it is exported and which actions it accepts. (`onTalkKeyDown`/`onTalkKeyUp` behaviour is already verified, see "M0 findings so far".)
5. Whether Mumla has an `Application` subclass, where it is, and how it is registered.
6. How Mumla's foreground service is declared (`foregroundServiceType`) and where it starts and stops.
7. How settings are built (preference XML, `PreferenceFragment`, or Compose) and how the transmit-mode preference is stored and read.
8. Language mix (Java, Kotlin, both), Gradle layout, product flavours (for example F-Droid), `minSdk`, `targetSdk`, and the build commands that actually work.
9. How the application id is referenced: manifest placeholders, custom permissions (the F-Droid page lists a `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` under the app id), authorities, `applicationIdSuffix`.

Rules for unknowns: read the code and measure. If the answer would change the design, say so in the notes file and adjust the design section. Do not invent method names.

## M0 findings so far

Reported by an earlier Claude Code run on the real repo. Treat as a strong lead and re-verify the line numbers and behaviour in M0.

- `HumlaService.java` (the `humla` library module): `isTalking()` returns `mToggleInputMode.isTalkingOn()`, and `setTalkingState(boolean)` calls `mToggleInputMode.setTalkingOn(talking)`.
- `ToggleInputMode` (`se.lublin.humla.audio.inputmode`) holds the `mInputOn` flag. `shouldTransmit()` returns it, and the audio handler consults it to decide whether to send audio.
- `MumlaService` (app module, extends `HumlaService`) has `onTalkKeyDown()` and `onTalkKeyUp()`, which call `setTalkingState(true/false)`.
- Existing triggers: `MumlaHotCorner` and `MumlaOverlay` (touch), key presses, and `TalkBroadcastReceiver` (handles broadcast intents that control talking).
- Transmit modes (`se.lublin.humla.Constants`): `TRANSMIT_VOICE_ACTIVITY` (0), `TRANSMIT_PUSH_TO_TALK` (1), `TRANSMIT_CONTINUOUS` (2). The `PREF_INPUT_METHOD` setting (`voiceActivity`, `ptt`, `continuous`) maps to these through `Settings.getHumlaInputMethod()`. `ToggleInputMode` is the PTT mode; its `waitForInput()` blocks the audio input thread until talking is on.
- Existing PTT-related settings: `PREF_PUSH_KEY`, `PREF_HOT_CORNER`, `PREF_PTT_TOGGLE` (toggle instead of hold, default false), `PREF_PUSH_BUTTON_HIDE_KEY`, `PREF_PTT_BUTTON_HEIGHT`.
- **Verified in code:** `MumlaService.onTalkKeyDown()` / `onTalkKeyUp()` (about lines 611-634) both require `isConnectionEstablished()` and `Settings.ARRAY_INPUT_METHOD_PTT.equals(mSettings.getInputMethod())`. They honour `PREF_PTT_TOGGLE`. With toggle off: down starts talking if not already talking, up stops talking if talking. With toggle on: down does nothing and up flips the talking state.

Consequences for the design:

- **Decision: the Flic controller must NOT call `onTalkKeyDown()` / `onTalkKeyUp()`.** With `PREF_PTT_TOGGLE` on they would turn the Flic into tap-to-toggle, which breaks the hold-to-talk-only decision. Implement the hold behaviour directly, either in the controller or as two small new methods on `MumlaService` (whichever the M0 notes show is cleaner), using `setTalkingState`:
  - Down: if `isConnectionEstablished()`, the input method is PTT, and not already talking, call `setTalkingState(true)` and remember that the Flic started this transmission.
  - Up: if the Flic started the transmission, call `setTalkingState(false)`. Do not require a connection or PTT mode on release.
  - Failsafe (button disconnect, unpair, server disconnect, service teardown, mode change, device switch): if the Flic started a transmission, call `setTalkingState(false)` with no preconditions.
  - This ignores `PREF_PTT_TOGGLE` entirely for the Flic. Tracking "the Flic started it" stops a Flic release from cutting off a transmission started by another control.
- `setTalkingState` most likely only affects transmission while the input method is PTT (the audio handler consults the selected input mode). Verify this in M0. The controller must therefore check that the input method is `TRANSMIT_PUSH_TO_TALK` before acting, and release on a mode change.
- Keep all changes in the app module. Do not edit the `humla` module unless there is no alternative.
- Read `TalkBroadcastReceiver` in M0 and record its actions and whether it is exported. This does not change the decision to fork, but it tells us whether a tiny external bridge app would also have worked.

## Requirements

| ID | Requirement |
|---|---|
| FR1 | User can pair a Flic 2 and/or a Flic Duo from Mumla's settings. One button is kept per device type; pairing a new one of that type replaces the old one after confirmation. |
| FR2 | Hold to talk is the only mode: button down starts transmitting, button up stops, when Mumla is connected to a server and the transmit mode is Push to Talk. |
| FR3 | Works with Mumla backgrounded and the screen off while Mumla's foreground service is running. |
| FR4 | Button disconnect, Bluetooth off, server disconnect or service teardown stops transmission immediately. A dropped link must never leave the mic live. |
| FR5 | Stale queued presses (older than about one second when delivered) are ignored. |
| FR6 | Settings show each paired device's state (not paired, connecting, ready, disconnected) and offer Pair and Forget per device. |
| FR7 | Only the active device is kept connected; it is re-connected after process restart. |
| FR8 | The user selects the active device (Flic 2 or Flic Duo). Only the active device drives PTT. Switching takes effect immediately and releases PTT if mid-hold. |
| FR9 | When the Duo is active, the user selects which of its inputs is PTT. The others are ignored. The choice persists. |

Non-functional:

- Callbacks run on the main thread (`Handler(Looper.getMainLooper())`).
- If Bluetooth permission is denied, the rest of Mumla works normally and the Flic settings explain what is missing. No crashes.
- Keep the change set small and isolated so rebasing on upstream stays cheap: new code in its own package (for example `.../flic`), minimal edits to existing files.
- Match the language and code style already used in the touched files.

## Design

### Components

- **Application subclass** (new or existing): calls `Flic2Manager.initAndGetInstance(applicationContext, Handler(Looper.getMainLooper()))` once in `onCreate`, then creates the controller.
- **`FlicPttController`** (new singleton in the `flic` package). Owns: the manager reference, the paired buttons (at most one Flic 2 and one Flic Duo), which one is active, one `Flic2ButtonListener`, and the PTT state machine. Exposes: `attach(serviceCallbacks)`, `detach()`, `connectActive()`, `disconnectAll()`, a status stream for the settings UI.
- **Service integration:** Mumla's existing service attaches the controller on start and detaches it on stop. The controller drives talking state through `setTalkingState` with its own hold-only logic (see "M0 findings so far"). It does not call `onTalkKeyDown()` / `onTalkKeyUp()`, because those honour the toggle setting.
- **Preferences:** active device, Duo PTT input. Stored in Mumla's existing settings mechanism.
- **Settings UI:** a "Flic button" group with, per device, status + Pair + Forget; an Active device selector; when Duo is active, a PTT input selector. No mode setting.

### PTT state machine

Inputs: button events, `onConnected`, `onReady`, `onDisconnected`, `onUnpaired`, service connect/disconnect, transmit-mode changes, active-device changes.

- **Down:** if transmit mode is Push to Talk, the service is connected to a server, the event is not stale, and it comes from the active device (on a Duo, from the selected PTT input), set talking to true.
- **Up:** set talking to false.
- **Failsafe:** on `onDisconnected`, `onUnpaired`, server disconnect, service teardown, set talking to false and clear state.
- **Mode guard:** if the transmit mode changes away from Push to Talk, or the active device changes, mid-hold, release immediately.
- **Stale filter:** drop an event when `wasQueued && button.getReadyTimestamp() - timestamp > 1000`.
- Events from a non-active device are ignored (and the non-active device should not be connected at all).

### Connection lifecycle

- Newly paired button: already connected, no `connect()` call needed.
- After process start: call `connect()` on the active device's button from `getButtons()`.
- Keep only the active device connected while Mumla's service runs. Disconnect the other when the selection changes. Call `disconnectOrAbortPendingConnection()` when the service stops so the button is free for other apps.

### Manifest, permissions, foreground service

- Request BLE runtime permissions when the user taps Pair (with a clear rationale), not at app start. Guard every `startScan`/`connect` call against `SecurityException`.
- Add `FOREGROUND_SERVICE_CONNECTED_DEVICE` and extend the service's `foregroundServiceType` to include `connectedDevice` alongside what it declares now (probably microphone). Set it in the manifest **and** at the `startForeground` call (required on Android 14+).
- Apply the README's `tools:remove` overrides only if Mumla already uses fine location or Bluetooth scanning for location.

### Build

- Add the JitPack repository (`maven { url = uri("https://jitpack.io") }`) wherever the project declares repositories.
- Add `implementation("com.github.50ButtonsEach:flic2lib-android:2.+")`. After it builds, pin the exact resolved version.
- Set the new application id and app name. Use a debug or personal signing key.

## Milestones

Work in order. Each milestone ends with a build and a commit.

### M0: Recon (no feature code)

- Answer every item under "Unknown" above by reading the code and the SDK classes.
- Write `docs/flic-notes.md` listing the exact files, classes and methods the feature will touch, and any corrections to this spec.
- Done when the notes are committed and cover: what `TalkBroadcastReceiver` does, the service declaration, the settings mechanism, and the Duo input identifiers.

### M1: Library, identity, init

- Add Gradle dependency and JitPack repo. Change application id and app name. Resolve anything keyed to the old id (custom permissions, placeholders).
- Add or extend the Application class to initialise `Flic2Manager`.
- Done when: clean debug build; the APK installs beside stock Mumla; Mumla behaves unchanged.
- **HUMAN CHECK:** install and confirm it runs next to the stock app.

### M2: Pairing UI

- Settings group; runtime permissions on Pair; scan flow using `Flic2ScanCallback`; per-device status; Forget; pairing a new button of the same type replaces the old after confirmation; handle `onUnpaired`.
- Done when a Flic 2 and a Flic Duo can each be paired, show Ready, and survive an app restart.
- **HUMAN CHECK:** pair each device on hardware.

### M2b: Device selection

- Active device selector, Duo PTT input selector, persisted preferences, connect only the active device.
- Done when switching the active device moves PTT to the other device and the chosen Duo input is the one that transmits.

### M3: PTT wiring

- Controller and state machine; attach/detach from the service; call the talking-state method; use `onButtonEvent` for both devices.
- Done when hold = transmit and release = stop against a real Mumble server with each device.
- **HUMAN CHECK:** listen from another Mumble client.

### M4: Background operation

- Foreground service type change and lifecycle connect/disconnect.
- Done when it works with the screen off and Mumla backgrounded for 10+ minutes.
- **HUMAN CHECK:** on an Android 14+ device if available.

### M5: Failsafes and polish

- Stale filter, disconnect release, mode guard, device-switch release, permission-denied paths, clear error text in settings.
- Done when every test below passes.

## Test plan (manual, real hardware)

Run these after M5. Report results in `docs/flic-notes.md`.

1. Pair, kill and relaunch: the active button reconnects and works.
2. Hold the button in a channel: another client hears audio; release stops within a fraction of a second.
3. Screen off, app backgrounded: repeat test 2.
4. Walk out of range while holding: transmission stops on disconnect.
5. Press several times out of range, then return: no transmission from the replayed presses.
6. Change transmit mode to voice activation mid-hold: transmission follows the new mode, PTT releases cleanly.
7. Disconnect from the server while holding: no crash, no live mic on reconnect.
8. Bluetooth off then on: the button reconnects automatically.
9. Deny Bluetooth permissions: Mumla works, Flic settings explain what is missing, no crash.
10. Android 14+: the foreground service starts with the new type without `SecurityException`.
11. Factory reset a button: `onUnpaired` is handled, the UI returns to Not paired; pairing a different button of that type works and replaces the old one.
12. Both devices paired, Flic 2 active: it transmits, the Duo does nothing. Switch to the Duo: roles swap. Switching mid-hold releases PTT.
13. Duo active: only the selected input transmits. Change the selected input and confirm.

## Constraints for the agent

- Do not touch Mumla's audio pipeline, protocol code, the `humla` library module or unrelated UI.
- Do not add a second foreground service or a separate bridge app.
- Do not change transmit-mode semantics. This feature only drives the existing PTT path.
- Do not claim hardware behaviour you could not observe. Say "compiles" or "untested on hardware" explicitly.
- If a design assumption in this file is wrong, fix `docs/flic-notes.md` and this file's affected section before continuing, and tell the user.

## Still open (answer in M0 or M1)

- Which inputs a Flic Duo exposes and how the SDK identifies them. List them so the selector can be populated.
- The exact SDK call for forgetting/unpairing a button.
