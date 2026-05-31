# OmniPin — Leveraging "OK Google" / Google Assistant (2026 reality)

**Question:** how can the OmniPin offline on-device LLM chat app leverage "OK Google"?

**Short answer:** In 2026 this is a moving target — Google is mid-migration from classic **Google Assistant** to **Gemini** (the ~March 2026 cutover is slipping, rolling through the year). The only thing that *robustly* works to route "OK Google" into OmniPin is the **free bare launch** ("Hey Google, open OmniPin") — and it already works with zero code. Everything richer is either dead, dying with classic Assistant, cloud-dependent (which kills the offline value prop), or gated to hardware the OnePlus 9R doesn't have.

> **Core tension:** OmniPin's whole point is on-device & private. "OK Google" runs the wake word + speech recognition + intent parsing **in Google's cloud**. Deep Assistant integration directly undermines the product *and* couples it to a service Google is shutting down. Only the trivial "open the app" launch is harmless.

---

## The two meanings of "leverage OK Google"

| | A. Plug INTO Google's assistant | B. BE the assistant |
|---|---|---|
| User says | "Hey Google, …" → Google launches/controls OmniPin | assist-gesture / hotword → OmniPin's **own** offline voice |
| NLP happens | Google cloud | on-device (OmniPin) |
| Status | rich versions dying / unshippable | durable but heavyweight |
| On-brand for offline app? | ❌ no | ✅ yes |

---

## Capability status matrix (2026)

| Capability | What it is | Status for OmniPin | Internet |
|---|---|---|---|
| **"Hey Google, open OmniPin"** | Bare launch by name | ✅ **WORKS, zero code.** Any installed app; carries over to Gemini | launch only |
| **Static/dynamic App Shortcuts** | Launcher long-press + Assistant suggestions | ✅ Launcher part fully supported & **offline**; Assistant-surfacing dying with sunset | shortcuts: no |
| **App Actions / BII** (`shortcuts.xml` capabilities) | Assistant routes "start a timer in OmniPin" to the app | ⚠️ **Documented-but-dying** — tied to classic Assistant; devs report Gemini ignoring them; needs Play review | yes |
| **OPEN_APP_FEATURE deep-link BII** | Voice lands on a specific screen | ⚠️ Works on legacy-Assistant devices, **degrading** under Gemini | yes |
| **Custom Intents** (custom phrases) | App-defined voice phrases | ⚠️ en-US only, first to break under Gemini — don't invest | yes |
| **`actions.xml`** (legacy schema) | Pre-2021 App Actions | 🔴 **DEAD / deprecated** — never use | — |
| **Conversational Actions** ("talk to OmniPin") | Voice apps on Assistant | 🔴 **DEAD** (sunset June 13, 2023) | — |
| **AppFunctions (Android MCP)** | The *real* Gemini-era successor — apps expose typed functions Gemini calls on-device | 🔴 **Unshippable for an indie** — experimental preview, Gemini hookup private/trusted-tester EAP only, **Android 16+**, execution gated to Galaxy S26 / Pixel 10. **OnePlus 9R is out of scope.** | varies |
| **`ACTION_ASSIST` / `VoiceInteractionService`** | OmniPin BECOMES the device assistant | ✅ **Supported, durable, offline** — core AOSP, API 29+, unaffected by Gemini sunset | no |
| **Gemini Live API** (in-app voice) | Self-serve in-app mic | ⚠️ Works but **cloud Gemini**, and it's just an in-app mic — NOT the system hotword. Contradicts offline | yes |

---

## What's dead vs durable (the generational split)

- **DEAD:** Conversational Actions (2023), `actions.xml`.
- **DYING (legacy/maintenance):** App Actions, BIIs, Custom Intents, Google Shortcuts Integration library — all bound to classic Assistant, decommissioning through 2026. Not a forward bet.
- **DURABLE (unaffected by the Gemini transition):** bare "open <app>" launch, App Links deep linking, **launcher App Shortcuts**, `ACTION_ASSIST` + being the default assistant, `VoiceInteractionService`.
- **THE FUTURE (but not yet reachable):** **AppFunctions** — Android 16+, EAP-gated, S26/Pixel 10 only. Register for the EAP to watch; don't build on it.

---

## Options ranked for OmniPin

