# OkelloNexus — Mobile Companion App

Android companion application for the **Okello humanoid robot**. Runs on the operator's phone and acts as the configuration hub: you define the robot's identity, knowledge, and deployment location here. The Quest 3 head unit polls this app over Wi-Fi and reloads Gemini Live whenever anything changes.

---

## Features

### Identities (Personas)
Each deployment of Okello can adopt a different persona — a named character with its own personality, greeting, and role. Only one persona is active at a time; all other features (knowledge, locations) are scoped to the active persona.

| Field | Purpose |
|---|---|
| **Name** | How the robot refers to itself |
| **Role** | Short job description injected into the system prompt |
| **Greeting** | Opening line when the robot first speaks |
| **Personality traits** | Comma-separated adjectives that shape tone |
| **Extra instructions** | Free-form directives (language rules, topics to avoid, etc.) |

- Create, edit, activate, or delete personas
- Active persona is highlighted with a teal `ACTIVE` badge

### Knowledge Base
A searchable fact store the robot draws on when answering questions. Facts are scoped per-persona so each identity has its own knowledge.

| Field | Purpose |
|---|---|
| **Title** | Short label (e.g. "Museum opening hours") |
| **Content** | The actual fact text the robot should know |
| **Category** | One of: General · History · Exhibits · Services · FAQ · Products · Events · Directions · Safety · Other |
| **Tags** | Comma-separated keywords to aid retrieval |

- Filter facts by persona using chip row at the top
- Filter facts by category within a persona
- Add Fact screen pre-selects the persona you are currently filtering by
- All facts for the active persona are bundled into the system prompt sent to the Quest

### Locations
A library of physical deployment venues, each linked to a persona and describing the environment the robot is operating in.

| Field | Purpose |
|---|---|
| **Location Name** | Venue name (e.g. "Uganda Museum") |
| **Type** | Venue category (Museum · Shopping Mall · Hotel · Hospital · Airport · Office Building · Exhibition Hall · Conference Centre · University · Other) |
| **Description** | General venue description |
| **Address** | Street / postal address |
| **GPS Coordinates** | Optional latitude/longitude (decimal degrees) |
| **Opening Hours** | Free-text hours (e.g. "Mon–Fri 9am–5pm") |
| **Notes** | Internal operational notes |
| **Special Instructions** | Robot-specific behaviour directives for this venue |

- Filter locations by persona using chip row at the top
- Activate a location to make it the live deployment context
- Active location details are included in the config served to the Quest
- Create/Edit Location screen links to the currently filtered persona

### Language Settings
- Select one or more languages from: English · Luganda · Acholi · Swahili · Arabic · French · Kinyarwanda · Zulu
- Toggle **code-switching** to let the robot mix languages in the same response
- Settings are persisted and included in every config response

### Connect Screen
Displays the phone's current Wi-Fi IP address so the operator can enter it in the Quest app settings.

- Uses `WifiManager.connectionInfo.ipAddress` (Wi-Fi specific — ignores hotspot interfaces)
- Shows a clearly labelled card: "Enter this IP in Quest app settings (port 8080)"

### HTTP Config Server
A lightweight HTTP server (`ConfigHttpServer`) that runs in the background as long as the app is in the foreground. The Quest polls it every 3 seconds.

**Endpoint:** `GET http://<phone-ip>:8080/config`

**Response (JSON):**
```json
{
  "personaName": "Mukisa",
  "role": "AI marketing assistant",
  "greeting": "Hello, I'm Mukisa!",
  "personality": "[\"friendly\",\"professional\"]",
  "extraInstructions": "...",
  "languages": "[\"en\",\"lg\"]",
  "codeSwitching": false,
  "locationName": "Uganda Museum",
  "locationDescription": "...",
  "locationOpeningHours": "Tue–Sun 10am–6pm",
  "locationSpecialInstructions": "...",
  "facts": [
    { "id": "...", "title": "Admission fee", "content": "...", "category": "Services" }
  ]
}
```

