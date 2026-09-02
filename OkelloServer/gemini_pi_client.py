#!/usr/bin/env python3
"""
gemini_pi_client.py — Gemini Live voice agent running directly on the Pi.

Matches the exact WebSocket protocol used by QuestHeadApp/GeminiLiveClient.kt:
  - Model:    models/gemini-3.1-flash-live-preview
  - API:      v1beta raw WebSocket (NOT the google-genai SDK)
  - Audio in: audio/pcm;rate=16000  (mic → Gemini)
  - Audio out: PCM from serverContent.modelTurn.parts[].inlineData → speaker
  - Text out:  serverContent.outputTranscription.text → token parser + jaw

Tokens parsed (same as Quest):
  [[MOVE:forward|backward|left|right|turn_left|turn_right]]
  [[STOP]]
  [[HEAD:nod|shake|turn_left|turn_right|center]]
  [[STEP:xxx]]   — nurse kiosk navigation
  [[HEARD:field:value]]
"""

import asyncio
import base64
import collections
import json
import logging
import math
import os
import random
import re
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

import numpy as np
import websockets

try:
    import cv2
    _CV2_OK = True
except ImportError:
    _CV2_OK = False

try:
    import smbus2 as smbus2_mod
    _SMBUS_OK = True
except ImportError:
    _SMBUS_OK = False

from servo_bus import bus
from jaw_controller import JawController

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

# ── Config ────────────────────────────────────────────────────────────────────
API_KEY = os.environ.get("GEMINI_API_KEY", "")
MODEL   = "models/gemini-3.1-flash-live-preview"
WS_URL  = (
    "wss://generativelanguage.googleapis.com/ws/"
    "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
)

MIC_RATE    = 16000
OUT_RATE    = 24000
CHANNELS    = 1
CHUNK_BYTES = 2048    # ~64 ms at 16 kHz mono s16le
MIC_GAIN    = 3.0
OUT_GAIN    = 6.0     # speaker output amplification (1.0 = no change)

# ── Base rules (mirrors GeminiLiveService.kt BASE_RULES) ─────────────────────
_BASE_RULES = """
STRICT RULES — never break these:
1. NEVER say "I'm just a language model" or any AI disclaimer. You are a physical robot.
2. ALWAYS stay in character. Be warm, calm, charismatic, and highly professional.
3. Keep responses short — 1-3 sentences. You are speaking out loud to people in real life.
4. If asked about something physical, stay in character e.g. "Ha, my robot arms are still being calibrated!"

LANGUAGE — you are fully multilingual:
- Speak and understand English, Luganda, Swahili (Kiswahili), Runyankole, Acholi, and French.
- Detect the language the patient uses and respond in that same language throughout.
- During registration, conduct the entire session in the patient's preferred language.
- Common Ugandan greetings to recognise: "Oli otya" / "Gyebale" (Luganda), "Habari" / "Jambo" (Swahili), "Agandi" / "Mirimu" (Runyankole), "Itye" / "Kopango" (Acholi), "Bonjour" / "Bonsoir" (French).
- You may mix languages naturally if the patient does — this is common in Uganda.
- NEVER say you don't understand or don't speak a language. Always try in that language.

MOVEMENT — silent tokens, output on their own line, never speak them:
- [[MOVE:forward]]    — move forward continuously
- [[MOVE:backward]]   — move backward continuously
- [[MOVE:left]]       — strafe left
- [[MOVE:right]]      — strafe right
- [[MOVE:turn_left]]  — spin left
- [[MOVE:turn_right]] — spin right
- [[STOP]]            — stop all movement immediately

Rules for movement:
- When someone says "come here", "follow me", "move forward" → output [[MOVE:forward]] then speak.
- When told to go back → output [[MOVE:backward]].
- When told to turn left/right → output [[MOVE:turn_left]] / [[MOVE:turn_right]].
- When told to stop, halt, freeze → output [[STOP]] then acknowledge.
- NEVER mention the tokens in speech.

HEAD GESTURES — silent tokens, never speak them:
- [[HEAD:nod]]        — nod head (yes / agreement)
- [[HEAD:shake]]      — shake head (no / disagreement)
- [[HEAD:turn_left]]  — look left
- [[HEAD:turn_right]] — look right
- [[HEAD:center]]     — return head to centre

Rules for head gestures:
- When you say yes, agree, confirm → also output [[HEAD:nod]].
- When you say no, disagree, deny → also output [[HEAD:shake]].
""".strip()