### #1 (Recommended) — Do NOTHING with Assistant; perfect OmniPin's own voice
OmniPin already owns the full loop: SpeechRecognizer STT + Kokoro TTS in `VoiceService.kt`. That **is** the offline value prop. "Leveraging OK Google" by *not* touching it and polishing the in-app voice + on-device function calling (see `ON_DEVICE_FUNCTIONS.md`) is the most coherent move.
- **⚠️ Audit `VoiceService.kt` STT:** Android `SpeechRecognizer` often routes to Google's **cloud** recognizer unless you use `EXTRA_PREFER_OFFLINE` / `SpeechRecognizer.createOnDeviceSpeechRecognizer()` (API 31+). If "offline" is a real promise, the STT must not silently hit Google's cloud. **Highest-value, on-brand voice work.**
- **Effort:** the STT audit is small; the rest is the function-calling roadmap.

### #2 (Near-free, do this) — "Hey Google, open OmniPin" + static shortcuts
Bare launch already works. The worthwhile *addition* is letting voice/long-press land on a useful screen.
- Add `res/xml/shortcuts.xml` with 1–3 **static App Shortcuts** ("New chat", "Voice chat") deep-linking into `MainActivity`; reference from manifest via `<meta-data android:name="android.app.shortcuts" android:resource="@xml/shortcuts"/>`.
- Primary payoff: **launcher long-press shortcuts** (durable, offline, never deprecated). Any Assistant-surfacing is a bonus that may rot.
- **Effort:** ~1–2 hours. No Play-review dependency for the launcher value; no internet for the shortcuts themselves.

### #3 (Only if hands-free IS the product) — Register as the device assistant
The **only** path that gives OmniPin a hotword/assist-gesture trigger into its *own offline brain* — philosophically consistent (replace Google's cloud assistant with OmniPin's offline one).
- **Light:** an `ACTION_ASSIST` activity (intent-filter `android.intent.action.ASSIST` + `category.DEFAULT`). Assist gesture / long-press home → OmniPin opens in voice mode. User opts in via Settings > Default apps > Digital assistant app (no one-tap system dialog for `ROLE_ASSISTANT` — deep-link them to the setting). **Effort: ~half a day.**
- **Heavy:** a full `VoiceInteractionService` (`BIND_VOICE_INTERACTION`) for always-on hotword. **Costs:** Google does **not** open the low-power hotword DSP to 3rd-party apps → CPU-awake wake word = **battery drain**; only one assistant active at a time; users must manually switch off Google. **Effort: several days + ongoing maintenance.**
- Honest take: cool demo, niche reach, real cost. Only if "replace OK Google with my private assistant" *is* the pitch.

### #4 (SKIP) — App Actions / BIIs into OmniPin
Requires Play publishing, only matches Google's predefined BII catalog (doesn't fit "ask my local LLM anything"), is cloud-NLP, and is exactly what's reported broken under Gemini. Investing in a sunsetting runtime. Don't.

### (SKIP for now) — AppFunctions
The genuine future, but unshippable to a OnePlus 9R indie in 2026. **Register for the EAP** to be ready; don't build on it yet.

---

## Recommendation

**Do now (~1–2 hours, near-zero risk):**
1. Add `res/xml/shortcuts.xml` with static App Shortcuts → deep-link into `MainActivity` (launcher shortcuts + free "open OmniPin" landing somewhere useful).
2. **Audit `VoiceService.kt` STT** for true on-device recognition (`EXTRA_PREFER_OFFLINE` / `createOnDeviceSpeechRecognizer`) so "offline" is a real promise.

**Do only if hands-free is the product (~half a day):**
3. Add an **`ACTION_ASSIST` activity** so users can *opt in* to making OmniPin their private, offline device assistant. Skip the full `VoiceInteractionService` unless you accept the battery cost.

**Do NOT do:** App Actions / BIIs / Custom Intents (dying, cloud, needs Play, off-brand) · `actions.xml` / Conversational Actions (dead) · Gemini Live API / Extensions (cloud, contradicts offline) · build on AppFunctions now (out of reach on this hardware).

**One-liner:** For an offline app in 2026, "leveraging OK Google" realistically means *just the free "Hey Google, open OmniPin" launch* — anything richer either rides classic Assistant (dying) or sends user data to Google (kills the value prop). Invest in OmniPin's own offline voice + on-device function calling; add static shortcuts as a cheap bonus; become the device assistant (`ACTION_ASSIST`) only if a private hands-free assistant is the actual pitch.

---

## Relevant files
- `app/src/main/AndroidManifest.xml` — where `shortcuts.xml` meta-data / `ACTION_ASSIST` intent-filter would go
- `app/src/main/java/com/locallink/pro/service/voice/VoiceService.kt` — existing STT/TTS to audit for true offline STT
- `app/build.gradle.kts` — minSdk 26 / targetSdk 35 (note: AppFunctions needs Android 16+, so the 9R is out of scope)

*See also: `ON_DEVICE_FUNCTIONS.md` for the on-device function-calling catalog.*
