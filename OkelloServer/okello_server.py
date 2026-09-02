#!/usr/bin/env python3
"""
OkelloServer — Pi motor-control REST API
Drives three Feetech STS3215 omni-wheels (IDs 7, 8, 9) via SCServo protocol.
Exposes a simple HTTP API consumed by the Android phone app.
"""

import collections
import json
import logging
import math
import os
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer

# ─── scservo_sdk ─────────────────────────────────────────────────────────────
try:
    import scservo_sdk as scs

    MOTORS_OK = True
except ImportError:
    MOTORS_OK = False
    logging.warning("scservo_sdk not found — running in mock mode (no wheel movement)")

# ─── Head servo controller (jaw lip-sync + head gestures) ────────────────────
try:
    import sys as _sys
    _head_dir = os.path.expanduser('~/lerobot/zunobot-app/robot-head')
    _sys.path.insert(0, _head_dir)
    from head_controller import (
        HeadController as _HeadController,
        NEUTRAL, CHANNEL_HEAD_PAN, HEAD_PAN_MIN, HEAD_PAN_MAX,
    )
    _head = _HeadController(i2c_bus=3)
    HEAD_OK = _head.hardware_ready
    logging.info("HeadController ready — jaw + head gestures active")
except Exception as _he:
    _head = None
    HEAD_OK = False
    NEUTRAL = {3: 90}
    CHANNEL_HEAD_PAN = 3
    HEAD_PAN_MIN = 30
    HEAD_PAN_MAX = 150
    logging.warning("HeadController not available: %s — jaw/head will not move", _he)

# ─── Config ──────────────────────────────────────────────────────────────────
PORT = int(os.environ.get("OKELLO_PORT", 5000))
def _find_device():
    for candidate in ["/dev/ttyACM0", "/dev/ttyACM1", "/dev/ttyUSB0", "/dev/ttyUSB1"]:
        if os.path.exists(candidate):
            return candidate
    return os.environ.get("OKELLO_DEVICE", "/dev/ttyACM0")

DEVICE = os.environ.get("OKELLO_DEVICE") or _find_device()
BAUDRATE = 1000000

# Motor IDs
MOTOR_LEFT = 7
MOTOR_BACK = 8
MOTOR_RIGHT = 9
MOTORS = [MOTOR_LEFT, MOTOR_BACK, MOTOR_RIGHT]

# Wheel geometry (same as LeKiwi)
WHEEL_RADIUS = 0.05    # metres
BASE_RADIUS = 0.125    # metres  (centre-to-wheel distance)
MAX_RAW = 3000         # max raw speed ticks

# SCServo registers for STS3215
ADDR_TORQUE_ENABLE = 40
ADDR_GOAL_SPEED = 46   # 2 bytes, signed
ADDR_OPERATING_MODE = 33

TORQUE_ON = 1
TORQUE_OFF = 0
MODE_WHEEL = 1    # continuous rotation (velocity) mode
MODE_POSITION = 0

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger("okello_server")

# ─── Motor bus ───────────────────────────────────────────────────────────────

class MotorBus:
    def __init__(self):
        self.connected = False
        self._lock = threading.Lock()
        if not MOTORS_OK:
            return
        try:
            self.port = scs.PortHandler(DEVICE)
            self.ph = scs.PacketHandler(0)  # protocol 0 = SCS
            if not self.port.openPort():
                raise RuntimeError(f"Cannot open {DEVICE}")
            self.port.setBaudRate(BAUDRATE)
            self._setup_motors()
            self.connected = True
            log.info("Motors connected on %s", DEVICE)
        except Exception as e:
            log.warning("Motor init failed: %s — running in mock mode", e)

    def _setup_motors(self):
        for mid in MOTORS:
            self.ph.write1ByteTxOnly(self.port, mid, ADDR_OPERATING_MODE, MODE_WHEEL)
            time.sleep(0.05)
            self.ph.write1ByteTxOnly(self.port, mid, ADDR_TORQUE_ENABLE, TORQUE_ON)
            time.sleep(0.05)

    def set_velocities(self, v_left: int, v_back: int, v_right: int):
        if not self.connected:
            return
        with self._lock:
            for mid, raw in [(MOTOR_LEFT, v_left), (MOTOR_BACK, v_back), (MOTOR_RIGHT, v_right)]:
                direction = 0 if raw >= 0 else 1
                magnitude = min(abs(raw), 32767)
                word = (direction << 15) | magnitude
                self.ph.write2ByteTxOnly(self.port, mid, ADDR_GOAL_SPEED, word)

    def stop(self):
        self.set_velocities(0, 0, 0)

    def disconnect(self):
        if self.connected:
            self.stop()
            self.port.closePort()
            self.connected = False


