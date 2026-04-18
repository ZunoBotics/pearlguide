"""
Gemini Live Agent — bidirectional audio conversation with jaw animation.

Architecture based on the official Google Gemini Live API quickstart.
Uses asyncio.Queue pipeline to keep the event loop free for WebSocket keepalives.

  listen_audio  →  out_queue  →  send_realtime  →  Gemini session
  Gemini session  →  audio_in_queue  →  play_audio  →  speaker + jaw
"""

import audioop
import asyncio
import os
import subprocess
import traceback

import numpy as np
import pyaudio

from google import genai
from google.genai import types

from museum_knowledge import MUSEUM_SYSTEM_PROMPT, STARTUP_GREETING

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

GEMINI_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
GEMINI_VOICE = "Charon"  # Male voice. Options: Puck, Charon, Fenrir, Orus (male) | Aoede, Kore, Leda, Zephyr (female)

MIC_SAMPLE_RATE     = 16_000   # Hz — required by Gemini Live input
SPEAKER_SAMPLE_RATE = 24_000   # Hz — Gemini Live outputs at 24 kHz
MIC_CHANNELS        = 1        # Microphone is mono
FORMAT              = pyaudio.paInt16
CHUNK_SIZE          = 1024     # frames per mic read

# Jaw animation tuning
JAW_SMOOTHING   = 0.4    # EMA factor (0=slow, 1=instant)
JAW_NOISE_FLOOR = 200    # RMS below this → jaw closed
JAW_SCALE       = 6_000  # RMS at this level → jaw fully open

# Audio playback buffering — accumulate this many output frames before each write.
# Larger = smoother playback, slightly more latency (~85 ms at 48 kHz).
SPEAKER_BUFFER_FRAMES = 4096


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _find_usb_audio_device(pa: pyaudio.PyAudio, want_input: bool):
    direction_key = "maxInputChannels" if want_input else "maxOutputChannels"
    for i in range(pa.get_device_count()):
        info = pa.get_device_info_by_index(i)
        name = info.get("name", "").upper()
        if info.get(direction_key, 0) > 0 and ("USB" in name or "AUDIO" in name):
            return i
    return None


def _detect_native_rate(pa: pyaudio.PyAudio, device_index, want_input: bool) -> int:
    COMMON_RATES = [48000, 44100, 32000, 22050, 16000, 8000]
    if device_index is not None:
        info = pa.get_device_info_by_index(device_index)
        default = int(info.get("defaultSampleRate", 44100))
        if default not in COMMON_RATES:
            COMMON_RATES.insert(0, default)
        for rate in COMMON_RATES:
            try:
                if want_input:
                    pa.is_format_supported(rate, input_device=device_index,
                                           input_channels=1, input_format=pyaudio.paInt16)
                else:
                    pa.is_format_supported(rate, output_device=device_index,
                                           output_channels=1, output_format=pyaudio.paInt16)
                return rate
            except Exception:
                continue
    return 44100


def _rms_amplitude(pcm_bytes: bytes) -> float:
    if not pcm_bytes:
        return 0.0
    samples = np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32)
    return float(np.sqrt(np.mean(samples ** 2)))


def _detect_spk_channels(pa: pyaudio.PyAudio, device_index, rate: int) -> int:
    if device_index is None:
        return 1
    try:
        pa.is_format_supported(rate, output_device=device_index,
                               output_channels=2, output_format=pyaudio.paInt16)
        return 2
    except Exception:
        return 1


# ---------------------------------------------------------------------------
# Main agent class
# ---------------------------------------------------------------------------

