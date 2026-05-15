#!/usr/bin/env python3
"""
Gemini Live Chat — Audio + Video on Raspberry Pi 5
Uses Google Gemini 2.0 Flash Live API for real-time multimodal conversation.

Requirements:
  - Google Gemini API key (set as GEMINI_API_KEY env var)
  - Microphone + Speaker configured
  - Camera (Pi Camera or USB webcam)
  - Python packages: google-generativeai, opencv-python-headless, sounddevice, numpy

Usage:
  source ~/gemini-live/venv/bin/activate
  python gemini_live.py
"""

import os
import sys
import time
import queue
import threading
import signal
import numpy as np

# ─── Configuration ───────────────────────────────────────────────────────────

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
if not GEMINI_API_KEY:
    print("ERROR: Set GEMINI_API_KEY environment variable first:")
    print('  export GEMINI_API_KEY="your-key-here"')
    sys.exit(1)

MODEL_NAME = "models/gemini-2.0-flash-live-001"

# Audio settings (Gemini Live requires these specs)
AUDIO_SAMPLE_RATE = 16000      # 16kHz input
AUDIO_CHANNELS = 1
AUDIO_DTYPE = np.int16
AUDIO_CHUNK_DURATION = 0.1     # 100ms chunks

# Camera settings
CAMERA_INDEX = 0               # /dev/video0
CAMERA_WIDTH = 640
CAMERA_HEIGHT = 480
CAMERA_FPS = 1                 # Send 1 frame/sec to Gemini (keeps bandwidth low)
CAMERA_QUALITY = 70            # JPEG quality (0-100)

# ─── Imports (after key check) ──────────────────────────────────────────────

import google.generativeai as genai
import cv2
import sounddevice as sd


# ─── Audio Queue ─────────────────────────────────────────────────────────────

audio_input_queue = queue.Queue()
audio_output_queue = queue.Queue()
running = True


def signal_handler(sig, frame):
    """Handle Ctrl+C gracefully."""
    global running
    print("\n[INFO] Stopping...")
    running = False


signal.signal(signal.SIGINT, signal_handler)


# ─── Audio Input (Microphone → Gemini) ──────────────────────────────────────

def audio_input_callback(indata, frames, time_info, status):
    """Callback that puts mic audio into the queue."""
    if status:
        print(f"[AUDIO-IN] Status: {status}")
    audio_input_queue.put(indata.copy())


def mic_stream():
    """Start microphone stream."""
    return sd.InputStream(
        samplerate=AUDIO_SAMPLE_RATE,
        channels=AUDIO_CHANNELS,
        dtype=AUDIO_DTYPE,
        blocksize=int(AUDIO_SAMPLE_RATE * AUDIO_CHUNK_DURATION),
        callback=audio_input_callback,
    )


# ─── Audio Output (Gemini → Speaker) ────────────────────────────────────────

def audio_output_callback(outdata, frames, time_info, status):
    """Callback that pulls audio from the output queue to the speaker."""
    if status:
        print(f"[AUDIO-OUT] Status: {status}")
    try:
        data = audio_output_queue.get_nowait()
        # Pad or trim to match requested frames
        if len(data) < frames:
            outdata[:len(data)] = data
            outdata[len(data):] = 0
        else:
            outdata[:] = data[:frames]
    except queue.Empty:
        outdata[:] = 0


def speaker_stream():
    """Start speaker output stream."""
    return sd.OutputStream(
        samplerate=24000,  # Gemini outputs at 24kHz
        channels=1,
        dtype=np.int16,
        blocksize=1024,
        callback=audio_output_callback,
    )


# ─── Camera ─────────────────────────────────────────────────────────────────

def get_camera():
    """Initialize and return the camera."""
    cap = cv2.VideoCapture(CAMERA_INDEX)
    if not cap.isOpened():
        print("[CAMERA] ERROR: Cannot open camera. Check connection.")
        return None

    cap.set(cv2.CAP_PROP_FRAME_WIDTH, CAMERA_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, CAMERA_HEIGHT)
    cap.set(cv2.CAP_PROP_FPS, CAMERA_FPS)

    # Verify settings
    actual_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    actual_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
    print(f"[CAMERA] Opened at {actual_w}x{actual_h}")

    return cap


