# Standalone On-Device LLM Chat — Design Spec

**Date:** 2026-05-28
**Status:** Approved (pending written-spec review)
**Project:** omni_pin_android (package `com.locallink.pro`, app name "Omni Pro" / OmniPin)

## 1. Goal

Convert the app from a thin client of the FastAPI/OmniPin desktop server into a **fully standalone Android app** that runs an LLM **on-device**, with no network connection required at runtime (one-time model download excepted).

The app supports:
- Text chat with **live streaming responses** (request → streamed response).
- **Voice** input (STT) and output (TTS) — existing pipeline kept.
- **Vision**: capture a photo with the camera or pick from the gallery and ask the model about it.
- **Persistent chat history** across sessions in a local SQLite database.

### Explicit non-goals
- No WebSocket/REST/Bluetooth/SSH connection to any server. (The word "WebSocket streaming" from the request is interpreted as **Flow-based async token streaming**, the correct on-device equivalent — MediaPipe streams tokens via an async callback exposed as a Kotlin `Flow`.)
- No agentic tool-calling, file browser, git, or terminal features (these required the server and are removed).
- No multi-client message mirroring or server-side chat history.

## 2. Key technology decisions

| Decision | Choice | Rationale |
|---|---|---|
| LLM location | On-device, fully offline | User requirement: standalone, private |
| Runtime | **MediaPipe LLM Inference API** | Official Google on-device LLM API; Kotlin, GPU accel, async streaming |
| Model | **Gemma 3n** (multimodal `.task` bundle) | Supports image input for vision; text + vision in one model |
| Model delivery | **Download on first launch** | Keeps APK small; downloads to app files dir with progress UI |
| Streaming | **Kotlin `Flow<String>`** over MediaPipe async callback | Live token-by-token UX, no network |
| Persistence | **Room (SQLite)** | Already a dependency (unused today); Flow-based reactive reads |
| Async IO | All DB + model IO on `Dispatchers.IO` | Responsiveness |
| Camera/photos | **CameraX** + system gallery picker | Standard, modern image capture/selection |

### Realistic caveat (documented, not a defect)
Gemma 3n is large (~3GB+). On the target device (OnePlus 9R, Snapdragon 870, 8GB RAM) text chat will be acceptable; **vision inference will be slow** (multiple seconds per image). This is inherent to on-device multimodal inference in 2026, not a fixable code issue.

## 3. Architecture

```
┌──────────────────────────── Android App (standalone) ────────────────────────────┐
│  UI (Compose)                                                                      │
│   ├─ ModelGate (download/loading screen)                                           │
│   ├─ SessionsScreen (list past sessions, new chat)                                 │
│   ├─ ChatScreen (messages, streaming, voice bar, camera/photo button)             │
│   └─ SettingsScreen (TTS controls, model status, clear data)                       │
│        │                                                                           │
│  ViewModels (ChatViewModel, SessionsViewModel, SettingsViewModel)                  │
│        │                                                                           │
│  ChatRepository ── orchestrates persistence + inference                            │
│     ├─ LlmService ──► MediaPipe LlmInference (Gemma 3n)                            │
│     ├─ AppDatabase (Room/SQLite): SessionDao, MessageDao                           │
│     ├─ ImageService (CameraX capture + gallery picker)                             │
│     └─ VoiceService ──► KokoroTtsService (TTS) + SpeechRecognizer (STT)  [KEPT]    │
│        │                                                                           │
│  ModelManager ── one-time model download (OkHttp), ready-state tracking            │
└───────────────────────────────────────────────────────────────────────────────────┘
```

## 4. Components

### 4.1 `LlmService` (new, Singleton)
- Wraps MediaPipe `LlmInference` + `LlmInferenceSession` for Gemma 3n.
- `suspend fun ensureLoaded()`: loads model from disk (path from `ModelManager`); idempotent.
- `fun generateStream(prompt: String, images: List<Bitmap> = emptyList()): Flow<String>`:
  emits partial tokens via `callbackFlow` bridging MediaPipe's async progress listener; completes when generation finishes.
- Holds conversation context for the active session (re-seeds session history into the MediaPipe session, or maintains a running prompt — see 4.2 context policy).
- Error states surfaced via Flow exceptions: model-not-loaded, OOM, inference failure.

### 4.2 Conversation context policy
- The MediaPipe session is **per chat session**. When the user opens an existing session, the prior messages are replayed into a fresh `LlmInferenceSession` to rebuild context (bounded to a max token budget; oldest messages dropped if over budget).
- Streaming an assistant reply accumulates tokens **in memory**; the full assistant message is **persisted once on completion** (not per-token) to avoid DB write thrashing.

### 4.3 `ModelManager` (new, Singleton)
- Knows the model URL, expected size, and on-disk path (`context.filesDir/models/gemma-3n.task`).
- `val state: StateFlow<ModelState>` where `ModelState = NotDownloaded | Downloading(progress) | Ready | Error(msg)`.
- `suspend fun download()`: streams the file via OkHttp with progress; writes to a `.part` temp file, validates final size, atomically renames to final path.
- `fun isReady(): Boolean`: final file exists and size matches.
- Download is restartable; partial `.part` files are cleaned/resumed on retry.

