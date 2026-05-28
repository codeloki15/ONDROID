# OmniPin Android Security Implementation Plan

## Overview

Implement Noise Protocol for secure communication between Android app and Desktop (Tauri), with locally encrypted key storage. Zero external dependencies, zero cost, maximum security.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        ANDROID APP                               │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐ │
│  │   UI Layer  │───►│  Transport  │───►│  Noise Encryption   │ │
│  │  (Compose)  │    │  (OkHttp WS)│    │  (noise-java)       │ │
│  └─────────────┘    └─────────────┘    └─────────────────────┘ │
│                                                │                 │
│                                                ▼                 │
│                                        ┌─────────────────────┐  │
│                                        │  Key Storage        │  │
│                                        │  (Double Encrypted) │  │
│                                        └─────────────────────┘  │
│                                                │                 │
│                           ┌────────────────────┼────────────────┐│
│                           ▼                    ▼                ▼│
│                    ┌───────────┐      ┌───────────┐    ┌────────┤│
│                    │ App-Level │      │ Android   │    │ Derived││
│                    │ AES-256   │      │ Keystore  │    │ From   ││
│                    │ Encryption│      │ (Hardware)│    │ PIN    ││
│                    └───────────┘      └───────────┘    └────────┤│
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ ws:// + Noise-encrypted payloads
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                     TAURI DESKTOP APP                            │
└─────────────────────────────────────────────────────────────────┘
```

---

## Security Layers

### Layer 1: Transport (WebSocket)
- Plain `ws://` connection (Noise handles encryption)
- OkHttp WebSocket client (already in use)

### Layer 2: Noise Protocol Encryption
- Every payload encrypted before sending
- ChaCha20-Poly1305 (256-bit)
- Curve25519 key exchange
- Forward secrecy per session

### Layer 3: Local Key Encryption (Double Layer)

**Problem:** Even Android Keystore can be compromised on rooted devices.

**Solution:** Encrypt keys at application level BEFORE storing in Keystore.

```
Raw Noise Private Key
        │
        ▼
┌───────────────────────────────┐
│  App-Level Encryption         │
│  AES-256-GCM                  │
│  Key derived from:            │
│  - Device-unique ID           │
│  - App installation ID        │
│  - Optional user PIN          │
│  Using PBKDF2 (100k rounds)   │
└───────────────────────────────┘
        │
        ▼
  Encrypted Blob
        │
        ▼
┌───────────────────────────────┐
│  Android Keystore             │
│  (Hardware-backed if avail)   │
│  Stores the encrypted blob    │
└───────────────────────────────┘
```

**Why double encryption?**
- Keystore breach → attacker gets encrypted blob, still needs app-level key
- App-level key is derived from device/installation specifics
- Optional PIN adds user-known factor
- Even with root access, key extraction requires significant effort

---

## Implementation Plan

### Phase 1: Crypto Foundation

#### 1.1 Add Dependencies

```kotlin
// app/build.gradle.kts

dependencies {
    // Noise Protocol
    implementation("com.southernstorm:noise-java:1.0.0")

    // For PBKDF2 and AES-GCM (Android built-in, but explicit)
    // No additional dependency needed - javax.crypto.*

    // Existing
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

#### 1.2 Create Key Derivation Module

```
app/src/main/java/com/locallink/pro/security/
├── KeyDerivation.kt        # PBKDF2 key derivation
├── AppLevelEncryption.kt   # AES-256-GCM encrypt/decrypt
├── SecureKeyStorage.kt     # Double-encrypted storage
├── NoiseSession.kt         # Noise handshake + encryption
└── DeviceIdentity.kt       # Device-unique ID generation
```

#### 1.3 Key Derivation Implementation

```kotlin
// KeyDerivation.kt

object KeyDerivation {
    private const val PBKDF2_ITERATIONS = 100_000
    private const val KEY_LENGTH_BITS = 256