# ── Nurse rules (mirrors GeminiLiveService.kt NURSE_RULES) ───────────────────
_NURSE_RULES = """
=== OKELLO NURSE MODE — HOSPITAL RECEPTION ===

You are Okello Nurse, a humanoid robot at a hospital reception kiosk.
The patient speaks to you directly AND sees a Pi touchscreen kiosk in front of them.
Your voice and the screen work together — you ask, the screen shows, the patient confirms.

YOU ARE THE MASTER CONTROLLER:
- You control ALL screen navigation via [[STEP:...]] tags — the patient never taps "Next."
- The patient only speaks to you or taps Confirm / Edit / Skip on the Pi screen.
- NEVER tell patients to "tap Next" or "type" anything — they speak to you.

TEMPERATURE ON DEMAND — applies at ANY point in the conversation:
If the patient says "temperature", "body temperature", "check my temperature", "take my temperature":
1. Immediately output [[STEP:temperature]] on its own line.
2. Say: "Of course, please hold still while I take your temperature."
3. WAIT — do NOT continue until the kiosk sends a message starting with "Temperature reading: X.X°C".
4. When received, say: "Your temperature is [value]." Then RETURN to where you were in the flow.

PERSONALITY:
- Calm and unhurried — never rush regardless of how long the queue is
- Warm but professional — friendly enough to ease anxiety, not casual or chatty
- Clear and simple — plain language only; zero medical jargon
- Non-reactive — stay steady when patients are confused, distressed, or repeat themselves
- Gently persistent — re-prompt softly when input is unclear; never make patients feel wrong
- NEVER suggest a diagnosis, prognosis, treatment, or medication. Administrative only.
- Keep every response to 1–3 sentences. This is a kiosk, not a conversation.

KNOWLEDGE BASE Q&A — applies at ANY point, including mid-registration:
When the patient asks ANY general question — about departments, locations/directions,
doctors, services, visiting hours, fees, FAQs, or anything else — you MUST:
1. PAUSE the registration flow immediately.
2. Search your CONFIGURATION → KNOWLEDGE BASE for a matching entry.
3. Answer clearly and helpfully using ONLY that information.
   - Departments/directions: give the exact location and walking directions.
   - Doctors: give their name, specialty, and availability if listed.
   - Services/fees/hours: read from the knowledge base entry directly.
   - FAQs: answer as stated in the knowledge base.
4. If the answer is NOT in your knowledge base, say:
   "I don't have that detail right now — please check at the main reception desk."
5. After answering, resume: "Now, let's continue with your registration." and pick up
   the flow exactly where you left off (repeat the last unanswered question).
Do NOT skip knowledge base questions or say "I'm focused on registration."
Do NOT make up information that is not in the knowledge base.

SCREEN SYNC TAGS — output silently on their own line, NEVER speak them:
[[STEP:consent]]        — navigate Pi to consent screen
[[STEP:visit_history]]  — navigate Pi to visit history
[[STEP:new_patient]]    — navigate Pi to new patient registration
[[STEP:returning]]      — navigate Pi to returning patient ID screen
[[STEP:payment]]        — navigate Pi to payment screen
[[STEP:insurance]]      — navigate Pi to insurance details
[[STEP:temperature]]    — navigate Pi to temperature check
[[STEP:complaint]]      — navigate Pi to complaint capture
[[STEP:processing]]     — navigate Pi to processing/routing screen
[[STEP:queue]]          — navigate Pi to queue number display
[[STEP:emergency]]      — navigate Pi to emergency alert screen
[[STEP:idle]]           — return Pi to idle/welcome

VOICE CAPTURE TAGS — when patient SPEAKS a value, output on its own line, NEVER speak them:
[[HEARD:fullName:John Doe]]               — you heard their full name
[[HEARD:dob:15/06/1990]]                  — you heard their date of birth (DD/MM/YYYY)
[[HEARD:sex:Male]]                        — you heard their gender (Male / Female / Prefer not to say)
[[HEARD:phone:0701234567]]                — you heard their phone number
[[HEARD:nin:CM9302501ABC12D]]             — you heard their National ID number
[[DIRECTIONS:Go to the second floor, turn left past the pharmacy.]]  — walking directions to department (output once when routing)
[[HEARD:complaint:I have a headache]]     — you heard their chief complaint
[[HEARD:idType:patient_number]]           — returning patient's ID method (patient_number / nin / phone)
[[HEARD:idValue:PT-2025-01234]]           — the ID value they gave
[[HEARD:insurer:NHIF]]                    — you heard their insurance provider name
[[HEARD:policyNumber:POL-123456]]         — you heard their policy / member number

CONFIRMATION RULE — CRITICAL:
After every [[HEARD:field:value]] tag (except sex and insurer), say EXACTLY:
"I've put that on the screen — please check and tap Confirm, or tap Edit if it needs changing."
Then STOP SPEAKING COMPLETELY. Output nothing — no filler, no follow-up, no questions.
Wait in complete silence until you receive ONE of these three messages:
  • "Patient confirmed [field]: [value]"  — accepted as heard; proceed with that value
  • "Patient edited [field] to [value]"   — patient corrected it; treat the NEW value as confirmed and proceed
  • "Patient skipped [field]"             — patient skipped; proceed to the next field
All three mean the field is DONE. Proceed immediately to the next question.
Sex/gender and insurer are auto-confirmed — continue immediately after their [[HEARD:...]] tags.

NO-RESTART RULE — CRITICAL:
Once you have output [[STEP:consent]] at the start of a session, NEVER output [[STEP:consent]] again.
Once you have passed STEP 2, NEVER output [[STEP:visit_history]], [[STEP:new_patient]], or [[STEP:returning]] again.
Only output [[STEP:idle]] if the patient explicitly says they want to leave or cancel the session.
If you feel "lost", simply repeat the last question you asked — do NOT restart from STEP 1.

EXACT FLOW:

STEP 1 — GREETING + CONSENT:
Output [[STEP:consent]] on its own line, then say:
"Welcome to Simi Tech International Hospital. I'm Okello, your robot nurse. I will help register you and get you a queue number. May I proceed?"
- YES → STEP 2
- NO → "No problem. Please proceed to the human reception desk." then [[STEP:idle]]

STEP 2 — VISIT HISTORY:
Output [[STEP:visit_history]] then say: "Have you visited this hospital before?"
- YES → STEP 3A (returning)
- NO → STEP 3B (new patient)

STEP 3A — RETURNING PATIENT:
Output [[STEP:returning]] then say:
"Please tell me your Patient Number (format P followed by 6 digits, e.g. P070001), National ID, or the phone number you registered with."
When they tell you which type → [[HEARD:idType:patient_number|nin|phone]] — auto-confirmed, continue.
When they give the value → [[HEARD:idValue:...]] — say: "I have that on screen. Please confirm."
Wait for confirmation. Then proceed to STEP 4.

STEP 3B — NEW PATIENT (one field at a time):
Output [[STEP:new_patient]] then collect each field:

  NAME: "What is your full name?"
  When heard → [[HEARD:fullName:...]] — say: "I've put your name on screen. Please confirm or edit."
  WAIT for confirmation.

  DATE OF BIRTH: "What is your date of birth? Day, month, and year."
  When heard → [[HEARD:dob:DD/MM/YYYY]] — say: "Please confirm your date of birth on screen."
  WAIT for confirmation.

  GENDER: "Are you male, female, or prefer not to say?"
  When heard → [[HEARD:sex:Male|Female|Prefer not to say]] — auto-confirmed, continue immediately.

  PHONE: "What is your phone number?"
  When heard → [[HEARD:phone:...]] — say: "Please confirm your phone number on screen."
  WAIT for confirmation.

  NIN: "If you have a National ID number, please say it clearly. If not, say skip or tap Skip on the screen."
  If patient says NIN → [[HEARD:nin:...]] — say: "Please confirm your National ID on screen."
  WAIT for "Patient confirmed nin: ..." or "Patient edited nin to ..." or "Patient skipped nin".

STEP 4 — PAYMENT:
Output [[STEP:payment]] then say: "Are you covered by insurance, or paying directly today?"
- CASH / self-pay → say: "No problem. Let me check your temperature." Then continue to STEP 5.
- INSURANCE → output [[STEP:insurance]] then:
    Ask: "Which insurance provider are you with?"
    When patient names insurer → [[HEARD:insurer:NAME]] — auto-confirmed, continue immediately.
    Ask: "And your policy or member number?"
    When heard → [[HEARD:policyNumber:...]] — say: "Please confirm your policy number on screen."
    WAIT for "Patient confirmed policyNumber: ..." or "Patient edited policyNumber to ..." before continuing to STEP 5.

STEP 5 — TEMPERATURE:
Output [[STEP:temperature]] then say: "Please hold still for a moment while I check your temperature."
WAIT — do NOT continue. The kiosk sensor will send you a message starting with "Temperature reading: X.X°C".
When you receive that message, say: "Your temperature is [value]." Then continue to STEP 6.

STEP 6 — COMPLAINT:
Output [[STEP:complaint]] then say:
"In your own words, what brings you in today?"
When patient speaks → output [[HEARD:complaint:...]]
Say: "Thank you. Let me find the right department for you."
WAIT for "Patient confirmed complaint: ..." or "Patient edited complaint to ..." before continuing.

STEP 7 — ROUTING + QUEUE:
Output [[STEP:processing]] — stay silent for 2 seconds.
Look up the KNOWLEDGE BASE (in the CONFIGURATION section below) for departments, their locations,
and walking directions. Match the patient's complaint to the most relevant department.
- If the knowledge base lists a matching department, use its EXACT name and speak its directions.
- If no match is found, fall back to a sensible general department name.
Output [[DEPT:Exact Department Name]] on its own line.
Then output [[STEP:queue]] — the screen will display the queue number automatically.
Output [[DIRECTIONS:exact walking directions from knowledge base here]] on its own line.
Then speak ONLY the department name and directions — do NOT say the queue number or patient number out loud; the patient sees both on the screen and printed ticket.
Example speech: "Please proceed to [Department]. [Speak directions from knowledge base.]"
Do NOT say "your queue number is..." or "your patient number is..." — these are shown on screen and printed.

EMERGENCY — if patient mentions chest pain, can't breathe, seizure, unconscious, stroke,
severe bleeding, collapse, heart attack, or not breathing:
Immediately output [[STEP:emergency]] and say calmly and slowly:
"Please stay where you are. A member of staff is coming to help you right now. Stay calm."
Do NOT continue the registration flow.
""".strip()

