#!/usr/bin/env python3
"""
Gemini Live Chat — Audio Only (Bluetooth Mic + Speaker)
Optimized for Raspberry Pi 5 with PipeWire + Bluetooth.

Based on the official Google Gemini Live API reference implementation.
https://github.com/google-gemini/gemini-live

Uses parecord/paplay for audio (bypasses sounddevice PortAudio issues)
and the google-genai SDK with proper LiveConnectConfig.

Requirements:
  - Google Gemini API key (GEMINI_API_KEY env var)
  - Bluetooth mic + speaker paired and set as default
  - pip install: google-genai, numpy
  - System: pipewire-pulse (for parecord/paplay)

Usage:
  source ~/gemini-live/venv/bin/activate
  export GEMINI_API_KEY="your-key-here"
  python gemini_live_audio.py
"""

import os
import sys
import subprocess
import signal
import asyncio
import numpy as np
import cv2
import time

# ─── Configuration ───────────────────────────────────────────────────────────

GEMINI_API_KEY = os.environ.get("GEMINI_API_KEY")
if not GEMINI_API_KEY:
    print("ERROR: Set GEMINI_API_KEY environment variable:")
    print('  export GEMINI_API_KEY="your-key-here"')
    sys.exit(1)

MODEL_NAME = "gemini-3.1-flash-live-preview"

# Audio input (mic → Gemini) — 16kHz mono 16-bit PCM
INPUT_SAMPLE_RATE = 16000
INPUT_CHANNELS = 1
INPUT_FORMAT = "s16le"
INPUT_CHUNK_SAMPLES = 1600  # 100ms at 16kHz

# Audio output (Gemini → speaker) — 24kHz mono 16-bit PCM
OUTPUT_SAMPLE_RATE = 24000
OUTPUT_CHANNELS = 1
OUTPUT_FORMAT = "s16le"

# Software gain — boost quiet BT mic so Gemini can hear you
# Adjust: 1=no boost, 4=moderate, 8=strong, 16=very strong
MIC_GAIN = 1

# ─── Imports ─────────────────────────────────────────────────────────────────

from google import genai
from google.genai import types

# ─── State ───────────────────────────────────────────────────────────────────

running = True


def signal_handler(sig, frame):
    global running
    print("\n[INFO] Stopping...")
    running = False


signal.signal(signal.SIGINT, signal_handler)


# ─── Audio I/O using parecord/paplay ────────────────────────────────────────

