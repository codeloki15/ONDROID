# OmniPro — Setup Guide

OmniPro is an on-device AI assistant for Android with three modes:

| Mode | What it does | Needs |
|------|--------------|-------|
| **New chat** | Conversational AI + cloud app tools (web search, Gmail, Slack, …) | OpenRouter key (Composio key optional) |
| **Voice chat** | Same as chat, but hands-free — on-device speech-to-text and streaming text-to-speech | Mic permission |
| **Automate my phone** | The agent reads your screen and taps/types/swipes to complete tasks in any app, learning routines as it goes | Accessibility service |

There are two ways to get the app: install the prebuilt APK (fastest) or build from source.

---

## Option A — Install the prebuilt APK (fastest)

1. On your Android phone (Android 8.0 / API 26 or newer), download **`OmniPro.apk`** from the
   [latest GitHub Release](https://github.com/codeloki15/ONDROID/releases).
2. Open the downloaded file. If prompted, allow your browser/file manager to
   **install unknown apps** (Settings → Apps → Special app access → Install unknown apps).
3. Tap **Install**. The APK is ~570 MB because the voice models (TTS, wake word, VAD)
   are bundled — no extra downloads needed for voice output.
4. Continue to **First-run configuration** below.

> The APK is a debug-signed build. If you previously installed a differently-signed build,
> uninstall it first.

---

## Option B — Build from source

### Prerequisites

- **Android Studio** (Ladybug or newer) — or plain JDK 17 + Android SDK (API 35)
- **git** and **git-lfs** — the model binaries (~400 MB: sherpa-onnx AAR, Kokoro TTS,
  wake-word models) are stored in Git LFS. **Install LFS before cloning** or you'll get
  tiny pointer files instead of models.

```bash
brew install git-lfs        # macOS (or: apt install git-lfs / choco install git-lfs)
git lfs install
```

### Clone & build

```bash
git clone https://github.com/codeloki15/ONDROID.git
cd ONDROID

# Build the debug APK
./gradlew :app:assembleDebug

# APK lands at:
#   app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press **Run** with your device connected.

### Install on a device

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Sanity check after cloning:** `ls -la app/libs/` should show
> `sherpa-onnx-1.12.23.aar` at ~37 MB. If it's a few hundred *bytes*, LFS didn't run —
> execute `git lfs pull` inside the repo.

---

## First-run configuration

### 1. Permissions

On first launch, grant **Microphone** (voice chat, wake word) and **Notifications**
(foreground service status) when prompted.

### 2. OpenRouter API key — required for the AI brain

All three modes use a cloud LLM via [OpenRouter](https://openrouter.ai).

1. Create a key at **openrouter.ai/keys** (free tier models are available).
2. In OmniPro: **Settings (gear icon) → AI model** → paste the key.
3. Pick a model from the **Model** dropdown. Choose a **tool-capable** model
   (the picker loads the catalog; e.g. `openai/gpt-oss-120b` or any model marked
   tool-capable) — Automate mode and Composio tools need function calling.

### 3. Composio — optional, unlocks cloud app tools in chat & voice

Composio lets chat/voice act in your cloud apps: search the web, read/send Gmail,
post to Slack, manage Calendar, and hundreds more.

1. Get a free API key at **app.composio.dev**.
2. **Settings → Connected apps → Composio apps** → paste the key → **Save & load apps**.
3. Tap any app in the grid (e.g. Gmail) to connect it via OAuth.

> Web search works with just the key — no app connection needed. Try
> *"Search the web: top news headlines today"* in New chat.

### 4. On-device speech-to-text — optional but recommended

Voice chat works out of the box using Android's system speech recognizer. For a much
better, fully offline experience:

- **Settings → Voice → Download speech model** — downloads NVIDIA Parakeet
  (~670 MB, resumes if interrupted). Once downloaded it's used automatically.

Text-to-speech (Kokoro, 11 voices) is already bundled in the APK and streams —
speech starts ~1 second after a reply begins.

### 5. Accessibility service — required only for "Automate my phone"

The Automate agent perceives the screen and acts through Android's accessibility API:

1. **Android Settings → Accessibility → Downloaded / Installed apps → OmniPro → On**.
2. Approve the confirmation dialog.
3. On the first Automate task you'll also be asked for **screen-capture** consent
   (used to let the model *see* the screen for harder tasks). Optional but recommended.

> **OEM note (OnePlus / OPPO / ColorOS, some others):** reinstalling or updating the
> app silently turns the accessibility service off. If Automate stops working after
> an update, re-enable it in the same place.

### 6. Hands-free "Hey Omni" — optional

Toggle hands-free mode in Settings to keep a low-power wake-word listener running.
Say **"Hey Omni"** from any screen: a live-transcription floater appears, your request
is captured, and the Automate agent takes over the phone to do it.

---

## Quick smoke test

1. **New chat** → "What's 7 × 8?" → instant reply (and it's spoken aloud if auto-TTS is on).
2. **New chat** → "Search the web: top news headlines today" → watch the agent call
   Composio tools and summarize (needs Composio key).
3. **Automate my phone** → "Open the settings app" → the agent drives the phone.
   Repeat it a second time — it replays the learned routine near-instantly.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| Build fails: `sherpa-onnx-1.12.23.aar` missing/invalid | `git lfs install && git lfs pull` |
| "No OpenRouter API key set" in chat | Settings → AI model → paste key |
| Automate says accessibility is off | Android Settings → Accessibility → OmniPro → On (re-enable after every reinstall on ColorOS/OxygenOS) |
| Voice chat hears nothing | Check mic permission; make sure another app isn't holding the mic |
| Wake word never triggers | Enable hands-free in Settings; speak clearly "Hey Omni"; works best within ~2 m |
| STT model download stuck | It resumes automatically; check storage (needs ~700 MB free) |
| Composio apps grid empty | Verify the API key (Settings → Connected apps → Edit), then Save & load apps |

## Storage requirements

- APK: ~570 MB (bundled TTS/wake-word/VAD models)
- Optional Parakeet STT model: ~670 MB (downloaded in-app to app storage)
- Recommended free space: **1.5 GB+**
