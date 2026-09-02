# STS3215 — Verified Configuration

Tested on: 2026-07-15  
Hardware: FE-URT-2 USB adapter → macOS (`/dev/cu.usbmodem5B790785651`)  
SDK: `scservo_sdk` (pip)

---

## Connection

| Parameter   | Value                            |
|-------------|----------------------------------|
| Port (Mac)  | `/dev/cu.usbmodem*` (FE-URT-2)  |
| Port (Pi)   | `/dev/ttyUSB0` or `/dev/ttyACM0`|
| Baud rate   | **1,000,000 (1 Mbps)**           |
| Protocol    | SCS — `PacketHandler(0)`         |
| Default ID  | **1**                            |
| Voltage     | 7.2–7.3 V (tested at 7.4 V)     |

---

## Critical: Little-Endian — NO swap16()

Unlike SCS125 and SCS0009, the STS3215 uses **little-endian** byte order.  
`scservo_sdk` already sends little-endian — **do NOT apply `swap16()`** to writes.

> Keep separate write helpers per servo family if mixing on one bus.

---

## Operating Mode

STS3215 wheel motors must be set to **wheel mode** before use:

```python
ADDR_OPERATING_MODE = 33
MODE_WHEEL          = 1
MODE_POSITION       = 0

ph.write1ByteTxOnly(port, sid, ADDR_OPERATING_MODE, MODE_WHEEL)
time.sleep(0.05)
ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 1)
```

> In wheel mode, Goal Position (42) is ignored. Speed is controlled via Goal Speed (46).

---

## Speed Control (Wheel Mode)

Speed is a direction bit + 15-bit magnitude packed into a 16-bit word:

```python
def set_speed(port, ph, sid, raw_speed: int):
    """raw_speed: positive = forward, negative = reverse, 0 = stop. Range: -32767 to 32767"""
    direction = 0 if raw_speed >= 0 else 1
    magnitude = min(abs(raw_speed), 32767)
    word = (direction << 15) | magnitude
    ph.write2ByteTxOnly(port, sid, ADDR_GOAL_SPEED, word)
```

| Speed value | Meaning         |
|-------------|-----------------|
| 0           | Stop            |
| 1–32767     | Forward (slow→fast) |
| -1 to -32767| Reverse (slow→fast) |
| ±500        | Low / safe test speed |
| ±3000       | **Verified high speed — all 3 motors confirmed at this value** |
| ±32767      | Theoretical max (untested) |

### High-speed sweep test (ID 7, 8, 9 simultaneously — 2026-07-15)

| Direction | ID 7 delta | ID 8 delta | ID 9 delta |
|-----------|-----------|-----------|-----------|
| Forward  +3000 | +65525→191 (wrap) | +3995→1733 | +1267→3057 |
| Reverse  −3000 | None→2723 | +3950→1722 | +3289→1507 |

All 3 spin freely at ±3000 with no stalling. Position reads occasionally return `None` at high speed when all 3 are on the bus simultaneously (half-duplex echo collision) — not a real error.

---

## Register Map (verified)

| Register           | Address | Access | Notes                              |
|--------------------|---------|--------|------------------------------------|
| Operating Mode     | 33      | R/W    | 0 = position, 1 = wheel            |
| Torque Enable      | 40      | R/W    | 0 = off, 1 = on                    |
| Goal Position      | 42      | W      | Position mode only                 |
| Goal Speed         | 46      | W      | **Wheel mode speed — direction+magnitude** |
| Present Position   | 56      | R      | 12-bit (0–4095), little-endian     |
| Present Voltage    | 62      | R      | Value ÷ 10 = volts                 |
| Present Temp       | 63      | R      | Value in °C                        |

---

## Reading Position

Use raw packet with **little-endian** decode (low byte first):

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
        return rx[5] | (rx[6] << 8)   # little-endian: low byte first
    return None
```

> Position range: 0–4095 (12-bit, 360°). In wheel mode the value wraps continuously.

---

## Assigning an ID

```python
ADDR_ID   = 5
ADDR_LOCK = 48

ph.write1ByteTxOnly(port, OLD_ID, ADDR_LOCK, 0)
time.sleep(0.1)
ph.write1ByteTxOnly(port, OLD_ID, ADDR_ID, NEW_ID)
time.sleep(0.3)
ph.write1ByteTxOnly(port, NEW_ID, ADDR_LOCK, 1)
time.sleep(0.1)
```

---

## Minimal Working Example

```python
import scservo_sdk as scs
import time

DEVICE   = '/dev/cu.usbmodem5B790785651'  # Mac; use /dev/ttyUSB0 on Pi
BAUDRATE = 1_000_000

ADDR_OPERATING_MODE = 33
ADDR_TORQUE_ENABLE  = 40
ADDR_GOAL_SPEED     = 46
MODE_WHEEL          = 1

port = scs.PortHandler(DEVICE)
ph   = scs.PacketHandler(0)
port.openPort()
port.setBaudRate(BAUDRATE)

def set_speed(sid, raw):
    direction = 0 if raw >= 0 else 1
    word = (direction << 15) | min(abs(raw), 32767)
    ph.write2ByteTxOnly(port, sid, ADDR_GOAL_SPEED, word)

MOTORS = [7, 8, 9]

for sid in MOTORS:
    ph.write1ByteTxOnly(port, sid, ADDR_OPERATING_MODE, MODE_WHEEL)
    time.sleep(0.05)
    ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 1)
    time.sleep(0.05)

# Forward
for sid in MOTORS: set_speed(sid, 2000)
time.sleep(2.0)

# Reverse
for sid in MOTORS: set_speed(sid, -2000)
time.sleep(2.0)

# Stop
for sid in MOTORS:
    set_speed(sid, 0)
    ph.write1ByteTxOnly(port, sid, ADDR_TORQUE_ENABLE, 0)

port.closePort()
```

---

## Intended Role in Okello Robot

| ID | Motor    | Role                   |
|----|----------|------------------------|
| 7  | STS3215  | Omni-wheel LEFT        |
| 8  | STS3215  | Omni-wheel BACK        |
| 9  | STS3215  | Omni-wheel RIGHT       |

All three share the same serial bus as SCS125 (IDs 1, 2) and SCS0009 (IDs 3, 4).

---

## Compatibility Notes

- Same SCS protocol (`PacketHandler(0)`) as SCS125 and SCS0009
- **Little-endian** — do NOT apply `swap16()` (SCS125/SCS0009 need it; STS3215 does not)
- Can share the same serial bus (IDs 7, 8, 9 confirmed coexisting with IDs 1–4)
- In wheel mode, position reads still work and can be used for odometry
