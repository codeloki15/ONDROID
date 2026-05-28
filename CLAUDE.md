# LocalLink Pro - Project Reference

## Project Overview
LocalLink Pro is a multi-transport Android-to-Desktop bridge with AI messaging. The Android app connects to a Python desktop server via Bluetooth SPP or WebSocket, sends user messages (text or voice), and receives AI-generated responses with streaming support and text-to-speech playback.

## Architecture

### Android App (Kotlin + Jetpack Compose)
```
app/src/main/java/com/locallink/pro/
├── LocalLinkApplication.kt          # Hilt Application entry point
├── di/
│   └── AppModule.kt                 # Hilt dependency injection module
├── domain/model/
│   ├── Message.kt                   # Chat message domain model
│   ├── ConnectionState.kt           # Connection state + transport enums
│   └── DeviceInfo.kt                # Bluetooth device + connection profiles
├── data/
│   ├── model/
│   │   └── ProtocolMessage.kt       # Wire protocol JSON message format
│   └── repository/
│       └── ChatRepository.kt        # Message store + incoming message handler
├── service/
│   ├── transport/
│   │   ├── TransportLayer.kt        # Abstract transport interface
│   │   ├── TransportManager.kt      # Active transport selector + unified API
│   │   └── ProtocolSerializer.kt    # JSON serialization/deserialization
│   ├── bluetooth/
│   │   ├── BluetoothTransport.kt    # Bluetooth SPP transport implementation
│   │   └── BluetoothConnectionService.kt  # Foreground service for BT
│   ├── websocket/
│   │   └── WebSocketTransport.kt    # WebSocket (OkHttp) transport implementation
│   └── voice/
│       └── VoiceService.kt          # STT (SpeechRecognizer) + TTS service
└── ui/
    ├── MainActivity.kt              # Entry activity, permissions, Compose host
    ├── theme/
    │   ├── Color.kt                 # Color palette (BT blue, SSH green, etc.)
    │   └── Theme.kt                 # Material 3 theme with dynamic colors
    ├── navigation/
    │   └── NavGraph.kt              # Navigation routes (connection → chat → settings)
    └── screens/
        ├── connection/
        │   ├── ConnectionScreen.kt  # Transport selector, BT devices, WS config
        │   └── ConnectionViewModel.kt
        ├── chat/
        │   ├── ChatScreen.kt        # Message bubbles, streaming, voice input bar
        │   └── ChatViewModel.kt
        └── settings/
            ├── SettingsScreen.kt    # TTS speed/pitch, auto-TTS, reconnect toggles
            └── SettingsViewModel.kt
```

### Python Server
```
server/
├── server.py          # Main entry — WebSocket server + optional BT SPP server
├── protocol.py        # Protocol message classes matching Android client
├── ai_handler.py      # AI provider abstraction (OpenAI, Ollama, Mock)
├── config.py          # Configuration loader from .env
├── requirements.txt   # Python dependencies
└── .env.example       # Environment variable template
```

## Key Design Decisions

### Communication Protocol
- **Format**: JSON over newline-delimited text streams (BT) or WebSocket text frames
- **Why not Protobuf**: JSON is simpler to debug, works natively in Python, and the message sizes are small enough that protobuf overhead savings don't matter
- **Message types**: `ai_request`, `ai_response`, `ai_stream_start`, `ai_stream_chunk`, `ai_stream_end`, `ping`/`pong`, `error`

### Transport Architecture
- `TransportLayer` interface is implemented by both `BluetoothTransport` and `WebSocketTransport`
- `TransportManager` wraps the active transport and provides a unified send/receive API
- All transports use Kotlin `StateFlow` for connection state and `SharedFlow` for incoming messages

### AI Streaming
- Server streams AI responses token-by-token via `ai_stream_start` → N × `ai_stream_chunk` → `ai_stream_end`
- Android `ChatRepository` buffers streaming tokens in `streamingText` StateFlow
- UI shows a blinking cursor during streaming

### Voice Pipeline
- **STT**: Android `SpeechRecognizer` with partial results → auto-sends on final result
- **TTS**: Android `TextToSpeech` engine → auto-speaks AI responses (toggleable)
- Voice input shows a pulsing mic button and partial transcription above the input bar

## Build & Run

### Android App
1. Open in Android Studio
2. Sync Gradle
3. Run on device/emulator (API 26+)

### Python Server
```bash
cd server
pip install -r requirements.txt
cp .env.example .env   # Edit with your AI provider config
python server.py
```

### Quick Test (Mock AI)
1. Start server: `python server.py` (defaults to mock AI on port 8765)
2. In the Android app, select WebSocket, enter your computer's IP, connect
3. Type or speak a message — you'll get mock AI responses

### Real AI Setup
Edit `server/.env`:
- **OpenAI**: Set `AI_PROVIDER=openai` and `OPENAI_API_KEY=sk-...`
- **Ollama**: Set `AI_PROVIDER=ollama` (requires Ollama running locally)

## Dependencies

### Android
- Jetpack Compose + Material 3
- Hilt (DI)
- OkHttp (WebSocket)
- Gson (JSON)
- SSHJ (SSH tunneling — future)
- Room (local DB — future)

### Python
- `websockets` — WebSocket server
- `openai` — OpenAI API client
- `aiohttp` — HTTP client for Ollama
- `python-dotenv` — Config loading
- `pybluez2` — Bluetooth SPP (Linux only, optional)

## Conventions
- **Android**: MVI architecture, Kotlin coroutines + Flow, Hilt DI
- **Server**: asyncio, dataclasses, type hints
- **Protocol**: All message types defined as constants in both `MessageTypes` (Android) and `MessageTypes` (Python) — keep in sync
- **Naming**: `TransportLayer` = abstract interface, `*Transport` = concrete implementation
