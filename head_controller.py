"""
Head Controller for EZ-InMoov based robot head.

PCA9685 16-channel servo driver wiring:
  D0 (CH0) - Eye Tilt  (MG90S micro servo)
  D1 (CH1) - Eye Pan   (MG90S micro servo)
  D3 (CH3) - Jaw       (MG996 13KG servo)
  D4 (CH4) - Head Pan  (MG996 13KG servo)

I2C wiring to Raspberry Pi:
  SCL -> GPIO3 / Pin 5
  SDA -> GPIO2 / Pin 3
  VCC -> 3.3V  / Pin 1
  GND -> GND   / Pin 6
"""

import time

try:
    from adafruit_servokit import ServoKit
    HARDWARE_AVAILABLE = True
    SERVOKIT_IMPORT_ERROR = None
except (ImportError, Exception) as exc:
    HARDWARE_AVAILABLE = False
    SERVOKIT_IMPORT_ERROR = exc
    print(f"[HeadController] Servo hardware support unavailable: {type(exc).__name__}: {exc}")


# PCA9685 channel assignments
CHANNEL_EYE_TILT = 0
CHANNEL_EYE_PAN  = 1
CHANNEL_JAW      = 3
CHANNEL_HEAD_PAN = 4
SERVO_CHANNELS = (
    CHANNEL_EYE_TILT,
    CHANNEL_EYE_PAN,
    CHANNEL_JAW,
    CHANNEL_HEAD_PAN,
)

# Servo angle limits (degrees)
EYE_TILT_MIN, EYE_TILT_MAX = 60, 120    # centre = 90
EYE_PAN_MIN,  EYE_PAN_MAX  = 50, 130    # centre = 90
JAW_MIN,      JAW_MAX      = 0,  135     # 0 = closed, 135 = wide open (servo supports 0-180)
HEAD_PAN_MIN, HEAD_PAN_MAX = 30, 150    # centre = 90

# Default neutral positions
NEUTRAL = {
    CHANNEL_EYE_TILT: 90,
    CHANNEL_EYE_PAN:  90,
    CHANNEL_JAW:       0,
    CHANNEL_HEAD_PAN: 90,
}


class HeadController:
    """Controls the robot head servos via PCA9685 over I2C."""

    def __init__(self, i2c_address: int = 0x40, frequency: int = 50):
        self._angles = dict(NEUTRAL)
        self._kit = None
        self._error_message = None
        if HARDWARE_AVAILABLE:
            try:
                self._kit = ServoKit(channels=16, address=i2c_address, frequency=frequency)
                self._configure_servos()
                self.center_all()
            except Exception as exc:
                self._error_message = (
                    f"PCA9685 initialisation failed at I2C address 0x{i2c_address:02X}: "
                    f"{type(exc).__name__}: {exc}"
                )
                print(f"[HeadController] {self._error_message}. Running in simulation mode.")
        else:
            details = "ServoKit import failed"
            if SERVOKIT_IMPORT_ERROR is not None:
                details = f"{details}: {type(SERVOKIT_IMPORT_ERROR).__name__}: {SERVOKIT_IMPORT_ERROR}"
            self._error_message = (
                f"Servo hardware Python dependencies are missing in the active interpreter. {details}"
            )

    def _configure_servos(self):
        """Set appropriate pulse-width ranges for MG90S and MG996 servos."""
        # Both MG90S and MG996 use a 500–2500 µs pulse range for full 180°
        for ch in SERVO_CHANNELS:
            self._kit.servo[ch].set_pulse_width_range(500, 2500)

    def _set_angle(self, channel: int, angle: float):
        angle = float(angle)
        self._angles[channel] = angle
        if self._kit is not None:
            try:
                self._kit.servo[channel].angle = angle
            except OSError as exc:
                print(f"[HeadController] I2C write error on channel {channel}: {exc}")

    # ------------------------------------------------------------------ #
    #  Public API                                                          #
    # ------------------------------------------------------------------ #

    def center_all(self):
        """Move all servos to neutral/home positions."""
        self.set_eye_tilt(NEUTRAL[CHANNEL_EYE_TILT])
        self.set_eye_pan(NEUTRAL[CHANNEL_EYE_PAN])
        self.set_jaw(0.0)
        self.set_head_pan(NEUTRAL[CHANNEL_HEAD_PAN])

    # --- Eyes ---

    def set_eye_tilt(self, angle: float):
        """Move eye-tilt servo. angle: 0–180 (up→down), centre = 90."""
        angle = max(EYE_TILT_MIN, min(EYE_TILT_MAX, angle))
        self._set_angle(CHANNEL_EYE_TILT, angle)

    def set_eye_pan(self, angle: float):
        """Move eye-pan servo. angle: 0–180 (left→right), centre = 90."""
        angle = max(EYE_PAN_MIN, min(EYE_PAN_MAX, angle))
        self._set_angle(CHANNEL_EYE_PAN, angle)

    def look_at(self, pan: float = 90.0, tilt: float = 90.0):
        """Move both eye servos simultaneously."""
        self.set_eye_pan(pan)
        self.set_eye_tilt(tilt)

    # --- Jaw ---

    def set_jaw(self, open_amount: float):
        """
        Open the jaw proportionally.
        open_amount: 0.0 (fully closed) → 1.0 (fully open)
        """
        angle = JAW_MIN + open_amount * (JAW_MAX - JAW_MIN)
        self._set_angle(CHANNEL_JAW, angle)

    def close_jaw(self):
        self.set_jaw(0.0)

    # --- Head ---

    def set_head_pan(self, angle: float):
        """Rotate head left/right. angle: 0–180, centre = 90."""
        angle = max(HEAD_PAN_MIN, min(HEAD_PAN_MAX, angle))
        self._set_angle(CHANNEL_HEAD_PAN, angle)

    def nod(self, cycles: int = 2, amplitude: float = 15.0, speed: float = 0.08):
        """Perform a nodding motion with the eye-tilt servo."""
        centre = NEUTRAL[CHANNEL_EYE_TILT]
        for _ in range(cycles):
            self.set_eye_tilt(centre - amplitude)
            time.sleep(speed)
            self.set_eye_tilt(centre + amplitude)
            time.sleep(speed)
        self.set_eye_tilt(centre)

    def shake_head(self, cycles: int = 2, amplitude: float = 20.0, speed: float = 0.08):
        """Perform a side-to-side shake with the head-pan servo."""
        centre = NEUTRAL[CHANNEL_HEAD_PAN]
        for _ in range(cycles):
            self.set_head_pan(centre - amplitude)
            time.sleep(speed)
            self.set_head_pan(centre + amplitude)
            time.sleep(speed)
        self.set_head_pan(centre)

    # --- State ---

    @property
    def angles(self) -> dict:
        """Return current servo angles as {channel: angle}."""
        return dict(self._angles)

    @property
    def hardware_ready(self) -> bool:
        """Return True when the PCA9685 stack is initialised and writable."""
        return self._kit is not None

    @property
    def error_message(self) -> str | None:
        """Describe why hardware mode is unavailable."""
        return self._error_message

    def shutdown(self):
        """Move to neutral and release (set angle to None disables PWM)."""
        self.center_all()
        time.sleep(0.5)
        if self._kit is not None:
            for ch in SERVO_CHANNELS:
                try:
                    self._kit.servo[ch].angle = None
                except OSError as exc:
                    print(f"[HeadController] I2C error on shutdown ch{ch}: {exc}")