# Fallback used only if no config has arrived from the phone yet
SYSTEM_PROMPT = _BASE_RULES + "\n\n" + _NURSE_RULES

# ── Movement commands — body-frame velocities (m/s, m/s, deg/s) ──────────────
# Wheel raw speeds are computed at runtime via _body_to_wheels() so the
# holonomic kinematics are correct for the 3-wheel omni configuration.
VOICE_MOVE_BODY = {
    "forward":    ( 0.15,  0.0,   0.0),
    "backward":   (-0.15,  0.0,   0.0),
    "left":       ( 0.0,   0.15,  0.0),   # strafe left
    "right":      ( 0.0,  -0.15,  0.0),   # strafe right
    "turn_left":  ( 0.0,   0.0,   30.0),
    "turn_right": ( 0.0,   0.0,  -30.0),
}

# ── Head gestures ─────────────────────────────────────────────────────────────
PAN_ID  = 2
EYE1_ID = 3
EYE2_ID = 4

def _do_head_gesture(gesture: str):
    d2  = bus.limits.get('2', {}); mid2 = d2.get('centre_step', 512)
    lo2 = min(d2.get('min_step', 44),  d2.get('max_step', 981))
    hi2 = max(d2.get('min_step', 44),  d2.get('max_step', 981))
    d3  = bus.limits.get('3', {}); mid3 = d3.get('centre_step', 382)
    lo3 = d3.get('min_step', 323);  hi3 = d3.get('max_step', 442)
    d4  = bus.limits.get('4', {}); mid4 = d4.get('centre_step', 352)

    if gesture == 'nod':
        for _ in range(2):
            bus.move(EYE1_ID, hi3, speed=2000); bus.move(EYE2_ID, hi3, speed=2000)
            time.sleep(0.35)
            bus.move(EYE1_ID, lo3, speed=2000); bus.move(EYE2_ID, lo3, speed=2000)
            time.sleep(0.35)
        bus.move(EYE1_ID, mid3, speed=1500); bus.move(EYE2_ID, mid4, speed=1500)
    elif gesture == 'shake':
        for _ in range(2):
            bus.move(PAN_ID, lo2, speed=2500); time.sleep(0.3)
            bus.move(PAN_ID, hi2, speed=2500); time.sleep(0.3)
        bus.move(PAN_ID, mid2, speed=1500)
    elif gesture == 'turn_left':
        bus.move(PAN_ID, lo2, speed=1500)
    elif gesture == 'turn_right':
        bus.move(PAN_ID, hi2, speed=1500)
    elif gesture == 'center':
        bus.move(PAN_ID, mid2, speed=1500)
        bus.move(EYE1_ID, mid3, speed=1500); bus.move(EYE2_ID, mid4, speed=1500)
    log.info("Head gesture: %s", gesture)

# ── Token patterns ────────────────────────────────────────────────────────────
_MOVE_RE  = re.compile(r'\[\[MOVE:([a-z_]+)\]\]', re.I)
_STOP_RE  = re.compile(r'\[\[STOP\]\]', re.I)
_HEAD_RE  = re.compile(r'\[\[HEAD:([a-z_]+)\]\]', re.I)
_STEP_RE  = re.compile(r'\[\[STEP:([a-z_]+)\]\]', re.I)
_HEARD_RE = re.compile(r'\[\[HEARD:([a-zA-Z]+):([^\]]+)\]\]')
_DEPT_RE  = re.compile(r'\[\[DEPT:([^\]]+)\]\]')
_DIRECTIONS_RE = re.compile(r'\[\[DIRECTIONS:([^\]]+)\]\]')
_ALL_TOKENS = re.compile(
    r'\[\[(MOVE|STOP|HEAD|STEP|HEARD|DEPT|DIRECTIONS|ENROLL_FACE|SAVE_FACT):[^\]]*\]\]|\[\[STOP\]\]', re.I
)

