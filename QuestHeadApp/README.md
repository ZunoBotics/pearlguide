# QuestHeadApp — Okello Robot Head Unit (Meta Quest 3)

Android application that turns a Meta Quest 3 into the head unit of the Okello humanoid robot. Powered by Google Gemini Live for real-time voice conversation and stereo computer vision. The robot's identity, role, knowledge base, and deployment location are fully configurable via the **OkelloNexus** companion phone app — Okello can be deployed as any persona in any venue without touching the code.

---

## Features

| Feature | Detail |
|---|---|
| **Gemini Live voice** | Full-duplex WebSocket conversation (Charon male voice) |
| **Dynamic persona** | Robot identity, role, greeting, and personality set from phone app |
| **Knowledge injection** | Operator-authored facts pushed into Gemini system prompt per deployment |
| **Location-aware** | Venue name, description, hours, and special instructions injected into system prompt |
| **Multilingual** | Language list and code-switching flag set from phone app |
| **Stereo vision** | Camera 50 (left) + Camera 51 (right) — Quest 3 world-facing passthrough cameras |
| **Live preview** | Camera feed displayed on the VR panel in real time |
| **Depth estimation** | Block-matching SAD disparity map at ~3fps, colorized overlay, nearest-obstacle readout |
| **HTTP config polling** | Polls companion phone app every 3 s; reloads Gemini only when config changes |
| **Manual IP config** | Gear-button dialog to enter the phone's IP address |
| **Status display** | Connection and sync status shown on screen in real time |
| **Auto-start** | Launches automatically on device boot |
| **Wake lock** | Keeps CPU alive when display sleeps |

---

## Hardware

- **Device:** Meta Quest 3
- **OS:** HorizonOS (Android 14 / API 34)
- **Cameras used:**
  - Camera ID `50` — left world-facing RGB (1280×1280, LENS_FACING_BACK)
  - Camera ID `51` — right world-facing RGB (1280×1280, LENS_FACING_BACK)

---

## Configuration — Connecting to the Phone App

The Quest does not need a server. It polls the phone app's built-in HTTP server.

### First-time setup

1. Open the phone app → **Connect** screen → note the displayed IP (e.g. `192.168.1.42`)
2. On the Quest, tap the **gear icon** (top-left corner of the main screen)
3. Enter the phone IP and tap **Connect**
4. The IP is saved to SharedPreferences (`nexus/broker_ip`) and survives reboots

### How polling works

`ConfigPoller` runs a coroutine loop:
1. Reads saved phone IP from SharedPreferences
2. `GET http://<phone-ip>:8080/config` with 5-second timeout
3. Compares response JSON to the last received JSON
4. If changed → calls `GeminiLiveService.updateConfig(config)` → Gemini reconnects with new system prompt
5. Status message shown on screen: `Config OK — 192.168.1.42` or `Config unreachable: …`
6. Repeats every 3 seconds

The first config received always triggers a Gemini reconnect (regardless of content), because `currentPrompt` is initialised to `""` instead of a hardcoded default.

---

## System Prompt Construction

When a config arrives, `GeminiLiveService.buildSystemPrompt(config)` assembles a Gemini system prompt from:

1. **Persona block** — name, role, greeting, personality traits, extra instructions
2. **Location block** — venue name, description, opening hours, special instructions (omitted if no location is set)
3. **Knowledge block** — all facts for the active persona, formatted as a numbered list with title, category, and content
4. **Base rules** — hardcoded behavioural constraints (never break character, keep responses short, etc.)

Gemini is only reconnected when the assembled prompt string changes — no unnecessary reconnects on identical polls.

---

## Architecture

```
QuestHeadApp/
└── app/src/main/java/com/okello/robot/head/
    ├── MainActivity.kt              Entry point, permission handling, UI wiring
    │                                  - Gear button → showBrokerIpDialog()
    │                                  - Binds GeminiLiveService
    │                                  - Starts ConfigPoller after service binds
    │                                  - Shows sync status on transcriptText view
    ├── BootReceiver.kt              Auto-start on boot
    ├── RobotApplication.kt
    ├── audio/
    │   ├── AudioInputManager.kt    Mic capture → 16-bit PCM chunks → Gemini
    │   └── AudioOutputManager.kt   Gemini audio chunks → AudioTrack speaker
    ├── camera/
    │   ├── StereoCameraCapture.kt  CameraX (cam 50) + Camera2 (cam 51) in parallel
    │   ├── StereoDepthEstimator.kt Block-matching SAD disparity → jet colormap overlay
    │   └── CameraFrameCapture.kt   Single-camera fallback
    ├── gemini/
    │   ├── GeminiLiveClient.kt     Java-WebSocket Gemini Live client (text + binary frames)
    │   ├── GeminiLiveService.kt    Foreground service; reconnect logic; prompt builder
    │   └── GeminiMessage.kt        JSON message builders for Gemini Live API
    └── mqtt/
        ├── ConfigPoller.kt         HTTP polling loop (OkHttp, 3 s interval)
        ├── NexusConfig.kt          Data class for parsed config (persona + location + facts)
        └── NexusCommandClient.kt   Legacy MQTT client (unused; retained for reference)
```