bus = MotorBus()

# ─── Omni-wheel kinematics (matches LeKiwi exactly) ─────────────────────────

def _degps_to_raw(degps: float) -> int:
    steps_per_deg = 4096.0 / 360.0
    raw = int(round(degps * steps_per_deg))
    return max(-32767, min(32767, raw))


def body_to_wheel_raw(x: float, y: float, theta_degps: float) -> tuple[int, int, int]:
    """Convert body velocities (m/s, m/s, deg/s) to raw wheel ticks."""
    theta_rad = theta_degps * (math.pi / 180.0)
    vel = [x, y, theta_rad]

    # Wheel mounting angles (deg) → radians, same as LeKiwi
    angles_deg = [240 - 90, 0 - 90, 120 - 90]  # left, back, right
    angles = [a * math.pi / 180.0 for a in angles_deg]

    wheel_degps = []
    for a in angles:
        linear_speed = math.cos(a) * vel[0] + math.sin(a) * vel[1] + BASE_RADIUS * vel[2]
        angular_degps = (linear_speed / WHEEL_RADIUS) * (180.0 / math.pi)
        wheel_degps.append(angular_degps)

    # Scale down if any wheel exceeds MAX_RAW
    steps_per_deg = 4096.0 / 360.0
    max_steps = max(abs(d) * steps_per_deg for d in wheel_degps)
    if max_steps > MAX_RAW:
        scale = MAX_RAW / max_steps
        wheel_degps = [d * scale for d in wheel_degps]

    raws = [_degps_to_raw(d) for d in wheel_degps]
    return raws[0], raws[1], raws[2]  # left, back, right

# ─── Continuous drive loop ───────────────────────────────────────────────────

_target_vel = [0.0, 0.0, 0.0]  # x, y, theta
_vel_lock = threading.Lock()
_drive_active = threading.Event()


_last_move_time = 0.0
MIN_MOVE_DURATION = 0.35  # seconds — hold velocity for at least this long after a move command


def _drive_loop():
    global _last_move_time
    while True:
        _drive_active.wait()
        with _vel_lock:
            x, y, th = _target_vel
        if x == 0.0 and y == 0.0 and th == 0.0:
            # Honour stop only after minimum hold time has elapsed
            if time.time() - _last_move_time >= MIN_MOVE_DURATION:
                bus.stop()
                _drive_active.clear()
            else:
                time.sleep(0.02)
            continue
        v_l, v_b, v_r = body_to_wheel_raw(x, y, th)
        bus.set_velocities(v_l, v_b, v_r)
        time.sleep(0.05)  # 20 Hz


threading.Thread(target=_drive_loop, daemon=True).start()


def set_target(x: float, y: float, theta: float):
    global _last_move_time
    with _vel_lock:
        _target_vel[0] = x
        _target_vel[1] = y
        _target_vel[2] = theta
    if x != 0.0 or y != 0.0 or theta != 0.0:
        _last_move_time = time.time()
    _drive_active.set()


# ─── HTTP server ──────────────────────────────────────────────────────────────

def _json_response(handler, code: int, data: dict):
    body = json.dumps(data).encode()
    handler.send_response(code)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.send_header("Access-Control-Allow-Origin", "*")
    handler.end_headers()
    handler.wfile.write(body)


class OkelloHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass  # silence default access log

    def do_GET(self):
        if self.path == "/ping":
            _json_response(self, 200, {
                "status": "ok",
                "motors": bus.connected,
                "device": DEVICE,
                "ip": _local_ip(),
                "port": PORT,
            })
        elif self.path == "/nurse/transcript":
            with _transcript_lock:
                lines = list(_transcript)
            _json_response(self, 200, {"lines": lines})

        elif self.path == "/nurse/user-input":
            with _transcript_lock:
                inputs = list(_user_inputs)
                _user_inputs.clear()
            _json_response(self, 200, {"inputs": inputs})

        elif self.path == "/nurse/session":
            with _session_lock:
                snap = dict(_nurse_session)
                snap["heard"] = dict(_nurse_session["heard"])
                snap["confirmed"] = dict(_nurse_session["confirmed"])
                snap["data"] = dict(_nurse_session["data"])
            _json_response(self, 200, snap)

        elif self.path == "/diagnose":
            result = {"port_open": bus.connected, "device": DEVICE, "found": [], "missing": []}
            if bus.connected:
                # Scan IDs 1-20 to find whatever is actually on the bus
                for mid in range(1, 21):
                    pos, res, err = bus.ph.read2ByteTxRx(bus.port, mid, 56)
                    if res == 0:  # COMM_SUCCESS
                        result["found"].append(mid)
                    else:
                        result["missing"].append(mid)
                result["configured"] = MOTORS
                result["all_configured_found"] = all(m in result["found"] for m in MOTORS)
            _json_response(self, 200, result)
        else:
            _json_response(self, 404, {"error": "not found"})

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode() if length else "{}"
        try:
            data = json.loads(body)
        except Exception:
            _json_response(self, 400, {"error": "bad json"})
            return

        if self.path == "/reinit":
            try:
                bus._setup_motors()
                log.info("Motors re-initialised (wheel mode + torque on)")
                _json_response(self, 200, {"ok": True, "message": "wheel mode + torque re-applied"})
            except Exception as e:
                _json_response(self, 500, {"ok": False, "error": str(e)})

        elif self.path == "/move":
            x = float(data.get("x", 0.0))
            y = float(data.get("y", 0.0))
            theta = float(data.get("theta", 0.0))
            set_target(x, y, theta)
            log.info("move x=%.2f y=%.2f theta=%.1f", x, y, theta)
            _json_response(self, 200, {"ok": True, "x": x, "y": y, "theta": theta})

        elif self.path == "/stop":
            set_target(0.0, 0.0, 0.0)
            log.info("stop")
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/transcript":
            # Quest pushes a Gemini speech line: {"role":"gemini","text":"..."}
            role = data.get("role", "gemini")
            text = data.get("text", "").strip()
            if text:
                entry = {"role": role, "text": text, "ts": time.time()}
                with _transcript_lock:
                    _transcript.append(entry)
                log.info("transcript [%s]: %s", role, text[:80])
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/user-input":
            # Flutter pushes patient typed text: {"text":"..."}
            text = data.get("text", "").strip()
            if text:
                with _transcript_lock:
                    _user_inputs.append({"text": text, "ts": time.time()})
                    _transcript.append({"role": "patient", "text": text, "ts": time.time()})
                log.info("patient typed: %s", text[:80])
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/session/step":
            step = data.get("step", "idle")
            with _session_lock:
                _nurse_session["step"] = step
                _nurse_session["ts"] = time.time()
            log.info("nurse session step → %s", step)
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/session/heard":
            field = data.get("field", "")
            value = data.get("value", "")
            if field:
                with _session_lock:
                    _nurse_session["heard"][field] = value
                    _nurse_session["pending"] = field
                    _nurse_session["ts"] = time.time()
                log.info("nurse heard [%s]: %s", field, value[:80])
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/session/confirm":
            field = data.get("field", "")
            value = data.get("value", "")
            skipped = data.get("skipped", False)
            if field:
                with _session_lock:
                    if not skipped:
                        _nurse_session["confirmed"][field] = value
                        _nurse_session["heard"][field] = value
                    if _nurse_session["pending"] == field:
                        _nurse_session["pending"] = None
                    _nurse_session["ts"] = time.time()
                if skipped:
                    msg = f"Patient skipped {field}"
                else:
                    msg = f"Patient confirmed {field}: {value}"
                with _transcript_lock:
                    _user_inputs.append({"text": msg, "ts": time.time()})
                    _transcript.append({"role": "patient", "text": msg, "ts": time.time()})
                log.info("nurse confirm [%s]: %s", field, value[:60] if value else "(skipped)")
            _json_response(self, 200, {"ok": True})

        elif self.path == "/nurse/session/reset":
            with _session_lock:
                _nurse_session["step"] = "idle"
                _nurse_session["heard"].clear()
                _nurse_session["pending"] = None
                _nurse_session["confirmed"].clear()
                _nurse_session["data"].clear()
                _nurse_session["ts"] = time.time()
            log.info("nurse session reset")
            _json_response(self, 200, {"ok": True})

        elif self.path == "/jaw":
            amount = max(0.0, min(1.0, float(data.get("open", 0.0))))
            if _head is not None:
                try:
                    _head.set_jaw(amount)
                except Exception as _je:
                    log.debug("jaw set_jaw: %s", _je)
            _json_response(self, 200, {"ok": True})

        elif self.path == "/head":
            gesture = data.get("gesture", "")
            if _head is not None and gesture:
                def _do_head_gesture():
                    try:
                        if gesture == "nod":
                            _head.nod(cycles=2)
                        elif gesture == "shake":
                            _head.shake_head(cycles=2)
                        elif gesture == "turn_left":
                            _head.set_head_pan(HEAD_PAN_MIN + 15)
                        elif gesture == "turn_right":
                            _head.set_head_pan(HEAD_PAN_MAX - 15)
                        elif gesture == "center":
                            _head.set_head_pan(NEUTRAL[CHANNEL_HEAD_PAN])
                    except Exception as _ge:
                        log.debug("head gesture %s: %s", gesture, _ge)
                threading.Thread(target=_do_head_gesture, daemon=True).start()
                log.info("head gesture: %s", gesture)
            _json_response(self, 200, {"ok": True})

        else:
            _json_response(self, 404, {"error": "not found"})

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


