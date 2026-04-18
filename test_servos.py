"""
Quick servo test — run this to verify all head servos are working.
Usage:  python test_servos.py
"""

import time
from head_controller import HeadController

head = HeadController()

if not head.hardware_ready:
    print(f"ERROR: {head.error_message}")
    print("If 'sudo i2cdetect -y 1' shows 0x40, install the servo dependencies into this Python environment:")
    print("  pip install -r requirements.txt")
    exit(1)

print("HeadController initialised. Starting tests...\n")
time.sleep(1)

# --- Eye Tilt ---
print("Eye Tilt: up → centre → down")
head.set_eye_tilt(60);  time.sleep(0.8)
head.set_eye_tilt(90);  time.sleep(0.8)
head.set_eye_tilt(120); time.sleep(0.8)
head.set_eye_tilt(90);  time.sleep(0.5)

# --- Eye Pan ---
print("Eye Pan: left → centre → right")
head.set_eye_pan(50);  time.sleep(0.8)
head.set_eye_pan(90);  time.sleep(0.8)
head.set_eye_pan(130); time.sleep(0.8)
head.set_eye_pan(90);  time.sleep(0.5)

# --- Jaw ---
print("Jaw: open → close")
head.set_jaw(0.0);  time.sleep(0.5)
head.set_jaw(0.5);  time.sleep(0.8)
head.set_jaw(1.0);  time.sleep(0.8)
head.set_jaw(0.0);  time.sleep(0.5)

# --- Head Pan ---
print("Head Pan: left → centre → right")
head.set_head_pan(50);  time.sleep(0.8)
head.set_head_pan(90);  time.sleep(0.8)
head.set_head_pan(130); time.sleep(0.8)
head.set_head_pan(90);  time.sleep(0.5)

# --- Combined ---
print("Nod...")
head.nod(cycles=2)
time.sleep(0.5)

print("Shake head...")
head.shake_head(cycles=2)
time.sleep(0.5)

# --- Interactive ---
print("\nInteractive test. Enter command or 'q' to quit.")
print("  et <angle>  — eye tilt (60–120)")
print("  ep <angle>  — eye pan  (50–130)")
print("  j  <0..1>   — jaw open amount")
print("  hp <angle>  — head pan (30–150)")
print("  c           — centre all")

while True:
    try:
        cmd = input("> ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        break
    if cmd == "q":
        break
    elif cmd == "c":
        head.center_all()
    elif cmd.startswith("et "):
        head.set_eye_tilt(float(cmd[3:]))
    elif cmd.startswith("ep "):
        head.set_eye_pan(float(cmd[3:]))
    elif cmd.startswith("j "):
        head.set_jaw(float(cmd[2:]))
    elif cmd.startswith("hp "):
        head.set_head_pan(float(cmd[3:]))
    else:
        print("Unknown command")

head.shutdown()
print("Done.")
