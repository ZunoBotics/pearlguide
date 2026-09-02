# SCS125 / SC-1250-C001 — Verified Configuration

Tested on: 2026-07-15  
Hardware: FE-URT-2 USB adapter → macOS (`/dev/cu.usbmodem5B790785651`)  
SDK: `scservo_sdk` (pip)

---

## Connection

| Parameter   | Value                          |
|-------------|--------------------------------|
| Port (Mac)  | `/dev/cu.usbmodem*` (FE-URT-2) |
| Port (Pi)   | `/dev/ttyUSB0` or `/dev/ttyACM0` |
| Baud rate   | **1,000,000 (1 Mbps)**         |
| Protocol    | SCS — `PacketHandler(0)`       |
| Default ID  | **1**                          |
| Voltage     | 7.3 V (tested at 4–8.4 V range)|

---

## Critical: Byte-Order Bug in scservo_sdk

The SCS125 uses **big-endian** byte order for 16-bit position values.  
`scservo_sdk` assumes **little-endian**.  

**All 16-bit writes and reads require a byte swap.**

```python
def swap16(val: int) -> int:
    return ((val & 0xFF) << 8) | ((val >> 8) & 0xFF)
```

### Write position (apply swap before sending)
```python
ph.write2ByteTxOnly(port, sid, ADDR_GOAL_POSITION, swap16(target))
```

### Read position (use raw packet + big-endian decode)
```python
def read_pos(port, sid):
    ADDR_PRESENT_POS = 56
    txpkt = [0xFF, 0xFF, sid, 0x04, 0x02, ADDR_PRESENT_POS, 0x02, 0x00]
    txpkt[-1] = (~sum(txpkt[2:-1])) & 0xFF
    port.ser.reset_input_buffer()
    port.ser.write(bytes(txpkt))
    time.sleep(0.04)
    rx = port.ser.read(20)
    if len(rx) >= 7 and rx[0] == 0xFF and rx[4] == 0x00:
        return (rx[5] << 8) | rx[6]   # big-endian: high byte first
    return None
```

> **1-byte reads (voltage, temperature) are NOT affected** — byte swap only applies to 2-byte position values.

---

## Register Map (verified)

| Register | Address | Access | Notes                        |
|----------|---------|--------|------------------------------|
| ID                | 5  | R/W | Default = 1                  |
| Torque Enable     | 40 | R/W | 0 = off, 1 = on              |
| Goal Position     | 42 | W   | **Requires swap16()**        |
| Present Position  | 56 | R   | **Use raw big-endian read**  |
| Present Load      | 60 | R   |                              |
| Present Voltage   | 62 | R   | Value ÷ 10 = volts           |
| Present Temp      | 63 | R   | Value in °C                  |

---

## Position Range

| Position | Angle   | Notes                     |
|----------|---------|---------------------------|
| 0        | 0°      | Minimum (mechanical limit) |
| 512      | ~110°   | Centre                    |
| 1023     | 220°    | Maximum                   |

**Usable range observed:** 23 – 1000 (mechanical hard stops)  
**Resolution:** 0.215° per step (220° / 1023 steps)  
**Accuracy:** ±4–5 steps (±0.9°) — confirmed in sweep test

---

## Minimal Working Example

```python
import scservo_sdk as scs
import time

DEVICE   = '/dev/cu.usbmodem5B790785651'  # Mac; use /dev/ttyUSB0 on Pi
BAUDRATE = 1_000_000
SID      = 1

ADDR_TORQUE_ENABLE = 40
ADDR_GOAL_POSITION = 42
ADDR_PRESENT_POS   = 56

port = scs.PortHandler(DEVICE)
ph   = scs.PacketHandler(0)   # protocol 0 = SCS
port.openPort()
port.setBaudRate(BAUDRATE)

def swap16(val):
    return ((val & 0xFF) << 8) | ((val >> 8) & 0xFF)

def read_pos():
    txpkt = [0xFF, 0xFF, SID, 0x04, 0x02, ADDR_PRESENT_POS, 0x02, 0x00]
    txpkt[-1] = (~sum(txpkt[2:-1])) & 0xFF
    port.ser.reset_input_buffer()
    port.ser.write(bytes(txpkt))
    time.sleep(0.04)
    rx = port.ser.read(20)
    if len(rx) >= 7 and rx[0] == 0xFF and rx[4] == 0x00:
        return (rx[5] << 8) | rx[6]
    return None

def move(target, wait=1.3):
    ph.write2ByteTxOnly(port, SID, ADDR_GOAL_POSITION, swap16(target))
    time.sleep(wait)
    return read_pos()

# Enable torque
ph.write1ByteTxOnly(port, SID, ADDR_TORQUE_ENABLE, 1)
time.sleep(0.1)

move(512)   # centre
move(100)   # left
move(900)   # right
move(512)   # centre

# Disable torque
ph.write1ByteTxOnly(port, SID, ADDR_TORQUE_ENABLE, 0)
port.closePort()
```

---

## Lip-Sync Notes

For jaw control at 20–50 Hz:

- At 1 Mbps, a single write packet takes ~0.1 ms → safe for 100 Hz updates
- Flush `port.ser.reset_input_buffer()` before every read to avoid half-duplex echo noise
- Jaw range for speech: **0 (closed) → ~300 (wide open)** — stay well below mechanical limit
- Smooth motion: send intermediate positions rather than jumping to target directly
  ```python
  # Ease jaw from current to target in small steps
  for pos in range(current, target, step):
      ph.write2ByteTxOnly(port, SID, ADDR_GOAL_POSITION, swap16(pos))
      time.sleep(0.01)
  ```

---

## Wiring (FE-URT-2 → SCS125)

```
FE-URT-2 pin    SCS125 wire
──────────────────────────
5V  (red)    →  VCC (power — or use external 7.4V supply)
GND (black)  →  GND
DATA (white) →  DATA (half-duplex TTL serial — single wire)
```

> Power the servo from an external 7.4V supply for full torque.  
> The FE-URT-2 5V rail can only power the servo lightly loaded.

---

## Compatibility with STS3215 (wheel motors)

Both use `scservo_sdk` with `PacketHandler(0)`.  
They **can share the same serial bus** (different IDs).  
**However:** STS3215 uses little-endian — do NOT apply `swap16()` to STS3215 writes.  
Keep separate helper functions per servo type if mixing on one bus.
