# Focus App — Design Spec

**Project:** teriakin
**Date:** 2025-07-09
**Status:** Draft

---

## 1. Overview

A minimal focus timer Android app built with Kotlin. Users start a focus session, and if they open distracting apps during the session, an alarm sounds to bring them back. Clean, dark, minimal UI — no fluff.

---

## 2. Tech Stack

| Layer | Choice |
|-------|--------|
| Language | Kotlin |
| Architecture | Single Activity + Navigation Component |
| UI | XML Layouts |
| State | ViewModel + StateFlow |
| Background | Foreground Service (timer + monitoring) |
| Storage | DataStore (SharedPreferences-based) |
| Monitoring | UsageStats API |
| Min SDK | 24 |
| Target SDK | 34 |

---

## 3. Screens & Navigation

### 3.1 Screen List

| Screen | Route | Purpose |
|--------|-------|---------|
| **HomeScreen** | `/` | Main timer + start button |
| **SetupScreen** | `/setup` | Select distracting apps + alarm sound config |
| **HistoryScreen** | `/history` | Focus session history |

### 3.2 Navigation Flow

```
HomeScreen ──(tap "Start Focus")──→ Timer Running (in-app overlay)
     │                                       │
     ├──(⚙ gear icon)──→ SetupScreen         ├──(distraction detected)──→ Alarm + Overlay
     │                                       │
     └──(📊 history icon)──→ HistoryScreen   └──(timer done)──→ Notif + save history
```

---

## 4. Timer & Session Logic

### 4.1 Presets
- Quick-select chips: 5, 15, 25, 45 minutes
- Custom: number picker 1–480 minutes

### 4.2 Controls
- Start → Pause → Resume → Stop (confirm dialog on stop)

### 4.3 Display
- `HH:MM:SS` large monospace font, updates every second
- Progress bar below timer

### 4.4 Background Behavior
- On start → Foreground Service with persistent notification
- Notification shows timer + Stop action
- Timer keeps running even when screen off / app minimized

### 4.5 State Machine

```
IDLE → RUNNING → PAUSED → RUNNING → COMPLETED
                    ↘ STOPPED (cancel)
RUNNING → INTERRUPTED (alarm triggered — timer keeps running)
```

### 4.6 Session Data (saved per completed session)
- `startTime` (epoch ms)
- `durationSeconds`
- `distractions` (count)
- `completed` (boolean)

---

## 5. Distraction Detection

### 5.1 Mechanism
- User picks distracting apps in SetupScreen (all installed apps listed)
- During session, Foreground Service checks `UsageStats` every 1 second
- If current foreground app matches a selected app → trigger alarm

### 5.2 Permission
- `PACKAGE_USAGE_STATS` — user grants via Settings > Usage Access

### 5.3 Alarm Trigger
- Sound plays (looping)
- Vibrate
- Heads-up notification
- Full-screen overlay: "GET BACK TO FOCUS!" with:
  - **"Go Back"** → alarm stops, timer continues
  - **"Dismiss & End"** → alarm stops, session logged as interrupted

---

## 6. Alarm & Sound

### 6.1 Sound Sources
| Source | Implementation |
|--------|---------------|
| **Built-in** | 3 files in `res/raw/` — sirine, teriakan, gentle alert |
| **Pick from storage** | MediaStore picker, copy to internal storage |
| **Record directly** | MediaRecorder → save as `.mp3` / `.3gp` to internal storage |

### 6.2 Engine
- `MediaPlayer` with looping
- Volume max (override DND if permission granted)
- Vibrate pattern: long-short-long

### 6.3 Setup Options
- Preview 3 seconds
- Choose default / file / recording

---

## 7. UI / UX

### 7.1 HomeScreen Layout
```
┌─────────────────────────────┐
│                             │
│       FOCUS TIMER           │
│                             │
│       00:25:00              │  ← large timer
│                             │
│   [5m] [15m] [25m] [45m]   │  ← preset chips
│                             │
│   ┌─────────────────────┐   │
│   │  START FOCUS SESSION │   │  ← primary CTA
│   └─────────────────────┘   │
│                             │
│   ⚙              📊        │  ← settings & history
└─────────────────────────────┘
```

### 7.2 Timer Running Overlay
```
┌─────────────────────────────┐
│                             │
│         00:18:42            │
│     ─────── ● ───────       │
│                             │
│     [⏸ Pause]  [⛔ Stop]   │
│                             │
│     📱 0 distractions       │
└─────────────────────────────┘
```

### 7.3 Color Palette (Dark Theme)
| Token | Hex |
|-------|-----|
| Background | `#0D0D0D` |
| Surface/Card | `#1A1A1A` |
| Primary (timer) | `#4ADE80` |
| Danger (alarm) | `#EF4444` |
| Text | `#F1F1F1` |

---

## 8. Data Storage (DataStore)

| Key | Type | Purpose |
|-----|------|---------|
| `distracting_apps` | `Set<String>` | Package names of selected apps |
| `alarm_source` | Enum | BUILTIN / FILE / RECORDING |
| `alarm_custom_path` | String | Path to custom sound file |
| `session_history` | JSON Array | Last 100 sessions serialized |
| `today_focus_seconds` | Long | Accumulated focus time today |

---

## 9. Permissions

| Permission | Purpose | Request At |
|-----------|---------|-----------|
| `PACKAGE_USAGE_STATS` | Foreground app detection | First setup screen visit |
| `RECORD_AUDIO` | Record alarm sound | User taps record |
| `READ_MEDIA_AUDIO` (API 33+) / `READ_EXTERNAL_STORAGE` | Pick MP3 file | User taps pick file |
| `POST_NOTIFICATIONS` | Background timer notification | First session start |
| `FOREGROUND_SERVICE` | Keep timer alive | Manifest-declared, implicit |

---

## 10. Out of Scope (V1)

- Streaks / statistics / charts
- Whitelist mode (select allowed apps instead of blocked)
- Multi-timer / parallel sessions
- Lock mode (prevent app dismiss without returning)
- Session notes / tagging
- Export data
- Cloud sync
