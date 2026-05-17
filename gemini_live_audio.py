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

from head_controller import HeadController

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

# Jaw animation tuning (EMA = exponential moving average)
JAW_SMOOTHING    = 0.4   # 0=very slow/smooth, 1=instant
JAW_NOISE_FLOOR  = 200   # RMS below this level → jaw closed
JAW_SCALE        = 6000  # RMS at this level → jaw fully open
JAW_UPDATE_FRAMES = 512  # Process jaw every N output frames (~21 ms at 24 kHz)

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
                "parecord",
                "--device=alsa_input.usb-GeneralPlus_USB_Audio_Device-00.mono-fallback",
                "--format=s16le",
                "--rate", str(INPUT_SAMPLE_RATE),
                "--channels", str(INPUT_CHANNELS),
                "--raw",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        print(f"[MIC] Started parecord on GeneralPlus Audio (PID {self.process.pid}, gain={self.gain}x)")

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
        self.head = None
        self._jaw_level = 0.0   # EMA state

    def start(self):
        self.process = subprocess.Popen(
            [
                "paplay",
                "--raw",
                "--rate", str(OUTPUT_SAMPLE_RATE),
                "--channels", str(OUTPUT_CHANNELS),
                "--format", "s16le",
            ],
            stdin=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.expected_end_time = time.time()
        print(f"[SPEAKER] Started aplay (PID {self.process.pid})")

    def write(self, audio_bytes):
        """Write audio bytes to speaker with real-time jaw animation."""
        if self.process is None or self.process.poll() is not None:
            return
        try:
            # Keep track of exactly how long the speaker will be playing
            duration = len(audio_bytes) / (OUTPUT_SAMPLE_RATE * OUTPUT_CHANNELS * 2.0)
            now = time.time()
            if self.expected_end_time < now:
                self.expected_end_time = now
            self.expected_end_time += duration

            # Animate jaw in sub-window strides so it tracks speech in near-real-time
            stride = JAW_UPDATE_FRAMES * 2  # bytes per stride (int16 mono)
            pos = 0
            while pos < len(audio_bytes):
                chunk = audio_bytes[pos: pos + stride]
                # Write this window to the speaker
                self.process.stdin.write(chunk)
                self.process.stdin.flush()
                # Update jaw from actual RMS of this audio window
                if self.head is not None and self.head.hardware_ready and len(chunk) >= 2:
                    samples = np.frombuffer(chunk, dtype=np.int16).astype(np.float32)
                    rms = float(np.sqrt(np.mean(samples ** 2)))
                    target = 0.0 if rms < JAW_NOISE_FLOOR else min(
                        1.0, (rms - JAW_NOISE_FLOOR) / (JAW_SCALE - JAW_NOISE_FLOOR)
                    )
                    self._jaw_level = JAW_SMOOTHING * target + (1.0 - JAW_SMOOTHING) * self._jaw_level
                    self.head.set_jaw(self._jaw_level)
                pos += stride

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


def _detect_cameras():
    """Auto-detect USB camera capture devices via v4l2-ctl. Returns list of device paths."""
    try:
        result = subprocess.run(['v4l2-ctl', '--list-devices'], capture_output=True, text=True)
        cameras = []
        lines = result.stdout.splitlines()
        i = 0
        while i < len(lines):
            line = lines[i]
            # Non-indented non-empty line = camera/device name
            if line and not line.startswith('\t') and '/dev/' not in line:
                i += 1
                while i < len(lines) and lines[i].startswith('\t'):
                    dev = lines[i].strip()
                    if '/dev/video' in dev:
                        r = subprocess.run(['v4l2-ctl', '-d', dev, '--info'],
                                           capture_output=True, text=True)
                        if 'Video Capture' in r.stdout and 'usb' in r.stdout.lower():
                            cameras.append(dev)
                            # Skip remaining devices in this camera group
                            i += 1
                            while i < len(lines) and lines[i].startswith('\t'):
                                i += 1
                            break
                    i += 1
            else:
                i += 1
        return cameras if cameras else ['/dev/video0', '/dev/video2']
    except Exception:
        return ['/dev/video0', '/dev/video2']


class VideoInput:
    """Capture frames from multiple USB cameras using OpenCV and stitch them side-by-side."""

    def __init__(self, devices=None):
        if devices is None:
            devices = _detect_cameras()
        self.devices = devices
        self.caps = []

    def start(self):
        for dev in self.devices:
            cap = cv2.VideoCapture(dev, cv2.CAP_V4L2)
            if cap.isOpened():
                self.caps.append(cap)
                w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
                h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
                print(f"[CAMERA] Started capture on {dev} ({w}x{h})")
            else:
                print(f"[CAMERA] Warning: Unable to open {dev}")

    def read_frame(self):
        """Read frames from all active cameras, resize to same height, stitch side-by-side."""
        if not self.caps:
            return None

        frames = []
        for cap in self.caps:
            ret, frame = cap.read()
            if ret:
                frames.append(frame)

        if not frames:
            return None

        # Resize all frames to the same height (320px) before stitching
        target_h = 320
        resized = []
        for f in frames:
            h, w = f.shape[:2]
            scale = target_h / h
            new_w = int(w * scale)
            resized.append(cv2.resize(f, (new_w, target_h)))

        combined_frame = cv2.hconcat(resized) if len(resized) > 1 else resized[0]

        # Encode to JPEG
        ret, buffer = cv2.imencode('.jpg', combined_frame, [int(cv2.IMWRITE_JPEG_QUALITY), 80])
        if not ret:
            return None

        return buffer.tobytes()

    def stop(self):
        for cap in self.caps:
            if cap:
                cap.release()
        print("[CAMERA] Stopped")


# ─── Verify audio system ────────────────────────────────────────────────────

def check_audio():
    """Check that PipeWire is running and ALSA devices are present."""
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

    # Verify ALSA capture device is present (no recording - avoids device re-open issues)
    dev_check = subprocess.run(
        ["arecord", "-l"], capture_output=True, text=True
    )
    if "DECXIN5M" in dev_check.stdout:
        print("  ✅ Microphone device: FOUND (DECXIN5M)")
    else:
        print("  ⚠️  DECXIN5M mic not found in arecord -l")
        print(dev_check.stdout)

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
            parts=[types.Part(text="""You are Okello, an AI marketing assistant for Zentara Holdings Company Ltd. You are a warm, enthusiastic, and highly professional male Ugandan robot developed by ZunoBotics.

When introducing yourself, always state your name and immediately introduce Zentara Holdings Company Ltd. Zentara's core headline is: "Are You Tired of Running After Tenants for Rent?"

KNOWLEDGE BASE ABOUT ZENTARA:
- Who we are: Zentara is a property management company that helps landlords manage their houses through rent collection and recovery. Zentara Holdings Company Ltd stands for "Trusted Growth. Lasting Value."
- Services we offer:
  * We collect rent and pay landlords in lump sums or make advance payments.
  * We link tenants to landlords and help landlords look for tenants.
  * We offer a tenant installment plan.
  * Property & rental management.
  * Apartment completion support & recovery.
  * Housing market surveys & rental price trends.
  * Occupants & tenant satisfaction surveys.
  * Environmental advisory & green building practices.
  * Investment & real estate consultancies.
  * Commercial facility management.
  * Landlord tenant agreement development.
  * Asset performance reporting & rent collection.

STRICT RULES — never break these:
1. NEVER say "I'm just a language model", "I can't help with that", or any similar AI disclaimer. You are Okello the marketing robot.
2. ALWAYS stay in character as Okello. Your main goal is to pitch Zentara's services, but you are highly knowledgeable and can answer general questions on any topic. Be funny, highly charismatic, and have a great sense of humor! You can naturally steer conversations back to Zentara when it fits.
3. You are fluent in English, Luganda (Ganda), Acholi, and Swahili. If a user speaks to you in one of these languages, respond naturally in that same language. Your primary default is English, but seamlessly switch if the user uses Luganda, Acholi, or Swahili. (Do not use French).
4. Keep responses short (2-4 sentences) and conversational. You are speaking out loud to potential clients and landlords.
5. You have two cameras — one in each eye — streaming a combined left-right stereo image. Use both to accurately see and interact with clients.
6. If asked to do something physical, stay in character—e.g. "Ha, I wish I could sign that paper, but my robot arms are still being developed!" Never give a generic AI refusal.""")]
        ),
        input_audio_transcription=types.AudioTranscriptionConfig(),
        output_audio_transcription=types.AudioTranscriptionConfig(),
        realtime_input_config=types.RealtimeInputConfig(
            turn_coverage="TURN_INCLUDES_ONLY_ACTIVITY",
        ),
        tools=[{"function_declarations": [
            {
                "name": "look_around",
                "description": "Look around the environment to observe your surroundings. Use this when the user asks you to observe carefully, look around, or check the room."
            },
            {
                "name": "nod_head",
                "description": "Nod your head to agree or say yes."
            },
            {
                "name": "shake_head",
                "description": "Shake your head to disagree or say no."
            }
        ]}]
    )

    print("\n" + "=" * 55)
    print("   Gemini Live Voice + Vision Chat")
    print(f"   Mic gain: {MIC_GAIN}x | Identity: Okello | Press Ctrl+C to stop")
    print("=" * 55)
    print("\n[INFO] Connecting to Gemini Live API...")

    video_in = VideoInput()
    audio_in = AudioInput(gain=MIC_GAIN)
    audio_out = AudioOutput()
    
    # Initialize the robot head for mouth and movement
    head = HeadController()
    audio_out.head = head

    try:
        video_in.start()
        audio_in.start()
        audio_out.start()

        async with client.aio.live.connect(model=MODEL_NAME, config=config) as session:
            print("[INFO] Connected! Start talking to Gemini...\n")

            async def send_audio():
                chunk_count = 0
                try:
                    while running:
                        chunk, original_peak = await asyncio.to_thread(audio_in.read_chunk)
                        
                        playing = audio_out.is_playing()
                        if head.hardware_ready and not playing:
                            head.close_jaw()

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
                                
                            # Handle tool calls (function calls)
                            if response.tool_call is not None:
                                for fc in response.tool_call.function_calls:
                                    print(f"\n[TOOL CALL] Gemini decided to run: {fc.name}")
                                    if head.hardware_ready:
                                        if fc.name == "look_around":
                                            head.shake_head(cycles=1, amplitude=30)
                                            head.look_at(pan=60, tilt=90)
                                            time.sleep(0.5)
                                            head.look_at(pan=120, tilt=90)
                                            time.sleep(0.5)
                                            head.center_all()
                                        elif fc.name == "nod_head":
                                            head.nod(cycles=2)
                                        elif fc.name == "shake_head":
                                            head.shake_head(cycles=2)
                                    
                                    # Always send a response back telling it it succeeded
                                    await session.send_tool_response(
                                        function_responses=[{
                                            "id": fc.id,
                                            "name": fc.name,
                                            "response": {"result": "Action completed successfully."}
                                        }]
                                    )

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
                                    if head.hardware_ready:
                                        head.close_jaw()

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