    /**
     * Derive AES-256 key from multiple entropy sources.
     *
     * @param deviceId Unique device identifier (ANDROID_ID or generated)
     * @param installId App installation UUID (generated on first launch)
     * @param userPin Optional user-provided PIN (null if not set)
     * @param salt Random salt (stored alongside encrypted data)
     */
    fun deriveKey(
        deviceId: String,
        installId: String,
        userPin: String?,
        salt: ByteArray
    ): SecretKey {
        // Combine entropy sources
        val combined = buildString {
            append(deviceId)
            append("|")
            append(installId)
            if (userPin != null) {
                append("|")
                append(userPin)
            }
        }

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(
            combined.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            KEY_LENGTH_BITS
        )

        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }

    fun generateSalt(): ByteArray {
        val salt = ByteArray(32)
        SecureRandom().nextBytes(salt)
        return salt
    }
}
```

#### 1.4 App-Level Encryption

```kotlin
// AppLevelEncryption.kt

object AppLevelEncryption {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_IV_LENGTH = 12

    /**
     * Encrypt data with AES-256-GCM.
     * Returns: IV (12 bytes) + Ciphertext + Auth Tag
     */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val ciphertext = cipher.doFinal(plaintext)

        // Prepend IV to ciphertext
        return iv + ciphertext
    }

    /**
     * Decrypt AES-256-GCM encrypted data.
     * Input format: IV (12 bytes) + Ciphertext + Auth Tag
     */
    fun decrypt(encrypted: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(ALGORITHM)

        val iv = encrypted.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = encrypted.sliceArray(GCM_IV_LENGTH until encrypted.size)

        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)

        return cipher.doFinal(ciphertext)
    }
}
```

#### 1.5 Secure Key Storage

```kotlin
// SecureKeyStorage.kt

