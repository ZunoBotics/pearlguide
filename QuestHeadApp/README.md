# QuestHeadApp — Okello Robot Head Unit (Meta Quest 3)

Android application that turns a Meta Quest 3 into the head unit of the Okello humanoid robot. Okello is an AI marketing assistant for **Zentara Holdings Company Ltd**, powered by Google Gemini Live for real-time voice conversation and stereo computer vision.

---

## Features

| Feature | Detail |
|---|---|
| **Gemini Live voice** | Full-duplex WebSocket conversation (Charon male voice) |
| **Multilingual** | English · Luganda · Acholi · Swahili |
| **Stereo vision** | Camera 50 (left) + Camera 51 (right) — Quest 3 world-facing passthrough cameras |
| **Live preview** | Camera feed displayed on the VR panel in real time |
| **Depth estimation** | Block-matching SAD disparity map at ~3fps, colorized overlay, nearest-obstacle readout |
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

## Setup

### 1. API Key

Create `local.properties` in the project root (same level as `build.gradle`) and add:

```
GEMINI_API_KEY=your_api_key_here
```

Get a key at [Google AI Studio](https://aistudio.google.com/). The key is injected at build time via `BuildConfig` and is excluded from version control.

### 2. Quest 3 Developer Mode

Enable developer mode in the Meta Quest mobile app, then connect via USB:

```bash
adb devices
```

Grant the `horizonos.permission.HEADSET_CAMERA` permission after first install:

```bash
adb shell pm grant com.okello.robot.head horizonos.permission.HEADSET_CAMERA
```

### 3. Build & Install

```bash
cd QuestHeadApp
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Architecture

```
QuestHeadApp/
└── app/src/main/java/com/okello/robot/head/
    ├── MainActivity.kt              # Entry point, permission handling, UI wiring
    ├── BootReceiver.kt              # Auto-start on boot
    ├── RobotApplication.kt
    ├── audio/
    │   ├── AudioInputManager.kt    # Mic capture → PCM → Gemini
    │   └── AudioOutputManager.kt   # Gemini audio chunks → speaker
    ├── camera/
    │   ├── StereoCameraCapture.kt  # Camera 50 (CameraX) + Camera 51 (Camera2)
    │   ├── StereoDepthEstimator.kt # Block-matching SAD disparity + jet colormap
    │   └── CameraFrameCapture.kt   # Single-camera fallback
    └── gemini/
        ├── GeminiLiveClient.kt     # Java-WebSocket Gemini Live client
        ├── GeminiLiveService.kt    # Foreground service, reconnect logic
        └── GeminiMessage.kt        # JSON message builders
```

### Key design decisions

- **Java-WebSocket instead of OkHttp** — OkHttp silently drops binary WebSocket frames on Android 10, causing Gemini responses to never arrive. Java-WebSocket handles both text and binary frames correctly.
- **Camera2 for Camera 51** — CameraX ConcurrentCamera mode is not supported for cameras 50/51 on Quest 3. Camera 51 is opened directly via Camera2 alongside the CameraX session for Camera 50.
- **CameraX COMPATIBLE mode** — Uses TextureView instead of SurfaceView so the preview renders inside the Quest 3 VR panel compositor.
- **Depth at 3fps** — SAD block-matching at 160×120 costs ~55M operations/frame. Throttled to avoid saturating the CPU alongside Gemini audio I/O.

---

## Personality — Okello

Okello is a warm, charismatic Ugandan robot who markets Zentara Holdings' property management services. He never breaks character, handles physical-limitation questions with humor, and responds in whichever language the client uses (English, Luganda, Acholi, or Swahili).

System prompt and voice configuration live in `GeminiLiveService.kt`.

---

## Related

- Python prototype (Raspberry Pi): [`gemini-live` branch](https://github.com/ZunoBotics/pearlguide/tree/gemini-live)
- Developed by [ZunoBotics](https://github.com/ZunoBotics)
