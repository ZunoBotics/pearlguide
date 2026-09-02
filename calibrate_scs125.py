"""
SCS125 Calibration Tool
-----------------------
Releases torque so you can manually move each servo to its physical limits.
Records min/max positions and saves them to servo_limits.json.
"""

import scservo_sdk as scs
import time
import threading
import json
import os
import sys

DEVICE   = '/dev/cu.usbmodem5B790785651'
BAUDRATE = 1_000_000

ADDR_TORQUE_ENABLE = 40
ADDR_GOAL_POSITION = 42
ADDR_PRESENT_POS   = 56
ADDR_PRESENT_TEMP  = 63

LIMITS_FILE = os.path.join(os.path.dirname(__file__), 'servo_limits.json')

# ── Serial setup ─────────────────────────────────────────────────────────────

port = scs.PortHandler(DEVICE)
ph   = scs.PacketHandler(0)
port.openPort()
port.setBaudRate(BAUDRATE)

def swap16(v):
    return ((v & 0xFF) << 8) | ((v >> 8) & 0xFF)

def read_pos(sid):
    pkt = [0xFF, 0xFF, sid, 0x04, 0x02, ADDR_PRESENT_POS, 0x02, 0x00]
    pkt[-1] = (~sum(pkt[2:-1])) & 0xFF
    port.ser.reset_input_buffer()
    port.ser.write(bytes(pkt))
    time.sleep(0.04)
    rx = port.ser.read(20)
    if len(rx) >= 7 and rx[0] == 0xFF and rx[4] == 0x00:
        return (rx[5] << 8) | rx[6]
    return None

def torque(sid, on: bool):
    ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 1 if on else 0)
    time.sleep(0.05)

# ── Live position display ─────────────────────────────────────────────────────

_live = False

def _live_loop(sid, deg_range):
    while _live:
        p = read_pos(sid)
        if p is not None:
            deg = round(p * deg_range / 1023, 1)
            print(f"\r    position: {p:4d} steps  ({deg:6.1f}°)   ", end='', flush=True)
        time.sleep(0.12)

def start_live(sid, deg_range=220):
    global _live
    _live = True
    t = threading.Thread(target=_live_loop, args=(sid, deg_range), daemon=True)
    t.start()

def stop_live():
    global _live
    _live = False
    time.sleep(0.2)
    print()   # newline after live display

# ── Calibration ───────────────────────────────────────────────────────────────

def calibrate_servo(sid, deg_range=220):
    print(f"\n{'='*50}")
    print(f"  Calibrating SCS125 ID {sid}")
    print(f"{'='*50}")

    temp, _, _ = ph.read1ByteTxRx(port, sid, ADDR_PRESENT_TEMP)
    if temp == 0:
        print(f"  ✗  No response from ID {sid} — skipping.")
        return None

    print(f"  Temp: {temp}°C")
    print(f"  Releasing torque — servo is now free to move.\n")
    torque(sid, False)
    time.sleep(0.1)

    # ── MIN ──
    print("  Move the servo to its MINIMUM physical limit.")
    print("  Hold it there, then press Enter to record.")
    start_live(sid, deg_range)
    input("\n  [Enter to record MIN] ")
    stop_live()
    min_pos = read_pos(sid)
    min_deg = round(min_pos * deg_range / 1023, 1) if min_pos is not None else None
    print(f"  ✓  MIN recorded: step {min_pos}  ({min_deg}°)\n")

    # ── MAX ──
    print("  Move the servo to its MAXIMUM physical limit.")
    print("  Hold it there, then press Enter to record.")
    start_live(sid, deg_range)
    input("\n  [Enter to record MAX] ")
    stop_live()
    max_pos = read_pos(sid)
    max_deg = round(max_pos * deg_range / 1023, 1) if max_pos is not None else None
    print(f"  ✓  MAX recorded: step {max_pos}  ({max_deg}°)\n")

    # ── CENTRE ──
    if min_pos is not None and max_pos is not None:
        centre = (min_pos + max_pos) // 2
        centre_deg = round(centre * deg_range / 1023, 1)
        print(f"  Calculated centre: step {centre}  ({centre_deg}°)")
        print(f"  Moving to centre...")
        torque(sid, True)
        ph.write2ByteTxOnly(port, sid, ADDR_GOAL_POSITION, swap16(centre))
        time.sleep(1.5)
        landed = read_pos(sid)
        landed_deg = round(landed * deg_range / 1023, 1) if landed else '?'
        print(f"  Landed at: step {landed}  ({landed_deg}°)")
        torque(sid, False)
    else:
        centre = 512
        centre_deg = None

    return {
        "model":      "SCS125",
        "deg_range":  deg_range,
        "min_step":   min_pos,
        "min_deg":    min_deg,
        "max_step":   max_pos,
        "max_deg":    max_deg,
        "centre_step": centre,
        "centre_deg": centre_deg,
        "calibrated": time.strftime("%Y-%m-%d"),
    }

# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    print("\n SCS125 Calibration Tool")
    print(" ========================")
    print(f" Limits will be saved to: {LIMITS_FILE}\n")

    # Load existing limits if any
    limits = {}
    if os.path.exists(LIMITS_FILE):
        with open(LIMITS_FILE) as f:
            limits = json.load(f)
        print(f" Existing limits found for IDs: {list(limits.keys())}\n")

    # Detect connected SCS125 motors (IDs 1 and 2)
    connected = []
    for sid in [1, 2]:
        temp, _, _ = ph.read1ByteTxRx(port, sid, ADDR_PRESENT_TEMP)
        if temp > 0:
            connected.append(sid)

    if not connected:
        print(" No SCS125 motors found on IDs 1 or 2. Check wiring.")
        port.closePort()
        return

    print(f" Found SCS125 on IDs: {connected}")
    choice = input(f" Which ID to calibrate? ({'/'.join(str(s) for s in connected)}/all): ").strip().lower()

    if choice == 'all':
        to_calibrate = connected
    elif choice.isdigit() and int(choice) in connected:
        to_calibrate = [int(choice)]
    else:
        print(" Invalid choice. Exiting.")
        port.closePort()
        return

    for sid in to_calibrate:
        result = calibrate_servo(sid)
        if result:
            limits[str(sid)] = result
            with open(LIMITS_FILE, 'w') as f:
                json.dump(limits, f, indent=2)
            print(f"\n  Saved limits for ID {sid} to {LIMITS_FILE}")

    print("\n\n Final calibration summary:")
    print(f"  {'ID':<4} {'Model':<10} {'Min step':>9} {'Min °':>7} {'Max step':>9} {'Max °':>7} {'Centre':>8}")
    print(f"  {'-'*56}")
    for sid_str, d in limits.items():
        print(f"  {sid_str:<4} {d['model']:<10} {str(d['min_step']):>9} {str(d['min_deg']):>7} {str(d['max_step']):>9} {str(d['max_deg']):>7} {str(d['centre_step']):>8}")

    port.closePort()
    print("\n Done.\n")

if __name__ == '__main__':
    main()
