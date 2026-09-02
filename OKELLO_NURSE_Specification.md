# OKELLO NURSE — Humanoid Hospital Reception Robot

**Technical Specification · June 2026**

---

## 1. System Overview

Okello is a rule-based humanoid robot deployed at hospital reception. It handles patient intake, identity verification, insurance/payment capture, temperature measurement, complaint capture, department routing, and queue generation. It does **not** diagnose, prescribe, or offer clinical advice.

| Attribute          | Detail                                                              |
| ------------------ | ------------------------------------------------------------------- |
| System Name        | Okello Nurse                                                        |
| Deployment         | Hospital reception / front desk                                     |
| Interface          | Touch screen + voice (TTS / STT) on humanoid frame                  |
| Routing Logic      | Strict rule-based keyword mapping                                   |
| Clinical Role      | Administrative only. No diagnosis, no treatment suggestion.         |
| ID Methods         | Patient Number · NIN · Phone Number                                 |

---

## 2. Core Functional Modules

| Module                  | Responsibility                                                                  | Key Constraints                                              |
| ----------------------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------ |
| Patient Intake          | Greet patient, collect consent, initiate session                                | Must obtain verbal/touch consent before data capture        |
| Identity Verification   | Determine new vs. returning; capture Patient No., NIN, or phone                 | Exact match against patient DB                              |
| Payment / Insurance     | Capture insurer name, policy number, or cash declaration                        | Record only — no payment processing                         |
| Temperature Check       | Measure body temperature via integrated IR sensor                                | Flag if ≥ 37.5 °C. Log reading to record.                   |
| Complaint Capture       | Record patient-stated chief complaint in free text                              | No interpretation, no follow-up clinical questions          |
| Rule-Based Routing      | Match complaint keywords to a department using a lookup table                   | Deterministic only. Ambiguous = General Medicine default.   |
| Queue Management        | Generate unique sequential queue number per department                          | Format: `[DEPT_CODE]-[YYYYMMDD]-[SEQ]`                      |
| Output / Direction      | Print or display queue ticket; direct patient to waiting area                   | Voice + screen output. No additional advice.                |

---

## 3. Full Process Flow

Sequential steps. Decision nodes use `IF / ELSE / END` syntax.

### START

### STEP 1 — Greeting + Consent

Okello says: *"Welcome. I will help register you. May I proceed?"* (voice + screen)

- **IF** patient selects **NO** (decline)
  - Print message: "Please proceed to the human reception desk."
  - END SESSION
- **ELSE** (consent given)
  - Record consent timestamp
  - Proceed to STEP 2

### STEP 2 — Visit History Check

Okello asks: *"Have you visited this hospital before?"*

- **IF YES** (returning patient) → Proceed to STEP 3A
- **ELSE** (new patient) → Proceed to STEP 3B

### STEP 3A — Returning Patient — Identity Capture

Okello asks: *"Please provide your Patient Number, NIN, or registered phone number."*

- **IF** Patient Number provided
  - Query DB: match on Patient Number
  - Match found → load patient record → go to STEP 4
  - Else → prompt retry (max 2 attempts) → escalate to staff on 3rd fail
- **ELSE IF** NIN provided
  - Query DB: match on NIN
  - Match found → load patient record → go to STEP 4
  - Else → prompt retry (max 2 attempts) → escalate to staff on 3rd fail
- **ELSE IF** phone number provided
  - Query DB: match on phone
  - Match found → load patient record → go to STEP 4
  - Else → prompt retry (max 2 attempts) → escalate to staff on 3rd fail

### STEP 3B — New Patient — Registration

Collect: Full name, date of birth, sex, phone number, NIN (optional)

- System assigns new Patient Number
- Create new patient record in DB
- Go to STEP 4

### STEP 4 — Payment / Insurance Capture

Okello asks: *"Are you covered by insurance or paying directly?"*

- **IF** Insurance
  - Collect: Insurer name, policy/member number
  - Record on visit record
- **ELSE** (cash / self-pay)
  - Record: Payment method = CASH
- Proceed to STEP 5

### STEP 5 — Temperature Measurement

Okello says: *"Please hold still while I check your temperature."*