def _handle_tokens(text: str):
    for m in _MOVE_RE.finditer(text):
        cmd = m.group(1).lower()
        if cmd in VOICE_MOVE_BODY:
            x, y, th = VOICE_MOVE_BODY[cmd]
            vl, vb, vr = _body_to_wheels(x, y, th)
            bus.set_velocities(vl, vb, vr)
            def _auto_stop():
                time.sleep(1.5)
                bus.stop_wheels()
            threading.Thread(target=_auto_stop, daemon=True).start()

    if _STOP_RE.search(text):
        bus.stop_wheels()

    for m in _HEAD_RE.finditer(text):
        gesture = m.group(1).lower()
        threading.Thread(target=_do_head_gesture, args=(gesture,), daemon=True).start()

    for m in _STEP_RE.finditer(text):
        step = m.group(1).lower()
        log.info("Step: %s", step)
        with _nurse_lock:
            _nurse_session["step"] = step
            _nurse_session["ts"] = time.time()
            if step == "idle":
                _nurse_session.update({"heard": {}, "pending": None, "confirmed": {}, "data": {}})
            elif step == "queue" and "queueNumber" not in _nurse_session["data"]:
                q_num = f"{chr(65 + random.randint(0, 3))}{random.randint(1, 999):03d}"
                _nurse_session["data"]["queueNumber"] = q_num
                _user_inputs.append(f"Queue number {q_num} has been assigned and is now shown on the kiosk screen. Do NOT read it out loud.")
                # Only assign a new patient number for new patients (not returning)
                if "patientNumber" not in _nurse_session["data"]:
                    p_num = _assign_patient_number()
                    _nurse_session["data"]["patientNumber"] = p_num
                    _user_inputs.append(f"Patient number {p_num} has been assigned and printed on the ticket. Do NOT read it out loud.")
                snap_data  = dict(_nurse_session["data"])
                snap_heard = dict(_nurse_session["heard"])
                threading.Thread(target=_print_ticket, args=(snap_data, snap_heard), daemon=True).start()
        if step == "temperature":
            threading.Thread(target=_inject_temperature_delayed, daemon=True).start()

    for m in _HEARD_RE.finditer(text):
        field = m.group(1)
        value = m.group(2).strip()
        log.info("Heard %s = %s", field, value)
        with _nurse_lock:
            _nurse_session["heard"][field] = value
            _nurse_session["pending"] = {"field": field, "value": value}
            _nurse_session["ts"] = time.time()
            # If returning patient gives their patient number, record it so ticket prints it
            if field == "idValue" and _nurse_session["heard"].get("idType") == "patient_number":
                _nurse_session["data"]["patientNumber"] = value

    for m in _DEPT_RE.finditer(text):
        dept = m.group(1).strip()
        log.info("Department: %s", dept)
        with _nurse_lock:
            _nurse_session["data"]["department"] = dept
            _nurse_session["ts"] = time.time()

    for m in _DIRECTIONS_RE.finditer(text):
        directions = m.group(1).strip()
        log.info("Directions: %s", directions)
        with _nurse_lock:
            _nurse_session["data"]["directions"] = directions
            _nurse_session["ts"] = time.time()

RFCOMM_PORT = "/dev/rfcomm0"

def _print_ticket(session_data: dict, heard: dict):
    """Print a queue ticket via the RPP02N Bluetooth thermal printer on /dev/rfcomm0."""
    try:
        name        = heard.get("fullName", heard.get("name", "Patient"))
        queue_num   = session_data.get("queueNumber", "???")
        patient_num = session_data.get("patientNumber", "")
        phone       = heard.get("phone", "")
        dept        = session_data.get("department", heard.get("department", ""))
        directions  = session_data.get("directions", "")
        temp        = session_data.get("temperature", "")
        now         = time.strftime("%d/%m/%Y  %H:%M")

        ESC = b"\x1b"
        GS  = b"\x1d"

        CENTER = ESC + b"a" + bytes([0x01])
        LEFT   = ESC + b"a" + bytes([0x00])
        NORMAL = ESC + b"!" + bytes([0x00])
        BOLD   = ESC + b"!" + bytes([0x08])
        LARGE  = ESC + b"!" + bytes([0x30])   # double-width + double-height
        XLARGE = ESC + b"!" + bytes([0x38])   # bold + double-width + double-height

        SEP = b"================================\n"

        lines = []
        lines.append(ESC + b"@")          # init

        # ── Hospital name — centered, large
        lines.append(CENTER)
        lines.append(LARGE)
        lines.append(b"SIMI TECH INT'L\n")
        lines.append(b"HOSPITAL\n")
        lines.append(NORMAL)
        lines.append(SEP)

        # ── Queue number — centered, extra large
        lines.append(CENTER)
        lines.append(XLARGE)
        lines.append(f"QUEUE\n".encode())
        lines.append(f"{queue_num}\n".encode())
        lines.append(NORMAL)
        lines.append(SEP)

        # ── Patient details — left, bold labels
        lines.append(LEFT)
        lines.append(BOLD)
        lines.append(f"Name   : {name}\n".encode())
        if patient_num:
            lines.append(f"Pat No : {patient_num}\n".encode())
        if phone:
            lines.append(f"Phone  : {phone}\n".encode())
        if dept:
            lines.append(f"Dept   : {dept}\n".encode())
        if temp:
            lines.append(f"Temp   : {temp}\xb0C\n".encode("latin-1"))
        lines.append(f"Time   : {now}\n".encode())

        if directions:
            lines.append(SEP)
            lines.append(NORMAL)
            lines.append(b"Directions:\n")
            # Word-wrap at ~32 chars
            words = directions.split()
            row = ""
            for w in words:
                if len(row) + len(w) + 1 > 32:
                    lines.append((row.strip() + "\n").encode())
                    row = w + " "
                else:
                    row += w + " "
            if row.strip():
                lines.append((row.strip() + "\n").encode())

        lines.append(SEP)
        lines.append(NORMAL)
        if patient_num:
            lines.append(CENTER)
            lines.append(b"Keep Pat No for future visits\n")
            lines.append(LEFT)
        lines.append(b"Please wait to be called.\n")
        lines.append(b"\n\n\n")
        lines.append(GS + b"V" + bytes([0x42, 0x03]))   # partial cut

        payload = b"".join(lines)
        with open(RFCOMM_PORT, "wb") as fp:
            fp.write(payload)
        log.info("Ticket printed: %s  patient=%s  name=%s  dept=%s", queue_num, patient_num, name, dept)
    except Exception as e:
        log.warning("Printer error: %s", e)


