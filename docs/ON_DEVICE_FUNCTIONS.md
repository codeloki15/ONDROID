# OmniPin — On-Device Function Calling Catalog

**What this is:** the full set of "functions" (tools) the on-device LLM (Qwen2.5-1.5B via MediaPipe) could call on the phone, turning the chat app into a lightweight **offline assistant**. The model decides *which* function + arguments from natural language; **the app's Kotlin code executes it** against a real Android API and feeds the result back.

**Target device:** OnePlus 9R — Snapdragon 870, Android 14 (API 34), 8GB. Everything below works **fully offline** (no internet) unless noted.

> **Status:** None of these are implemented yet — this is the design catalog. The chat path works (Qwen replies); function calling is the proposed next capability. See [Architecture](#architecture) and [Phased plan](#phased-build-plan).

---

> **UPDATE:** All Tier-1 + Tier-2 functions below are now IMPLEMENTED (Qwen2.5-1.5B emits `<tool_call>` JSON, parsed and executed on-device). See the example commands below.

## What's possible — example commands

OmniPin runs fully offline, on-device — no cloud, no network. Just talk to it naturally and it picks the right tool.

### Info
- **Date & time** — "What's today's date?" / "What time is it right now?" → reads the device clock and tells you instantly.
- **Calculate** — "What's 18% tip on a $64 bill?" / "Square root of 2025?" → evaluates the math and returns the answer.
- **Battery** — "How much battery do I have left?" / "Am I charging?" → reports current charge level and charging state.

### Device control
- **Flashlight** — "Turn on the flashlight." / "Kill the torch." → toggles the camera LED on or off.
- **Timer** — "Set a timer for 10 minutes." / "Start a 90-second timer." → starts a countdown timer.
- **Alarm** — "Wake me up at 6:30am." / "Set an alarm for 2pm tomorrow." → creates an alarm in the clock app.
- **Volume** — "Turn the volume up to max." / "Mute the media volume." → adjusts the device volume level.
- **Open settings** — "Open Wi-Fi settings." / "Take me to Bluetooth settings." → jumps straight to the requested settings page.

### Productivity
- **Clipboard** — "Copy 'meet at 7pm' to my clipboard." / "What's on my clipboard?" → writes to or reads from the clipboard.
- **Create note** — "Note: pick up dry cleaning Friday." / "Jot down the Wi-Fi password is hunter2." → saves a note.
- **Calendar event** — "Add a dentist appointment tomorrow at 3pm." / "Schedule a team sync Monday 10–11am." → creates a calendar event.

### Communication
- **Add contact** — "Save Priya's number, 555-0142." / "Add a new contact: Sam, sam@work.com." → creates a contact.
- **Send SMS** — "Text Mom that I'm running late." / "Message 555-0199: on my way." → opens a pre-filled SMS ready to send.
- **Dial** — "Call Dad." / "Dial 555-0123." → opens the dialer with the number loaded.

### Multi-step examples
- **"What's 15% of 80, then set a timer for that many minutes."** → calculates 12, then starts a 12-minute timer.
- **"Copy my Wi-Fi password to the clipboard and make a note of it too."** → writes the text to the clipboard, then saves a matching note.
- **"Check my battery, and if it's low set a reminder alarm for 1 hour from now to charge."** → reads the battery level, then sets an alarm an hour out.

---

## Quick legend

| Symbol | Meaning |
|---|---|
| 🟢 | No permission needed (or normal/auto-granted) |
| 🟡 | Runtime permission prompt, OR confirm-in-system-UI intent |
| 🔴 | Sensitive / special-access / needs explicit user confirmation |
| **RO** | Read-only — safe to auto-run, no confirmation |
| **MUT** | Mutating — changes device state, needs confirmation |
| **EGRESS** | Sends data off-device — always confirm |

---

## Tier 1 — Build first (low effort, reliable, no/normal permission)

These are deterministic, single-action, clean numeric/string slots — exactly what a 1.5B model targets reliably.

### `get_datetime` 🟢 RO
- **Does:** current time / date / "next Friday" math grounded in the real device clock.
- **API:** system clock + `java.time` (`LocalDateTime`, `ZoneId`), `TimeZone.getDefault()`.
- **Permission:** none. **Offline:** yes. **Effort:** low.
- **Why:** fixes the model's notoriously bad date arithmetic. Auto-runs.

### `calculate` 🟢 RO
- **Does:** evaluate a math expression ("what is 18% of 240").
- **API:** in-app expression evaluator (Shunting-yard / `exp4j`) — no system API.
- **Permission:** none. **Offline:** yes. **Effort:** low.
- **Why:** offloads arithmetic the LLM gets wrong. Deterministic. Highest value-per-effort.

### `get_battery_status` 🟢 RO
- **Does:** battery % and charging state ("am I charging?").
- **API:** `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)`; `registerReceiver(null, ACTION_BATTERY_CHANGED)` for charging/temperature/plugged type.
- **Permission:** none. **Offline:** yes. **Effort:** low. Auto-runs.

### `toggle_flashlight` 🟢 MUT
- **Does:** turn the torch on/off ("flashlight on").
- **API:** `CameraManager.setTorchMode(id, true/false)`; id from `getCameraIdList()` where `FLASH_INFO_AVAILABLE`. Brightness via `turnOnTorchWithStrengthLevel()` (API 33+, 9R supports it).
- **Permission:** **none** — `setTorchMode` does NOT require `CAMERA`. **Offline:** yes. **Effort:** low.
- **Why:** instant, binary, permissionless — perfect small-model target. Light confirm optional.

### `set_timer` 🟢 MUT
- **Does:** "set a 10 minute timer."
- **API:** `Intent(AlarmClock.ACTION_SET_TIMER)` + `EXTRA_LENGTH` (seconds), `EXTRA_MESSAGE`, `EXTRA_SKIP_UI=true` (fires with no extra screen). Handled by the system Clock app.
- **Permission:** `com.android.alarm.permission.SET_ALARM` (**normal**, auto-granted, no runtime prompt). **Offline:** yes. **Effort:** low.

### `set_alarm` 🟢 MUT
- **Does:** "wake me at 7am" / "alarm at 6:30 on weekdays."
- **API:** `Intent(AlarmClock.ACTION_SET_ALARM)` + `EXTRA_HOUR`, `EXTRA_MINUTES`, `EXTRA_MESSAGE`, `EXTRA_DAYS` (recurrence), `EXTRA_SKIP_UI`.
- **Permission:** `SET_ALARM` (normal). The app does **not** need `SCHEDULE_EXACT_ALARM` (the Clock app schedules). **Offline:** yes. **Effort:** low.

### `set_volume` 🟢 MUT
- **Does:** "set volume to 50%" / "mute the ringer."
- **API:** `AudioManager.setStreamVolume(STREAM_MUSIC/RING/ALARM, index, flags)`; `getStreamMaxVolume()` for the %→index mapping.
- **Permission:** none for normal volume (`MODIFY_AUDIO_SETTINGS` is normal). Total-silent/DND needs `ACCESS_NOTIFICATION_POLICY` (🔴 special access). **Offline:** yes. **Effort:** low.
- **Ambiguity:** which stream — let the model pick or default to ring/media.

### `read_clipboard` / `set_clipboard` 🟢 RO/MUT
- **Does:** "copy this" / "summarize what I copied."
- **API:** `ClipboardManager.getPrimaryClip()` / `setPrimaryClip(ClipData.newPlainText(...))`.
- **Permission:** none (Android 10+ allows clipboard *read* only for the focused app — fine for a foreground chat). **Offline:** yes. **Effort:** low.

---

## Tier 2 — High value, more work or a permission prompt

### `create_calendar_event` 🟡 MUT
- **Does:** "add lunch with Sam tomorrow at noon."
- **API:** `Intent(ACTION_INSERT, CalendarContract.Events.CONTENT_URI)` + `EXTRA_EVENT_BEGIN_TIME`/`END_TIME`, `TITLE`, `EVENT_LOCATION`, `DESCRIPTION`. User confirms in the Calendar UI.
- **Permission:** **none** for the intent variant (silent insert via `ContentResolver` needs `WRITE_CALENDAR` 🔴). **Offline:** yes. **Effort:** low.

### `create_contact` 🟡 MUT
- **Does:** "save this number as John."
- **API:** `Intent(ContactsContract.Intents.Insert.ACTION)` + `EXTRA` NAME/PHONE/EMAIL — opens the contact editor pre-filled.
- **Permission:** none for the editor intent (`WRITE_CONTACTS` 🔴 only for silent insert). **Offline:** yes. **Effort:** low.

### `send_sms` 🟡 EGRESS
- **Does:** "text Mom I'm running late."
- **API:** **safe variant:** `Intent(ACTION_SENDTO, "smsto:<number>")` + `sms_body` extra → opens SMS app pre-filled. **Silent:** `SmsManager.sendTextMessage()` (🔴 `SEND_SMS`).
- **Permission:** none for compose intent. **Offline:** yes (SMS = carrier, not internet). **Effort:** low.
- **⚠️ Always confirm** — recipient + body preview. Prefer the compose-intent variant.

### `lookup_contact` / `call` 🟡 RO/EGRESS
- **Does:** "what's Mom's number?" / "call Alex."
- **API:** `ContentResolver` query on `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`. Dial via `ACTION_DIAL` (🟢 no perm) or `ACTION_CALL` (🔴 `CALL_PHONE`).
- **Permission:** `READ_CONTACTS` (🟡 runtime) to resolve a name→number. **Offline:** yes. **Effort:** med (name matching against a cursor).

### `query_calendar_events` 🟡 RO
- **Does:** "what's on my calendar today?"
- **API:** `ContentResolver` query on `CalendarContract.Instances.CONTENT_URI` with a begin/end range via `ContentUris.appendId`.
- **Permission:** `READ_CALENDAR` (🟡 runtime). **Offline:** yes. **Effort:** med (cursor + date-range building).

### `open_settings_screen` 🟢 MUT
- **Does:** "open Wi-Fi settings" / "turn on Bluetooth" (opens the toggle, can't flip it silently).
- **API:** `Intent` to `ACTION_WIFI_SETTINGS`, `ACTION_BLUETOOTH_SETTINGS`, `ACTION_AIRPLANE_MODE_SETTINGS`, etc.; or `Settings.Panel.ACTION_WIFI` / `ACTION_INTERNET_CONNECTIVITY` (API 29+) for an in-context slider.
- **Permission:** none. **Offline:** yes. **Effort:** low.
- **Note:** apps **cannot** toggle Wi-Fi/Bluetooth programmatically since Android 10/13 — opening the settings panel is the realistic action.

### `search_photos` / `list_media` 🟡 RO
- **Does:** "show photos from last weekend" (metadata filter only).
- **API:** `ContentResolver` query on `MediaStore.Images.Media` (filter by `DATE_TAKEN`, `DISPLAY_NAME`, album `BUCKET_DISPLAY_NAME`).
- **Permission:** `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO` (🟡 runtime, Android 13+). **Offline:** yes. **Effort:** med.
- **Limit:** content search ("photos of dogs") needs ML the small model can't do — **metadata only**.

---

## Tier 3 — Niche / capped usefulness

### `get_location` 🟡 RO
- **Does:** current coordinates.
- **API:** `FusedLocationProviderClient.getCurrentLocation()` or `LocationManager.getLastKnownLocation(GPS_PROVIDER)`.
- **Permission:** `ACCESS_COARSE/FINE_LOCATION` (🟡). **Offline:** partial. **Effort:** med.
- **⚠️ Offline limit:** returns **lat/lng only** — reverse-geocoding to an address (`Geocoder`) needs network. GPS fix slow indoors.

### `set_brightness` 🔴 MUT
- **Does:** "dim the screen."
- **API:** per-app via `WindowManager.LayoutParams.screenBrightness` (🟢, affects only this app's screen). System-wide via `Settings.System.putInt(SCREEN_BRIGHTNESS)` needs `WRITE_SETTINGS` (🔴 special access).
- **Effort:** med. Usefulness capped — most users won't grant `WRITE_SETTINGS`.

### `read_sensors` 🟡 RO
- **Does:** "how many steps today" / "is it bright."
- **API:** `SensorManager.getDefaultSensor(TYPE_LIGHT / PRESSURE / STEP_COUNTER / ...)` + `registerListener`.
- **Permission:** none for most; step counter needs `ACTIVITY_RECOGNITION` (🟡). **Offline:** yes. **Effort:** med.
- **Limits:** 9R has light/accel/gyro/barometer but **no ambient-temp/humidity** sensor; step counter is steps-since-boot, not per-day.

---

## Summary table

| Function | Tier | Perm | Risk | Effort | Offline |
|---|---|---|---|---|---|
| `get_datetime` | 1 | 🟢 | RO | low | ✅ |
| `calculate` | 1 | 🟢 | RO | low | ✅ |
| `get_battery_status` | 1 | 🟢 | RO | low | ✅ |
| `toggle_flashlight` | 1 | 🟢 | MUT | low | ✅ |
| `set_timer` | 1 | 🟢 | MUT | low | ✅ |
| `set_alarm` | 1 | 🟢 | MUT | low | ✅ |
| `set_volume` | 1 | 🟢 | MUT | low | ✅ |
| `read/set_clipboard` | 1 | 🟢 | RO/MUT | low | ✅ |
| `create_calendar_event` | 2 | 🟡 | MUT | low | ✅ |
| `create_contact` | 2 | 🟡 | MUT | low | ✅ |
| `send_sms` | 2 | 🟡 | EGRESS | low | ✅ |
| `lookup_contact` / `call` | 2 | 🟡 | RO/EGRESS | med | ✅ |
| `query_calendar_events` | 2 | 🟡 | RO | med | ✅ |
| `open_settings_screen` | 2 | 🟢 | MUT | low | ✅ |
| `search_photos` | 2 | 🟡 | RO | med | ✅ (metadata) |
| `get_location` | 3 | 🟡 | RO | med | ⚠️ coords only |
| `set_brightness` | 3 | 🔴 | MUT | med | ✅ |
| `read_sensors` | 3 | 🟡 | RO | med | ✅ |

---

## Architecture

The model does NOT act — it picks a function; the app executes it. Layers:

```
User msg ──▶ AgentOrchestrator
                │  builds prompt with tool declarations + history
                ▼
            LlmService (Qwen2.5-1.5B)
                │  emits <tool_call>{"name":...,"arguments":{...}}</tool_call>
                ▼
            ToolCallParser  ──▶  risk check
                │                   │ RO → auto-run
                │                   │ MUT/EGRESS → confirm card (UI)
                ▼
            ToolHandler registry  ──▶  real Android API (Intent / ContentResolver / Manager)
                │  returns ToolResult
                ▼
            feed result back ──▶ LlmService generates final prose reply
```

**Model strategy: ONE model.** Qwen2.5-1.5B has function calling **native** (Hermes `<tool_call>` JSON in its chat template). Do **not** add FunctionGemma-270M as a second model — it'd risk OOM on 8GB (already on CPU backend to avoid it), it's gated, has no ready `.task`, and is weak without fine-tuning. The MediaPipe Function Calling SDK doesn't cover Qwen, so we parse the `<tool_call>` blocks ourselves.

**New code (proposed):**
| Piece | Location | Role |
|---|---|---|
| `AgentOrchestrator` | `service/agent/` | Owns transcript, runs call→execute→feedback loop, emits `AgentEvent` Flow |
| `ToolCallParser` + `StreamingToolDetector` | `service/agent/` | Extract `<tool_call>` JSON; hide raw markup while streaming |
| `ToolHandler` registry | `service/tools/` | `interface ToolHandler { name; risk; suspend execute(args) }`, Hilt `@IntoMap` |
| `LlmService` (edit) | `service/llm/` | Add Qwen chat templating + **multi-turn** (currently single-turn) |
| `ChatRepository` (edit) | `data/repository/` | Delegate to orchestrator; persist tool-call/tool-result rows |

---

## Safety model (policy-driven)

- **RO → auto-run.** MUT → confirm. EGRESS/SPENDING → confirm + show *what* and *where*.
- Confirm card shows **parsed, human-readable args** (never raw grammar). Approve / Deny / "Always allow" (low-risk MUT only — never EGRESS).
- **Sandboxing = capability confinement:** fixed allowlist dispatch (never reflect a model-supplied name = RCE risk), schema-validate args before execute, path-confine to app dirs, `withTimeout(~10s)`, cap result size (~4KB).
- **Malformed/hallucinated calls** → never crash; turn every failure into model-readable feedback ("No such tool 'x'. Available: [...]") so the model retries or answers directly.
- **Prompt-injection via tool output:** results are untrusted — strip control tokens before appending; keep the system preamble immutable & first.
- `MAX_TOOL_HOPS` (~5) stops runaway loops.

---

## Phased build plan

- **Phase 0 — Multi-turn (prerequisite).** `LlmService` is single-turn today; tool calling is inherently multi-turn (call → result → reply). Add Qwen chat templating with role-tagged history. *Useful on its own.*
- **Phase 1 — Loop + 1 read-only tool.** `AgentOrchestrator` + parser + registry with `get_datetime` or `calculate`. Lower temperature to ~0.0–0.3 on tool turns (current 0.8 drifts). Prove the loop. *(Bulk of the work.)*
- **Phase 2 — Tier-1 actions + confirm UX.** `set_timer`, `set_alarm`, `toggle_flashlight`, `get_battery_status`, `set_volume` + risk-tiered confirm card + streaming markup hiding.
- **Phase 3 — Permissioned/intent tools.** Calendar, contacts, SMS (compose-intent variants first), runtime-permission gating, persist tool rows in Room.
- **Phase 4 — Harden.** Brace-repair, injection stripping, hop cap, "always allow" allowlist.

## Honest limits of a 1.5B model on this device
- **Moderate reliability**, not high — expect arg-key hallucination, prose-wrapped calls, JSON drift. Mitigate with low temperature, a **small curated tool set** (more tools = more confusion), strict schema validation + repair + retry.
- **Latency/battery grows per hop** (transcript re-prefilled each generate). Cap hops; truncate old history under the 2048-token budget.
- Best at **deterministic slot-filling** (timer, flashlight, volume, math, datetime); shaky at open-ended multi-tool planning. Curate accordingly.

---

*See also: `OK_GOOGLE_INTEGRATION.md` for voice-assistant integration options.*