- IR sensor reads temperature
- Log reading + timestamp to visit record
- **IF** temperature ≥ 37.5 °C
  - Set flag: `HIGH_TEMP = TRUE`
  - Note on visit record (staff will see at department)
  - Do **not** tell patient they have a fever or suggest illness
- Proceed to STEP 6

### STEP 6 — Complaint Capture

Okello asks: *"In your own words, what brings you in today?"*

- Accept free-text input (voice or keyboard)
- Store raw text to visit record (no interpretation shown to patient)
- Internally run keyword match against routing table → go to STEP 7

### STEP 7 — Rule-Based Routing

Run complaint text through keyword lookup table (see Section 4).

- **IF** keyword match found → assign matched department
- **ELSE IF** `HIGH_TEMP = TRUE` AND no match → route to General Medicine
- **ELSE** → route to General Medicine (default)

**SPECIAL CASE — Emergency keywords detected**

- Set flag: `EMERGENCY = TRUE`
- Skip queue → alert desk staff immediately → go to STEP 8

### STEP 8 — Queue Number Generation

Format: `[DEPT_CODE]-[YYYYMMDD]-[SEQ_3_DIGIT]`

Example: `GEN-20260629-042`

- Increment department counter atomically
- Write to visit record

### STEP 9 — Output + Direction

Okello says: *"Your queue number is [NUMBER]. Please proceed to [DEPT] waiting area."*

- Print ticket (queue number + department + date/time)
- Display directions on screen
- Session ends. Record marked complete.

### END

---

## 4. Decision Rules — Complaint → Department Mapping

Keyword matching is **case-insensitive**. First match wins. Keywords are checked in order below. Partial word match is acceptable (e.g., "coughing" matches "cough").

| Priority      | Complaint Keywords (any match)                                                                | Department                  | Code |
| ------------- | --------------------------------------------------------------------------------------------- | --------------------------- | ---- |
| 1 — EMERGENCY | chest pain, can't breathe, seizure, unconscious, stroke, severe bleeding, collapse, heart attack, not breathing | EMERGENCY                   | EMG  |
| 2             | child, infant, baby, toddler, newborn, pediatric, kid                                          | Pediatrics                  | PED  |
| 3             | pregnant, pregnancy, maternity, labor, labour, antenatal, postnatal, gynecology, gynaecology, ovarian, uterus, womb, menstrual | Maternity / Gynaecology     | MAT  |
| 4             | tooth, teeth, dental, gum, jaw, mouth pain, cavity, toothache                                 | Dental                      | DEN  |
| 5             | eye, vision, sight, blurry, blind, cataract, conjunctivitis, ophthalmology                    | Ophthalmology               | OPH  |
| 6             | fracture, broken bone, ortho, joint, knee, hip, spine, back pain, injury, sprain, dislocation, limb | Orthopaedics                | ORT  |
| 7             | skin, rash, acne, eczema, dermatology, wound, lesion, burn, itch                              | Dermatology                 | DER  |
| 8             | ear, hearing, deaf, nose, throat, ENT, sinus, tonsil, tinnitus                                | ENT                         | ENT  |
| 9             | mental health, anxiety, depression, stress, psychiatric, psychosis, suicide, self-harm, counselling | Psychiatry / Mental Health  | PSY  |
| DEFAULT       | fever, cough, cold, flu, fatigue, headache, nausea, vomiting, diarrhea, general, unwell, sick, pain (unmatched), or no keyword match | General Medicine            | GEN  |

> **Note:** A patient flagged `HIGH_TEMP` with no keyword match is routed to General Medicine, not a dedicated fever clinic.

---

## 5. Output Behavior Rules

| Okello **MUST** say / do                                  | Okello **MUST NOT** say / do                                                              |
| --------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| Greet patient in clear, neutral language                  | Suggest what might be wrong with the patient                                              |
| Request and confirm consent before collecting data        | Ask clinical follow-up questions (e.g., "How long have you had this?")                    |
| State the assigned department and queue number clearly    | Interpret or rephrase the patient's stated complaint                                      |
| Direct patient to the correct physical waiting area       | State or imply a diagnosis (e.g., "You may have malaria")                                 |
| Alert staff immediately on EMERGENCY flag                 | Recommend medication, dosage, or home remedies                                            |
| Log all session data to the visit record                  | Reassure patient about their condition or prognosis                                       |
| Escalate to human staff if ID verification fails 3 times  | Override or second-guess the routing rule table                                           |
| Offer to repeat instructions on patient request           | Share one patient's data with another session                                             |