# ─── Nurse transcript store (Quest ↔ Flutter kiosk) ─────────────────────────
# Quest pushes Gemini speech lines; Flutter polls them.
# Flutter pushes typed patient input; Quest polls for it.

_transcript: collections.deque = collections.deque(maxlen=30)
_user_inputs: collections.deque = collections.deque(maxlen=20)
_transcript_lock = threading.Lock()

# ─── Nurse session state (Quest ↔ Pi kiosk) ─────────────────────────────────
# Tracks current registration step + heard-but-unconfirmed field values.
# Quest POSTs step/heard; Pi POSTs confirmation; both GET the current state.

_nurse_session: dict = {
    "step": "idle",
    "heard": {},       # field → value Quest heard from patient speech
    "pending": None,   # field name currently awaiting Pi confirmation
    "confirmed": {},   # field → confirmed value
    "data": {},        # arbitrary session data
    "ts": 0.0,
}
_session_lock = threading.Lock()


def _local_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


# ─── mDNS broadcast (avahi) ──────────────────────────────────────────────────

def _start_mdns():
    try:
        import subprocess
        subprocess.Popen([
            "avahi-publish-service", "OkelloServer", "_http._tcp", str(PORT)
        ], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        log.info("mDNS: OkelloServer advertised on port %d", PORT)
    except Exception:
        log.info("avahi not available — Pi discoverable only by IP")


# ─── Entry point ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    _start_mdns()
    ip = _local_ip()
    log.info("OkelloServer starting — http://%s:%d", ip, PORT)
    log.info("Motors: %s", "connected" if bus.connected else "mock mode")
    httpd = HTTPServer(("0.0.0.0", PORT), OkelloHandler)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        log.info("Shutting down")
    finally:
        bus.disconnect()
        httpd.server_close()
