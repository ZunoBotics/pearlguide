"""
servo_bus.py — Unified driver for all Okello servos on a single SCS serial bus.

Bus layout:
  ID 1  SCS125   Jaw (lip-sync)
  ID 2  SCS125   Head pan
  ID 3  SCS0009  Eye pan/tilt
  ID 4  SCS0009  Eye pan/tilt 2
  ID 7  STS3215  Omni-wheel LEFT
  ID 8  STS3215  Omni-wheel BACK
  ID 9  STS3215  Omni-wheel RIGHT

SCS125 / SCS0009 — big-endian 16-bit, swap16() required.
STS3215           — little-endian, no swap, wheel-mode speed control.
"""

import json, os, time, logging, threading
log = logging.getLogger(__name__)

try:
    import scservo_sdk as scs
    _SDK_OK = True
except ImportError:
    _SDK_OK = False
    log.warning("scservo_sdk not found — ServoB running in mock mode")

LIMITS_FILE = os.path.join(os.path.dirname(__file__), 'servo_limits.json')

# ── Registers (shared across SCS/STS families) ────────────────────────────────
ADDR_OPERATING_MODE = 33
ADDR_TORQUE_ENABLE  = 40
ADDR_GOAL_POSITION  = 42
ADDR_GOAL_TIME      = 44   # movement duration (ms), big-endian on SCS
ADDR_GOAL_SPEED     = 46   # max speed / wheel speed
ADDR_PRESENT_POS    = 56
ADDR_PRESENT_VOLT   = 62
ADDR_PRESENT_TEMP   = 63

MODE_POSITION = 0
MODE_WHEEL    = 1


def _swap16(v: int) -> int:
    return ((v & 0xFF) << 8) | ((v >> 8) & 0xFF)


class ServoBus:
    """
    Thread-safe wrapper around a single serial bus carrying all 7 servos.
    """

    def __init__(self, device: str = None, baudrate: int = 1_000_000):
        self._lock = threading.Lock()
        self.connected = False
        self.port = None
        self.ph   = None
        self.limits = self._load_limits()

        if not _SDK_OK:
            return

        device = device or self._find_device()
        try:
            self.port = scs.PortHandler(device)
            self.ph   = scs.PacketHandler(0)
            self.port.openPort()
            self.port.setBaudRate(baudrate)
            self.connected = True
            log.info("ServoBus connected on %s", device)
            self._setup_wheels()
        except Exception as e:
            log.warning("ServoBus init failed: %s — mock mode", e)

    # ── Setup ─────────────────────────────────────────────────────────────────

    def _find_device(self):
        for candidate in ["/dev/ttyACM0", "/dev/ttyACM1", "/dev/ttyUSB0", "/dev/ttyUSB1",
                          "/dev/cu.usbmodem5B790785651"]:
            if os.path.exists(candidate):
                return candidate
        return "/dev/ttyACM0"

    def _load_limits(self):
        if not os.path.exists(LIMITS_FILE):
            log.warning("servo_limits.json not found — no clamping applied")
            return {}
        with open(LIMITS_FILE) as f:
            return json.load(f)

    def _setup_wheels(self):
        for sid in [7, 8, 9]:
            self.ph.write1ByteTxOnly(self.port, sid, ADDR_OPERATING_MODE, MODE_WHEEL)
            time.sleep(0.05)
            self.ph.write1ByteTxOnly(self.port, sid, ADDR_TORQUE_ENABLE, 1)
            time.sleep(0.05)

    # ── Low-level read/write ──────────────────────────────────────────────────

    def _read_pos_be(self, sid: int):
        """Big-endian raw read (SCS125, SCS0009)."""
        pkt = [0xFF, 0xFF, sid, 0x04, 0x02, ADDR_PRESENT_POS, 0x02, 0x00]
        pkt[-1] = (~sum(pkt[2:-1])) & 0xFF
        self.port.ser.reset_input_buffer()
        self.port.ser.write(bytes(pkt))
        time.sleep(0.04)
        rx = self.port.ser.read(20)
        if len(rx) >= 7 and rx[0] == 0xFF and rx[4] == 0x00:
            return (rx[5] << 8) | rx[6]
        return None

    def _clamp(self, sid: int, pos: int) -> int:
        d = self.limits.get(str(sid))
        if not d:
            return pos
        lo = min(d['min_step'], d['max_step'])
        hi = max(d['min_step'], d['max_step'])
        return max(lo, min(hi, pos))

    # ── Public: SCS position servos (IDs 1–4) ────────────────────────────────

    def torque(self, sid: int, on: bool):
        if not self.connected:
            return
        with self._lock:
            self.ph.write1ByteTxOnly(self.port, sid, ADDR_TORQUE_ENABLE, 1 if on else 0)
            time.sleep(0.05)

    def move(self, sid: int, position: int, speed: int = 2000, time_ms: int = 0):
        """
        Move SCS125/SCS0009 to position (big-endian, clamped to limits).
        speed    — max steps/sec (0 = servo's own default)
        time_ms  — movement duration override (0 = let speed govern)
        """
        if not self.connected:
            return
        position = self._clamp(sid, int(position))
        with self._lock:
            if time_ms > 0:
                self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_TIME, _swap16(time_ms))
                time.sleep(0.01)
            if speed > 0:
                self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_SPEED, _swap16(speed))
                time.sleep(0.01)
            self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_POSITION, _swap16(position))

    def write_pvt(self, sid: int, position: int, velocity: int, time_ms: int):
        """
        Position-Velocity-Time command — natural trapezoidal trajectory.
        Used by JawController for smooth lip-sync.
        """
        if not self.connected:
            return
        position = self._clamp(sid, int(position))
        with self._lock:
            self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_TIME,  _swap16(int(time_ms)))
            self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_SPEED, _swap16(int(velocity)))
            self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_POSITION, _swap16(position))

    def read_pos(self, sid: int):
        if not self.connected:
            return None
        with self._lock:
            return self._read_pos_be(sid)

    # ── Public: STS3215 wheel motors (IDs 7–9) ───────────────────────────────

    def set_wheel_speed(self, sid: int, raw: int):
        """
        raw: positive = forward, negative = reverse, 0 = stop.
        Encodes direction bit + 15-bit magnitude.
        """
        if not self.connected:
            return
        direction = 0 if raw >= 0 else 1
        word = (direction << 15) | min(abs(raw), 32767)
        with self._lock:
            self.ph.write2ByteTxOnly(self.port, sid, ADDR_GOAL_SPEED, word)

    def set_velocities(self, v_left: int, v_back: int, v_right: int):
        self.set_wheel_speed(7, v_left)
        self.set_wheel_speed(8, v_back)
        self.set_wheel_speed(9, v_right)

    def stop_wheels(self):
        self.set_velocities(0, 0, 0)

    # ── Vitals ────────────────────────────────────────────────────────────────

    def vitals(self, sid: int):
        if not self.connected:
            return {}
        with self._lock:
            temp, _, _ = self.ph.read1ByteTxRx(self.port, sid, ADDR_PRESENT_TEMP)
            volt, _, _ = self.ph.read1ByteTxRx(self.port, sid, ADDR_PRESENT_VOLT)
        return {"temp": temp, "voltage": volt / 10.0}

    def close(self):
        if self.connected:
            self.stop_wheels()
            self.port.closePort()
            self.connected = False


# Singleton shared across all modules
bus = ServoBus()