def capture_frame(cap):
    """Capture a single frame and return as JPEG bytes."""
    ret, frame = cap.read()
    if not ret:
        return None
    # Encode to JPEG
    encode_params = [cv2.IMWRITE_JPEG_QUALITY, CAMERA_QUALITY]
    _, jpeg_buf = cv2.imencode('.jpg', frame, encode_params)
    return jpeg_buf.tobytes()


# ─── Main Gemini Live Session ───────────────────────────────────────────────

def run_gemini_live():
    global running

    # Configure Gemini
    genai.configure(api_key=GEMINI_API_KEY)

    # Model config for Live API
    config = {
        "response_modalities": ["AUDIO"],  # We want audio responses
        "speech_config": {
            "voice_config": {
                "prebuilt_voice_config": {
                    "voice_name": "Aoede"  # Natural-sounding voice
                }
            }
        },
    }

    print("=" * 60)
    print("  Gemini Live Chat — Audio + Video")
    print("  Press Ctrl+C to stop")
    print("=" * 60)

    # Start camera
    cap = get_camera()
    if cap is None:
        print("[WARN] Running without camera (audio-only mode)")

    # Start audio streams
    mic = mic_stream()
    speaker = speaker_stream()

    try:
        model = genai.GenerativeModel(MODEL_NAME)

        with mic, speaker:
            print("[INFO] Connecting to Gemini Live API...")

            async for session in model.connect_live(config=config):
                print("[INFO] Connected! Start talking...")

                # ── Thread: Send microphone audio ──
                def send_audio():
                    while running:
                        try:
                            audio_data = audio_input_queue.get(timeout=0.5)
                            # Convert numpy array to bytes
                            raw_bytes = audio_data.flatten().tobytes()
                            session.send_audio(raw_bytes)
                        except queue.Empty:
                            continue
                        except Exception as e:
                            if running:
                                print(f"[SEND-AUDIO] Error: {e}")

                # ── Thread: Send camera frames ──
                def send_video():
                    last_frame_time = 0
                    while running and cap is not None:
                        now = time.time()
                        if now - last_frame_time < 1.0 / CAMERA_FPS:
                            time.sleep(0.05)
                            continue
                        last_frame_time = now
                        try:
                            frame_bytes = capture_frame(cap)
                            if frame_bytes:
                                session.send_image(frame_bytes, mime_type="image/jpeg")
                        except Exception as e:
                            if running:
                                print(f"[SEND-VIDEO] Error: {e}")
                                time.sleep(1)

                # Start sender threads
                audio_thread = threading.Thread(target=send_audio, daemon=True)
                video_thread = threading.Thread(target=send_video, daemon=True)
                audio_thread.start()
                video_thread.start()

                # ── Main thread: Receive responses ──
                try:
                    for msg in session.receive():
                        if not running:
                            break

                        # Handle audio response
                        if hasattr(msg, 'audio') and msg.audio:
                            audio_data = np.frombuffer(msg.audio, dtype=np.int16)
                            audio_output_queue.put(audio_data)

                        # Handle text (tool calls, etc.)
                        if hasattr(msg, 'text') and msg.text:
                            print(f"\n[GEMINI-TEXT] {msg.text}")

                        # Handle turn complete
                        if hasattr(msg, 'turn_complete') and msg.turn_complete:
                            print()  # New line after response

                except Exception as e:
                    if running:
                        print(f"[RECEIVE] Error: {e}")

                # Wait for threads
                running = False
                audio_thread.join(timeout=2)
                video_thread.join(timeout=2)

    except Exception as e:
        print(f"\n[ERROR] {e}")
        print("\nTroubleshooting:")
        print("  1. Check your API key: echo $GEMINI_API_KEY")
        print("  2. Ensure you have internet access: ping google.com")
        print("  3. Check Gemini API status: https://status.cloud.google.com")
    finally:
        if cap is not None:
            cap.release()
        running = False
        print("[INFO] Session ended.")


# ─── Entry Point ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    # List audio devices for debugging
    print("[INFO] Available audio devices:")
    print(sd.query_devices())
    print()

    run_gemini_live()
