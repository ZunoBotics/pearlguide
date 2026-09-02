# SCS0009 — Verified Configuration

Tested on: 2026-07-15  
Hardware: FE-URT-2 USB adapter → macOS (`/dev/cu.usbmodem5B790785651`)  
SDK: `scservo_sdk` (pip)

---

## Connection

| Parameter   | Value                           |
|-------------|---------------------------------|
| Port (Mac)  | `/dev/cu.usbmodem*` (FE-URT-2)  |
| Port (Pi)   | `/dev/ttyUSB0` or `/dev/ttyACM0`|
| Baud rate   | **1,000,000 (1 Mbps)**          |
| Protocol    | SCS — `PacketHandler(0)`        |
| Default ID  | **1** (reassigned to 3 in test) |
| Voltage     | 7.4 V (tested at 4–8.4 V range) |

---

## Critical: Same Big-Endian Bug as SCS125

The SCS0009 uses **big-endian** byte order for 16-bit position values,  
exactly like the SCS125. `scservo_sdk` assumes little-endian.  

**Apply `swap16()` to all 16-bit writes. Use raw packet for reads.**

```python
def swap16(val: int) -> int:
    return ((val & 0xFF) << 8) | ((val >> 8) & 0xFF)
```

### Write position
```python
ph.write2ByteTxOnly(port, sid, ADDR_GOAL_POSITION, swap16(target))
```

### Read position
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

> **1-byte reads (voltage, temperature) are NOT affected** — swap16 only applies to 2-byte values.

---

## Register Map (verified)

| Register          | Address | Access | Notes                        |
|-------------------|---------|--------|------------------------------|
| ID                | 5       | R/W    | Default = 1                  |
| Lock (EEPROM)     | 48      | R/W    | 0 = unlocked, 1 = locked     |
| Torque Enable     | 40      | R/W    | 0 = off, 1 = on              |
| Goal Position     | 42      | W      | **Requires swap16()**        |
| Present Position  | 56      | R      | **Use raw big-endian read**  |
| Present Voltage   | 62      | R      | Value ÷ 10 = volts           |
| Present Temp      | 63      | R      | Value in °C                  |

---

## Position Range & Accuracy

| Position | Angle (est.) | Notes                      |
|----------|--------------|----------------------------|
| 0        | 0°           | Minimum (mechanical limit) |
| 512      | ~150°        | Centre                     |
| 1023     | ~300°        | Maximum                    |

**Accuracy: ±0–2 steps (≤ 0.3°)** — noticeably better than SCS125 (±4–5 steps)  
**Resolution:** ~0.293° per step (300° / 1023 steps)

### Sweep test results (ID 3)

| Target | Landed | Error |
|--------|--------|-------|
| 512    | 511    | 1     |
| 100    | 100    | 0     |
| 300    | 300    | 0     |
| 512    | 510    | 2     |
| 700    | 699    | 1     |
| 900    | 899    | 1     |
| 512    | 511    | 1     |

---

## Assigning an ID

```python
ADDR_ID   = 5
ADDR_LOCK = 48

ph.write1ByteTxOnly(port, OLD_ID, ADDR_LOCK, 0)    # unlock EEPROM
time.sleep(0.1)
ph.write1ByteTxOnly(port, OLD_ID, ADDR_ID, NEW_ID)  # write new ID
time.sleep(0.3)
ph.write1ByteTxOnly(port, NEW_ID, ADDR_LOCK, 1)    # re-lock EEPROM
time.sleep(0.1)
```

> After reassignment, verify with a 1-byte read (temperature) before relying on the new ID.

---

## Minimal Working Example

```python
import scservo_sdk as scs
import time

DEVICE   = '/dev/cu.usbmodem5B790785651'  # Mac; use /dev/ttyUSB0 on Pi
BAUDRATE = 1_000_000
SID      = 3   # assigned ID

ADDR_TORQUE_ENABLE = 40
ADDR_GOAL_POSITION = 42
ADDR_PRESENT_POS   = 56

port = scs.PortHandler(DEVICE)
ph   = scs.PacketHandler(0)
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

def move(target, wait=1.2):
    ph.write2ByteTxOnly(port, SID, ADDR_GOAL_POSITION, swap16(target))
    time.sleep(wait)
    return read_pos()

ph.write1ByteTxOnly(port, SID, ADDR_TORQUE_ENABLE, 1)
time.sleep(0.1)

move(512)   # centre
move(100)   # min
move(900)   # max
move(512)   # centre

ph.write1ByteTxOnly(port, SID, ADDR_TORQUE_ENABLE, 0)
port.closePort()
```

---

## Intended Role in Okello Head

| ID | Servo   | Role           |
|----|---------|----------------|
| 1  | SCS125  | Jaw (lip-sync) |
| 2  | SCS125  | Head pan       |
| 3  | SCS0009 | Eye pan / tilt |

> If a second SCS0009 is added for the other eye axis, assign it **ID 4**.

---

## Compatibility Notes

- Same SCS protocol (`PacketHandler(0)`) and `swap16()` fix as SCS125
- Can share the **same serial bus** as SCS125 (IDs 1, 2, 3 confirmed coexisting)
- Same `scservo_sdk` — no additional library needed
- STS3215 wheel motors also share the bus but do **NOT** need `swap16()` — keep separate write helpers per servo family
