"""
Head-pan servo calibration.

Walks you through setting three reference positions:
  1. Centre  — head faces straight forward
  2. Left    — maximum comfortable left turn
  3. Right   — maximum comfortable right turn

Results are saved to head_calibration.json and can be loaded by
head_controller.py at startup instead of hardcoded constants.

Usage
-----
    python calibrate_head.py

Controls during each step
-------------------------
  Enter (blank)    confirm current angle and move to next step
  <number>         jump to that angle, e.g.  75
  +  or  +<n>      nudge right by 1° (or n°), e.g.  +5
  -  or  -<n>      nudge left  by 1° (or n°), e.g.  -10
  f                free mode — cuts PWM so you can rotate the head by hand
  e                engage   — re-enables servo at the current angle
  r                re-run current step from scratch
  q                quit without saving
"""

import json
import os
import time

# ---------------------------------------------------------------------------
# Hardware init
# ---------------------------------------------------------------------------

try:
    from adafruit_servokit import ServoKit
except ImportError:
    print("ERROR: adafruit_servokit not installed. Run:  pip install -r requirements.txt")
    raise SystemExit(1)

CHANNEL   = 2        # head pan
PULSE_MIN = 500      # µs → 0°
PULSE_MAX = 2500     # µs → 180°
FREQ      = 50       # Hz

OUTPUT_FILE = os.path.join(os.path.dirname(__file__), "head_calibration.json")

kit = ServoKit(channels=16, address=0x40, frequency=FREQ)
kit.servo[CHANNEL].set_pulse_width_range(PULSE_MIN, PULSE_MAX)

# ---------------------------------------------------------------------------
# Low-level helpers
# ---------------------------------------------------------------------------

_current_angle: float = 90.0
_free: bool = False


def _apply(angle: float):
    global _current_angle, _free
    angle = max(0.0, min(180.0, angle))
    _current_angle = angle
    _free = False
    kit.servo[CHANNEL].angle = angle


def _release():
    global _free
    _free = True
    kit.servo[CHANNEL].angle = None


def _status():
    state = "FREE (no torque)" if _free else f"{_current_angle:.1f}°"
    print(f"    [current: {state}]")


# ---------------------------------------------------------------------------
# Calibration step
# ---------------------------------------------------------------------------

STEP_HELP = (
    "  Enter        → confirm and record this angle\n"
    "  <number>     → jump to angle, e.g.  85\n"
    "  + or +<n>    → nudge right (increase), e.g.  +3\n"
    "  - or -<n>    → nudge left  (decrease), e.g.  -5\n"
    "  f            → free mode (cut torque, move by hand)\n"
    "  e            → engage (re-enable servo at current angle)\n"
    "  r            → restart this step\n"
    "  q            → quit\n"
)


def calibrate_step(name: str, hint: str, default: float) -> float | None:
    """
    Interactive calibration for one named position.
    Returns the confirmed angle, or None if the user quits.
    """
    print()
    print("=" * 55)
    print(f"  STEP: {name.upper()}")
    print(f"  {hint}")
    print("=" * 55)
    print(STEP_HELP)

    _apply(default)
    _status()

    while True:
        try:
            raw = input("  > ").strip()
        except (EOFError, KeyboardInterrupt):
            return None

        if raw == "q":
            return None

        if raw == "r":
            _apply(default)
            print(f"  Reset to {default}°")
            _status()
            continue

        if raw == "f":
            _release()
            print("  Servo released — rotate the head freely by hand.")
            print("  Type 'e' to re-engage when done.")
            continue

        if raw == "e":
            engage_at = _current_angle if not _free else default
            _apply(engage_at)
            print(f"  Servo engaged at {engage_at:.1f}°")
            _status()
            continue

        if raw == "":
            # Confirm
            if _free:
                print("  Servo is in free mode — engage first with 'e', then confirm.")
                continue
            print(f"  ✓  Recorded '{name}' = {_current_angle:.1f}°")
            return _current_angle

        # Nudge: + or +N or - or -N
        if raw.startswith("+") or raw.startswith("-"):
            try:
                delta = float(raw) if len(raw) > 1 else (1.0 if raw == "+" else -1.0)
                new_angle = _current_angle + delta
                _apply(new_angle)
                _status()
                continue
            except ValueError:
                pass

        # Direct angle
        try:
            new_angle = float(raw)
            _apply(new_angle)
            _status()
            continue
        except ValueError:
            pass

        print("  Unknown command — see controls above.")


# ---------------------------------------------------------------------------
# Save / load
# ---------------------------------------------------------------------------

def save(calibration: dict):
    with open(OUTPUT_FILE, "w") as f:
        json.dump(calibration, f, indent=2)
    print(f"\n  Saved → {OUTPUT_FILE}")


def load_existing() -> dict | None:
    if os.path.exists(OUTPUT_FILE):
        with open(OUTPUT_FILE) as f:
            return json.load(f)
    return None


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print()
    print("╔══════════════════════════════════════════════════╗")
    print("║       Robot Head — Pan Servo Calibration         ║")
    print("╚══════════════════════════════════════════════════╝")

    existing = load_existing()
    if existing:
        print(f"\n  Existing calibration found in {OUTPUT_FILE}:")
        for k, v in existing.items():
            print(f"    {k}: {v}")
        print()
        try:
            ans = input("  Overwrite? [y/N] ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            print("\nAborted.")
            return
        if ans != "y":
            print("Keeping existing calibration. Bye!")
            return

    steps = [
        ("centre",      "Point the head straight forward (neutral position).", 90.0),
        ("left_limit",  "Turn the head as far left as it can go comfortably.", 30.0),
        ("right_limit", "Turn the head as far right as it can go comfortably.", 150.0),
    ]

    calibration = {
        "channel":       CHANNEL,
        "pulse_min_us":  PULSE_MIN,
        "pulse_max_us":  PULSE_MAX,
    }

    try:
        for name, hint, default in steps:
            result = calibrate_step(name, hint, default)
            if result is None:
                print("\n  Calibration aborted — nothing saved.")
                return
            calibration[name] = result
            time.sleep(0.2)

    except KeyboardInterrupt:
        print("\n  Interrupted — nothing saved.")
        return
    finally:
        _release()
        time.sleep(0.3)
        print("  Servo released.")

    # Summary
    print()
    print("  === Calibration complete ===")
    print(f"    Centre      : {calibration['centre']:.1f}°")
    print(f"    Left limit  : {calibration['left_limit']:.1f}°")
    print(f"    Right limit : {calibration['right_limit']:.1f}°")
    print()
    print("  To apply these values, add this to head_controller.py:")
    print()
    print("    import json")
    print("    _cal = json.load(open('head_calibration.json'))")
    print("    HEAD_PAN_MIN    = _cal['left_limit']")
    print("    HEAD_PAN_MAX    = _cal['right_limit']")
    print("    NEUTRAL[CHANNEL_HEAD_PAN] = _cal['centre']")
    print()

    save(calibration)


if __name__ == "__main__":
    main()