### Okello's Personality

- **Calm and unhurried** — speaks at a measured pace; never sounds rushed or impatient regardless of queue length
- **Warm but professional** — friendly enough to put anxious patients at ease, but not overly casual or chatty
- **Clear and simple** — uses plain language; avoids medical jargon in all spoken output
- **Non-reactive** — remains steady when patients are distressed, confused, or repeat themselves
- **Reassuring without overstepping** — acknowledges the patient is being helped; never comments on their health
- **Respectful of all ages** — adjusts pace and tone for elderly or very young patients without being condescending
- **Quietly persistent** — gently re-prompts when input is missing or unclear, without making the patient feel they've made a mistake

---

## 6. Escalation Rules

- **EMERGENCY keyword detected** → alert front desk staff in real time; issue no queue number; instruct patient to stay still
- **Identity verification fails 3 times** → freeze session; notify staff; do not create a record
- **Temperature sensor failure** → log error; skip step; continue with rest of flow; notify maintenance
- **DB unavailable** → freeze session; display: "System temporarily unavailable. Please see reception staff."
- **Consent declined** → end session immediately; store no data

---

## 7. Visit Record — Minimum Fields

| Field                | Type      | Notes                                    |
| -------------------- | --------- | ---------------------------------------- |
| `visit_id`           | UUID      | Auto-generated on session start          |
| `patient_id`         | String    | Assigned or retrieved from DB            |
| `session_timestamp`  | DateTime  | UTC ISO 8601                             |
| `consent_given`      | Boolean   | Must be TRUE to proceed                  |
| `visit_type`         | Enum      | `NEW` \| `RETURNING`                     |
| `payment_type`       | Enum      | `INSURANCE` \| `CASH`                    |
| `insurer_name`       | String    | Null if CASH                             |
| `policy_number`      | String    | Null if CASH                             |
| `temperature_c`      | Float     | Null if sensor failed                    |
| `high_temp_flag`     | Boolean   | TRUE if ≥ 37.5 °C                        |
| `complaint_raw`      | Text      | Verbatim patient input, unmodified       |
| `routed_department`  | String    | Dept code from routing table             |
| `queue_number`       | String    | Format: `DEPT-YYYYMMDD-SEQ`              |
| `emergency_flag`     | Boolean   | TRUE triggers immediate staff alert      |
| `staff_escalated`    | Boolean   | TRUE if transferred to human staff       |

---

## 8. Raspberry Pi Screen App — Display Specification

