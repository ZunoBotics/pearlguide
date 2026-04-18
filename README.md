# Robot Head — Gemini Live Museum Guide

An interactive robot head that holds real-time voice conversations with museum visitors using the Google Gemini Live API. The jaw servo animates in sync with the robot's speech.

> **Project:** Pearl Guide — ZunoBotics museum robot  
> **Branch:** `robot-head`

---

## Quick Start (TL;DR)

```bash
# 1 — clone and enter the directory
git clone https://github.com/ZunoBotics/pearlguide.git -b robot-head
cd pearlguide

# 2 — install system packages (once)
sudo apt update
sudo apt install -y python3-pip python3-dev i2c-tools libportaudio2 portaudio19-dev

# 3 — install Python dependencies (inside your conda / venv)
pip install -r requirements.txt

# 4 — set your Gemini API key
echo 'export GEMINI_API_KEY="your-key-here"' >> ~/.bashrc && source ~/.bashrc

# 5 — run
python main.py
```

---

## Hardware

| Component | Details |
|-----------|---------|
| Servo driver | PCA9685 16-channel, I2C address `0x40` |
| Eye tilt | MG90S → **D0 (CH0)** |
| Eye pan | MG90S → **D1 (CH1)** |
| Jaw | MG996 → **D3 (CH3)** |
| Head pan | MG996 → **D4 (CH4)** |
| Camera | USB camera (mounted on one eye) |
| Audio | 7.1-channel USB audio adapter (mic + speaker) |

### I2C wiring to Raspberry Pi

| PCA9685 pin | Pi GPIO | Pi physical pin |
|-------------|---------|-----------------|
| SCL | GPIO3 | Pin 5 |
| SDA | GPIO2 | Pin 3 |
| VCC | 3.3 V | Pin 1 |
| GND | GND | Pin 6 |

> **Verify the PCA9685 is detected** before running:
> ```bash
> sudo i2cdetect -y 1
> # Should show "40" at row 40, column 0
> ```

---

## Raspberry Pi Setup

### 1. Enable I2C

```bash
sudo raspi-config
# → Interface Options → I2C → Enable
sudo reboot
```

### 2. Install system packages

```bash
sudo apt update
sudo apt install -y python3-pip python3-dev i2c-tools libportaudio2 portaudio19-dev
```

### 3. Install Python dependencies

Always install into your active conda environment or venv — **not system Python** — to avoid permission issues with CircuitPython's hardware layer.

```bash
# Activate your environment first, e.g.:
conda activate lerobot

pip install -r requirements.txt
```

### 4. Set the Gemini API key

1. Visit [aistudio.google.com](https://aistudio.google.com) and create a free API key.
2. Copy `.env.example` to `.env` and fill in your key:

```bash
cp .env.example .env
# Edit .env and replace the placeholder value
```

3. Or export it directly (for a quick test):

```bash
export GEMINI_API_KEY="your-key-here"
# To persist across reboots:
echo 'export GEMINI_API_KEY="your-key-here"' >> ~/.bashrc
```

> **Note:** `.env` is listed in `.gitignore` and will never be committed.

---

## Running

```bash
cd ~/lerobot/zunobot-app/robot-head   # or wherever you cloned

# Normal run — servos + audio + Gemini Live
python main.py

# Simulation / audio-only (no servo hardware required)
python main.py --no-head

# List detected audio devices (useful for first-time setup)
python main.py --list-audio
```

What happens at runtime:
1. Connects to Gemini Live
2. Speaks the startup greeting ("Oli otya! Welcome…")
3. Listens continuously and responds in real time
4. Moves the jaw servo in sync with speech output

Press **Ctrl+C** to stop gracefully — servos return to neutral before shutdown.

---

## Testing Servos

Run the servo test **before** the full application to confirm wiring:

```bash
python test_servos.py
```

This sweeps each servo through its range and ends with an interactive prompt so you can move individual servos by typing commands. The script prints a clear error message with corrective steps if the PCA9685 driver or Python dependencies are missing.

---

## File Overview

| File | Purpose |
|------|---------|
| `main.py` | Entry point — argument parsing, signal handling, startup |
| `head_controller.py` | PCA9685 servo control (eye tilt/pan, jaw, head pan) |
| `gemini_live_agent.py` | Gemini Live session, audio I/O pipeline, jaw animation |
| `museum_knowledge.py` | System prompt and Uganda museum knowledge base |
| `test_servos.py` | Standalone servo sweep test + interactive prompt |
| `requirements.txt` | Python dependencies |
| `.env.example` | Template for the `GEMINI_API_KEY` environment variable |

---

## Tuning

### Jaw animation

In `gemini_live_agent.py`:

| Constant | Default | Effect |
|----------|---------|--------|
| `JAW_SMOOTHING` | `0.4` | EMA factor — `0.0` = very slow/smooth, `1.0` = instant |
| `JAW_NOISE_FLOOR` | `200` | RMS below this → jaw stays closed. Raise if jaw twitches in silence |
| `JAW_SCALE` | `6000` | RMS at this level → jaw fully open. Lower to make jaw open wider |

### Servo limits

In `head_controller.py`, adjust `*_MIN` and `*_MAX` constants if your servo's physical range or mounting position differs from the defaults.

### Voice

The robot's name is **Okello**. Change `GEMINI_VOICE` in `gemini_live_agent.py` to any Gemini Live voice:

| Male | Female |
|------|--------|
| `Puck` · `Charon` · `Fenrir` · `Orus` | `Aoede` · `Kore` · `Leda` · `Zephyr` |

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| `No module named 'adafruit_servokit'` | Dependencies missing from active interpreter | `pip install -r requirements.txt` inside the correct conda env / venv |
| `PCA9685 initialisation failed` | Wiring or I2C not enabled | Check `sudo i2cdetect -y 1` shows `40`; run `sudo raspi-config` → I2C → Enable |
| No audio output | Wrong ALSA device selected | Run `python main.py --list-audio` and cross-check with `aplay -l` |
| Jaw twitches in silence | Mic picking up background noise | Increase `JAW_NOISE_FLOOR` in `gemini_live_agent.py` |
| `GEMINI_API_KEY not set` | Env var missing | `export GEMINI_API_KEY="..."` or add to `.env` / `~/.bashrc` |

---

## Integration with the Robot Body

This module is intentionally standalone. Planned integration steps:

1. `robot_api_server.py` can import and call `HeadController` methods directly.
2. Add a `/api/head` endpoint to expose eye/head pan controls to the Android app.
3. `GeminiLiveAgent` can be exposed over a local socket so the body controller can trigger greetings on human detection.