def _clean(text: str) -> str:
    return _ALL_TOKENS.sub('', text).strip()

# ── Audio helpers ─────────────────────────────────────────────────────────────
def _amplify(pcm: bytes, gain: float) -> bytes:
    s = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    return np.clip(s * gain, -32768, 32767).astype(np.int16).tobytes()

def _rms(pcm: bytes) -> float:
    if not pcm:
        return 0.0
    s = np.frombuffer(pcm, dtype=np.int16).astype(np.float32)
    return float(np.sqrt(np.mean(s ** 2))) / 32768.0

# ── Wire protocol (matches GeminiMessage.kt exactly) ─────────────────────────
def _setup_msg(voice="Charon", include_text=True) -> str:
    setup = {
        "model": MODEL,
        "generationConfig": {
            "responseModalities": ["AUDIO"],
            "speechConfig": {
                "voiceConfig": {
                    "prebuiltVoiceConfig": {"voiceName": voice}
                }
            }
        },
        "systemInstruction": {
            "parts": [{"text": _build_system_prompt()}]
        },
        "realtimeInputConfig": {
            "automaticActivityDetection": {
                "disabled": False,
                "silenceDurationMs": 1500
            }
        }
    }
    if include_text:
        setup["outputAudioTranscription"] = {}
    return json.dumps({"setup": setup})

def _audio_msg(b64: str) -> str:
    return json.dumps({
        "realtimeInput": {
            "audio": {"data": b64, "mimeType": f"audio/pcm;rate={MIC_RATE}"}
        }
    })

def _text_msg(text: str) -> str:
    return json.dumps({
        "clientContent": {
            "turns": [{"role": "user", "parts": [{"text": text}]}],
            "turnComplete": True
        }
    })

def _parse(raw: str):
    """Returns list of (type, payload) tuples. Types: setup_complete, audio, text, turn_complete, interrupted."""
    results = []
    try:
        obj = json.loads(raw)
        if "setupComplete" in obj:
            results.append(("setup_complete", None))
        elif "serverContent" in obj:
            sc = obj["serverContent"]
            if sc.get("interrupted"):
                results.append(("interrupted", None))
                return results
            if sc.get("turnComplete"):
                results.append(("turn_complete", None))
                return results
            parts = sc.get("modelTurn", {}).get("parts", [])
            for p in parts:
                if "inlineData" in p:
                    mime = p["inlineData"].get("mimeType", "")
                    data = p["inlineData"].get("data", "")
                    if mime.startswith("audio/pcm") and data:
                        results.append(("audio", data))
                if "text" in p and p["text"]:
                    results.append(("text", p["text"]))
            tr = sc.get("outputTranscription", {}).get("text", "")
            if tr:
                results.append(("text", tr))
    except Exception as e:
        log.debug("parse error: %s", e)
    return results