### 4.4 Persistence — Room (SQLite)
**Entities:**
```
@Entity sessions(
  id: String (PK, UUID),
  title: String,            // derived from first user message, fallback "New chat"
  createdAt: Long,
  updatedAt: Long
)

@Entity messages(
  id: String (PK, UUID),
  sessionId: String (FK -> sessions.id, indexed, ON DELETE CASCADE),
  role: String,             // "user" | "assistant"
  text: String,
  imageUri: String?,        // local content/file URI for vision messages, nullable
  isVoice: Boolean,
  timestamp: Long
)
```
**DAOs:**
- `SessionDao`: `observeSessions(): Flow<List<SessionEntity>>`, `upsert`, `delete`, `getById`.
- `MessageDao`: `observeMessages(sessionId): Flow<List<MessageEntity>>`, `insert`, `deleteBySession`, `deleteAll`.
- All access on `Dispatchers.IO`.

### 4.5 `ChatRepository` (rewritten)
- No transport/REST dependencies. Depends on `LlmService`, `SessionDao`, `MessageDao`, `VoiceService`, `ImageService`.
- `createSession()`, `loadSession(id)`, `observeMessages(id)`.
- `suspend fun send(text: String, image: Bitmap? = null, isVoice: Boolean)`:
  1. Ensure a current session exists (create on first message; set title from first user text).
  2. Persist user message (with `imageUri` if image).
  3. Expose a `streamingText: StateFlow<String>` that fills from `LlmService.generateStream`.
  4. On completion, persist assistant message; clear streaming buffer; trigger auto-TTS.
- `clearMessages()` / `deleteSession(id)` / `deleteAll()`.

### 4.6 `ImageService` (new)
- CameraX-based capture to a temp file in app storage; system `PickVisualMedia` for gallery.
- Returns a `Bitmap` (downscaled to a max dimension, e.g. 768px longest side) for inference, plus the saved URI for persistence/display.

### 4.7 Voice (kept, minor changes)
- `VoiceService` + `KokoroTtsService` unchanged in behavior. The only change: it is driven by the new `ChatRepository` instead of the transport-based one. Auto-TTS still speaks the latest assistant message; markdown stripping retained.

### 4.8 UI screens
- **ModelGate**: shown when `ModelManager.state != Ready`. Download button + progress + error/retry. Blocks chat until ready.
- **SessionsScreen**: list of sessions (title + last-updated), tap to open, "+" for new chat, swipe/long-press to delete.
- **ChatScreen** (rebuilt, simplified): message bubbles (user/assistant), inline image thumbnails for vision messages, live streaming bubble with cursor, voice input bar (mic + partial transcript), **camera/photo button** to attach an image, input field + send. Removes: tool-call cards, sub-tools, mirroring, connection indicator, history pagination, model selector dropdown.
- **SettingsScreen** (trimmed + extended): keep TTS speed/pitch/speaker/auto-TTS/STT toggles; add model status (re-download/clear) and "clear all chat data".

### 4.9 Navigation
`Sessions` (start) → `Chat/{sessionId}` → `Settings`. ModelGate is shown over the start destination until the model is ready (or as the actual start destination, redirecting to Sessions when ready).

## 5. Removals (server-coupled — deleted)

**Files to delete:**
- `service/transport/` (TransportLayer, TransportManager, ProtocolSerializer)
- `service/bluetooth/` (BluetoothTransport, BluetoothConnectionService)
- `service/websocket/` (WebSocketTransport, FastApiWebSocketTransport)
- `service/rest/OmniPinApiClient.kt`
- `service/files/LocalFileProvider.kt`
- `service/notification/NotificationHelper.kt`
- `ui/screens/connection/`, `ui/screens/files/`, `ui/screens/git/`, `ui/screens/terminal/`
- `data/model/ProtocolMessage.kt`, `data/model/ServerModels.kt`, `data/model/ServerConfig.kt`
- `data/local/ConnectionPreferences.kt`
- Server-coupled fields/types in `domain/model/` (ConnectionState/ConnectionInfo/TransportType, ToolCallInfo/SubToolInfo, MessageSender.USER_REMOTE, server-only MessageTypes)

**Dependencies to remove:** SSHJ (`com.hierynomus:sshj`). Evaluate Gson for removal (likely unused after; Room/MediaPipe don't need it). Keep OkHttp (model download only).

**Manifest cleanup:** remove Bluetooth, location, external-storage, foreground-service permissions and the `BluetoothConnectionService` + (old) FileProvider entries that are no longer used. Keep `INTERNET` (model download), `RECORD_AUDIO` (STT), add `CAMERA`. Keep a FileProvider only if needed for camera temp files.

**Dependencies to add:** MediaPipe GenAI (`com.google.mediapipe:tasks-genai`), CameraX (`androidx.camera:*`), Room compiler is already present (KSP).

## 6. Error handling & edge cases
- Model not downloaded → ModelGate blocks chat.
- Download interrupted/failed → `.part` cleanup, retry; verify final size before `Ready`.
- Model load failure / OOM → surfaced as error state with message; chat send disabled.
- Empty/blank input → ignored.
- Vision: downscale large images before inference; show thumbnail in the bubble.
- Voice errors → existing `VoiceService` error flows retained.
- Session with no messages → not persisted / cleaned up.

## 7. Testing
- **Unit:** Room DAOs against in-memory database (insert/observe/cascade-delete); `ChatRepository` with a fake `LlmService` (verify persist-user → stream → persist-assistant ordering and title derivation).
- **Manual on-device** (OnePlus 9R, already connected via adb): model download flow, text streaming, session persistence across app restart, voice in/out, camera capture + gallery pick + vision answer. Build & install via `./gradlew installDebug`.

## 8. Open implementation details (resolved during planning)
- Exact Gemma 3n `.task` artifact URL/size and license/auth (HuggingFace token may be required for download) — to be pinned in the implementation plan.
- Whether MediaPipe maintains multi-turn context internally vs. manual prompt reconstruction — confirm against the installed MediaPipe version's API during implementation; context policy (4.2) is the fallback.
```