@Singleton
class SecureKeyStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "omnipin_secure_keys"
        private const val KEY_NOISE_PRIVATE = "noise_private_key"
        private const val KEY_NOISE_PUBLIC = "noise_public_key"
        private const val KEY_PAIRED_DEVICES = "paired_devices"
        private const val KEY_SALT = "derivation_salt"
        private const val KEY_INSTALL_ID = "install_id"
    }

    private val prefs: SharedPreferences by lazy {
        // Use EncryptedSharedPreferences as outer layer
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val installId: String by lazy {
        prefs.getString(KEY_INSTALL_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, id).apply()
            id
        }
    }

    private val deviceId: String by lazy {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: UUID.randomUUID().toString()
    }

    /**
     * Store Noise keypair with double encryption.
     */
    fun storeNoiseKeypair(
        privateKey: ByteArray,
        publicKey: ByteArray,
        userPin: String? = null
    ) {
        // Generate or retrieve salt
        val salt = prefs.getString(KEY_SALT, null)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        } ?: run {
            val newSalt = KeyDerivation.generateSalt()
            prefs.edit().putString(KEY_SALT, Base64.encodeToString(newSalt, Base64.NO_WRAP)).apply()
            newSalt
        }

        // Derive encryption key
        val encKey = KeyDerivation.deriveKey(deviceId, installId, userPin, salt)

        // Encrypt private key (public key doesn't need encryption but we encrypt anyway)
        val encryptedPrivate = AppLevelEncryption.encrypt(privateKey, encKey)
        val encryptedPublic = AppLevelEncryption.encrypt(publicKey, encKey)

        // Store in EncryptedSharedPreferences (second layer)
        prefs.edit()
            .putString(KEY_NOISE_PRIVATE, Base64.encodeToString(encryptedPrivate, Base64.NO_WRAP))
            .putString(KEY_NOISE_PUBLIC, Base64.encodeToString(encryptedPublic, Base64.NO_WRAP))
            .apply()

        // Zero out sensitive data
        privateKey.fill(0)
        encKey.encoded.fill(0)
    }

    /**
     * Retrieve Noise keypair.
     */
    fun retrieveNoiseKeypair(userPin: String? = null): Pair<ByteArray, ByteArray>? {
        val encryptedPrivate = prefs.getString(KEY_NOISE_PRIVATE, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val encryptedPublic = prefs.getString(KEY_NOISE_PUBLIC, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        val salt = prefs.getString(KEY_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null

        val encKey = KeyDerivation.deriveKey(deviceId, installId, userPin, salt)

        return try {
            val privateKey = AppLevelEncryption.decrypt(encryptedPrivate, encKey)
            val publicKey = AppLevelEncryption.decrypt(encryptedPublic, encKey)
            Pair(privateKey, publicKey)
        } catch (e: Exception) {
            // Decryption failed (wrong PIN or corrupted data)
            null
        } finally {
            encKey.encoded.fill(0)
        }
    }

    /**
     * Store paired device info.
     * Device info: { deviceId, publicKey, displayName, lastConnected }
     */
    fun storePairedDevice(
        deviceId: String,
        publicKey: ByteArray,
        displayName: String,
        userPin: String? = null
    ) {
        val salt = prefs.getString(KEY_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return
        val encKey = KeyDerivation.deriveKey(this.deviceId, installId, userPin, salt)

        // Encrypt the public key
        val encryptedPubKey = AppLevelEncryption.encrypt(publicKey, encKey)

        // Store as JSON in encrypted prefs
        val devices = getPairedDevicesRaw().toMutableMap()
        devices[deviceId] = PairedDevice(
            deviceId = deviceId,
            encryptedPublicKey = Base64.encodeToString(encryptedPubKey, Base64.NO_WRAP),
            displayName = displayName,
            lastConnected = System.currentTimeMillis()
        )

        prefs.edit()
            .putString(KEY_PAIRED_DEVICES, Gson().toJson(devices))
            .apply()
    }

    fun getPairedDevices(userPin: String? = null): List<DecryptedPairedDevice> {
        val salt = prefs.getString(KEY_SALT, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return emptyList()
        val encKey = KeyDerivation.deriveKey(deviceId, installId, userPin, salt)

        return getPairedDevicesRaw().values.mapNotNull { device ->
            try {
                val encryptedPubKey = Base64.decode(device.encryptedPublicKey, Base64.NO_WRAP)
                val publicKey = AppLevelEncryption.decrypt(encryptedPubKey, encKey)
                DecryptedPairedDevice(
                    deviceId = device.deviceId,
                    publicKey = publicKey,
                    displayName = device.displayName,
                    lastConnected = device.lastConnected
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun getPairedDevicesRaw(): Map<String, PairedDevice> {
        val json = prefs.getString(KEY_PAIRED_DEVICES, null) ?: return emptyMap()
        return try {
            Gson().fromJson(json, object : TypeToken<Map<String, PairedDevice>>() {}.type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    data class PairedDevice(
        val deviceId: String,
        val encryptedPublicKey: String,
        val displayName: String,
        val lastConnected: Long
    )

    data class DecryptedPairedDevice(
        val deviceId: String,
        val publicKey: ByteArray,
        val displayName: String,
        val lastConnected: Long
    )
}
```

---

### Phase 2: Noise Protocol Integration

#### 2.1 Noise Session Manager

```kotlin
// NoiseSession.kt

class NoiseSession(
    private val secureKeyStorage: SecureKeyStorage
) {
    private var handshakeState: HandshakeState? = null
    private var cipherStatePair: CipherStatePair? = null
    private var isInitiator: Boolean = false

    companion object {
        // Noise_XX: Mutual authentication, both sides learn each other's static keys
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    /**
     * Initialize as initiator (phone connecting to desktop).
     */
    fun initializeAsInitiator(
        localKeypair: Pair<ByteArray, ByteArray>, // (private, public)
        remotePublicKey: ByteArray? = null        // Known from previous pairing, or null for first time
    ) {
        isInitiator = true

        handshakeState = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR).apply {
            localKeyPair = DHState("25519").apply {
                setKeys(localKeypair.first, 0, localKeypair.second, 0)
            }

            // If we know the remote public key (re-connection), use Noise_IK for efficiency
            // For now, stick with XX for simplicity

            start()
        }
    }

    /**
     * Process handshake message from remote.
     * Returns: response message to send, or null if handshake complete.
     */
    fun processHandshakeMessage(message: ByteArray): ByteArray? {
        val hs = handshakeState ?: throw IllegalStateException("Handshake not initialized")

        return when (hs.action) {
            HandshakeState.READ_MESSAGE -> {
                val payload = ByteArray(message.size)
                val payloadLen = hs.readMessage(message, 0, message.size, payload, 0)

                if (hs.action == HandshakeState.SPLIT) {
                    // Handshake complete
                    cipherStatePair = hs.split()
                    handshakeState = null
                    null
                } else {
                    // Need to write response
                    generateHandshakeMessage()
                }
            }
            HandshakeState.WRITE_MESSAGE -> {
                generateHandshakeMessage()
            }
            else -> null
        }
    }

    /**
     * Generate next handshake message.
     */
    fun generateHandshakeMessage(): ByteArray {
        val hs = handshakeState ?: throw IllegalStateException("Handshake not initialized")

        val message = ByteArray(256) // Max handshake message size
        val messageLen = hs.writeMessage(message, 0, null, 0, 0)

        if (hs.action == HandshakeState.SPLIT) {
            cipherStatePair = hs.split()
            handshakeState = null
        }

        return message.sliceArray(0 until messageLen)
    }

    /**
     * Check if handshake is complete.
     */
    fun isHandshakeComplete(): Boolean = cipherStatePair != null

    /**
     * Get remote's static public key (available after handshake).
     */
    fun getRemotePublicKey(): ByteArray? {
        return handshakeState?.remotePublicKey?.publicKey
    }

    /**
     * Encrypt message after handshake complete.
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = if (isInitiator) {
            cipherStatePair?.sender
        } else {
            cipherStatePair?.receiver
        } ?: throw IllegalStateException("Handshake not complete")

        val ciphertext = ByteArray(plaintext.size + 16) // +16 for auth tag
        val len = cipher.encryptWithAd(null, plaintext, 0, ciphertext, 0, plaintext.size)
        return ciphertext.sliceArray(0 until len)
    }

    /**
     * Decrypt message after handshake complete.
     */
    fun decrypt(ciphertext: ByteArray): ByteArray {
        val cipher = if (isInitiator) {
            cipherStatePair?.receiver
        } else {
            cipherStatePair?.sender
        } ?: throw IllegalStateException("Handshake not complete")

        val plaintext = ByteArray(ciphertext.size)
        val len = cipher.decryptWithAd(null, ciphertext, 0, plaintext, 0, ciphertext.size)
        return plaintext.sliceArray(0 until len)
    }
}
```

#### 2.2 Secure WebSocket Transport

```kotlin
// SecureNoiseTransport.kt

@Singleton
class SecureNoiseTransport @Inject constructor(
    private val secureKeyStorage: SecureKeyStorage
) {
    private var webSocket: WebSocket? = null
    private var noiseSession: NoiseSession? = null

    private val _connectionState = MutableStateFlow<SecureConnectionState>(SecureConnectionState.Disconnected)
    val connectionState: StateFlow<SecureConnectionState> = _connectionState.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<ByteArray> = _incomingMessages.asSharedFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class SecureConnectionState {
        object Disconnected : SecureConnectionState()
        object Connecting : SecureConnectionState()
        object Handshaking : SecureConnectionState()
        object Connected : SecureConnectionState()
        data class Error(val message: String) : SecureConnectionState()
    }

    /**
     * Connect to desktop with Noise encryption.
     */
    fun connect(
        host: String,
        port: Int,
        userPin: String? = null
    ) {
        _connectionState.value = SecureConnectionState.Connecting

        // Get or generate keypair
        val keypair = secureKeyStorage.retrieveNoiseKeypair(userPin) ?: run {
            // Generate new keypair
            val dh = DHState("25519")
            dh.generateKeyPair()
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32)
            dh.getPrivateKey(privateKey, 0)
            dh.getPublicKey(publicKey, 0)
            secureKeyStorage.storeNoiseKeypair(privateKey, publicKey, userPin)
            Pair(privateKey, publicKey)
        }

        // Initialize Noise session
        noiseSession = NoiseSession(secureKeyStorage).apply {
            initializeAsInitiator(keypair)
        }

        // Connect WebSocket
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("ws://$host:$port/ws/secure")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = SecureConnectionState.Handshaking

                // Send first handshake message
                val msg = noiseSession?.generateHandshakeMessage()
                if (msg != null) {
                    webSocket.send(ByteString.of(*msg))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val session = noiseSession ?: return

                if (!session.isHandshakeComplete()) {
                    // Process handshake
                    val response = session.processHandshakeMessage(bytes.toByteArray())
                    if (response != null) {
                        webSocket.send(ByteString.of(*response))
                    }

                    if (session.isHandshakeComplete()) {
                        _connectionState.value = SecureConnectionState.Connected

                        // Store remote public key for future connections
                        session.getRemotePublicKey()?.let { remotePubKey ->
                            // TODO: Verify against known key or prompt user for new device
                        }
                    }
                } else {
                    // Decrypt and emit message
                    try {
                        val plaintext = session.decrypt(bytes.toByteArray())
                        scope.launch { _incomingMessages.emit(plaintext) }
                    } catch (e: Exception) {
                        Log.e("SecureTransport", "Decryption failed", e)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = SecureConnectionState.Error(t.message ?: "Connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = SecureConnectionState.Disconnected
            }
        })
    }

    /**
     * Send encrypted message.
     */
    fun send(plaintext: ByteArray): Boolean {
        val session = noiseSession ?: return false
        if (!session.isHandshakeComplete()) return false

        val ciphertext = session.encrypt(plaintext)
        return webSocket?.send(ByteString.of(*ciphertext)) ?: false
    }

    /**
     * Send encrypted JSON message.
     */
    fun sendJson(message: Any): Boolean {
        val json = Gson().toJson(message)
        return send(json.toByteArray(Charsets.UTF_8))
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        noiseSession = null
        _connectionState.value = SecureConnectionState.Disconnected
    }
}
```

---

### Phase 3: Pairing Flow

#### 3.1 QR Code Data Structure

```kotlin
// PairingData.kt

data class PairingData(
    val version: Int = 1,
    val deviceId: String,           // Desktop's unique ID
    val deviceName: String,         // "MacBook Pro"
    val ip: String,                 // "192.168.1.100"
    val port: Int,                  // 8766
    val publicKey: String,          // Base64-encoded Curve25519 public key
    val nonce: String,              // One-time pairing nonce (expires in 10 min)
    val timestamp: Long             // When QR was generated
) {
    fun toQrString(): String {
        // Format: omnipin://pair?data=<base64_json>
        val json = Gson().toJson(this)
        val encoded = Base64.encodeToString(json.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return "omnipin://pair?data=$encoded"
    }

    companion object {
        fun fromQrString(qrContent: String): PairingData? {
            return try {
                val encoded = qrContent.removePrefix("omnipin://pair?data=")
                val json = String(Base64.decode(encoded, Base64.URL_SAFE))
                Gson().fromJson(json, PairingData::class.java)
            } catch (e: Exception) {
                null
            }
        }

        fun isExpired(data: PairingData): Boolean {
            val tenMinutesMs = 10 * 60 * 1000
            return System.currentTimeMillis() - data.timestamp > tenMinutesMs
        }
    }
}
```

#### 3.2 Pairing Screen

```kotlin
// PairingScreen.kt

@Composable
fun PairingScreen(
    onPaired: () -> Unit,
    viewModel: PairingViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Pair with Desktop",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        when (uiState) {
            is PairingUiState.Scanning -> {
                // QR Scanner
                QrScannerView(
                    onQrScanned = { qrContent ->
                        viewModel.processQrCode(qrContent)
                    }
                )

                Text(
                    text = "Scan the QR code shown on your desktop",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            is PairingUiState.Verifying -> {
                CircularProgressIndicator()
                Text("Verifying...")
            }

            is PairingUiState.PinRequired -> {
                // Optional PIN entry for extra security
                var pin by remember { mutableStateOf("") }

                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("Enter PIN (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                )

                Button(onClick = { viewModel.confirmPairing(pin.ifBlank { null }) }) {
                    Text("Confirm Pairing")
                }
            }

            is PairingUiState.Success -> {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.Green)
                Text("Paired successfully!")
                LaunchedEffect(Unit) {
                    delay(1000)
                    onPaired()
                }
            }

            is PairingUiState.Error -> {
                Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red)
                Text((uiState as PairingUiState.Error).message)
                Button(onClick = { viewModel.retry() }) {
                    Text("Try Again")
                }
            }
        }
    }
}
```

---

### Phase 4: mDNS Discovery

#### 4.1 Service Discovery

```kotlin
// DeviceDiscovery.kt

@Singleton
class DeviceDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    data class DiscoveredDevice(
        val name: String,
        val host: String,
        val port: Int
    )

    fun startDiscovery() {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d("Discovery", "Started discovering: $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == "_omnipin._tcp.") {
                    // Resolve to get IP and port
                    nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e("Discovery", "Resolve failed: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val device = DiscoveredDevice(
                                name = serviceInfo.serviceName,
                                host = serviceInfo.host.hostAddress ?: "",
                                port = serviceInfo.port
                            )
                            _discoveredDevices.value = _discoveredDevices.value + device
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _discoveredDevices.value = _discoveredDevices.value.filter {
                    it.name != serviceInfo.serviceName
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        nsdManager?.discoverServices("_omnipin._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stopDiscovery() {
        discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        discoveryListener = null
    }
}
```

---

## File Structure (Final)

```
app/src/main/java/com/locallink/pro/
├── security/
│   ├── KeyDerivation.kt           # PBKDF2 key derivation
│   ├── AppLevelEncryption.kt      # AES-256-GCM
│   ├── SecureKeyStorage.kt        # Double-encrypted storage
│   ├── NoiseSession.kt            # Noise protocol handshake
│   ├── SecureNoiseTransport.kt    # Encrypted WebSocket
│   ├── PairingData.kt             # QR code data structure
│   └── DeviceDiscovery.kt         # mDNS scanner
├── ui/screens/pairing/
│   ├── PairingScreen.kt           # QR scanner UI
│   └── PairingViewModel.kt        # Pairing logic
└── ... (existing files)
```

---

## Security Summary

| Layer | Protection | Algorithm |
|-------|------------|-----------|
| Transport encryption | Noise Protocol | ChaCha20-Poly1305 |
| Key exchange | Noise XX pattern | Curve25519 ECDH |
| Local key encryption | App-level | AES-256-GCM |
| Key derivation | From device+install+PIN | PBKDF2 (100k rounds) |
| Storage encryption | Android | EncryptedSharedPreferences |
| Forward secrecy | Per-session ephemeral keys | Yes |

---

## Testing Checklist

- [ ] Key generation and storage
- [ ] Key retrieval with correct PIN
- [ ] Key retrieval with wrong PIN (should fail)
- [ ] Noise handshake completion
- [ ] Message encryption/decryption
- [ ] QR code parsing
- [ ] mDNS discovery on same network
- [ ] Pairing flow end-to-end
- [ ] Re-connection with stored keys
- [ ] Device unpairing
