# OmniPro — Project Reference

## What this is

OmniPro (`com.locallink.pro`) is a standalone Android AI assistant (Kotlin + Jetpack
Compose) with three entry modes wired to distinct backends:

| Mode (home card) | Backend entry | Capabilities |
|---|---|---|
| New chat | `ChatRepository.runChatOnly` → `runChatWithTools` | LLM chat + Composio cloud tools (web search, Gmail, Slack, …) |
| Voice chat | same as chat, `isVoice=true` | + on-device STT (Parakeet/sherpa-onnx) and streaming TTS (Kokoro) |
| Automate my phone | `ChatRepository.runAgent` | planner + screen pilot via AccessibilityService; learns & replays routines. **Never routes through Composio.** |

The AI brain is cloud-only via OpenRouter (BYO key). On-device LLM code
(Qwen/FunctionGemma) exists but is dormant — not routed to.

## Architecture map

```
app/src/main/java/com/locallink/pro/
├── data/repository/ChatRepository.kt      # mode routing, agent serialization (one run at a time)
├── data/db/                               # Room: sessions, messages, experiences (v3)
├── service/llm/
│   ├── OpenRouterClient.kt                # chat + Composio MCP meta-tool loop (run()), plainChat()
│   ├── ComposioMcpClient.kt               # Composio Tool Router over MCP (search/execute/connect)
│   └── ComposioClient.kt                  # REST: app grid, OAuth connect/disconnect
├── service/pilot/                         # Automate: PlanExecutor, PilotController, MemoryPilot,
│   │                                      #   ExperienceReplayer (learned routines), OmniAccessibilityService
│   └── PilotActionSchema.kt               # the pilot's action-space prompt
├── service/routine/                       # routine scheduling: RoutineScheduler (WorkManager,
│                                          #   self-chaining daily) + RoutineWorker → runAgent
├── service/notify/OmniNotificationListener.kt  # notification triggers (rules in Room) → speak/agent
├── service/call/                          # dial fast-path support + in-call speakerphone assistant (beta)
├── service/voice/
│   ├── VoiceLoopController.kt             # hands-free "Hey Omni" state machine (mic is single-owner!)
│   ├── WakeWordEngine.kt                  # sherpa-onnx KeywordSpotter (assets/kws)
│   ├── ParakeetSttEngine.kt + SttModelManager.kt   # on-device STT (~670MB, downloaded in-app)
│   └── KokoroTtsService.kt                # streaming TTS (assets/kokoro-en-v0_19)
└── ui/screens/                            # Compose: sessions (home + setup-health banner), chat,
                                           #   onboarding (first-run wizard), routines (library+schedule),
                                           #   memory (user facts), notifyrules, connect, settings
```

## Hard-won constraints — do not regress

1. **sherpa-onnx JNI callbacks must be explicit `object : Function1<…>`, never Kotlin
   lambdas.** Kotlin 2.x lambdas compile via invokedynamic; the desugared class lacks the
   specialized `invoke` signature the native side looks up → process SIGABRT.
2. **The mic is single-owner** — wake engine, STT, TTS are time-exclusive.
   `WakeWordEngine.stop()` must stay synchronous (joins its worker) and be called
   off-main; otherwise STT gets `ERROR_NO_MATCH` from mic contention.
3. **KWS models must be the fp32 export** of
   `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` — the `-mobile` int8 encoder
   has an incompatible streaming export and aborts natively in `KeywordSpotter.decode`.
4. **Agent runs are serialized** (`ChatRepository.serialized`) — two concurrent pilot
   runs fight over the one screen and both spiral into replans.
5. Composio powers **chat and voice only**; Automate's planner emits only chat/pilot
   channels by design.
6. **The device-tool fast path may only run tools that COMPLETE a task.** Navigation-only
   tools (`launch_app`, `open_settings`, `open_url`, `web_search`) are excluded in
   `DeviceToolFastPath.NAVIGATION_ONLY`: "open LinkedIn and comment on the top post"
   matched `launch_app`, which opened the app and reported the task done, so the pilot
   never ran. Prompt wording alone did not prevent it. `make_phone_call` is excluded
   separately — it uses `ACTION_CALL` and would dial unattended.
7. **Kokoro playback: the AudioTrack buffer size IS the lookahead budget.** `track.write`
   is `WRITE_BLOCKING`, so a small buffer throttles synthesis to real time and the next
   sentence can't generate ahead. Playback is also held until ~0.5s is banked
   (`primeSamples`) — starting on the first chunk drains the track mid-sentence and the
   device logs `disabled due to previous underrun`.
8. **`AgentEvent.Token` is a DELTA, not a snapshot** — consumers must append. All three
   `ChatRepository` collectors accumulate and reset per turn.
