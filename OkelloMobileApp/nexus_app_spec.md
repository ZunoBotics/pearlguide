# Nexus — Robot Companion Mobile App
## Complete Specification for Claude Code
**Version:** 1.0 | **Date:** June 2026 | **Status:** Ready for Development  
**Platform:** Android (primary), iOS (phase 2)  
**Purpose:** Remote configuration, control, and teaching interface for the humanoid robot head unit (Meta Quest 3 + Raspberry Pi 5)

---

## TABLE OF CONTENTS
1. [App Overview](#1-app-overview)
2. [Tech Stack](#2-tech-stack)
3. [Architecture](#3-architecture)
4. [Communication Layer](#4-communication-layer)
5. [Screen Map & Navigation](#5-screen-map--navigation)
6. [Feature Modules — Detailed Spec](#6-feature-modules--detailed-spec)
7. [Data Models](#7-data-models)
8. [MQTT Topic Reference](#8-mqtt-topic-reference)
9. [Local Storage & Persistence](#9-local-storage--persistence)
10. [UI/UX Guidelines](#10-uiux-guidelines)
11. [Build & Project Structure](#11-build--project-structure)
12. [Phase Roadmap](#12-phase-roadmap)

---

## 1. APP OVERVIEW

### What Nexus Does
Nexus is the operator control center for the humanoid robot. It communicates directly with the Meta Quest 3 head unit (running the robot's Android app) over Wi-Fi using MQTT. The operator uses Nexus to:

- Set the robot's **identity and persona** (name, role, personality)
- Set the **deployment location** (museum, office, mall, event)
- Configure the **language** the robot speaks
- **Teach** the robot about its environment (upload documents, label what it sees, add facts)
- Send **live commands** and custom instructions
- **Monitor** the robot in real-time (camera feed, status, battery)
- Manage the robot's **knowledge base**

### Key Principle
The app does NOT run any AI. All AI (Gemini Live, YOLO, depth sensing) runs on the Quest 3. Nexus is purely a configuration and control interface. It sends structured MQTT messages; the Quest 3 acts on them.

### Connectivity Model
```
Nexus App (Android phone)
        |
    Wi-Fi (same network as robot)
        |
    MQTT Broker (running on Quest 3 OR Raspberry Pi 5)
        |
    Quest 3 ←→ Raspberry Pi 5
```

The MQTT broker runs on the **Raspberry Pi 5** (more stable as a server). Both the Quest 3 and Nexus connect to it. The Pi 5 is always on and always reachable at a fixed IP on the robot's internal Wi-Fi Direct network.

### Robot Wi-Fi Setup
- Pi 5 creates a Wi-Fi Direct hotspot (SSID: `NexusRobot`, password: configurable)
- Quest 3 joins this hotspot
- Nexus app joins this hotspot when in range
- Pi 5 IP on this network: `192.168.49.1` (fixed)
- MQTT broker port: `1883` (unencrypted local) or `8883` (TLS — phase 2)
- Nexus connects to: `tcp://192.168.49.1:1883`

---

## 2. TECH STACK

### Android App
| Component | Choice | Reason |
|---|---|---|
| Language | Kotlin | Modern Android, concise, coroutine support |
| Min SDK | API 26 (Android 8.0) | Broad device support |
| Target SDK | API 34 (Android 14) | Latest features |
| UI Framework | Jetpack Compose | Declarative, modern, matches design goals |
| Architecture | MVVM + Repository pattern | Clean separation, testable |
| DI | Hilt | Standard Android DI |
| Navigation | Compose Navigation | Single-activity, screen-based nav |
| MQTT Client | Eclipse Paho MQTT Android | Reliable, well-maintained |
| Camera (live feed) | CameraX or raw ImageView | Display JPEG stream from Quest 3 |
| Local Database | Room (SQLite) | Store personas, locations, knowledge entries |
| Preferences | DataStore (Proto) | App settings, last-used config |
| Networking | OkHttp (for any REST fallback) | HTTP if needed |
| JSON | Kotlin Serialization (kotlinx) | Fast, Kotlin-native |
| File Picker | ActivityResultContracts.GetContent | Document upload for knowledge base |
| Image Loading | Coil | Lightweight, Compose-compatible |
| State Management | StateFlow + ViewModel | Reactive, lifecycle-aware |

### Build
```
minSdk 26
targetSdk 34
compileSdk 34
kotlin version: 1.9+
AGP: 8.x
Jetpack Compose BOM: 2024.x
```

### Key Dependencies (build.gradle.kts)
```kotlin
dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Architecture
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    // MQTT
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.android.service:1.1.1")

    // Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Image
    implementation("io.coil-kt:coil-compose:2.6.0")

    // File / document handling
    implementation("androidx.documentfile:documentfile:1.0.1")
}
```

---

## 3. ARCHITECTURE

### MVVM + Repository
```
UI Layer (Compose Screens)
        ↓ observes StateFlow
ViewModel Layer (per screen/feature)
        ↓ calls
Repository Layer
    ├── MqttRepository      — all MQTT pub/sub
    ├── RobotConfigRepo     — Room DB: personas, locations
    ├── KnowledgeRepo       — Room DB: knowledge entries
    └── SettingsRepo        — DataStore: app preferences

Infrastructure
    ├── MqttService         — Android foreground service (keeps MQTT alive)
    ├── RobotDatabase       — Room database
    └── NetworkMonitor      — Wi-Fi connection state
```

### MqttService (Foreground Service)
- Runs as Android foreground service so MQTT stays connected even when app is backgrounded
- Shows persistent notification: "Nexus — Connected to robot" or "Nexus — Searching for robot"
- Exposes a `SharedFlow<MqttMessage>` that all ViewModels subscribe to
- Handles reconnection automatically with exponential backoff
- Publishes on behalf of ViewModels via a command channel

### Connection States
```kotlin
enum class RobotConnectionState {
    DISCONNECTED,       // No Wi-Fi / not on robot network
    CONNECTING,         // MQTT connecting
    CONNECTED_IDLE,     // Connected, robot standing by
    CONNECTED_ACTIVE,   // Connected, robot fully operational
    QUEST_OFFLINE,      // Pi 5 connected but Quest 3 not responding
    ERROR               // Connection error with message
}
```

---

## 4. COMMUNICATION LAYER

### MQTT Broker
- Host: Raspberry Pi 5 at `192.168.49.1:1883`
- Client IDs: `nexus-app-{uuid}` for the phone, `quest3-head` for Quest 3, `pi5-body` for Pi 5

### Message Format
All payloads are JSON. All messages include a `timestamp_ms` field.

### Publish (App → Robot)
```kotlin
// Publish helper
suspend fun publish(topic: String, payload: Any) {
    val json = Json.encodeToString(payload)
    mqttClient.publish(topic, json.toByteArray(), qos = 1, retained = false)
}
```

### Subscribe (Robot → App)
```kotlin
// The app subscribes to all robot/# topics on connection
mqttClient.subscribe("robot/#", qos = 0) { topic, message ->
    incomingMessages.emit(MqttMessage(topic, String(message.payload)))
}
```

### QoS Strategy
- Config changes (persona, location, language): QoS 1 (at least once)
- Live sensor status: QoS 0 (fire and forget — high frequency)
- Knowledge uploads: QoS 1 (important, must arrive)
- Emergency stop: QoS 2 (exactly once — critical)

---

## 5. SCREEN MAP & NAVIGATION

### Navigation Graph
```
SplashScreen
    └── ConnectScreen (if not connected)
            └── HomeScreen (Dashboard)
                    ├── PersonaScreen
                    │       └── CreatePersonaScreen
                    ├── LocationScreen
                    │       └── CreateLocationScreen
                    ├── LanguageScreen
                    ├── KnowledgeScreen
                    │       ├── AddDocumentScreen
                    │       ├── TeachModeScreen
                    │       └── KnowledgeDetailScreen
                    ├── CommandScreen
                    ├── MonitorScreen (Live feed + status)
                    └── SettingsScreen
```

### Bottom Navigation Tabs (main app)
| Tab | Icon | Screen |
|---|---|---|
| Home | Dashboard icon | HomeScreen — status overview |
| Identity | Person icon | PersonaScreen |
| Location | Map pin icon | LocationScreen |
| Knowledge | Brain/book icon | KnowledgeScreen |
| Monitor | Camera icon | MonitorScreen |

---

## 6. FEATURE MODULES — DETAILED SPEC

---

### 6.1 CONNECT SCREEN

**Purpose:** Shown when not connected to robot network. Guides operator to connect.

**UI Elements:**
- Nexus logo + app name
- Connection status indicator (animated pulse)
- Text: "Connect your phone to the robot Wi-Fi network"
- Network name display: `NexusRobot`
- Button: "Open Wi-Fi Settings" → opens Android Wi-Fi settings
- Auto-retry indicator — shows "Searching..." with spinner
- Once connected: auto-navigates to HomeScreen

**Logic:**
```kotlin
// Check Wi-Fi SSID — if matches robot network, attempt MQTT connect
// Poll every 2 seconds when disconnected
// On MQTT connect success → navigate to Home
```

---

### 6.2 HOME SCREEN (Dashboard)

**Purpose:** At-a-glance robot status. First screen after connection.

**UI Elements:**
- Top bar: "Nexus" title + connection indicator dot (green/red/amber)
- **Robot Status Card:**
  - Current persona name + role
  - Current location name
  - Active language
  - Robot mode badge (ACTIVE / IDLE / NAVIGATING / TEACHING)
- **Quick Stats Row:**
  - Quest 3 battery %
  - Pi 5 uptime
  - Active knowledge entries count
  - Last command timestamp
- **Live Mini-Feed:** small 160x120 camera thumbnail (updates every 2 seconds)
- **Quick Action Buttons:**
  - "Send Command" → CommandScreen
  - "Start Teach Mode" → TeachModeScreen
  - "Emergency Stop" (red) → publishes emergency stop immediately
- **Recent Activity Log:** last 10 MQTT events (person detected, command sent, etc.)

**MQTT Subscriptions on this screen:**
- `robot/status` → update all status cards
- `robot/camera/thumbnail` → update mini-feed

---

### 6.3 PERSONA SCREEN

**Purpose:** Configure who/what the robot is in this deployment.

**UI Elements:**
- List of saved personas (Room DB)
- Each persona card shows: name, role tag, language, created date
- FAB: "Create New Persona"
- Active persona has a checkmark + highlighted border
- Tap a persona → activates it (sends to robot) + shows detail/edit

**Persona Card Actions:**
- Activate → sends to robot via MQTT
- Edit → CreatePersonaScreen (pre-filled)
- Delete → confirmation dialog

**Create/Edit Persona Screen fields:**
```
Robot Name *              [text input]          e.g. "Amara"
Role *                    [dropdown]            Museum Guide / Marketing Agent / 
                                                Receptionist / Tour Guide / 
                                                Event Host / Custom
Custom Role               [text, if Custom]     e.g. "Brand Ambassador for Nile Breweries"
Greeting Message          [multiline text]      First thing robot says on activation
Personality Traits        [chip multi-select]   Friendly / Professional / Formal /
                                                Energetic / Calm / Humorous
Voice Speed               [slider 0.5x–2.0x]   TTS playback speed
Extra Instructions        [multiline text]      Additional Gemini system prompt additions
                                                e.g. "Always mention the opening hours"
Active Language           [see Language module] Linked from Language screen
```

**MQTT Payload on Activate:**
```json
{
  "topic": "robot/config/persona",
  "payload": {
    "name": "Amara",
    "role": "Museum Tour Guide",
    "greeting": "Hello! Welcome to the Uganda National Museum. I am Amara, your guide today.",
    "personality": ["friendly", "energetic"],
    "voice_speed": 1.0,
    "extra_instructions": "Always mention current exhibit hours: 9am-5pm daily.",
    "language_code": "en",
    "timestamp_ms": 1749120000000
  }
}
```

---

### 6.4 LOCATION SCREEN

**Purpose:** Tell the robot where it is and what context it operates in.

**UI Elements:**
- List of saved locations (Room DB)
- Each location card: name, type tag, knowledge entry count, last used date
- FAB: "Add New Location"
- Active location highlighted with checkmark

**Create/Edit Location Screen fields:**
```
Location Name *           [text input]          e.g. "Uganda National Museum — Main Hall"
Location Type *           [dropdown]            Museum / Office / Mall / Hospital /
                                                Airport / School / Event Venue / Other
Address / Description     [multiline text]      Physical address or description
GPS Coordinates           [auto-fill button]    Uses phone GPS to capture coordinates
Floor Map Image           [image picker]        Optional: floor plan image
Opening Hours             [text]                e.g. "Mon-Sat 9am-5pm"
Special Instructions      [multiline text]      e.g. "Do not enter the storage room corridor"
Notes for Robot           [multiline text]      Context the robot should always know
                                                e.g. "Ticket price: 10,000 UGX adults"
```

**MQTT Payload on Activate:**
```json
{
  "topic": "robot/config/location",
  "payload": {
    "name": "Uganda National Museum — Main Hall",
    "type": "museum",
    "description": "National museum in Kampala, Uganda",
    "gps": { "lat": 0.3383, "lng": 32.5766 },
    "opening_hours": "Monday to Saturday, 9am to 5pm",
    "notes": "Admission: 10,000 UGX adults, 5,000 UGX children. No photography in Gallery 3.",
    "special_instructions": "Do not navigate into the staff corridor east of Gallery 2.",
    "timestamp_ms": 1749120000000
  }
}
```

---

### 6.5 LANGUAGE SCREEN

**Purpose:** Select the language(s) the robot should use.

**UI Elements:**
- Primary Language selector (single select)
- Secondary/Fallback Language (optional, single select)
- Auto-detect toggle: "Auto-detect speaker's language" (uses Gemini)
- Code-switching toggle: "Allow mixing languages mid-conversation"
- Coming Soon section: local language models (Luganda, Runyankole)

**Available Languages:**
```kotlin
enum class RobotLanguage(val code: String, val displayName: String, val model: String, val available: Boolean) {
    ENGLISH("en", "English", "Gemini Live", true),
    SWAHILI("sw", "Swahili (Kiswahili)", "Gemini Live", true),
    FRENCH("fr", "French (Français)", "Gemini Live", true),
    ARABIC("ar", "Arabic (العربية)", "Gemini Live", true),
    LUGANDA("lg", "Luganda", "SunFlower (coming)", false),
    RUNYANKOLE("nyn", "Runyankole", "SunFlower (coming)", false),
    ACHOLI("ach", "Acholi", "TBD (coming)", false),
}
```

**MQTT Payload:**
```json
{
  "topic": "robot/config/language",
  "payload": {
    "primary": "en",
    "secondary": "sw",
    "auto_detect": true,
    "code_switching": false,
    "timestamp_ms": 1749120000000
  }
}
```

---

### 6.6 KNOWLEDGE SCREEN

**Purpose:** Manage everything the robot knows about the current location and deployment.

**UI Elements:**
- Knowledge entries list grouped by category (Facts, Documents, Exhibits, People, Rules)
- Each entry card: title, category tag, source (manual/document/taught/voice), date added
- Search bar to filter entries
- FAB with expandable options:
  - "Add Fact" (manual text)
  - "Upload Document" (PDF/TXT)
  - "Start Teach Mode" (walk-through)
  - "Voice Add" (speak a fact)
- Filter chips: All / Facts / Documents / Exhibits / Rules

**Add Fact (quick entry):**
```
Title *                   [text input]          e.g. "Gallery 3 — Buganda Kingdom"
Content *                 [multiline text]      Full knowledge content
Category *                [dropdown]            Exhibit / Rule / Fact / Person / Product / FAQ
Tags                      [chip input]          e.g. "gallery3", "history", "kingdom"
Location-specific         [toggle]              Only tell visitors when near this location
```

**Upload Document:**
- File picker (PDF, TXT, DOCX)
- Document is read on-device → chunked → sent to Quest 3 as knowledge entries
- Progress indicator during chunking and upload
- Each chunk becomes a separate knowledge entry tagged to the document

**MQTT Payload — Add Knowledge Entry:**
```json
{
  "topic": "robot/knowledge/add",
  "payload": {
    "id": "know_003",
    "title": "Buganda Kingdom Exhibit",
    "content": "The Buganda Kingdom exhibit in Gallery 3 covers the history of the Buganda people from the 14th century. Key artifacts include the royal drums (Engoma), the royal throne replica, and traditional bark cloth garments. The exhibit was renovated in 2019.",
    "category": "exhibit",
    "tags": ["gallery3", "buganda", "history", "drums"],
    "location_specific": true,
    "source": "manual",
    "timestamp_ms": 1749120000000
  }
}
```

**MQTT Payload — Delete Knowledge Entry:**
```json
{
  "topic": "robot/knowledge/delete",
  "payload": { "id": "know_003", "timestamp_ms": 1749120000000 }
}
```

**MQTT Payload — Request Full Knowledge List:**
```json
{ "topic": "robot/knowledge/list_request", "payload": {} }
```
→ Quest 3 responds on `robot/knowledge/list` with full array.

---

### 6.7 TEACH MODE SCREEN

**Purpose:** Operator walks robot through a space while labeling what the robot sees in real time.

**Flow:**
```
Operator taps "Start Teach Mode"
        ↓
App sends: robot/teach/start
        ↓
Robot enters teach mode:
  - Moves slowly (or stays stationary)
  - Captures RGB frames continuously
  - Gemini Vision describes what it sees
  - Descriptions streamed back to app
        ↓
Nexus shows:
  - Live camera feed (full size)
  - Auto-generated description of current view (from Gemini)
  - Text field: "Label this view" (operator can override/add context)
  - "Save this view" button → adds to knowledge base
  - "Move to next spot" button → Pi 5 moves robot forward/turns
        ↓
Operator taps "End Teach Mode"
App sends: robot/teach/stop
```

**UI Elements (during teach mode):**
- Full-screen camera feed from Quest 3
- Overlay bottom panel (semi-transparent):
  - Auto description text (updates every 2 seconds)
  - Label input field
  - [Save This View] [Skip] buttons
- Top bar: session name + entry count saved + [End Session] button
- Entry count badge (how many spots taught so far)

**MQTT Topics for Teach Mode:**
```
robot/teach/start           App → Robot    Begin teach mode session
robot/teach/stop            App → Robot    End session
robot/teach/save_view       App → Robot    Save current view with label
robot/teach/move_next       App → Robot    Advance robot to next position
robot/teach/camera_frame    Robot → App    Current camera frame (JPEG, ~1fps)
robot/teach/description     Robot → App    Gemini's auto description of current view
robot/teach/status          Robot → App    Session status + entry count
```

---

### 6.8 COMMAND SCREEN

**Purpose:** Send live, freeform instructions to the robot.

**UI Elements:**
- **Quick Command Buttons (grid):**
  - "Come Here" → navigates to operator (using camera tracking)
  - "Go to [Location]" → dropdown of saved waypoints
  - "Stop / Stand By"
  - "Greet Next Person"
  - "Start Patrol"
  - "Return to Base"
  - "Announce [text]" → text input popup
  - "Emergency Stop" (red, large)
- **Custom Instruction Field:**
  - Large multiline text field
  - "Send Instruction" button
  - Instructions go directly into Gemini's context as an immediate directive
  - Recent instructions list (last 10, tap to resend)
- **Scheduled Commands:**
  - "Schedule an announcement" → time picker + text
  - Recurring schedule (e.g. announce every 30 minutes)

**MQTT Payload — Custom Command:**
```json
{
  "topic": "robot/command",
  "payload": {
    "type": "custom_instruction",
    "instruction": "For the next 30 minutes, focus on telling visitors about the new temporary exhibition on African textiles in Gallery 5.",
    "duration_minutes": 30,
    "timestamp_ms": 1749120000000
  }
}
```

**MQTT Payload — Navigation Command:**
```json
{
  "topic": "robot/command",
  "payload": {
    "type": "navigate",
    "target": "gallery_entrance",
    "timestamp_ms": 1749120000000
  }
}
```

**MQTT Payload — Emergency Stop:**
```json
{
  "topic": "robot/emergency",
  "payload": {
    "type": "stop",
    "timestamp_ms": 1749120000000
  }
}
```
> QoS 2 (exactly once) — most critical message in the system.

---

### 6.9 MONITOR SCREEN

**Purpose:** Live view of what the robot sees and its current state.

**UI Elements:**
- **Live Camera Feed:**
  - Full-width display of Quest 3 Left RGB camera
  - JPEG stream from `robot/camera/feed` (target: 5fps in monitor mode, 1fps when app backgrounded)
  - Toggle: Left Camera / Right Camera / Split View (side by side)
- **Status Panel (bottom half):**
  - Robot mode badge
  - Person detected indicator (green dot when someone is in view)
  - Obstacle warning (red alert if obstacle within 80cm)
  - Current speech text (what the robot is saying right now)
  - Head orientation (pan/tilt degrees — mini visual indicator)
- **System Stats (expandable):**
  - Quest 3 battery %
  - Quest 3 CPU/GPU temp (if available via ADB log stream)
  - Pi 5 uptime
  - MQTT message rate (msgs/sec)
  - Active knowledge entries
  - Current Gemini session ID

**MQTT Subscriptions:**
```
robot/camera/feed           JPEG bytes → display in ImageView
robot/status                Full status JSON
robot/speech/current        Current TTS text being spoken
robot/sensors               Full sensor packet (obstacles, person detected)
```

---

### 6.10 SETTINGS SCREEN

**Purpose:** App-level settings, not robot settings.

**Settings:**
```
CONNECTION
  Robot IP Address          [text, default: 192.168.49.1]
  MQTT Port                 [number, default: 1883]
  Robot Wi-Fi SSID          [text, default: NexusRobot]
  Auto-connect on launch    [toggle, default: on]
  Reconnect interval (sec)  [slider 2-30, default: 5]

DISPLAY
  Camera feed quality       [Low (1fps) / Medium (3fps) / High (5fps)]
  Dark mode                 [System / Light / Dark]

NOTIFICATIONS
  Alert on obstacle warning     [toggle]
  Alert on person detected      [toggle]
  Alert on robot error          [toggle]

DEVELOPER (hidden behind tap-count unlock on version label)
  Show raw MQTT log             [toggle]
  MQTT log export               [button]
  Clear all local data          [button, destructive]
  App version                   [text]
```

---

## 7. DATA MODELS

### Room Database: RobotDatabase

```kotlin
@Database(
    entities = [PersonaEntity::class, LocationEntity::class, KnowledgeEntry::class, CommandHistory::class],
    version = 1
)
abstract class RobotDatabase : RoomDatabase() {
    abstract fun personaDao(): PersonaDao
    abstract fun locationDao(): LocationDao
    abstract fun knowledgeDao(): KnowledgeDao
    abstract fun commandDao(): CommandDao
}
```

### PersonaEntity
```kotlin
@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,           // UUID
    val name: String,                      // "Amara"
    val role: String,                      // "Museum Tour Guide"
    val greeting: String,
    val personality: String,               // JSON array stored as string
    val voiceSpeed: Float,                 // 0.5 – 2.0
    val extraInstructions: String,
    val languageCode: String,
    val isActive: Boolean,
    val createdAt: Long,                   // epoch ms
    val updatedAt: Long
)
```

### LocationEntity
```kotlin
@Entity(tableName = "locations")
data class LocationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,                      // museum, office, mall, etc.
    val description: String,
    val address: String,
    val gpsLat: Double?,
    val gpsLng: Double?,
    val openingHours: String,
    val notes: String,
    val specialInstructions: String,
    val isActive: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
```

### KnowledgeEntry
```kotlin
@Entity(tableName = "knowledge")
data class KnowledgeEntry(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val category: String,                  // exhibit, rule, fact, person, product, faq
    val tags: String,                      // JSON array as string
    val locationId: String?,               // linked location (nullable = global)
    val locationSpecific: Boolean,
    val source: String,                    // manual, document, taught, voice
    val documentName: String?,             // if from a document upload
    val createdAt: Long,
    val syncedAt: Long?                    // when successfully sent to robot
)
```

### CommandHistory
```kotlin
@Entity(tableName = "command_history")
data class CommandHistory(
    @PrimaryKey val id: String,
    val type: String,
    val payload: String,                   // full JSON payload
    val sentAt: Long,
    val acknowledged: Boolean
)
```

---

## 8. MQTT TOPIC REFERENCE

### Full Topic List

| Topic | Direction | QoS | Description |
|---|---|---|---|
| `robot/config/persona` | App → Quest3 | 1 | Set active persona |
| `robot/config/location` | App → Quest3 | 1 | Set active location |
| `robot/config/language` | App → Quest3 | 1 | Set active language |
| `robot/knowledge/add` | App → Quest3 | 1 | Add knowledge entry |
| `robot/knowledge/delete` | App → Quest3 | 1 | Remove knowledge entry |
| `robot/knowledge/list_request` | App → Quest3 | 1 | Request full knowledge list |
| `robot/knowledge/list` | Quest3 → App | 1 | Full knowledge list response |
| `robot/command` | App → Quest3/Pi5 | 1 | Live command |
| `robot/emergency` | App → Pi5 | 2 | Emergency stop |
| `robot/teach/start` | App → Quest3 | 1 | Begin teach mode |
| `robot/teach/stop` | App → Quest3 | 1 | End teach mode |
| `robot/teach/save_view` | App → Quest3 | 1 | Save current teach view |
| `robot/teach/move_next` | App → Pi5 | 1 | Move robot to next position |
| `robot/teach/camera_frame` | Quest3 → App | 0 | Current frame during teach mode |
| `robot/teach/description` | Quest3 → App | 0 | Gemini description of current view |
| `robot/teach/status` | Quest3 → App | 0 | Teach session status |
| `robot/status` | Quest3+Pi5 → App | 0 | Full robot status (30s interval) |
| `robot/camera/feed` | Quest3 → App | 0 | Live JPEG camera stream |
| `robot/camera/thumbnail` | Quest3 → App | 0 | 160x120 thumbnail (2s interval) |
| `robot/speech/current` | Quest3 → App | 0 | Current TTS text |
| `robot/sensors` | Quest3 → App | 0 | Sensor packet (obstacles, person) |
| `robot/nav/status` | Pi5 → App | 0 | Navigation status |
| `robot/heartbeat` | Quest3+Pi5 → App | 0 | Keepalive (5s interval) |
| `robot/error` | Quest3/Pi5 → App | 1 | Error notification |

### Retained Topics (broker retains last value)
- `robot/config/persona` (retained = true) — robot reloads on boot
- `robot/config/location` (retained = true)
- `robot/config/language` (retained = true)

---

## 9. LOCAL STORAGE & PERSISTENCE

### Room Database (robotic_data.db)
- Personas (CRUD, one active at a time)
- Locations (CRUD, one active at a time)
- Knowledge entries (CRUD + sync status)
- Command history (last 100)

### DataStore Preferences
```kotlin
object AppPrefsKeys {
    val ROBOT_IP = stringPreferencesKey("robot_ip")              // default: 192.168.49.1
    val MQTT_PORT = intPreferencesKey("mqtt_port")               // default: 1883
    val ROBOT_SSID = stringPreferencesKey("robot_ssid")          // default: NexusRobot
    val AUTO_CONNECT = booleanPreferencesKey("auto_connect")     // default: true
    val RECONNECT_INTERVAL = intPreferencesKey("reconnect_interval") // default: 5
    val CAMERA_QUALITY = stringPreferencesKey("camera_quality")  // low/medium/high
    val THEME = stringPreferencesKey("theme")                    // system/light/dark
    val ACTIVE_PERSONA_ID = stringPreferencesKey("active_persona_id")
    val ACTIVE_LOCATION_ID = stringPreferencesKey("active_location_id")
    val ACTIVE_LANGUAGE = stringPreferencesKey("active_language")
}
```

### Sync Strategy
- Knowledge entries have a `syncedAt` field
- On MQTT connect: compare local DB with `robot/knowledge/list` response
- Unsynced entries are re-published automatically on reconnect
- Deleted entries are tracked with a soft-delete flag until sync confirmed

---

## 10. UI/UX GUIDELINES

### Design Language
- **Style:** Clean, professional, dark-mode first
- **Primary color:** Deep blue `#1E3A5F`
- **Accent color:** Electric blue `#2563EB`
- **Surface:** Dark `#111827` / Light `#F9FAFB`
- **Success:** Green `#059669`
- **Warning:** Amber `#D97706`
- **Danger:** Red `#DC2626`
- **Font:** System default (Roboto on Android)

### Connection Status Indicator
- Always visible in top bar
- Green dot = connected + robot active
- Amber dot = connected but Quest 3 not responding
- Red dot = disconnected
- Pulsing animation when connecting

### Empty States
Every list screen needs an empty state with:
- Illustration or icon
- Clear message: "No personas yet. Create one to get started."
- Action button

### Loading States
- Use `CircularProgressIndicator` during MQTT operations
- Skeleton loading for camera feed before first frame arrives
- Button loading state (disable + show spinner) when publishing

### Error Handling
- MQTT errors shown as Snackbar (non-blocking)
- Connection errors shown as full-screen message with retry button
- Validation errors shown inline below input fields
- Destructive actions (delete) always require confirmation dialog

### Accessibility
- Content descriptions on all icon buttons
- Minimum touch target: 48dp
- Support system font size scaling

---

## 11. BUILD & PROJECT STRUCTURE

```
nexus-android/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/nexus/robotapp/
│   │   │   ├── NexusApplication.kt          // Hilt app class
│   │   │   ├── MainActivity.kt              // Single activity
│   │   │   │
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── RobotDatabase.kt
│   │   │   │   │   ├── entity/              // PersonaEntity, LocationEntity, etc.
│   │   │   │   │   └── dao/                 // PersonaDao, LocationDao, etc.
│   │   │   │   ├── mqtt/
│   │   │   │   │   ├── MqttService.kt       // Foreground service
│   │   │   │   │   ├── MqttClient.kt        // Paho wrapper
│   │   │   │   │   └── MqttMessage.kt       // Sealed class for incoming messages
│   │   │   │   ├── repository/
│   │   │   │   │   ├── MqttRepository.kt
│   │   │   │   │   ├── PersonaRepository.kt
│   │   │   │   │   ├── LocationRepository.kt
│   │   │   │   │   ├── KnowledgeRepository.kt
│   │   │   │   │   └── SettingsRepository.kt
│   │   │   │   └── model/                   // Domain models (not DB entities)
│   │   │   │
│   │   │   ├── ui/
│   │   │   │   ├── theme/
│   │   │   │   │   ├── Color.kt
│   │   │   │   │   ├── Theme.kt
│   │   │   │   │   └── Type.kt
│   │   │   │   ├── navigation/
│   │   │   │   │   └── NexusNavGraph.kt
│   │   │   │   ├── components/              // Shared Compose components
│   │   │   │   │   ├── ConnectionBadge.kt
│   │   │   │   │   ├── RobotStatusCard.kt
│   │   │   │   │   ├── CameraFeedView.kt
│   │   │   │   │   └── ConfirmDialog.kt
│   │   │   │   └── screens/
│   │   │   │       ├── connect/
│   │   │   │       │   ├── ConnectScreen.kt
│   │   │   │       │   └── ConnectViewModel.kt
│   │   │   │       ├── home/
│   │   │   │       │   ├── HomeScreen.kt
│   │   │   │       │   └── HomeViewModel.kt
│   │   │   │       ├── persona/
│   │   │   │       │   ├── PersonaScreen.kt
│   │   │   │       │   ├── PersonaViewModel.kt
│   │   │   │       │   └── CreatePersonaScreen.kt
│   │   │   │       ├── location/
│   │   │   │       │   ├── LocationScreen.kt
│   │   │   │       │   ├── LocationViewModel.kt
│   │   │   │       │   └── CreateLocationScreen.kt
│   │   │   │       ├── language/
│   │   │   │       │   ├── LanguageScreen.kt
│   │   │   │       │   └── LanguageViewModel.kt
│   │   │   │       ├── knowledge/
│   │   │   │       │   ├── KnowledgeScreen.kt
│   │   │   │       │   ├── KnowledgeViewModel.kt
│   │   │   │       │   ├── AddFactScreen.kt
│   │   │   │       │   ├── UploadDocumentScreen.kt
│   │   │   │       │   └── TeachModeScreen.kt
│   │   │   │       ├── command/
│   │   │   │       │   ├── CommandScreen.kt
│   │   │   │       │   └── CommandViewModel.kt
│   │   │   │       ├── monitor/
│   │   │   │       │   ├── MonitorScreen.kt
│   │   │   │       │   └── MonitorViewModel.kt
│   │   │   │       └── settings/
│   │   │   │           ├── SettingsScreen.kt
│   │   │   │           └── SettingsViewModel.kt
│   │   │   │
│   │   │   └── di/
│   │   │       ├── DatabaseModule.kt
│   │   │       ├── MqttModule.kt
│   │   │       └── RepositoryModule.kt
│   │   │
│   │   └── res/
│   │       ├── drawable/                    // Icons, illustrations
│   │       ├── values/strings.xml
│   │       └── xml/network_security_config.xml  // Allow cleartext to robot IP
│   │
│   └── build.gradle.kts
│
├── build.gradle.kts
└── settings.gradle.kts
```

### AndroidManifest.xml — Required Permissions
```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />   <!-- GPS for location tagging -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />           <!-- Voice teach mode -->
```

### network_security_config.xml
```xml
<!-- Allow cleartext HTTP/MQTT to local robot IP -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="false">192.168.49.1</domain>
    </domain-config>
</network-security-config>
```

---

## 12. PHASE ROADMAP

### Phase 1 — Core Connection & Config (Build First)
**Goal:** App connects to robot, sends persona/location/language config.

Screens to build:
- [ ] ConnectScreen (Wi-Fi detection + MQTT connect)
- [ ] HomeScreen (status display, live data from MQTT)
- [ ] PersonaScreen + CreatePersonaScreen
- [ ] LocationScreen + CreateLocationScreen
- [ ] LanguageScreen
- [ ] SettingsScreen (IP, port config)

Infrastructure to build:
- [ ] MqttService (foreground service)
- [ ] MqttRepository (pub/sub)
- [ ] Room database (PersonaEntity, LocationEntity)
- [ ] DataStore (app preferences)
- [ ] Hilt DI setup
- [ ] NavGraph

MQTT topics needed for Phase 1:
- `robot/config/persona`, `robot/config/location`, `robot/config/language`
- `robot/status`, `robot/heartbeat`

---

### Phase 2 — Commands & Live Monitor
Screens to build:
- [ ] CommandScreen (quick commands + custom instruction)
- [ ] MonitorScreen (live camera feed + status panel)

MQTT topics needed:
- `robot/command`, `robot/emergency`
- `robot/camera/feed`, `robot/camera/thumbnail`
- `robot/speech/current`, `robot/sensors`

---

### Phase 3 — Knowledge Base
Screens to build:
- [ ] KnowledgeScreen (list, search, filter)
- [ ] AddFactScreen
- [ ] UploadDocumentScreen (PDF/TXT → chunk → send)

Infrastructure:
- [ ] KnowledgeRepository
- [ ] Document chunking logic (split large docs into entries)
- [ ] Knowledge sync (compare local DB vs robot DB)

MQTT topics needed:
- `robot/knowledge/add`, `robot/knowledge/delete`
- `robot/knowledge/list_request`, `robot/knowledge/list`

---

### Phase 4 — Teach Mode
Screens to build:
- [ ] TeachModeScreen (live camera + labeling overlay)

MQTT topics needed:
- `robot/teach/*` (all teach topics)

---

### Phase 5 — Polish & iOS
- [ ] Scheduled commands UI
- [ ] Notification system (obstacle alerts, person detected)
- [ ] Dark/light theme polish
- [ ] Onboarding flow for first-time users
- [ ] iOS port (Swift/SwiftUI or Flutter rewrite)

---

## APPENDIX — QUICK REFERENCE

### Robot Network Details
| Property | Value |
|---|---|
| Wi-Fi SSID | `NexusRobot` |
| Pi 5 IP | `192.168.49.1` |
| MQTT Port | `1883` |
| MQTT Client ID (app) | `nexus-app-{uuid}` |
| MQTT Client ID (Quest 3) | `quest3-head` |
| MQTT Client ID (Pi 5) | `pi5-body` |

### Emergency Stop — Most Critical Flow
```
User taps Emergency Stop button
        ↓
App publishes immediately (no confirmation dialog):
  topic: robot/emergency
  payload: { "type": "stop", "timestamp_ms": ... }
  QoS: 2 (exactly once)
        ↓
Pi 5 receives → cuts all servo power immediately
Quest 3 receives → stops all navigation commands
        ↓
App shows confirmation: "Emergency stop sent"
```

### Related Files
| File | Format | Contents |
|---|---|---|
| `quest3_setup_guide.md` | Markdown | Quest 3 hardware + SDK reference |
| `humanoid_robot_architecture_v2.docx` | Word | Full system architecture |
| `humanoid_robot_architecture_v2.pdf` | PDF | Print version |

---

*Nexus App Specification | v1.0 | June 2026 | Ready for Claude Code*