class GeminiLiveAgent:
    def __init__(self, api_key=None, head_controller=None):
        self._api_key = api_key or os.environ.get("GEMINI_API_KEY", "")
        self._head    = head_controller
        self._running = False
        self._jaw_level = 0.0
        self._rs_mic = None
        self._rs_spk = None
        self._speaking = False   # True while speaker is playing → suppress mic

        self._pa            = pyaudio.PyAudio()
        self._input_device  = _find_usb_audio_device(self._pa, want_input=True)
        self._output_device = _find_usb_audio_device(self._pa, want_input=False)
        self._mic_native_rate = _detect_native_rate(self._pa, self._input_device, want_input=True)
        self._spk_native_rate = _detect_native_rate(self._pa, self._output_device, want_input=False)
        self._spk_channels    = _detect_spk_channels(self._pa, self._output_device, self._spk_native_rate)

        if self._input_device is None:
            print("[GeminiLiveAgent] WARNING: no USB mic found; using system default.")
        else:
            info = self._pa.get_device_info_by_index(self._input_device)
            print(f"[GeminiLiveAgent] Microphone : [{self._input_device}] {info['name']} @ {self._mic_native_rate} Hz")

        if self._output_device is None:
            print("[GeminiLiveAgent] WARNING: no USB speaker found; using system default.")
        else:
            info = self._pa.get_device_info_by_index(self._output_device)
            print(f"[GeminiLiveAgent] Speaker    : [{self._output_device}] {info['name']} @ {self._spk_native_rate} Hz ch={self._spk_channels}")

        self._unmute_speaker()

    # ------------------------------------------------------------------
    # ALSA speaker unmute
    # ------------------------------------------------------------------

    def _unmute_speaker(self):
        """Detect USB audio ALSA card and unmute all output channels automatically."""
        card = 1  # fallback default
        try:
            result = subprocess.run(["aplay", "-l"], capture_output=True, text=True, timeout=3)
            for line in result.stdout.splitlines():
                if "USB" in line.upper():
                    # Line format: "card N: NAME [NAME], device M: ..."
                    parts = line.strip().split()
                    if parts and parts[0] == "card":
                        card = int(parts[1].rstrip(":"))
                        break
        except Exception:
            pass

        for ctl in ("Speaker", "PCM", "Headphone", "Master"):
            try:
                subprocess.run(
                    ["amixer", "-c", str(card), "sset", ctl, "100%", "unmute"],
                    capture_output=True, timeout=3,
                )
            except Exception:
                pass
        print(f"[GeminiLiveAgent] Unmuted ALSA card {card} output channels.")

    # ------------------------------------------------------------------
    # Jaw
    # ------------------------------------------------------------------

    def _update_jaw(self, pcm_bytes: bytes):
        if self._head is None:
            return
        rms = _rms_amplitude(pcm_bytes)
        target = 0.0 if rms < JAW_NOISE_FLOOR else min(1.0, (rms - JAW_NOISE_FLOOR) / (JAW_SCALE - JAW_NOISE_FLOOR))
        self._jaw_level = JAW_SMOOTHING * target + (1 - JAW_SMOOTHING) * self._jaw_level
        self._head.set_jaw(self._jaw_level)

    def _close_jaw(self):
        if self._head is not None:
            self._jaw_level = 0.0
            self._head.close_jaw()

    # ------------------------------------------------------------------
    # Pipeline tasks
    # ------------------------------------------------------------------

    async def _listen_audio(self, mic_stream, out_queue):
        """Read mic → resample to 16 kHz → push to out_queue.

        Drops mic data while the speaker is playing (half-duplex echo
        suppression). This prevents Gemini from hearing its own voice
        and triggering an infinite self-answer loop.
        """
        while self._running:
            try:
                data = await asyncio.to_thread(mic_stream.read, CHUNK_SIZE, False)
                if self._speaking:
                    # Discard — speaker output would echo back into mic
                    continue
                if self._mic_native_rate != MIC_SAMPLE_RATE:
                    data, self._rs_mic = audioop.ratecv(
                        data, 2, 1, self._mic_native_rate, MIC_SAMPLE_RATE, self._rs_mic
                    )
                await out_queue.put({"data": data, "mime_type": f"audio/pcm;rate={MIC_SAMPLE_RATE}"})
            except asyncio.CancelledError:
                break
            except Exception as exc:
                print(f"[GeminiLiveAgent] Mic error: {exc}")
                break

    async def _send_realtime(self, session, out_queue):
        """Drain out_queue and send each item to Gemini."""
        while self._running:
            try:
                msg = await out_queue.get()
                await session.send(input=msg)
            except asyncio.CancelledError:
                break
            except Exception as exc:
                print(f"[GeminiLiveAgent] Send error: {exc}")
                break

    async def _receive_audio(self, session, audio_in_queue):
        """Receive responses from Gemini and push audio chunks to audio_in_queue."""
        try:
            while self._running:
                turn = session.receive()
                async for response in turn:
                    if data := response.data:
                        await audio_in_queue.put(data)
                    elif text := response.text:
                        print(f"[Okello] {text}", end="", flush=True)

                # Turn complete — send sentinel so _play_audio flushes its buffer
                # and closes the jaw. Do NOT flush the queue here; that discards
                # audio that hasn't been played yet and causes mid-sentence cutoffs.
                await audio_in_queue.put(None)

        except asyncio.CancelledError:
            pass
        except Exception as exc:
            print(f"[GeminiLiveAgent] Receive error: {exc}")

    async def _play_audio(self, spk_stream, audio_in_queue):
        """Drain audio_in_queue, resample, upmix, animate jaw, write to speaker.

        Audio from Gemini arrives in many tiny chunks. Writing each chunk
        individually causes buffer gaps and crackling. Instead we accumulate
        chunks into a buffer and flush only when we have SPEAKER_BUFFER_FRAMES
        frames worth of data, or when a turn-end sentinel (None) is received.
        """
        min_bytes = SPEAKER_BUFFER_FRAMES * 2 * self._spk_channels  # frames * 2 bytes * ch
        buf = bytearray()
        rms_accum = []  # collect RMS values across chunks; update jaw once per flush

        async def _flush(buf, rms_accum):
            if buf:
                # Update jaw position once per flush — reduces servo thrashing and
                # the electrical noise it induces on the USB audio power rail.
                if rms_accum:
                    avg_rms = float(np.mean(rms_accum))
                    rms_accum.clear()
                    if self._head is not None:
                        target = 0.0 if avg_rms < JAW_NOISE_FLOOR else min(1.0, (avg_rms - JAW_NOISE_FLOOR) / (JAW_SCALE - JAW_NOISE_FLOOR))
                        self._jaw_level = JAW_SMOOTHING * target + (1 - JAW_SMOOTHING) * self._jaw_level
                        self._head.set_jaw(self._jaw_level)
                self._speaking = True
                try:
                    await asyncio.to_thread(spk_stream.write, bytes(buf))
                except OSError as exc:
                    print(f"[GeminiLiveAgent] Speaker write error (skipping): {exc}")
                finally:
                    self._speaking = False
                buf.clear()

        try:
            while self._running:
                audio_bytes = await audio_in_queue.get()

                # Sentinel from _receive_audio signals end of turn
                if audio_bytes is None:
                    await _flush(buf, rms_accum)
                    self._close_jaw()
                    continue

                # Accumulate RMS for jaw animation (computed after resampling below)
                if self._spk_native_rate != SPEAKER_SAMPLE_RATE:
                    audio_bytes, self._rs_spk = audioop.ratecv(
                        audio_bytes, 2, 1, SPEAKER_SAMPLE_RATE, self._spk_native_rate, self._rs_spk
                    )

                if len(audio_bytes) % 2 != 0:
                    audio_bytes = audio_bytes[:-1]

                rms_accum.append(_rms_amplitude(audio_bytes))

                if self._spk_channels == 2:
                    audio_bytes = audioop.tostereo(audio_bytes, 2, 1, 1)

                buf.extend(audio_bytes)

                if len(buf) >= min_bytes:
                    await _flush(buf, rms_accum)

        except asyncio.CancelledError:
            pass
        finally:
            await _flush(buf, rms_accum)
            self._close_jaw()

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    async def run(self):
        if not self._api_key:
            raise ValueError("GEMINI_API_KEY not set.")

        client = genai.Client(
            api_key=self._api_key,
            http_options={"api_version": "v1beta"},
        )

        live_config = types.LiveConnectConfig(
            response_modalities=["AUDIO"],
            speech_config=types.SpeechConfig(
                voice_config=types.VoiceConfig(
                    prebuilt_voice_config=types.PrebuiltVoiceConfig(voice_name=GEMINI_VOICE)
                )
            ),
            system_instruction=types.Content(
                parts=[types.Part(text=MUSEUM_SYSTEM_PROMPT)]
            ),
        )

        mic_stream = await asyncio.to_thread(
            self._pa.open,
            format=FORMAT, channels=MIC_CHANNELS, rate=self._mic_native_rate,
            input=True, input_device_index=self._input_device, frames_per_buffer=CHUNK_SIZE,
        )
        spk_stream = await asyncio.to_thread(
            self._pa.open,
            format=FORMAT, channels=self._spk_channels, rate=self._spk_native_rate,
            output=True, output_device_index=self._output_device,
            frames_per_buffer=SPEAKER_BUFFER_FRAMES,
        )

        self._running = True
        self._rs_mic  = None
        self._rs_spk  = None
        print(f"[GeminiLiveAgent] Connecting to {GEMINI_MODEL} ...")

        try:
            async with client.aio.live.connect(model=GEMINI_MODEL, config=live_config) as session:
                audio_in_queue = asyncio.Queue()
                out_queue      = asyncio.Queue(maxsize=10)

                print("[GeminiLiveAgent] Session open. Say something!")
                await session.send(input=STARTUP_GREETING, end_of_turn=True)

                tasks = [
                    asyncio.create_task(self._listen_audio(mic_stream, out_queue)),
                    asyncio.create_task(self._send_realtime(session, out_queue)),
                    asyncio.create_task(self._receive_audio(session, audio_in_queue)),
                    asyncio.create_task(self._play_audio(spk_stream, audio_in_queue)),
                ]

                # Wait until any task finishes (error or cancellation)
                done, pending = await asyncio.wait(
                    tasks, return_when=asyncio.FIRST_COMPLETED
                )
                for task in pending:
                    task.cancel()
                    try:
                        await task
                    except asyncio.CancelledError:
                        pass
                # Re-raise any exception from completed tasks
                for task in done:
                    if task.exception():
                        raise task.exception()

        except asyncio.CancelledError:
            pass
        except Exception as exc:
            print(f"[GeminiLiveAgent] Error: {exc}")
        finally:
            self._running = False
            self._close_jaw()
            mic_stream.stop_stream()
            mic_stream.close()
            spk_stream.stop_stream()
            spk_stream.close()
            self._pa.terminate()
            print("\n[GeminiLiveAgent] Session closed.")

    def stop(self):
        self._running = False