9. **Composio triggers can't work on-device — don't rebuild them.** Probed against a live
   account (2026-07-25): `/api/v3/triggers_types` lists fine (86 types across 7 connected
   apps) and `/api/v3/trigger_instances/active` returns 200, so subscriptions *can* be
   managed. But every event-retrieval candidate 404s — `trigger_logs`, `logs?type=trigger`,
   `trigger_instances/logs`. Composio delivers events by **webhook only**, and a phone has
   no public URL, so a subscription UI would let users arm triggers that could never fire.
   Needs a relay server; until then the existing notification triggers
   (`OmniNotificationListener`) already cover the common cases — a Gmail push becomes an
   Android notification, which OmniPro rules can match — with no server at all.

## Cross-cutting systems

- **Memory facts** (`data/repository/MemoryStore.kt`, Room `memory_facts`): persistent
  user facts injected into chat/voice system prompts, plainChat, vision and the Automate
  planner; auto-extracted from user messages (regex-gated background LLM pass).
- **Routine library** (Settings → Learned routines): rename/run/delete learned routines,
  optional daily schedule via WorkManager self-chaining one-time work.
- **Onboarding** (`ui/screens/onboarding/`): 4-step first-run wizard (gate: DataStore
  `onboarding_done`); home shows a SetupHealthBanner when the OpenRouter key is missing
  or the a11y service is off (ColorOS kills it on every reinstall).
- **Vision**: `OpenRouterClient.visionChat` — data-URI JPEG content; falls back through
  known multimodal models when the selected model rejects images.
- **Dial fast-path** in `runAgent`: "call <number|remembered contact>" opens the dialer
  prefilled (ACTION_DIAL only — never places calls unattended).
- **Device-tool fast path** (`service/llm/tools/DeviceToolFastPath.kt`): before Automate
  plans anything, one tool-calling turn asks whether a single local tool
  (`ToolRegistry`, 22 intent-backed handlers) completes the task — "set an alarm for 7am"
  becomes one intent instead of a planner pass plus a screenshot→reason→tap loop. Runs
  *before* the a11y check, so it works with the service off. Declines → pilot, untouched.
- **Streaming** (`OpenRouterClient.postChatStreaming`): SSE; `AgentEvent.Token` carries
  deltas. `ChatViewModel` speaks each completed sentence as it arrives (Kokoro warms up at
  init and plays a queue gaplessly), so speech starts on sentence 1, not the whole reply.
- **Composio direct tools** (`ComposioClient.directToolSchemas`): connected apps' tool
  schemas are fetched once and registered natively, so the model calls `GMAIL_SEND_EMAIL`
  in one hop and it executes over REST. Meta-tools stay registered as the discovery
  fallback. Bounded (6/toolkit, 24 total) — every registered tool costs tokens per turn.
- **Activity dashboard** (`ui/screens/dashboard/`): merges trigger runs and chat sessions
  into one Planned / Event / Ad-hoc feed, so voice- and trigger-initiated work is visible.
  Derives voice/agent flags from existing rows (`messages.isVoice`, `tool_call` rows) —
  no schema migration.
- **Routine-informed planning**: on a near-miss, `ExperienceStore.priorRoutinesBlock`
  feeds similar learned routines to the planner as worked examples via `PlanExecutor`'s
  `priorRoutines`, instead of planning from scratch.

## Model assets (Git LFS)

`app/libs/sherpa-onnx-1.12.23.aar`, `assets/kokoro-en-v0_19/{model.onnx,voices.bin}`,
`assets/kws/*.onnx` are tracked via **Git LFS**. `assets/kws/keywords.txt` is the
custom "Hey Omni" keyword spec — never overwrite it with a release tarball's copy.

## Build & test

```bash
./gradlew :app:assembleDebug       # APK → app/build/outputs/apk/debug/
./gradlew test                     # JVM unit tests (pilot/experience suites)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

JDK 17 (Android Studio JBR works: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).

## Conventions

- MVI-ish: StateFlow UI state, Hilt DI, Room, DataStore prefs (`SettingsPreferences`)
- Design system: "Porcelain" light theme — warm off-white `#F7F5F3`, ink text, pastel
  lavender/mint washes, black CTA pills (`GradientPill`), violet→pink orb identity,
  Epilogue font. Components in `ui/components/` (AuroraBackground, ParticleSphere, pills).
- Wire protocol for tools: OpenAI-style function calling; Composio meta-tools are the
  only top-level tools (`COMPOSIO_SEARCH_TOOLS` → `COMPOSIO_MULTI_EXECUTE_TOOL`).

See [SETUP.md](SETUP.md) for user-facing installation and configuration.