This section defines exactly what the Raspberry Pi touchscreen (mounted on Okello's humanoid frame) displays at every stage of the patient interaction. The Pi runs a local kiosk-mode GUI application that is the **sole visual interface** between Okello and the patient. All screens below are full-screen, high-contrast, large-type, and reachable by touch. Voice prompts (TTS) accompany each screen but are not duplicated verbatim — voice and screen are complementary, not redundant.

### 8.1 Design Principles for the Pi Screen

1. **Touch-first** — every screen has at least one large touch target (minimum 60 × 60 mm equivalent). No keyboard required unless explicit text entry is needed.
2. **High contrast** — dark text on light background by default; accessible color palette (WCAG AA minimum).
3. **Large type** — body text minimum 24 pt; headlines 40–60 pt; queue numbers 120 pt+.
4. **One question per screen** — never split a decision across multiple screens.
5. **Persistent status bar** — top of every screen shows: hospital name/logo, current step indicator (e.g., "Step 3 of 9"), and a small clock.
6. **Idle return** — after 90 seconds of no input, the app returns to the Idle/Welcome screen and clears any partial session data.
7. **No patient health info on screen** — temperature reading, complaint text, and any HIGH_TEMP flag are NEVER shown to the patient. They are logged to the visit record only and shown to staff on the staff-facing dashboard.
8. **Bilingual-ready** — layout must accommodate English plus one local language toggle (top-right corner).
9. **Accessibility** — every interactive element has a visible focus state and works with an external switch/assistive device via the Pi's GPIO or USB.

### 8.2 Screen Inventory

The Pi app renders the following screens. Each is described with: trigger, on-screen elements, voice companion, and exit condition.

---

#### Screen 0 — Idle / Welcome (Attractor Loop)

- **Trigger:** No active session; robot is waiting for a new patient.
- **On-screen elements:**
  - Hospital logo (top center)
  - Large heading: "Welcome to Okello Nurse"
  - Subheading: "Touch the screen to begin"
  - Animated soft pulse / breathing circle (calming visual)
  - Date and time (bottom right)
  - Language toggle (bottom left)
- **Voice companion (looped every 60 s):** "Welcome. I am Okello Nurse. Touch the screen to begin registration."
- **Exit condition:** Any touch → Screen 1 (Consent).

---

#### Screen 1 — Consent

- **Trigger:** Patient touched the screen to start a session. A new `visit_id` UUID is generated at this point.
- **On-screen elements:**
  - Heading: "May I register you?"
  - Body text (plain language, 26 pt): "I will ask a few questions, check your temperature, and give you a queue number. I will not examine you or give medical advice."
  - Two large buttons side by side:
    - **[ YES, PROCEED ]** (green, primary)
    - **[ NO, THANK YOU ]** (grey, secondary)
  - "Press the speaker icon to hear this again" (bottom left)
- **Voice companion:** "Welcome. I will help register you. May I proceed?"
- **Exit conditions:**
  - YES → record `consent_given = TRUE` + timestamp → Screen 2 (Visit History)
  - NO → Screen 1b (Decline)

---

#### Screen 1b — Decline / Redirect

- **Trigger:** Patient selected NO on Screen 1.
- **On-screen elements:**
  - Heading: "No problem."
  - Body: "Please proceed to the human reception desk. It is to your [left/right]."
  - Directional arrow (large, animated)
  - Button: **[ END ]**
- **Voice companion:** "Please proceed to the human reception desk."
- **Exit condition:** END pressed → session closed, no data stored → Screen 0 (Idle).

---

#### Screen 2 — Visit History Check

- **Trigger:** Consent recorded.
- **On-screen elements:**
  - Heading: "Have you visited this hospital before?"
  - Two large buttons:
    - **[ YES, I HAVE ]**
    - **[ NO, FIRST VISIT ]**
  - Back button (top left) — returns to Screen 1
- **Voice companion:** "Have you visited this hospital before?"
- **Exit conditions:**
  - YES → Screen 3A (Returning Patient ID)
  - NO → Screen 3B (New Patient Registration)

---

#### Screen 3A — Returning Patient: Identity Capture

- **Trigger:** Patient indicated prior visit.
- **On-screen elements:**
  - Heading: "How would you like to identify yourself?"
  - Three large option cards (vertically stacked):
    - **[ Patient Number ]**
    - **[ National ID (NIN) ]**
    - **[ Registered Phone Number ]**
  - On-card subtext: small example of the format (e.g., "e.g., PT-2025-01234")
  - Back button (top left)
- **Voice companion:** "Please provide your Patient Number, NIN, or registered phone number."
- **Exit condition:** Selecting any card → Screen 3A-Input (numeric keypad).

##### Screen 3A-Input — Numeric Keypad

- **Trigger:** Patient chose an ID method.
- **On-screen elements:**
  - Heading reflects selected method, e.g., "Enter your Patient Number"
  - Large numeric keypad (0–9, backspace, clear)
  - Input field showing masked entry (last 4 digits visible, rest as •)
  - **[ SUBMIT ]** button
  - **[ BACK ]** button to return to method selection
  - Attempts remaining indicator (small, top right): "Attempt 1 of 3"
- **Exit conditions:**
  - Submit + match found → Screen 4 (Payment)
  - Submit + no match → Screen 3A-Retry
  - 3rd failed attempt → Screen 9b (Staff Escalation — ID)

##### Screen 3A-Retry

- **Trigger:** ID lookup returned no match.
- **On-screen elements:**
  - Heading: "We couldn't find that record."
  - Body: "Please try again, or use a different identifier."
  - Buttons: **[ TRY AGAIN ]** · **[ USE DIFFERENT ID ]** · **[ ASK STAFF ]**
  - Attempts indicator updated
- **Voice companion:** "I'm sorry, that didn't match. Please try again or use a different identifier."
- **Exit conditions:**
  - TRY AGAIN → Screen 3A-Input (same method)
  - USE DIFFERENT ID → Screen 3A
  - ASK STAFF → Screen 9b

---

#### Screen 3B — New Patient Registration

- **Trigger:** Patient indicated first visit.
- **On-screen elements:**
  - Heading: "Let's register you."
  - Step-by-step form (one field per sub-screen, large type):
    1. Full name — on-screen QWERTY keyboard
    2. Date of birth — date picker (day / month / year)
    3. Sex — three buttons: **[ Male ] [ Female ] [ Other / Prefer not to say ]**
    4. Phone number — numeric keypad
    5. NIN — numeric keypad, marked "Optional — you may skip"
  - Progress dots (1 of 5, 2 of 5 …)
  - **[ NEXT ]** and **[ BACK ]** buttons
  - On final sub-screen: **[ SUBMIT REGISTRATION ]**
- **Voice companion (per sub-screen):** "Please enter your full name." / "Please enter your date of birth." etc.
- **Exit condition:** Submit → system creates new patient record, assigns Patient Number → Screen 4 (Payment).

---

#### Screen 4 — Payment / Insurance Capture

- **Trigger:** Patient identified (returning) or registered (new).
- **On-screen elements:**
  - Heading: "How will you be paying today?"
  - Two large cards:
    - **[ INSURANCE ]** — icon of an insurance card
    - **[ CASH / SELF-PAY ]** — icon of banknotes
  - Back button (top left)
- **Voice companion:** "Are you covered by insurance or paying directly?"
- **Exit conditions:**
  - INSURANCE → Screen 4A (Insurance Details)
  - CASH → set `payment_type = CASH` → Screen 5 (Temperature)

##### Screen 4A — Insurance Details

- **On-screen elements:**
  - Heading: "Insurance details"
  - Field 1: Insurer name — drop-down of approved insurers + "Other" option
  - Field 2: Policy / member number — alphanumeric keypad
  - **[ SUBMIT ]** button
  - **[ BACK ]** button
- **Voice companion:** "Please select your insurer and enter your policy number."
- **Exit condition:** Submit → record `insurer_name` + `policy_number`, set `payment_type = INSURANCE` → Screen 5.

---

#### Screen 5 — Temperature Measurement

- **Trigger:** Payment recorded.
- **On-screen elements:**
  - Heading: "Temperature check"
  - Large icon: thermometer
  - Body: "Please hold still while I check your temperature."
  - Animated progress ring (3-second sweep) — *visual only; does not show the actual reading*
  - When complete: green checkmark and message: "Done. Thank you."
  - **Important:** The actual °C value is **never displayed** to the patient. Only the success/acknowledgment is shown.
- **Voice companion:** "Please hold still while I check your temperature." After completion: "Thank you. Done."
- **Exit conditions:**
  - Reading successful → log `temperature_c` + set `high_temp_flag` if ≥ 37.5 °C → Screen 6
  - Sensor failure → log error, display "Sensor unavailable — skipping" for 2 s → Screen 6 (continue without temperature)

---

#### Screen 6 — Complaint Capture

- **Trigger:** Temperature step completed or skipped.
- **On-screen elements:**
  - Heading: "What brings you in today?"
  - Body: "Speak now, or type on the screen. Use your own words."
  - Microphone icon (pulsing while listening) — tap to start/stop
  - On-screen QWERTY keyboard for typed input (toggle button bottom left)
  - Live transcription area showing what the patient said/typed — *visible to the patient for confirmation only, then stored verbatim*
  - **[ DONE ]** button to confirm and submit
  - **[ CLEAR ]** button to re-enter
  - **[ I'M NOT SURE ]** escape button — routes to General Medicine
- **Voice companion:** "In your own words, what brings you in today?"
- **Exit condition:** DONE pressed → store `complaint_raw` → Screen 7 (Processing).

---

#### Screen 7 — Routing / Processing

- **Trigger:** Complaint text submitted.
- **On-screen elements:**
  - Heading: "Finding the right department for you…"
  - Animated spinner / gentle pulsing Okello logo
  - This screen intentionally lasts 1.5–2.5 seconds (perceived processing time) even if the lookup is instant — gives the patient a moment of pause and reinforces the rule-based decision.
- **Voice companion:** None (silent processing).
- **Exit conditions (internal logic):**
  - Emergency keyword matched → Screen 9a (Emergency Alert)
  - Department keyword matched → assign department → Screen 8
  - No match + `HIGH_TEMP = TRUE` → route to General Medicine → Screen 8
  - No match, no flags → route to General Medicine (default) → Screen 8

---

#### Screen 8 — Queue Number Display

- **Trigger:** Department assigned, queue number generated.
- **On-screen elements:**
  - Heading (small): "Your department:"
  - Department name in large, friendly text (e.g., "General Medicine")
  - Huge queue number display (120 pt+), e.g., **GEN-20260629-042**
  - Subtext: "Today's date and time"
  - **[ PRINT TICKET ]** button (primary)
  - **[ REPEAT ]** button — repeats voice + on-screen display
  - **[ WHERE TO GO? ]** button — opens Screen 8b (Directions)
- **Voice companion:** "Your queue number is [NUMBER]. Please proceed to [DEPARTMENT] waiting area."
- **Exit conditions:**
  - PRINT TICKET → ticket printed → Screen 8b (Directions)
  - After 30 s idle → Screen 8b automatically

##### Screen 8b — Directions

- **On-screen elements:**
  - Heading: "Please go to:"
  - Department name + waiting area name (e.g., "General Medicine — Waiting Area B")
  - Large directional arrow (left / right / straight)
  - Simple floor map thumbnail with highlighted path
  - Estimated walk time (e.g., "About 1 minute walk")
  - Button: **[ DONE ]**
- **Voice companion:** "Please proceed to the [DEPARTMENT] waiting area. It is to your [direction]."
- **Exit condition:** DONE pressed, or 30 s idle → session closed, record marked complete → Screen 0 (Idle).

---

#### Screen 9a — Emergency Alert

- **Trigger:** Emergency keywords detected during complaint routing.
- **On-screen elements:**
  - Full-screen red border, calm but unmistakable
  - Heading: "Stay calm. Help is on the way."
  - Body: "Please stay where you are. A member of staff is coming to you now."
  - Large static icon: staff alert / cross
  - No buttons — patient cannot dismiss this screen
  - Small footer: "Staff have been notified."
- **Voice companion (calm, slow):** "Please stay still. A member of staff is on the way. You are being helped."
- **Exit condition:** Staff member arrives and enters a PIN on the Pi to dismiss → session closed, `emergency_flag = TRUE`, no queue number issued → Screen 0.

---

#### Screen 9b — Staff Escalation — ID Verification Failure

- **Trigger:** 3rd failed ID attempt.
- **On-screen elements:**
  - Heading: "Let's get a staff member to help."
  - Body: "Please wait here. Someone will be with you shortly."
  - Animated "staff notified" indicator
  - No buttons for the patient
  - Small footer: "Reference: [visit_id last 6 chars]"
- **Voice companion:** "I'm having trouble finding your record. A staff member will assist you shortly."
- **Exit condition:** Staff PIN to dismiss → session closed, `staff_escalated = TRUE`, no patient record created → Screen 0.

---

#### Screen 9c — System Unavailable

- **Trigger:** Database unreachable / critical backend failure.
- **On-screen elements:**
  - Heading: "System temporarily unavailable."
  - Body: "Please see the reception staff. We apologize for the inconvenience."
  - Hospital logo
  - No buttons
- **Voice companion:** "The system is temporarily unavailable. Please see reception staff."
- **Exit condition:** Backend restored → automatic return to Screen 0.

---

#### Screen 9d — Sensor / Hardware Fault

- **Trigger:** Temperature sensor failure mid-session.
- **On-screen elements (brief, 2 s):**
  - Heading: "Continuing without temperature check."
  - Body: "You can proceed."
- **Voice companion:** "The temperature sensor is unavailable. We'll continue with the rest of your registration."
- **Exit condition:** 2 s timeout → Screen 6 (Complaint). Maintenance is notified via the staff dashboard out-of-band.

---

### 8.3 Persistent UI Elements (Present on Every Screen)

| Element          | Location      | Content                                                                    |
| ---------------- | ------------- | -------------------------------------------------------------------------- |
| Hospital logo    | Top left      | Hospital name + logo                                                       |
| Step indicator   | Top center    | "Step X of 9" — omitted on Idle, Emergency, and Error screens              |
| Clock            | Top right     | Current date and time (Africa/Kampala)                                     |
| Language toggle  | Top right     | EN / local language switch                                                 |
| Repeat button    | Bottom left   | Speaker icon — repeats the current voice prompt                            |
| Accessibility    | Bottom right  | Volume up / down, contrast toggle, text-size toggle                        |
| Back button      | Top left (sub)| Returns to previous decision screen — disabled on Emergency / Error screens|

### 8.4 Screen Color System

| State / Screen type        | Background | Primary text | Accent        | Use case                                  |
| -------------------------- | ---------- | ------------ | ------------- | ----------------------------------------- |
| Idle / Welcome             | Soft blue  | Dark navy    | White         | Calm attractor                            |
| Standard flow              | White      | Dark grey    | Hospital teal | Default for all Steps 1–8                 |
| Success / Confirmation     | White      | Dark grey    | Green         | Queue number, registration complete       |
| Caution / Retry            | Pale amber | Dark grey    | Amber         | ID retry, sensor unavailable              |
| Emergency                  | White      | Dark red     | Red border    | Screen 9a — staff alert                   |
| System error               | Pale grey  | Dark grey    | Red text      | Screen 9c — DB unavailable                |

### 8.5 State Machine Summary

```
[Idle/Welcome] ──touch──> [Consent] ──yes──> [Visit History]
                              │                     │
                              │ no                  yes│  no
                              ▼                     ▼   ▼
                         [Decline]         [Returning ID] [New Patient Reg]
                              │                     │           │
                              ▼                     ▼           ▼
                          [Idle]               [Payment/Insurance] ◄────────┐
                                                    │                      │
                                                    ▼                      │
                                            [Temperature]                  │
                                                    │                      │
                                                    ▼                      │
                                            [Complaint Capture]            │
                                                    │                      │
                                                    ▼                      │
                                            [Routing/Processing]           │
                                          │   │   │                         │
                            emergency ────┘   │   └──── normal ────┐        │
                                    ▼         │                     ▼        │
                            [Emergency Alert] │             [Queue Number]   │
                                    │         │                     │        │
                                    ▼         │                     ▼        │
                                [Staff PIN]   │             [Directions]     │
                                    │         │                     │        │
                                    ▼         │                     ▼        │
                                [Idle]        │                 [Idle]       │
                                              ▼                                │
                                  [Emergency → Staff PIN] ─── idle ──────────┘
```

### 8.6 Logging — Screen Events

The Pi app emits a structured event to the local log file (and to the staff dashboard via WebSocket) every time the patient transitions between screens. Each event includes:

- `visit_id`
- `from_screen` / `to_screen`
- `timestamp` (UTC ISO 8601)
- `dwell_time_ms` (time spent on the previous screen)
- `input_method` (touch / voice / keypad / assistive switch)
- `error_code` (if applicable)

These logs are **separate from** the visit record in Section 7 and are used for operational analytics (bottleneck detection, average session length, drop-off points, etc.). They contain no patient health information.

### 8.7 Hardware Notes for the Pi

- **Display:** 7″ or 10″ capacitive touchscreen, 1024 × 600 minimum, mounted at chest height on the humanoid frame
- **Audio:** External USB speaker for TTS; 3.5 mm jack microphone (or USB mic array) for STT
- **Thermal printer:** Connected via USB or GPIO for queue ticket printing on Screen 8
- **IR temperature sensor:** Connected via GPIO or USB-serial; reads occur only on Screen 5
- **Staff PIN pad:** Software-based overlay triggered by a hidden long-press gesture on the top-right clock — used to dismiss Screen 9a / 9b
- **Kiosk mode:** RPi boots directly into the app via `chromium-browser --kiosk` (if web-based) or a Python/PyQt fullscreen app; all OS-level shortcuts disabled
- **Network:** Hospital LAN via Ethernet preferred; offline mode falls back to local SQLite cache for patient DB queries and syncs when connectivity returns

---

*End of specification.*