class AudioInput:
    """Read microphone audio via parecord subprocess with software gain."""

    def __init__(self, gain=1):
        self.process = None
        self.gain = gain

    def start(self):
        self.process = subprocess.Popen(
            [
                "arecord",
                "-D", "plughw:0,0",
                "-f", "S16_LE",
                "-r", str(INPUT_SAMPLE_RATE),
                "-c", str(INPUT_CHANNELS),
                "-t", "raw",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        print(f"[MIC] Started arecord (PID {self.process.pid}, gain={self.gain}x)")

    def read_chunk(self):
        """Read one chunk, apply gain, return (bytes, original_peak)."""
        if self.process is None or self.process.poll() is not None:
            return None, None
        try:
            raw = self.process.stdout.read(INPUT_CHUNK_SAMPLES * 2)
            if len(raw) == 0:
                return None, None

            samples = np.frombuffer(raw, dtype=np.int16).astype(np.float32)
            original_peak = int(np.max(np.abs(samples)))

            if self.gain != 1:
                samples = samples * self.gain
                samples = np.clip(samples, -32768, 32767)

            amplified = samples.astype(np.int16).tobytes()
            return amplified, original_peak
        except Exception as e:
            print(f"[MIC] Read error: {e}")
            return None, None

    def stop(self):
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
            print("[MIC] Stopped")


class AudioOutput:
    """Play audio via aplay subprocess."""

    def __init__(self):
        self.process = None
        self.expected_end_time = 0.0

    def start(self):
        self.process = subprocess.Popen(
            [
                "aplay",
                "-D", "plughw:0,0",
                "-f", "S16_LE",
                "-r", str(OUTPUT_SAMPLE_RATE),
                "-c", str(OUTPUT_CHANNELS),
                "-t", "raw",
            ],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.expected_end_time = time.time()
        print(f"[SPEAKER] Started aplay (PID {self.process.pid})")

    def write(self, audio_bytes):
        """Write audio bytes to speaker."""
        if self.process is None or self.process.poll() is not None:
            return
        try:
            self.process.stdin.write(audio_bytes)
            self.process.stdin.flush()
            
            # Keep track of exactly how long the speaker will be playing
            duration = len(audio_bytes) / (OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2.0)
            now = time.time()
            if self.expected_end_time < now:
                self.expected_end_time = now
            self.expected_end_time += duration
        except (BrokenPipeError, OSError):
            pass

    def is_playing(self):
        # We add 0.5 sec to let reverberations wrap up before listening again
        return time.time() < (self.expected_end_time + 0.5)

    def stop(self):
        if self.process:
            try:
                self.process.stdin.close()
            except:
                pass
            self.process.terminate()
            try:
                self.process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                self.process.kill()
            print("[SPEAKER] Stopped")


class VideoInput:
    """Capture frames from USB camera using OpenCV."""

    def __init__(self, device="/dev/video0"):
        self.device = device
        self.cap = None

    def start(self):
        self.cap = cv2.VideoCapture(self.device, cv2.CAP_V4L2)
        # Lower resolution to save bandwidth & latency
        self.cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
        self.cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
        print(f"[CAMERA] Started capture on {self.device}")

    def read_frame(self):
        """Read a frame and encode it as JPEG. Returns bytes or None."""
        if not self.cap or not self.cap.isOpened():
            return None
        ret, frame = self.cap.read()
        if not ret:
            return None
        
        # Encode to JPEG
        ret, buffer = cv2.imencode('.jpg', frame, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
        if not ret:
            return None
            
        return buffer.tobytes()

    def stop(self):
        if self.cap:
            self.cap.release()
            print("[CAMERA] Stopped")


# ─── Verify audio system ────────────────────────────────────────────────────

def check_audio():
    """Check that parecord/paplay can see devices."""
    print("\n[INFO] Checking audio system...")

    result = subprocess.run(
        ["pactl", "info"],
        capture_output=True, text=True
    )
    if result.returncode == 0:
        for line in result.stdout.split("\n"):
            if "Server Name" in line or "Default Sink" in line or "Default Source" in line:
                print(f"  {line.strip()}")
    else:
        print("  ⚠ pactl not responding. Is pipewire-pulse running?")
        return False

    # Quick mic test with volume check
    print("\n[INFO] Testing mic volume (speak now — 3 seconds)...")
    test = subprocess.Popen(
        ["arecord", "-D", "plughw:0,0", "-f", "S16_LE", "-r", "16000",
         "-c", "1", "-t", "raw"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE
    )
    import time
    time.sleep(3)
    test.terminate()
    test.wait(timeout=2)

    raw = test.stdout.read()
    if len(raw) < 100:
        print("  ❌ Microphone: no data captured")
        return False

    samples = np.frombuffer(raw, dtype=np.int16)
    peak = np.max(np.abs(samples))
    rms = np.sqrt(np.mean(samples.astype(float) ** 2))
    print(f"  📊 Raw mic: peak={peak}, rms={rms:.1f}")
    print(f"  📊 With {MIC_GAIN}x gain: peak ~ {min(int(peak) * MIC_GAIN, 32767)}")

    if peak < 50:
        print("  ⚠️  Mic is very quiet. Try increasing MIC_GAIN in the script.")
    else:
        print("  ✅ Microphone: WORKING")

    return True


# ─── Main Async Gemini Live Session ─────────────────────────────────────────

async def run_gemini_live():
    global running

    # Check audio first
    if not check_audio():
        print("\n[ERROR] Audio system not ready. Fix and try again.")
        return

    # Create Gemini client
    client = genai.Client(api_key=GEMINI_API_KEY)

    # ── Config matching the official reference implementation ──
    config = types.LiveConnectConfig(
        response_modalities=[types.Modality.AUDIO],
        speech_config=types.SpeechConfig(
            voice_config=types.VoiceConfig(
                prebuilt_voice_config=types.PrebuiltVoiceConfig(
                    voice_name="Charon"
                )
            )
        ),
        system_instruction=types.Content(
            parts=[types.Part(text="You are Okello, the first African Robot Tour guide developed in Uganda by ZunoBotics. You have a friendly, welcoming male Ugandan persona, eager to help tourists. Keep your responses concise, informative, and super friendly.")]
        ),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        realtime_input_config=types.RealtimeInputConfig(
            turn_coverage="TURN_INCLUDES_ONLY_ACTIVITY",
        ),
    )

    print("\n" + "=" * 55)
    print("   Gemini Live Voice + Vision Chat")
    print(f"   Mic gain: {MIC_GAIN}x | Identity: Okello | Press Ctrl+C to stop")
    print("=" * 55)
    print("\n[INFO] Connecting to Gemini Live API...")

    video_in = VideoInput(device="/dev/video0")
    audio_in = AudioInput(gain=MIC_GAIN)
    audio_out = AudioOutput()

    try:
        video_in.start()
        audio_in.start()
        audio_out.start()

        async with client.aio.live.connect(model=MODEL_NAME, config=config) as session:
            print("[INFO] Connected! Start talking to Gemini...\n")

            # ── Task: Send mic audio to Gemini ──
            async def send_audio():
                chunk_count = 0
                try:
                    while running:
                        chunk, original_peak = await asyncio.to_thread(audio_in.read_chunk)
                        
                        playing = audio_out.is_playing()
                        if chunk and len(chunk) > 0:
                            chunk_count += 1
                            if chunk_count % 50 == 0:
                                boosted = min(original_peak * MIC_GAIN, 32767)
                                vol_bar = "█" * min(int(boosted / 1500), 20)
                                status = "MUTED" if playing else "ACTIVE"
                                print(f"[MIC] #{chunk_count} [{status}] raw={original_peak:5d} boosted={boosted:5d} {vol_bar}")

                            # Drop frames if the speaker is playing so Gemini doesn't hear itself!
                            if playing:
                                continue

                            await session.send_realtime_input(
                                audio=types.Blob(
                                    data=chunk,
                                    mime_type=f"audio/pcm;rate={INPUT_SAMPLE_RATE}",
                                )
                            )
                        else:
                            await asyncio.sleep(0.05)
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    if running:
                        print(f"[SEND] Error: {e}")

            # ── Task: Receive audio + events from Gemini ──
            async def recv_audio():
                try:
                    while True:
                        async for response in session.receive():
                            if not running:
                                break

                            # Server content (audio + transcriptions)
                            if response.server_content:
                                sc = response.server_content

                                # Audio from model
                                if sc.model_turn:
                                    for part in sc.model_turn.parts:
                                        if part.inline_data:
                                            await asyncio.to_thread(audio_out.write, part.inline_data.data)

                                # Transcription of what user said
                                if sc.input_transcription and sc.input_transcription.text:
                                    print(f"\n🎤 You: {sc.input_transcription.text}")

                                # Transcription of what Gemini said
                                if sc.output_transcription and sc.output_transcription.text:
                                    print(f"🤖 Gemini: {sc.output_transcription.text}")

                                # Turn complete
                                if sc.turn_complete:
                                    print()  # newline after response

                                # Interrupted (user spoke while Gemini was talking)
                                if sc.interrupted:
                                    print("[INTERRUPTED] You spoke over Gemini")

                            # Go away warning
                            if response.go_away:
                                print(f"[WARN] Server sent go_away: {response.go_away}")

                        # session.receive() iterator may end after turn_complete — re-enter
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    if running:
                        print(f"[RECV] Error: {e}")

            # ── Task: Send camera frames to Gemini ──
            async def send_video():
                try:
                    while running:
                        frame_bytes = await asyncio.to_thread(video_in.read_frame)
                        if frame_bytes:
                            await session.send_realtime_input(
                                video=types.Blob(
                                    data=frame_bytes,
                                    mime_type="image/jpeg",
                                )
                            )
                        # Send 1 frame per second to save bandwidth and API limits
                        await asyncio.sleep(1.0)
                except asyncio.CancelledError:
                    pass
                except Exception as e:
                    if running:
                        print(f"[VIDEO] Error: {e}")

            # Run all tasks concurrently
            send_task = asyncio.create_task(send_audio())
            recv_task = asyncio.create_task(recv_audio())
            video_task = asyncio.create_task(send_video())

            try:
                while running:
                    await asyncio.sleep(0.5)
            except KeyboardInterrupt:
                pass
            finally:
                running = False
                send_task.cancel()
                recv_task.cancel()
                video_task.cancel()
                try:
                    await asyncio.gather(send_task, recv_task, video_task, return_exceptions=True)
                except:
                    pass

    except Exception as e:
        print(f"\n[ERROR] {e}")
        print("\nTroubleshooting:")
        print("  1. Check API key: echo $GEMINI_API_KEY")
        print("  2. Test internet:  ping google.com")
        print("  3. Check audio:    pactl list sources short")
        print("  4. Check profile:  pactl list cards | grep 'Active Profile'")
    finally:
        running = False
        video_in.stop()
        audio_in.stop()
        audio_out.stop()
        print("[INFO] Session ended.")


# ─── Entry Point ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    try:
        asyncio.run(run_gemini_live())
    except KeyboardInterrupt:
        running = False
        print("\n[INFO] Stopped.")