The server only returns facts belonging to the active persona. If no persona is active, all facts are returned.

---

## Architecture

```
OkelloMobileApp/
├── data/
│   ├── db/
│   │   ├── RobotDatabase.kt          Room DB (version 2)
│   │   ├── dao/
│   │   │   ├── PersonaDao.kt
│   │   │   ├── KnowledgeDao.kt       getAll / getByPersona / getByPersonaFlow
│   │   │   ├── LocationDao.kt        getAll / getByPersona / activate
│   │   │   └── CommandDao.kt
│   │   └── entity/
│   │       ├── PersonaEntity.kt
│   │       ├── KnowledgeEntry.kt     includes personaId field
│   │       ├── LocationEntity.kt     includes personaId field
│   │       └── CommandHistory.kt
│   ├── mqtt/
│   │   └── ConfigHttpServer.kt       ServerSocket HTTP server on port 8080
│   └── repository/
│       ├── PersonaRepository.kt
│       ├── KnowledgeRepository.kt    entriesForPersona / getByPersona
│       ├── LocationRepository.kt     locationsForPersona
│       └── SettingsRepository.kt
├── di/
│   ├── DatabaseModule.kt             Hilt Room module (with MIGRATION_1_2)
│   └── RepositoryModule.kt
└── ui/
    ├── navigation/
    │   ├── Screen.kt                 Route definitions with personaId params
    │   └── NexusNavGraph.kt          NavHost with persona ID threading
    ├── components/
    │   ├── NexusTopBar.kt
    │   ├── EmptyState.kt
    │   ├── ConfirmDialog.kt
    │   └── ConnectionBadge.kt
    └── screens/
        ├── splash/
        ├── home/                     Dashboard with quick-action cards
        ├── connect/                  IP display for Quest pairing
        ├── persona/                  Persona list + create/edit
        ├── knowledge/                Knowledge list + add fact
        ├── location/                 Location list + create/edit
        ├── language/
        ├── command/
        ├── monitor/
        └── settings/
```

**Stack:** Jetpack Compose · Material3 · Hilt DI · Room · Kotlin Coroutines/Flow · Kotlinx Serialization · OkHttp (for future use)

**Pattern:** MVVM — each screen has a paired ViewModel injected by Hilt, using `StateFlow` for reactive UI state.

**Database migration (v1 → v2):**
```sql
ALTER TABLE knowledge ADD COLUMN personaId TEXT NOT NULL DEFAULT ''
ALTER TABLE locations ADD COLUMN personaId TEXT NOT NULL DEFAULT ''
```

---

## Setup

### Prerequisites
- Android Studio Flamingo or later
- JDK 17
- Android SDK API 31+
- A phone on the same Wi-Fi network as the Quest 3

### Build & Install

```bash
cd OkelloMobileApp
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or run directly from Android Studio (`Run > Run 'app'`).

### Connecting to the Quest

1. Open the app → tap **Connect** from the home screen
2. Note the IP address displayed (e.g. `192.168.1.42`)
3. On the Quest, tap the **gear icon** (top-left) and enter that IP
4. The Quest will begin polling `http://192.168.1.42:8080/config` every 3 seconds

---

## Configuration Flow

```
Operator sets up persona + knowledge + location on phone
        ↓
ConfigHttpServer listens on :8080
        ↓
Quest ConfigPoller polls every 3 s → HTTP GET /config
        ↓
GeminiLiveService rebuilds system prompt
        ↓
Gemini Live reconnects with new persona / knowledge / location context
```

---

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | HTTP server + any outbound calls |
| `ACCESS_WIFI_STATE` | Reading the phone's Wi-Fi IP for display |
| `FOREGROUND_SERVICE` | ConfigHttpServer runs as a foreground service |

---

## Package

`com.zunobotics.okellonexus`
