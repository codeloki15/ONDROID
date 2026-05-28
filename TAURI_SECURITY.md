# OmniPin Tauri Desktop Security Implementation Plan

## Overview

Implement Noise Protocol for secure communication between Desktop (Tauri/Rust) and mobile apps (Android/iOS). This document is the counterpart to `ANDROID_SECURITY.md` — both must implement the same protocol.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      TAURI DESKTOP APP                           │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────────────┐ │
│  │ Python      │◄──►│   Tauri     │◄──►│  Noise Encryption   │ │
│  │ Server      │    │   Bridge    │    │  (snow crate)       │ │
│  │ (FastAPI)   │    │   (Rust)    │    │                     │ │
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
│                    │ App-Level │      │ OS        │    │ Derived││
│                    │ AES-256   │      │ Keychain/ │    │ From   ││
│                    │ Encryption│      │ Credential│    │ Device ││
│                    └───────────┘      │ Manager   │    │ ID     ││
│                                       └───────────┘    └────────┤│
└─────────────────────────────────────────────────────────────────┘
         │                              │
         │ Internal (localhost)         │ ws:// + Noise-encrypted
         │                              │
         ▼                              ▼
┌─────────────────┐            ┌─────────────────┐
│  Python Server  │            │  Mobile App     │
│  (AI, Files,    │            │  (Android/iOS)  │
│   Terminal)     │            │                 │
└─────────────────┘            └─────────────────┘
```

---

## Rust Crates Required

```toml
# src-tauri/Cargo.toml

[dependencies]
# Noise Protocol
snow = "0.9"

# Cryptography
aes-gcm = "0.10"
pbkdf2 = { version = "0.12", features = ["std"] }
sha2 = "0.10"
rand = "0.8"

# Key storage
keyring = "2"  # Cross-platform keychain access

# Serialization
serde = { version = "1", features = ["derive"] }
serde_json = "1"
base64 = "0.21"

# QR Code generation
qrcode = "0.13"
image = "0.24"

# mDNS
mdns-sd = "0.10"

# WebSocket server
tokio-tungstenite = "0.20"
tokio = { version = "1", features = ["full"] }

# Existing dependencies...
```

---

## Implementation Plan

### Phase 1: Crypto Foundation

#### 1.1 Key Derivation Module

```rust
// src-tauri/src/security/key_derivation.rs

use pbkdf2::pbkdf2_hmac;
use sha2::Sha256;
use rand::{RngCore, rngs::OsRng};

const PBKDF2_ITERATIONS: u32 = 100_000;
const KEY_LENGTH: usize = 32; // 256 bits
const SALT_LENGTH: usize = 32;

/// Derive AES-256 key from multiple entropy sources.
pub fn derive_key(
    device_id: &str,
    install_id: &str,
    user_pin: Option<&str>,
    salt: &[u8],
) -> [u8; KEY_LENGTH] {
    // Combine entropy sources
    let mut combined = String::new();
    combined.push_str(device_id);
    combined.push('|');
    combined.push_str(install_id);
    if let Some(pin) = user_pin {
        combined.push('|');
        combined.push_str(pin);
    }

    let mut key = [0u8; KEY_LENGTH];
    pbkdf2_hmac::<Sha256>(
        combined.as_bytes(),
        salt,
        PBKDF2_ITERATIONS,
        &mut key,
    );

    key
}

/// Generate cryptographically secure random salt.
pub fn generate_salt() -> [u8; SALT_LENGTH] {
    let mut salt = [0u8; SALT_LENGTH];
    OsRng.fill_bytes(&mut salt);
    salt
}

/// Get unique device identifier.
/// Uses machine-id on Linux, IOPlatformUUID on macOS, MachineGuid on Windows.
pub fn get_device_id() -> String {
    machine_uid::get().unwrap_or_else(|_| {
        // Fallback: generate and persist a random ID
        uuid::Uuid::new_v4().to_string()
    })
}
```

#### 1.2 App-Level Encryption

```rust
// src-tauri/src/security/encryption.rs