### Key design decisions

- **HTTP polling instead of MQTT** — a custom MQTT broker had silent message drops and protocol parsing bugs. Replaced with a plain `ServerSocket` HTTP server on the phone (port 8080) and OkHttp polling on the Quest. Trivially debuggable with `curl`.
- **`currentPrompt = ""`** — initialising to empty string (not a hardcoded default prompt) ensures the first received config always triggers a Gemini reconnect, even if the persona name happens to match the fallback defaults.
- **Java-WebSocket instead of OkHttp WebSocket** — OkHttp silently drops binary WebSocket frames on Android 10+, causing Gemini audio responses to never arrive. Java-WebSocket handles both text and binary frames correctly.
- **Camera2 for Camera 51** — CameraX ConcurrentCamera mode is not supported for cameras 50/51 on Quest 3. Camera 51 is opened directly via Camera2 alongside the CameraX session for Camera 50.
- **CameraX COMPATIBLE mode** — Uses TextureView instead of SurfaceView so the preview renders inside the Quest 3 VR panel compositor.
- **Depth at 3fps** — SAD block-matching at 160×120 costs ~55M operations/frame. Throttled to avoid saturating the CPU alongside Gemini audio I/O.
- **Cleartext HTTP allowed** — `res/xml/network_security_config.xml` sets `<base-config cleartextTrafficPermitted="true" />` because LAN communication to the phone uses plain HTTP (no TLS on a private network).

---

## Setup

### 1. API Key

Create `local.properties` in the project root (same level as `build.gradle`) and add:

```
GEMINI_API_KEY=your_api_key_here
```

Get a key at [Google AI Studio](https://aistudio.google.com/). The key is injected at build time via `BuildConfig` and is never committed.

### 2. Quest 3 Developer Mode

Enable developer mode in the Meta Quest mobile app, then connect via USB:

```bash
adb devices
```

Grant the headset camera permission after first install:

```bash
adb shell pm grant com.okello.robot.head horizonos.permission.HEADSET_CAMERA
```

### 3. Build & Install

```bash
cd QuestHeadApp
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 4. Pair with Phone App

See [**Configuration — Connecting to the Phone App**](#configuration--connecting-to-the-phone-app) above.

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | WebSocket to Gemini Live + HTTP polling to phone |
| `RECORD_AUDIO` | Microphone input for voice conversation |
| `CAMERA` | Quest 3 passthrough cameras |
| `horizonos.permission.HEADSET_CAMERA` | Quest-specific camera access grant |
| `RECEIVE_BOOT_COMPLETED` | Auto-start on device boot |
| `FOREGROUND_SERVICE` | GeminiLiveService runs as a persistent foreground service |
| `WAKE_LOCK` | CPU kept alive when display sleeps |

---

## Personality — Okello

Okello is a warm, charismatic Ugandan robot who markets Zentara Holdings' property management services. He never breaks character, handles physical-limitation questions with humor, and responds in whichever language the client uses.

Base behavioural rules (always active, regardless of persona config):
- Never say "I'm just a language model" or any AI disclaimer — you are a physical robot
- Keep responses to 2–4 sentences (spoken out loud to people in real life)
- Handle physical-limitation questions with humour ("my robot arms are still being calibrated!")
- Use stereo cameras to acknowledge and react to people in the environment

Persona-specific overrides (set from phone app) layer on top of the base rules.

---

## Related

- **OkelloNexus companion app** — `OkelloNexus` branch of this repository
- Python prototype (Raspberry Pi): [`gemini-live` branch](https://github.com/ZunoBotics/pearlguide/tree/gemini-live)
- Developed by [ZunoBotics](https://github.com/ZunoBotics)