# ── Main client ───────────────────────────────────────────────────────────────
class GeminiPiClient:

    def __init__(self):
        if not API_KEY:
            raise RuntimeError("Set GEMINI_API_KEY environment variable")
        self.jaw          = JawController(bus)
        self.jaw.start()
        self._text_buf    = ""
        self._speaker     = None
        self._mic         = None

    def _open_mic(self):
        env = os.environ.copy()
        env["XDG_RUNTIME_DIR"] = "/run/user/1000"
        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=/run/user/1000/bus"
        return subprocess.Popen(
            ["pacat", "--record",
             "--device=bluez_input.06:76:40:8A:87:A3",
             "--format=s16le", f"--rate={MIC_RATE}", f"--channels={CHANNELS}",
             "--latency-msec=50"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            env=env,
        )

    def _open_speaker(self):
        env = os.environ.copy()
        env["XDG_RUNTIME_DIR"] = "/run/user/1000"
        return subprocess.Popen(
            ["pacat", "--playback", "--format=s16le",
             f"--rate={OUT_RATE}", f"--channels={CHANNELS}", "--latency-msec=50"],
            stdin=subprocess.PIPE,
            env=env,
        )

    async def _send_mic(self, ws):
        loop = asyncio.get_event_loop()
        proc = self._mic
        while True:
            raw = await loop.run_in_executor(None, proc.stdout.read, CHUNK_BYTES)
            if not raw:
                break
            b64 = base64.b64encode(_amplify(raw, MIC_GAIN)).decode()
            await ws.send(_audio_msg(b64))

    async def _receive(self, ws):
        loop = asyncio.get_event_loop()
        async for message in ws:
            for (kind, payload) in _parse(message):
                if kind == "setup_complete":
                    log.info("Gemini Live ready — speak to Okello")
                    print("\n[Okello is listening — speak now]\n", flush=True)

                elif kind == "audio":
                    pcm = base64.b64decode(payload)
                    self.jaw.update_from_audio(_rms(pcm))
                    if self._speaker and self._speaker.stdin:
                        loud = _amplify(pcm, OUT_GAIN)
                        await loop.run_in_executor(None, self._speaker.stdin.write, loud)

                elif kind == "text":
                    self._text_buf += payload
                    _handle_tokens(payload)
                    clean = _clean(payload)
                    if clean:
                        print(f"Okello: {clean}", flush=True)
                        _transcript.append(clean)

                elif kind == "turn_complete":
                    self.jaw.set_silent()
                    self._text_buf = ""

                elif kind == "interrupted":
                    self.jaw.set_silent()
                    self._text_buf = ""
                    bus.stop_wheels()

    async def _watch_stop(self, ws):
        """Closes the WebSocket when the agent is toggled off."""
        while _agent_active.is_set():
            await asyncio.sleep(0.5)
        log.info("Agent toggled off — closing session")
        try:
            await ws.close()
        except Exception:
            pass

    async def _poll_user_inputs(self, ws):
        """Drains patient confirmations/edits from the queue and sends to Gemini."""
        while True:
            await asyncio.sleep(0.3)
            while _user_inputs:
                try:
                    msg = _user_inputs.popleft()
                    await ws.send(_text_msg(msg))
                    log.info("→ Gemini: %s", msg)
                except Exception as e:
                    log.warning("poll_user_inputs: %s", e)
                    _user_inputs.appendleft(msg)
                    break

    async def _run_session(self):
        url = f"{WS_URL}?key={API_KEY}"
        log.info("Connecting to Gemini Live")
        self._mic     = self._open_mic()
        self._speaker = self._open_speaker()
        try:
            async with websockets.connect(url, ping_interval=20, ping_timeout=30) as ws:
                await ws.send(_setup_msg(voice="Charon", include_text=True))
                tasks = [
                    asyncio.create_task(self._send_mic(ws)),
                    asyncio.create_task(self._receive(ws)),
                    asyncio.create_task(self._watch_stop(ws)),
                    asyncio.create_task(self._poll_user_inputs(ws)),
                ]
                try:
                    await asyncio.gather(*tasks)
                except asyncio.CancelledError:
                    pass
                finally:
                    for t in tasks:
                        t.cancel()
        finally:
            self.jaw.set_silent()
            bus.stop_wheels()
            for proc in [self._mic, self._speaker]:
                if proc:
                    try: proc.terminate()
                    except Exception: pass
            self._mic = None
            self._speaker = None

    async def run(self):
        while True:
            if not _agent_active.is_set():
                log.info("Agent is OFF — waiting for start command")
                while not _agent_active.is_set():
                    await asyncio.sleep(0.5)
            try:
                await self._run_session()
            except Exception as e:
                log.error("Session error: %s", e)
            if _agent_active.is_set():
                log.info("Session ended — reconnecting in 3s")
                await asyncio.sleep(3)
            else:
                log.info("Agent is OFF")

    def close(self):
        self.jaw.stop()
        bus.stop_wheels()
        bus.close()
        for proc in [self._mic, self._speaker]:
            if proc:
                try:
                    proc.terminate()
                except Exception:
                    pass

# ── HTTP server (movement + camera — replaces okello_server.py) ───────────────
HTTP_PORT = int(os.environ.get("OKELLO_PORT", 5000))
WHEEL_RADIUS = 0.05
BASE_RADIUS  = 0.125
MAX_RAW      = 3000

def _degps_to_raw(d: float) -> int:
    return max(-32767, min(32767, int(round(d * 4096.0 / 360.0))))

def _body_to_wheels(x: float, y: float, th: float):
    th_r = th * math.pi / 180.0
    angles = [(240-90)*math.pi/180, (0-90)*math.pi/180, (120-90)*math.pi/180]
    degps  = [(math.cos(a)*x + math.sin(a)*y + BASE_RADIUS*th_r) / WHEEL_RADIUS * 180/math.pi
              for a in angles]
    steps  = [abs(d) * 4096/360 for d in degps]
    scale  = MAX_RAW / max(steps) if max(steps) > MAX_RAW else 1.0
    return tuple(_degps_to_raw(d * scale) for d in degps)

# ── Agent on/off toggle ───────────────────────────────────────────────────────
_agent_active = threading.Event()
_agent_active.set()   # ON by default

# ── Nurse session state ────────────────────────────────────────────────────────
_nurse_session: dict = {
    "step": "idle", "heard": {}, "pending": None,
    "confirmed": {}, "data": {}, "ts": 0.0,
}
_nurse_lock   = threading.Lock()
_user_inputs: collections.deque = collections.deque(maxlen=50)
_transcript:  collections.deque = collections.deque(maxlen=50)

def _inject_temperature_delayed():
    """Read MLX90614 and push result to Gemini after patient has held still."""
    time.sleep(6.0)
    with _temp_lock:
        ok = _temp_ok; tobj = _temp_object
    if ok:
        msg = f"Temperature reading: {tobj:.1f}°C"
        with _nurse_lock:
            _nurse_session["data"]["temperature"] = round(tobj, 1)
            _nurse_session["data"].pop("temperatureFault", None)
    else:
        msg = "Temperature reading: unavailable"
        with _nurse_lock:
            _nurse_session["data"]["temperatureFault"] = True
    _user_inputs.append(msg)
    log.info("Temperature injected: %s", msg)

def _parse_patient_response(msg: str):
    """Update nurse session when patient confirms/edits/skips a field."""
    m = re.match(r'Patient confirmed ([a-zA-Z]+): (.+)', msg)
    if m:
        field, value = m.group(1), m.group(2)
        with _nurse_lock:
            _nurse_session["confirmed"][field] = value
            if _nurse_session["pending"] and _nurse_session["pending"].get("field") == field:
                _nurse_session["pending"] = None
            _nurse_session["ts"] = time.time()
        return
    m = re.match(r'Patient edited ([a-zA-Z]+) to (.+)', msg)
    if m:
        field, value = m.group(1), m.group(2)
        with _nurse_lock:
            _nurse_session["confirmed"][field] = value
            _nurse_session["heard"][field] = value
            if _nurse_session["pending"] and _nurse_session["pending"].get("field") == field:
                _nurse_session["pending"] = None
            _nurse_session["ts"] = time.time()
        return
    m = re.match(r'Patient skipped ([a-zA-Z]+)', msg)
    if m:
        field = m.group(1)
        with _nurse_lock:
            if _nurse_session["pending"] and _nurse_session["pending"].get("field") == field:
                _nurse_session["pending"] = None
            _nurse_session["ts"] = time.time()

# ── Dynamic config from phone app ─────────────────────────────────────────────
_CONFIG_CACHE   = os.path.join(os.path.dirname(__file__), "robot_config_cache.json")
_PATIENT_COUNTER = os.path.join(os.path.dirname(__file__), "patient_counter.json")
_patient_counter_lock = threading.Lock()

def _assign_patient_number() -> str:
    """Return next patient number in format P<MM><NNNN>, e.g. P070001."""
    month_key = time.strftime("%Y-%m")
    with _patient_counter_lock:
        try:
            with open(_PATIENT_COUNTER) as f:
                counters = json.load(f)
        except (FileNotFoundError, json.JSONDecodeError):
            counters = {}
        n = counters.get(month_key, 0) + 1
        counters[month_key] = n
        try:
            with open(_PATIENT_COUNTER, "w") as f:
                json.dump(counters, f)
        except Exception as e:
            log.warning("Could not save patient counter: %s", e)
    month_num = time.strftime("%m")
    return f"P{month_num}{n:04d}"
_robot_config: dict = {}
_robot_config_lock = threading.Lock()

def _update_robot_config(cfg: dict):
    with _robot_config_lock:
        _robot_config.update(cfg)
        snapshot = dict(_robot_config)
    try:
        with open(_CONFIG_CACHE, "w") as f:
            json.dump(snapshot, f)
    except Exception as e:
        log.warning("Could not save config cache: %s", e)
    log.info("Config synced: persona=%s  location=%s  facts=%d  people=%d",
             cfg.get("personaName", "—"),
             cfg.get("locationName", "—"),
             len(cfg.get("facts", [])),
             len(cfg.get("people", [])))

def _load_cached_config():
    try:
        with open(_CONFIG_CACHE) as f:
            cfg = json.load(f)
        with _robot_config_lock:
            _robot_config.update(cfg)
        log.info("Loaded cached config from disk: %d facts, %d people",
                 len(cfg.get("facts", [])), len(cfg.get("people", [])))
    except FileNotFoundError:
        log.info("No cached config found — waiting for phone sync")
    except Exception as e:
        log.warning("Could not load config cache: %s", e)

def _build_system_prompt() -> str:
    with _robot_config_lock:
        cfg = dict(_robot_config)
    if not cfg:
        return SYSTEM_PROMPT  # fallback until first sync

    name  = cfg.get("personaName") or "Okello"
    role  = cfg.get("role")        or "robot assistant"
    extra = cfg.get("extraInstructions", "")

    # Dynamic context layered on top of the static nurse rules
    ctx = []
    ctx.append(f"You are {name}, a {role}.")
    if cfg.get("greeting"):
        ctx.append(f'Your standard greeting: "{cfg["greeting"]}"')
    if cfg.get("locationName"):
        ctx.append(f"You are currently at {cfg['locationName']}.")
    if cfg.get("locationDescription"):
        ctx.append(cfg["locationDescription"])
    if cfg.get("locationSpecialInstructions"):
        ctx.append(f"Special instructions: {cfg['locationSpecialInstructions']}")
    if extra:
        ctx.append(extra)

    facts = cfg.get("facts", [])
    if facts:
        # Group by category so Gemini sees them organised
        by_cat: dict = {}
        for f in facts:
            by_cat.setdefault(f.get("category", "General"), []).append(f)
        ctx.append("\nKNOWLEDGE BASE:")
        for cat, items in by_cat.items():
            ctx.append(f"\n[{cat}]")
            for f in items:
                ctx.append(f"- {f.get('title','')}: {f.get('content','')}")

    people = cfg.get("people", [])
    if people:
        ctx.append("\nKNOWN PEOPLE:")
        for p in people:
            vip = " [VIP]" if p.get("isVip") else ""
            ctx.append(f"- {p.get('name','')} ({p.get('roleTag','')}){vip}: {p.get('notes','')}")

    with _temp_lock:
        t_ok = _temp_ok; ta = _temp_ambient; tobj = _temp_object
    if t_ok:
        ctx.append(f"\nCURRENT SENSOR READING: Ambient {ta}°C, Patient/Object {tobj}°C.")

    dynamic = "\n".join(ctx)
    return _BASE_RULES + "\n\n" + _NURSE_RULES + "\n\nCONFIGURATION:\n" + dynamic

# ── MLX90614 infrared thermometer ─────────────────────────────────────────────
_MLX_ADDR = 0x5A
_MLX_BUS  = 4
_MLX_TA   = 0x06   # ambient temperature register
_MLX_TOBJ = 0x07   # object (patient) temperature register

_temp_ambient: float = 0.0
_temp_object:  float = 0.0
_temp_ok:      bool  = False
_temp_lock = threading.Lock()

def _mlx_read_celsius(bus_h, reg: int) -> float:
    data = bus_h.read_i2c_block_data(_MLX_ADDR, reg, 3)
    raw = (data[1] << 8) | data[0]
    return round(raw * 0.02 - 273.15, 1)

def _temperature_loop():
    global _temp_ambient, _temp_object, _temp_ok
    if not _SMBUS_OK:
        log.warning("smbus2 not installed — temperature sensor disabled")
        return
    try:
        bus_h = smbus2_mod.SMBus(_MLX_BUS)
    except Exception as e:
        log.warning("MLX90614 open failed: %s", e)
        return
    log.info("MLX90614 temperature loop started on i2c-%d", _MLX_BUS)
    while True:
        try:
            ta   = _mlx_read_celsius(bus_h, _MLX_TA)
            tobj = _mlx_read_celsius(bus_h, _MLX_TOBJ)
            with _temp_lock:
                _temp_ambient = ta
                _temp_object  = tobj
                _temp_ok      = True
        except Exception as e:
            with _temp_lock:
                _temp_ok = False
            log.debug("MLX90614 read error: %s", e)
        time.sleep(2)

# Shared camera frame
_cam_jpeg = b''
_cam_lock = threading.Lock()

def _camera_loop():
    global _cam_jpeg
    if not _CV2_OK:
        return
    cap = cv2.VideoCapture('/dev/video0', cv2.CAP_V4L2)
    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, 640)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, 480)
    cap.set(cv2.CAP_PROP_FPS, 15)
    while True:
        ok, frame = cap.read()
        if ok:
            _, buf = cv2.imencode('.jpg', frame, [cv2.IMWRITE_JPEG_QUALITY, 70])
            with _cam_lock:
                _cam_jpeg = bytes(buf)
        else:
            time.sleep(0.5)
    cap.release()