use aes_gcm::{
    aead::{Aead, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use rand::RngCore;

const NONCE_LENGTH: usize = 12;
const TAG_LENGTH: usize = 16;

/// Encrypt data with AES-256-GCM.
/// Returns: Nonce (12 bytes) || Ciphertext || Tag (16 bytes)
pub fn encrypt(plaintext: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, String> {
    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Invalid key: {}", e))?;

    let mut nonce_bytes = [0u8; NONCE_LENGTH];
    OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(nonce, plaintext)
        .map_err(|e| format!("Encryption failed: {}", e))?;

    // Prepend nonce to ciphertext
    let mut result = Vec::with_capacity(NONCE_LENGTH + ciphertext.len());
    result.extend_from_slice(&nonce_bytes);
    result.extend_from_slice(&ciphertext);

    Ok(result)
}

/// Decrypt AES-256-GCM encrypted data.
/// Input format: Nonce (12 bytes) || Ciphertext || Tag (16 bytes)
pub fn decrypt(encrypted: &[u8], key: &[u8; 32]) -> Result<Vec<u8>, String> {
    if encrypted.len() < NONCE_LENGTH + TAG_LENGTH {
        return Err("Encrypted data too short".to_string());
    }

    let cipher = Aes256Gcm::new_from_slice(key)
        .map_err(|e| format!("Invalid key: {}", e))?;

    let (nonce_bytes, ciphertext) = encrypted.split_at(NONCE_LENGTH);
    let nonce = Nonce::from_slice(nonce_bytes);

    cipher
        .decrypt(nonce, ciphertext)
        .map_err(|e| format!("Decryption failed: {}", e))
}
```

#### 1.3 Secure Key Storage

```rust
// src-tauri/src/security/key_storage.rs

use keyring::Entry;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

const SERVICE_NAME: &str = "com.omnipin.desktop";
const KEY_NOISE_KEYPAIR: &str = "noise_keypair";
const KEY_PAIRED_DEVICES: &str = "paired_devices";
const KEY_SALT: &str = "derivation_salt";
const KEY_INSTALL_ID: &str = "install_id";

#[derive(Serialize, Deserialize)]
struct EncryptedKeypair {
    encrypted_private: String, // Base64
    encrypted_public: String,  // Base64
}

#[derive(Serialize, Deserialize, Clone)]
pub struct PairedDevice {
    pub device_id: String,
    pub encrypted_public_key: String, // Base64
    pub display_name: String,
    pub last_connected: u64,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct DecryptedPairedDevice {
    pub device_id: String,
    pub public_key: Vec<u8>,
    pub display_name: String,
    pub last_connected: u64,
}

pub struct SecureKeyStorage {
    device_id: String,
    install_id: String,
}

impl SecureKeyStorage {
    pub fn new() -> Result<Self, String> {
        let device_id = super::key_derivation::get_device_id();

        // Get or create install ID
        let install_id = match Entry::new(SERVICE_NAME, KEY_INSTALL_ID) {
            Ok(entry) => match entry.get_password() {
                Ok(id) => id,
                Err(_) => {
                    let id = uuid::Uuid::new_v4().to_string();
                    entry.set_password(&id).map_err(|e| e.to_string())?;
                    id
                }
            },
            Err(e) => return Err(format!("Failed to access keychain: {}", e)),
        };

        Ok(Self {
            device_id,
            install_id,
        })
    }

    /// Store Noise keypair with double encryption.
    pub fn store_noise_keypair(
        &self,
        private_key: &[u8],
        public_key: &[u8],
        user_pin: Option<&str>,
    ) -> Result<(), String> {
        // Get or create salt
        let salt = self.get_or_create_salt()?;

        // Derive encryption key
        let enc_key = super::key_derivation::derive_key(
            &self.device_id,
            &self.install_id,
            user_pin,
            &salt,
        );

        // Encrypt keys
        let encrypted_private = super::encryption::encrypt(private_key, &enc_key)?;
        let encrypted_public = super::encryption::encrypt(public_key, &enc_key)?;

        // Store in keychain
        let keypair = EncryptedKeypair {
            encrypted_private: base64::encode(&encrypted_private),
            encrypted_public: base64::encode(&encrypted_public),
        };

        let json = serde_json::to_string(&keypair)
            .map_err(|e| format!("Serialization failed: {}", e))?;

        Entry::new(SERVICE_NAME, KEY_NOISE_KEYPAIR)
            .map_err(|e| format!("Keychain error: {}", e))?
            .set_password(&json)
            .map_err(|e| format!("Failed to store keypair: {}", e))?;

        // Zero out sensitive data
        // Note: Rust doesn't guarantee zeroing, but we overwrite
        let mut enc_key_copy = enc_key;
        enc_key_copy.fill(0);

        Ok(())
    }

    /// Retrieve Noise keypair.
    pub fn retrieve_noise_keypair(
        &self,
        user_pin: Option<&str>,
    ) -> Result<Option<(Vec<u8>, Vec<u8>)>, String> {
        let json = match Entry::new(SERVICE_NAME, KEY_NOISE_KEYPAIR)
            .map_err(|e| format!("Keychain error: {}", e))?
            .get_password()
        {
            Ok(json) => json,
            Err(_) => return Ok(None),
        };

        let keypair: EncryptedKeypair = serde_json::from_str(&json)
            .map_err(|e| format!("Deserialization failed: {}", e))?;

        let salt = self.get_salt()?.ok_or("Salt not found")?;

        let enc_key = super::key_derivation::derive_key(
            &self.device_id,
            &self.install_id,
            user_pin,
            &salt,
        );

        let encrypted_private = base64::decode(&keypair.encrypted_private)
            .map_err(|e| format!("Base64 decode failed: {}", e))?;
        let encrypted_public = base64::decode(&keypair.encrypted_public)
            .map_err(|e| format!("Base64 decode failed: {}", e))?;

        let private_key = super::encryption::decrypt(&encrypted_private, &enc_key)?;
        let public_key = super::encryption::decrypt(&encrypted_public, &enc_key)?;

        Ok(Some((private_key, public_key)))
    }

    /// Store paired device info.
    pub fn store_paired_device(
        &self,
        device_id: &str,
        public_key: &[u8],
        display_name: &str,
        user_pin: Option<&str>,
    ) -> Result<(), String> {
        let salt = self.get_or_create_salt()?;
        let enc_key = super::key_derivation::derive_key(
            &self.device_id,
            &self.install_id,
            user_pin,
            &salt,
        );

        let encrypted_pub_key = super::encryption::encrypt(public_key, &enc_key)?;

        let mut devices = self.get_paired_devices_raw()?;
        devices.insert(
            device_id.to_string(),
            PairedDevice {
                device_id: device_id.to_string(),
                encrypted_public_key: base64::encode(&encrypted_pub_key),
                display_name: display_name.to_string(),
                last_connected: std::time::SystemTime::now()
                    .duration_since(std::time::UNIX_EPOCH)
                    .unwrap()
                    .as_secs(),
            },
        );

        let json = serde_json::to_string(&devices)
            .map_err(|e| format!("Serialization failed: {}", e))?;

        Entry::new(SERVICE_NAME, KEY_PAIRED_DEVICES)
            .map_err(|e| format!("Keychain error: {}", e))?
            .set_password(&json)
            .map_err(|e| format!("Failed to store devices: {}", e))?;

        Ok(())
    }

    /// Get all paired devices (decrypted).
    pub fn get_paired_devices(
        &self,
        user_pin: Option<&str>,
    ) -> Result<Vec<DecryptedPairedDevice>, String> {
        let salt = match self.get_salt()? {
            Some(s) => s,
            None => return Ok(vec![]),
        };

        let enc_key = super::key_derivation::derive_key(
            &self.device_id,
            &self.install_id,
            user_pin,
            &salt,
        );

        let devices = self.get_paired_devices_raw()?;

        let mut result = Vec::new();
        for device in devices.values() {
            let encrypted = base64::decode(&device.encrypted_public_key)
                .map_err(|e| format!("Base64 decode failed: {}", e))?;

            match super::encryption::decrypt(&encrypted, &enc_key) {
                Ok(public_key) => {
                    result.push(DecryptedPairedDevice {
                        device_id: device.device_id.clone(),
                        public_key,
                        display_name: device.display_name.clone(),
                        last_connected: device.last_connected,
                    });
                }
                Err(_) => {
                    // Skip devices that fail decryption (wrong PIN)
                    continue;
                }
            }
        }

        Ok(result)
    }

    /// Remove a paired device.
    pub fn remove_paired_device(&self, device_id: &str) -> Result<(), String> {
        let mut devices = self.get_paired_devices_raw()?;
        devices.remove(device_id);

        let json = serde_json::to_string(&devices)
            .map_err(|e| format!("Serialization failed: {}", e))?;

        Entry::new(SERVICE_NAME, KEY_PAIRED_DEVICES)
            .map_err(|e| format!("Keychain error: {}", e))?
            .set_password(&json)
            .map_err(|e| format!("Failed to store devices: {}", e))?;

        Ok(())
    }

    fn get_or_create_salt(&self) -> Result<Vec<u8>, String> {
        match self.get_salt()? {
            Some(salt) => Ok(salt),
            None => {
                let salt = super::key_derivation::generate_salt();

                Entry::new(SERVICE_NAME, KEY_SALT)
                    .map_err(|e| format!("Keychain error: {}", e))?
                    .set_password(&base64::encode(&salt))
                    .map_err(|e| format!("Failed to store salt: {}", e))?;

                Ok(salt.to_vec())
            }
        }
    }

    fn get_salt(&self) -> Result<Option<Vec<u8>>, String> {
        match Entry::new(SERVICE_NAME, KEY_SALT)
            .map_err(|e| format!("Keychain error: {}", e))?
            .get_password()
        {
            Ok(encoded) => {
                let salt = base64::decode(&encoded)
                    .map_err(|e| format!("Base64 decode failed: {}", e))?;
                Ok(Some(salt))
            }
            Err(_) => Ok(None),
        }
    }

    fn get_paired_devices_raw(&self) -> Result<HashMap<String, PairedDevice>, String> {
        match Entry::new(SERVICE_NAME, KEY_PAIRED_DEVICES)
            .map_err(|e| format!("Keychain error: {}", e))?
            .get_password()
        {
            Ok(json) => serde_json::from_str(&json)
                .map_err(|e| format!("Deserialization failed: {}", e)),
            Err(_) => Ok(HashMap::new()),
        }
    }
}
```

---

### Phase 2: Noise Protocol Integration

#### 2.1 Noise Session Manager

```rust
// src-tauri/src/security/noise_session.rs

use snow::{Builder, HandshakeState, TransportState};

const NOISE_PATTERN: &str = "Noise_XX_25519_ChaChaPoly_SHA256";

pub struct NoiseSession {
    handshake: Option<HandshakeState>,
    transport: Option<TransportState>,
    is_initiator: bool,
    remote_public_key: Option<Vec<u8>>,
}

impl NoiseSession {
    /// Create a new session as responder (desktop waiting for phone).
    pub fn new_responder(local_private_key: &[u8]) -> Result<Self, String> {
        let builder = Builder::new(NOISE_PATTERN.parse().unwrap());

        let handshake = builder
            .local_private_key(local_private_key)
            .build_responder()
            .map_err(|e| format!("Failed to build responder: {}", e))?;

        Ok(Self {
            handshake: Some(handshake),
            transport: None,
            is_initiator: false,
            remote_public_key: None,
        })
    }

    /// Create a new session as initiator (for testing).
    pub fn new_initiator(local_private_key: &[u8]) -> Result<Self, String> {
        let builder = Builder::new(NOISE_PATTERN.parse().unwrap());

        let handshake = builder
            .local_private_key(local_private_key)
            .build_initiator()
            .map_err(|e| format!("Failed to build initiator: {}", e))?;

        Ok(Self {
            handshake: Some(handshake),
            transport: None,
            is_initiator: true,
            remote_public_key: None,
        })
    }

    /// Process incoming handshake message, return response if needed.
    pub fn process_handshake_message(&mut self, message: &[u8]) -> Result<Option<Vec<u8>>, String> {
        let hs = self.handshake.as_mut().ok_or("Handshake already complete")?;

        // Read incoming message
        let mut payload = vec![0u8; 65535];
        let payload_len = hs
            .read_message(message, &mut payload)
            .map_err(|e| format!("Failed to read handshake message: {}", e))?;
        payload.truncate(payload_len);

        // Check if we need to send a response
        if hs.is_handshake_finished() {
            // Extract remote public key before converting to transport
            self.remote_public_key = hs.get_remote_static().map(|k| k.to_vec());

            // Convert to transport mode
            let transport = std::mem::take(&mut self.handshake)
                .unwrap()
                .into_transport_mode()
                .map_err(|e| format!("Failed to enter transport mode: {}", e))?;
            self.transport = Some(transport);

            Ok(None)
        } else if hs.is_my_turn() {
            // Generate response
            let mut response = vec![0u8; 65535];
            let response_len = hs
                .write_message(&[], &mut response)
                .map_err(|e| format!("Failed to write handshake message: {}", e))?;
            response.truncate(response_len);

            // Check if handshake complete after writing
            if hs.is_handshake_finished() {
                self.remote_public_key = hs.get_remote_static().map(|k| k.to_vec());

                let transport = std::mem::take(&mut self.handshake)
                    .unwrap()
                    .into_transport_mode()
                    .map_err(|e| format!("Failed to enter transport mode: {}", e))?;
                self.transport = Some(transport);
            }

            Ok(Some(response))
        } else {
            // Wait for more messages
            Ok(None)
        }
    }

    /// Generate initial handshake message (for initiator).
    pub fn generate_handshake_message(&mut self) -> Result<Vec<u8>, String> {
        let hs = self.handshake.as_mut().ok_or("Handshake already complete")?;

        let mut message = vec![0u8; 65535];
        let len = hs
            .write_message(&[], &mut message)
            .map_err(|e| format!("Failed to write handshake message: {}", e))?;
        message.truncate(len);

        Ok(message)
    }

    /// Check if handshake is complete.
    pub fn is_handshake_complete(&self) -> bool {
        self.transport.is_some()
    }

    /// Get remote's static public key (available after handshake).
    pub fn get_remote_public_key(&self) -> Option<&[u8]> {
        self.remote_public_key.as_deref()
    }

    /// Encrypt message (after handshake complete).
    pub fn encrypt(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, String> {
        let transport = self.transport.as_mut().ok_or("Handshake not complete")?;

        let mut ciphertext = vec![0u8; plaintext.len() + 16]; // +16 for auth tag
        let len = transport
            .write_message(plaintext, &mut ciphertext)
            .map_err(|e| format!("Encryption failed: {}", e))?;
        ciphertext.truncate(len);

        Ok(ciphertext)
    }

    /// Decrypt message (after handshake complete).
    pub fn decrypt(&mut self, ciphertext: &[u8]) -> Result<Vec<u8>, String> {
        let transport = self.transport.as_mut().ok_or("Handshake not complete")?;

        let mut plaintext = vec![0u8; ciphertext.len()];
        let len = transport
            .read_message(ciphertext, &mut plaintext)
            .map_err(|e| format!("Decryption failed: {}", e))?;
        plaintext.truncate(len);

        Ok(plaintext)
    }
}
```

---

### Phase 3: Secure WebSocket Server

#### 3.1 Secure Transport Server

```rust
// src-tauri/src/security/secure_server.rs

use tokio::net::{TcpListener, TcpStream};
use tokio_tungstenite::{accept_async, tungstenite::Message};
use futures_util::{StreamExt, SinkExt};
use std::sync::Arc;
use tokio::sync::Mutex;

use super::noise_session::NoiseSession;
use super::key_storage::SecureKeyStorage;

pub struct SecureServer {
    key_storage: Arc<SecureKeyStorage>,
    private_key: Vec<u8>,
    public_key: Vec<u8>,
}

impl SecureServer {
    pub fn new(user_pin: Option<&str>) -> Result<Self, String> {
        let key_storage = Arc::new(SecureKeyStorage::new()?);

        // Get or generate keypair
        let (private_key, public_key) = match key_storage.retrieve_noise_keypair(user_pin)? {
            Some((priv_key, pub_key)) => (priv_key, pub_key),
            None => {
                // Generate new keypair using snow
                let builder = snow::Builder::new("Noise_XX_25519_ChaChaPoly_SHA256".parse().unwrap());
                let keypair = builder.generate_keypair().map_err(|e| e.to_string())?;

                key_storage.store_noise_keypair(&keypair.private, &keypair.public, user_pin)?;

                (keypair.private.to_vec(), keypair.public.to_vec())
            }
        };

        Ok(Self {
            key_storage,
            private_key,
            public_key,
        })
    }

    pub fn get_public_key(&self) -> &[u8] {
        &self.public_key
    }

    /// Start the secure WebSocket server.
    pub async fn start(
        self: Arc<Self>,
        host: &str,
        port: u16,
        message_handler: impl Fn(Vec<u8>) -> Vec<u8> + Send + Sync + 'static,
    ) -> Result<(), String> {
        let addr = format!("{}:{}", host, port);
        let listener = TcpListener::bind(&addr)
            .await
            .map_err(|e| format!("Failed to bind: {}", e))?;

        println!("Secure server listening on {}", addr);

        let handler = Arc::new(message_handler);

        loop {
            match listener.accept().await {
                Ok((stream, addr)) => {
                    println!("New connection from: {}", addr);
                    let server = Arc::clone(&self);
                    let handler = Arc::clone(&handler);

                    tokio::spawn(async move {
                        if let Err(e) = server.handle_connection(stream, handler).await {
                            eprintln!("Connection error: {}", e);
                        }
                    });
                }
                Err(e) => {
                    eprintln!("Accept error: {}", e);
                }
            }
        }
    }

    async fn handle_connection(
        &self,
        stream: TcpStream,
        message_handler: Arc<impl Fn(Vec<u8>) -> Vec<u8> + Send + Sync>,
    ) -> Result<(), String> {
        let ws_stream = accept_async(stream)
            .await
            .map_err(|e| format!("WebSocket handshake failed: {}", e))?;

        let (mut write, mut read) = ws_stream.split();

        // Create Noise session as responder
        let session = Arc::new(Mutex::new(
            NoiseSession::new_responder(&self.private_key)?
        ));

        // Handshake phase
        while let Some(msg) = read.next().await {
            let msg = msg.map_err(|e| format!("Read error: {}", e))?;

            if let Message::Binary(data) = msg {
                let mut session_guard = session.lock().await;

                if !session_guard.is_handshake_complete() {
                    // Process handshake
                    match session_guard.process_handshake_message(&data)? {
                        Some(response) => {
                            write
                                .send(Message::Binary(response))
                                .await
                                .map_err(|e| format!("Send error: {}", e))?;
                        }
                        None => {
                            if session_guard.is_handshake_complete() {
                                println!("Handshake complete!");

                                // Verify remote public key against known devices
                                if let Some(remote_pk) = session_guard.get_remote_public_key() {
                                    let known_devices = self.key_storage.get_paired_devices(None)?;
                                    let is_known = known_devices.iter().any(|d| d.public_key == remote_pk);

                                    if !is_known {
                                        // New device - TODO: prompt user for approval
                                        println!("New device connected, public key: {:?}", base64::encode(remote_pk));
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Decrypt and handle message
                    let plaintext = session_guard.decrypt(&data)?;

                    // Process message
                    let response = message_handler(plaintext);

                    // Encrypt and send response
                    let ciphertext = session_guard.encrypt(&response)?;
                    drop(session_guard); // Release lock before async operation

                    write
                        .send(Message::Binary(ciphertext))
                        .await
                        .map_err(|e| format!("Send error: {}", e))?;
                }
            }
        }

        Ok(())
    }
}
```

---

### Phase 4: QR Code Generation

#### 4.1 Pairing Data Structure

```rust
// src-tauri/src/security/pairing.rs

use serde::{Deserialize, Serialize};
use qrcode::QrCode;
use qrcode::render::svg;

#[derive(Serialize, Deserialize, Clone)]
pub struct PairingData {
    pub version: u32,
    pub device_id: String,
    pub device_name: String,
    pub ip: String,
    pub port: u16,
    pub public_key: String, // Base64
    pub nonce: String,      // One-time pairing nonce
    pub timestamp: u64,
}

impl PairingData {
    pub fn new(
        device_id: &str,
        device_name: &str,
        ip: &str,
        port: u16,
        public_key: &[u8],
    ) -> Self {
        use rand::Rng;

        let nonce: String = rand::thread_rng()
            .sample_iter(&rand::distributions::Alphanumeric)
            .take(32)
            .map(char::from)
            .collect();

        Self {
            version: 1,
            device_id: device_id.to_string(),
            device_name: device_name.to_string(),
            ip: ip.to_string(),
            port,
            public_key: base64::encode(public_key),
            nonce,
            timestamp: std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_secs(),
        }
    }

    pub fn to_qr_string(&self) -> Result<String, String> {
        let json = serde_json::to_string(self)
            .map_err(|e| format!("Serialization failed: {}", e))?;
        let encoded = base64::encode_config(&json, base64::URL_SAFE_NO_PAD);
        Ok(format!("omnipin://pair?data={}", encoded))
    }

    pub fn from_qr_string(qr_content: &str) -> Result<Self, String> {
        let encoded = qr_content
            .strip_prefix("omnipin://pair?data=")
            .ok_or("Invalid QR format")?;

        let json = base64::decode_config(encoded, base64::URL_SAFE_NO_PAD)
            .map_err(|e| format!("Base64 decode failed: {}", e))?;

        let json_str = String::from_utf8(json)
            .map_err(|e| format!("UTF-8 decode failed: {}", e))?;

        serde_json::from_str(&json_str)
            .map_err(|e| format!("JSON parse failed: {}", e))
    }

    pub fn is_expired(&self) -> bool {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_secs();

        // Expire after 10 minutes
        now - self.timestamp > 600
    }

    /// Generate QR code as SVG string.
    pub fn generate_qr_svg(&self) -> Result<String, String> {
        let qr_string = self.to_qr_string()?;

        let code = QrCode::new(qr_string.as_bytes())
            .map_err(|e| format!("QR generation failed: {}", e))?;

        let svg = code.render::<svg::Color>()
            .min_dimensions(200, 200)
            .max_dimensions(400, 400)
            .build();

        Ok(svg)
    }
}
```

---

### Phase 5: mDNS Broadcasting

#### 5.1 Service Broadcaster

```rust
// src-tauri/src/security/mdns.rs

use mdns_sd::{ServiceDaemon, ServiceInfo};
use std::collections::HashMap;

const SERVICE_TYPE: &str = "_omnipin._tcp.local.";

pub struct MdnsBroadcaster {
    daemon: ServiceDaemon,
    service_name: Option<String>,
}

impl MdnsBroadcaster {
    pub fn new() -> Result<Self, String> {
        let daemon = ServiceDaemon::new()
            .map_err(|e| format!("Failed to create mDNS daemon: {}", e))?;

        Ok(Self {
            daemon,
            service_name: None,
        })
    }

    /// Start broadcasting the service.
    pub fn start(&mut self, device_name: &str, port: u16) -> Result<(), String> {
        let host_name = hostname::get()
            .map(|h| h.to_string_lossy().to_string())
            .unwrap_or_else(|_| "omnipin-desktop".to_string());

        let service_name = format!("{}.{}", device_name, SERVICE_TYPE);

        let mut properties = HashMap::new();
        properties.insert("version".to_string(), "1".to_string());

        let service_info = ServiceInfo::new(
            SERVICE_TYPE,
            device_name,
            &host_name,
            (),
            port,
            Some(properties),
        )
        .map_err(|e| format!("Failed to create service info: {}", e))?;

        self.daemon
            .register(service_info)
            .map_err(|e| format!("Failed to register service: {}", e))?;

        self.service_name = Some(service_name);

        println!("mDNS: Broadcasting as {} on port {}", device_name, port);

        Ok(())
    }

    /// Stop broadcasting.
    pub fn stop(&mut self) -> Result<(), String> {
        if let Some(name) = self.service_name.take() {
            self.daemon
                .unregister(&name)
                .map_err(|e| format!("Failed to unregister service: {}", e))?;
        }
        Ok(())
    }
}

impl Drop for MdnsBroadcaster {
    fn drop(&mut self) {
        let _ = self.stop();
    }
}
```

---

### Phase 6: Tauri Commands

#### 6.1 Expose to Frontend

```rust
// src-tauri/src/security/commands.rs

use tauri::State;
use std::sync::Arc;
use tokio::sync::Mutex;

use super::key_storage::SecureKeyStorage;
use super::pairing::PairingData;
use super::mdns::MdnsBroadcaster;

pub struct SecurityState {
    pub key_storage: Arc<SecureKeyStorage>,
    pub mdns: Arc<Mutex<MdnsBroadcaster>>,
    pub current_pairing: Arc<Mutex<Option<PairingData>>>,
}

#[tauri::command]
pub async fn generate_pairing_qr(
    state: State<'_, SecurityState>,
) -> Result<String, String> {
    let key_storage = &state.key_storage;

    // Get or generate keypair
    let (_, public_key) = key_storage
        .retrieve_noise_keypair(None)?
        .ok_or("No keypair found")?;

    // Get local IP
    let ip = local_ip_address::local_ip()
        .map(|ip| ip.to_string())
        .unwrap_or_else(|_| "127.0.0.1".to_string());

    // Get device name
    let device_name = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "Desktop".to_string());

    let device_id = super::key_derivation::get_device_id();

    let pairing_data = PairingData::new(
        &device_id,
        &device_name,
        &ip,
        8766, // Default secure port
        &public_key,
    );

    // Store current pairing data for verification
    *state.current_pairing.lock().await = Some(pairing_data.clone());

    // Generate QR SVG
    pairing_data.generate_qr_svg()
}

#[tauri::command]
pub async fn get_paired_devices(
    state: State<'_, SecurityState>,
) -> Result<Vec<super::key_storage::DecryptedPairedDevice>, String> {
    state.key_storage.get_paired_devices(None)
}

#[tauri::command]
pub async fn remove_paired_device(
    device_id: String,
    state: State<'_, SecurityState>,
) -> Result<(), String> {
    state.key_storage.remove_paired_device(&device_id)
}

#[tauri::command]
pub async fn start_mdns_broadcast(
    state: State<'_, SecurityState>,
) -> Result<(), String> {
    let device_name = hostname::get()
        .map(|h| h.to_string_lossy().to_string())
        .unwrap_or_else(|_| "OmniPin Desktop".to_string());

    state.mdns.lock().await.start(&device_name, 8766)
}

#[tauri::command]
pub async fn stop_mdns_broadcast(
    state: State<'_, SecurityState>,
) -> Result<(), String> {
    state.mdns.lock().await.stop()
}
```

---

## File Structure (Final)

```
src-tauri/
├── Cargo.toml                      # Updated dependencies
├── src/
│   ├── lib.rs                      # Existing + register commands
│   └── security/
│       ├── mod.rs                  # Module exports
│       ├── key_derivation.rs       # PBKDF2 key derivation
│       ├── encryption.rs           # AES-256-GCM
│       ├── key_storage.rs          # Double-encrypted storage
│       ├── noise_session.rs        # Noise protocol handshake
│       ├── secure_server.rs        # Encrypted WebSocket server
│       ├── pairing.rs              # QR code data + generation
│       ├── mdns.rs                 # mDNS broadcaster
│       └── commands.rs             # Tauri commands
```

---

## Integration with Existing Server

The secure WebSocket server runs alongside (or replaces) the existing FastAPI server:

```
                    ┌─────────────────────────────────────┐
                    │           TAURI APP                  │
                    │                                      │
Phone ──ws://──────►│  Secure WS Server (port 8766)       │
(Noise encrypted)   │         │                            │
                    │         ▼                            │
                    │  ┌─────────────────┐                │
                    │  │ Decrypt message │                │
                    │  └────────┬────────┘                │
                    │           │                          │
                    │           ▼                          │
                    │  ┌─────────────────┐                │
                    │  │ Forward to      │──────────────► │ Python Server
                    │  │ Python Server   │                │ (localhost:8765)
                    │  └─────────────────┘                │
                    │                                      │
                    └─────────────────────────────────────┘
```

Tauri acts as a secure proxy:
1. Phone connects to Tauri's secure WebSocket (port 8766)
2. Noise handshake establishes encrypted channel
3. Tauri decrypts incoming messages
4. Tauri forwards decrypted messages to Python server (localhost only)
5. Tauri encrypts Python server responses and sends to phone

---

## Security Summary

| Layer | Protection | Algorithm |
|-------|------------|-----------|
| Transport encryption | Noise Protocol | ChaCha20-Poly1305 |
| Key exchange | Noise XX pattern | Curve25519 ECDH |
| Local key encryption | App-level | AES-256-GCM |
| Key derivation | From device+install ID | PBKDF2 (100k rounds) |
| Storage encryption | OS Keychain | Platform-specific |
| Forward secrecy | Per-session ephemeral keys | Yes |

---

## Testing Checklist

- [ ] Keypair generation and storage (macOS Keychain)
- [ ] Keypair generation and storage (Windows Credential Manager)
- [ ] Keypair generation and storage (Linux Secret Service)
- [ ] Key retrieval with correct PIN
- [ ] Key retrieval with wrong PIN (should fail)
- [ ] Noise handshake with Android client
- [ ] Message encryption/decryption
- [ ] QR code generation
- [ ] QR code contains correct data
- [ ] mDNS broadcasting starts
- [ ] mDNS broadcasting stops
- [ ] Paired device listing
- [ ] Paired device removal
- [ ] Python server message forwarding
- [ ] Full end-to-end encrypted chat

---

## Protocol Compatibility

**CRITICAL**: Both Android and Tauri implementations MUST use identical:

1. **Noise pattern**: `Noise_XX_25519_ChaChaPoly_SHA256`
2. **QR data format**: `omnipin://pair?data=<base64_json>`
3. **JSON structure**: Same field names and types
4. **Key encoding**: Base64 standard (no URL-safe variant mismatch)

Test cross-platform handshake thoroughly before deploying.
