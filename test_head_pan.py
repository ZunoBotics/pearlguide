"""
Head-pan full-range test.

Purpose
-------
The head_controller.py clamps head pan to 30–150° for safe operation.
This script bypasses those limits and drives the PCA9685 channel 2
(head pan servo) directly so you can verify the physical full range
and find the safe mechanical limits for your specific mounting.

Usage
-----
    python test_head_pan.py           # runs the sweep + interactive mode
    python test_head_pan.py --sweep   # sweep only, no interactive prompt

CAUTION
-------
- Watch the robot head and be ready to press Ctrl+C.
- Stop immediately if the servo sounds strained or the head hits a stop.
- Note the lowest and highest angles that work cleanly — then update
  HEAD_PAN_MIN and HEAD_PAN_MAX in head_controller.py accordingly.
"""

import argparse
import time

# ---------------------------------------------------------------------------
# Hardware init — bypass ServoKit wrapper to set angle directly
# ---------------------------------------------------------------------------

try:
    from adafruit_servokit import ServoKit
except ImportError:
    print("ERROR: adafruit_servokit not installed. Run:  pip install -r requirements.txt")
    raise SystemExit(1)

CHANNEL = 2          # head pan is on CH2
PULSE_MIN = 1000      # µs — standard servo minimum pulse (0°)
PULSE_MAX = 2600     # µs — extended maximum pulse (180°)
FREQUENCY = 50       # Hz

kit = ServoKit(channels=16, address=0x40, frequency=FREQUENCY)
kit.servo[CHANNEL].set_pulse_width_range(PULSE_MIN, PULSE_MAX)

print(f"PCA9685 ready — driving channel {CHANNEL} (head pan)")
print(f"Pulse range: {PULSE_MIN}–{PULSE_MAX} µs  |  Frequency: {FREQUENCY} Hz\n")


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def set_angle(angle: float, label: str = ""):
    """Set servo angle with clamping to 0–180 and optional console label."""
    angle = max(0.0, min(180.0, float(angle)))
    kit.servo[CHANNEL].angle = angle
    tag = f"  [{label}]" if label else ""
    print(f"  → {angle:6.1f}°{tag}")


def centre():
    set_angle(90, "centre")


# ---------------------------------------------------------------------------
# Sweep
# ---------------------------------------------------------------------------

def run_sweep():
    print("=== Slow sweep 0 → 180 → 0 ===")
    print("Watch the head. Press Ctrl+C if it sounds strained or hits a stop.\n")

    centre()
    time.sleep(1.0)

    # 0 → 180 in 5° steps
    print("→ sweeping 0° … 180°")
    for angle in range(0, 181, 5):
        set_angle(angle)
        time.sleep(0.12)

    time.sleep(0.5)

    # 180 → 0 in 5° steps
    print("→ sweeping 180° … 0°")
    for angle in range(180, -1, -5):
        set_angle(angle)
        time.sleep(0.12)

    time.sleep(0.5)
    centre()
    print("\nSweep complete. Did the head reach both extremes cleanly?")
    print("Note the angles where it starts to strain — those are your true limits.\n")


# ---------------------------------------------------------------------------
# Shake-head gesture (left ↔ right)
# ---------------------------------------------------------------------------

def shake(cycles: int = 3, left: float = 30.0, right: float = 150.0,
          speed: float = 0.1):
    print(f"=== Shake head ({cycles} cycles, {left}° ↔ {right}°) ===")
    centre()
    time.sleep(0.3)
    for _ in range(cycles):
        set_angle(left,  "left")
        time.sleep(speed)
        set_angle(right, "right")
        time.sleep(speed)
    centre()
    print()


# ---------------------------------------------------------------------------
# Interactive prompt
# ---------------------------------------------------------------------------

INTERACTIVE_HELP = """\
Commands:
  <angle>          set angle (0–180), e.g.  45
  s                slow sweep (0 → 180 → 0)
  n                shake-head gesture using current left/right limits
  n <l> <r>        shake with custom limits, e.g.  n 20 160
  c                centre (90°)
  p <min> <max>    change pulse-width range (µs), e.g.  p 400 2600
  q                quit
"""


def run_interactive():
    print("=== Interactive mode ===")
    print(INTERACTIVE_HELP)
    while True:
        try:
            cmd = input("> ").strip()
        except (EOFError, KeyboardInterrupt):
            break

        if not cmd:
            continue
        if cmd == "q":
            break
        elif cmd == "c":
            centre()
        elif cmd == "s":
            run_sweep()
        elif cmd.startswith("n"):
            parts = cmd.split()
            if len(parts) == 3:
                shake(left=float(parts[1]), right=float(parts[2]))
            else:
                shake()
        elif cmd.startswith("p "):
            parts = cmd.split()
            if len(parts) == 3:
                pmin, pmax = int(parts[1]), int(parts[2])
                kit.servo[CHANNEL].set_pulse_width_range(pmin, pmax)
                print(f"  Pulse range updated: {pmin}–{pmax} µs")
            else:
                print("  Usage: p <min_us> <max_us>")
        else:
            try:
                set_angle(float(cmd))
            except ValueError:
                print("  Unknown command. Type a number or see help above.")


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(description="Head-pan full-range servo test")
    parser.add_argument("--sweep", action="store_true",
                        help="Run sweep only, skip interactive prompt")
    args = parser.parse_args()

    try:
        run_sweep()
        if not args.sweep:
            run_interactive()
    except KeyboardInterrupt:
        print("\nInterrupted.")
    finally:
        centre()
        time.sleep(0.3)
        kit.servo[CHANNEL].angle = None   # release PWM
        print("Done — servo released.")


if __name__ == "__main__":
    main()