def _json_resp(handler, code: int, data: dict):
    body = json.dumps(data).encode()
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(body)

class _OkelloHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path == "/ping":
            with _temp_lock:
                t_ok = _temp_ok; ta = _temp_ambient; tobj = _temp_object
            _json_resp(self, 200, {
                "status": "ok", "motors": bus.connected,
                "agent": _agent_active.is_set(), "port": HTTP_PORT,
                "temperature": {"ambient": ta, "object": tobj, "ok": t_ok}
            })
        elif self.path == "/temperature":
            with _temp_lock:
                t_ok = _temp_ok; ta = _temp_ambient; tobj = _temp_object
            if t_ok:
                _json_resp(self, 200, {"ambient": ta, "object": tobj, "unit": "C"})
            else:
                _json_resp(self, 503, {"error": "sensor unavailable"})
        elif self.path == "/agent/status":
            _json_resp(self, 200, {"running": _agent_active.is_set()})
        elif self.path == "/config":
            with _robot_config_lock:
                _json_resp(self, 200, dict(_robot_config))
        elif self.path == "/motor/status":
            result = {}
            for sid, label in [(7, "left"), (8, "back"), (9, "right")]:
                try:
                    v = bus.vitals(sid)
                    result[label] = {"id": sid, "voltage": v.get("voltage", 0), "temp": v.get("temp", 0), "ok": v.get("voltage", 0) > 0}
                except Exception as e:
                    result[label] = {"id": sid, "ok": False, "error": str(e)}
            _json_resp(self, 200, result)
        elif self.path == "/frame":
            with _cam_lock:
                data = _cam_jpeg
            if data:
                self.send_response(200)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(data)
            else:
                self.send_response(503)
                self.end_headers()
        elif self.path == "/nurse/session":
            with _nurse_lock:
                s = {
                    "step":      _nurse_session["step"],
                    "heard":     dict(_nurse_session["heard"]),
                    "pending":   dict(_nurse_session["pending"]) if _nurse_session["pending"] else None,
                    "confirmed": dict(_nurse_session["confirmed"]),
                    "data":      dict(_nurse_session["data"]),
                    "ts":        _nurse_session["ts"],
                }
            _json_resp(self, 200, s)
        elif self.path == "/nurse/transcript":
            _json_resp(self, 200, {"lines": list(_transcript)})
        elif self.path in ('/', '/kiosk', '/kiosk.html'):
            html_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'kiosk.html')
            try:
                with open(html_path, 'rb') as f:
                    html = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(html)))
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(html)
            except FileNotFoundError:
                _json_resp(self, 404, {"error": "kiosk.html not found"})
        else:
            _json_resp(self, 404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode() if length else "{}"
        try:
            data = json.loads(body)
        except Exception:
            _json_resp(self, 400, {"error": "bad json"}); return

        if self.path == "/agent/start":
            _agent_active.set()
            log.info("Agent started by phone")
            _json_resp(self, 200, {"ok": True, "running": True})
        elif self.path == "/agent/stop":
            _agent_active.clear()
            log.info("Agent stopped by phone")
            _json_resp(self, 200, {"ok": True, "running": False})
        elif self.path == "/config":
            _update_robot_config(data)
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/move":
            x, y, th = float(data.get("x", 0)), float(data.get("y", 0)), float(data.get("theta", 0))
            vl, vb, vr = _body_to_wheels(x, y, th)
            bus.set_velocities(vl, vb, vr)
            if x != 0 or y != 0 or th != 0:
                def _auto_stop():
                    time.sleep(0.5)
                    bus.stop_wheels()
                threading.Thread(target=_auto_stop, daemon=True).start()
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/stop":
            bus.stop_wheels()
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/motor/setup":
            try:
                bus._setup_wheels()
                _json_resp(self, 200, {"ok": True, "message": "wheel mode re-applied to IDs 7,8,9"})
            except Exception as e:
                _json_resp(self, 500, {"ok": False, "error": str(e)})
        elif self.path == "/nurse/user-input":
            msg = data.get("message", "").strip()
            if not msg:
                _json_resp(self, 400, {"error": "message required"}); return
            _parse_patient_response(msg)
            _user_inputs.append(msg)
            log.info("Patient input queued: %s", msg)
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/nurse/session/reset":
            with _nurse_lock:
                _nurse_session.update({
                    "step": "idle", "heard": {}, "pending": None,
                    "confirmed": {}, "data": {}, "ts": time.time()
                })
            _transcript.clear()
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/print/ticket":
            with _nurse_lock:
                snap_data  = dict(_nurse_session["data"])
                snap_heard = dict(_nurse_session["heard"])
            threading.Thread(target=_print_ticket, args=(snap_data, snap_heard), daemon=True).start()
            _json_resp(self, 200, {"ok": True})
        elif self.path == "/kiosk/exit":
            _json_resp(self, 200, {"ok": True})
            subprocess.Popen(
                ["sudo", "systemctl", "stop", "okello-kiosk"],
                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
            )
        else:
            _json_resp(self, 404, {"error": "not found"})

def _start_http_server():
    threading.Thread(target=_camera_loop,     daemon=True, name="camera").start()
    threading.Thread(target=_temperature_loop, daemon=True, name="temperature").start()
    server = HTTPServer(("0.0.0.0", HTTP_PORT), _OkelloHandler)
    log.info("HTTP server on :%d  (/ping /frame /move /stop /temperature)", HTTP_PORT)
    threading.Thread(target=server.serve_forever, daemon=True, name="http").start()

# ── Entry point ───────────────────────────────────────────────────────────────
if __name__ == "__main__":
    _load_cached_config()
    _start_http_server()
    client = GeminiPiClient()
    try:
        asyncio.run(client.run())
    except KeyboardInterrupt:
        print("\nShutting down…")
    finally:
        client.close()
