"""
SCS125 Auto-Calibration Tool
------------------------------
Sweeps slowly in both directions to find physical limits.
Saves min/max to servo_limits.json.
"""

import scservo_sdk as scs, time, json, os

DEVICE   = '/dev/cu.usbmodem5B790785651'
BAUDRATE = 1_000_000

ADDR_TORQUE_ENABLE = 40
ADDR_GOAL_POSITION = 42
ADDR_PRESENT_POS   = 56
ADDR_PRESENT_TEMP  = 63

DEG_RANGE     = 220
STEP_SIZE     = 15
STEP_WAIT     = 0.5
STUCK_THRESH  = 10
STUCK_CONFIRM = 3
SAFETY_MARGIN = 20

LIMITS_FILE = os.path.join(os.path.dirname(__file__), 'servo_limits.json')

port = scs.PortHandler(DEVICE)
ph   = scs.PacketHandler(0)
port.openPort()
port.setBaudRate(BAUDRATE)

def swap16(v): return ((v & 0xFF) << 8) | ((v >> 8) & 0xFF)

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

def move_to(sid, target):
    target = max(0, min(1023, target))
    ph.write2ByteTxOnly(port, sid, ADDR_GOAL_POSITION, swap16(target))
    time.sleep(STEP_WAIT)
    return read_pos(sid)

def sweep(sid, start, direction):
    label = '→ MAX' if direction > 0 else '← MIN'
    print(f"    Sweeping {label} from step {start}...")
    last_pos = start
    stuck_count = 0
    limit_pos = start
    target = start

    while True:
        target = max(0, min(1023, target + direction * STEP_SIZE))
        landed = move_to(sid, target)
        if landed is None:
            time.sleep(0.2)
            landed = read_pos(sid)
            if landed is None:
                break

        moved = abs(landed - last_pos)
        deg = round(landed * DEG_RANGE / 1023, 1)
        print(f"      target={target:4d}  landed={landed:4d} ({deg:6.1f}°)  moved={moved}")

        if moved < STUCK_THRESH:
            stuck_count += 1
            if stuck_count >= STUCK_CONFIRM:
                limit_pos = last_pos
                print(f"    ✓  Limit at step {limit_pos} ({round(limit_pos * DEG_RANGE / 1023, 1)}°)")
                break
        else:
            stuck_count = 0
            last_pos = landed
            limit_pos = landed

        if target <= 0 or target >= 1023:
            limit_pos = landed
            print(f"    ✓  Hit absolute boundary at step {limit_pos}")
            break

    return limit_pos

def calibrate(sid):
    print(f"\n{'='*55}")
    print(f"  Auto-calibrating SCS125 ID {sid}")
    print(f"{'='*55}")

    temp, _, _ = ph.read1ByteTxRx(port, sid, ADDR_PRESENT_TEMP)
    if temp == 0:
        print(f"  No response from ID {sid} — skipping.")
        return None

    start = read_pos(sid)
    print(f"  Temp: {temp}°C   Start: step {start} ({round(start * DEG_RANGE / 1023, 1)}°)\n")

    ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 1)
    time.sleep(0.1)

    print("  Moving to centre (512)...")
    move_to(sid, 512)
    time.sleep(0.5)

    raw_min = sweep(sid, 512, -1)
    safe_min = raw_min + SAFETY_MARGIN
    print(f"  Safe MIN: step {safe_min} ({round(safe_min * DEG_RANGE / 1023, 1)}°)\n")

    print("  Returning to centre...")
    move_to(sid, 512)
    time.sleep(0.8)

    raw_max = sweep(sid, 512, +1)
    safe_max = raw_max - SAFETY_MARGIN
    print(f"  Safe MAX: step {safe_max} ({round(safe_max * DEG_RANGE / 1023, 1)}°)\n")

    safe_centre = (safe_min + safe_max) // 2
    print(f"  Moving to safe centre: step {safe_centre}")
    move_to(sid, safe_centre)
    ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 0)

    return {
        "model":         "SCS125",
        "deg_range":     DEG_RANGE,
        "raw_min_step":  raw_min,
        "raw_max_step":  raw_max,
        "min_step":      safe_min,
        "min_deg":       round(safe_min * DEG_RANGE / 1023, 1),
        "max_step":      safe_max,
        "max_deg":       round(safe_max * DEG_RANGE / 1023, 1),
        "centre_step":   safe_centre,
        "centre_deg":    round(safe_centre * DEG_RANGE / 1023, 1),
        "safety_margin": SAFETY_MARGIN,
        "calibrated":    time.strftime("%Y-%m-%d"),
    }


def main():
    print("\n SCS125 Auto-Calibration Tool")
    print(" ==============================")
    print(f" Limits saved to: {LIMITS_FILE}\n")

    limits = {}
    if os.path.exists(LIMITS_FILE):
        with open(LIMITS_FILE) as f:
            limits = json.load(f)

    connected = []
    for sid in [1, 2]:
        temp, _, _ = ph.read1ByteTxRx(port, sid, ADDR_PRESENT_TEMP)
        if temp > 0:
            connected.append(sid)

    if not connected:
        print(" No SCS125 found on IDs 1 or 2.")
        port.closePort()
        return

    print(f" Found SCS125 on IDs: {connected}")
    choice = input(f" Which ID to calibrate? ({'/'.join(str(s) for s in connected)}/all): ").strip().lower()

    if choice == 'all':
        to_calibrate = connected
    elif choice.isdigit() and int(choice) in connected:
        to_calibrate = [int(choice)]
    else:
        print(" Invalid choice.")
        port.closePort()
        return

    for sid in to_calibrate:
        result = calibrate(sid)
        if result:
            limits[str(sid)] = result
            with open(LIMITS_FILE, 'w') as f:
                json.dump(limits, f, indent=2)
            print(f"\n  ✓  Saved limits for ID {sid}")

    print("\n\n Final limits:")
    print(f"  {'ID':<4} {'Model':<10} {'Min':>6} {'Min°':>7} {'Max':>6} {'Max°':>7} {'Centre':>8}")
    print(f"  {'-'*52}")
    with open(LIMITS_FILE) as f:
        data = json.load(f)
    for sid_str, d in data.items():
        print(f"  {sid_str:<4} {d['model']:<10} {str(d['min_step']):>6} {str(d['min_deg']):>7} {str(d['max_step']):>6} {str(d['max_deg']):>7} {str(d['centre_step']):>8}")

    port.closePort()
    print("\n Done.\n")

if __name__ == '__main__':
    main()
