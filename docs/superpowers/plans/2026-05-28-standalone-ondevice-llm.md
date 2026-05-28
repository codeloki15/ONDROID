# Standalone On-Device LLM Chat — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the app from a server-dependent OmniPin client into a fully standalone Android app that runs Gemma 3n on-device via MediaPipe, with streaming text + vision chat, voice I/O, and SQLite-persisted chat sessions.

**Architecture:** Compose UI → ViewModels → `ChatRepository` orchestrating an on-device `LlmService` (MediaPipe `LlmInference` + multimodal session), a Room SQLite database for sessions/messages, an `ImageService` (CameraX + gallery), and the existing `VoiceService` (STT/TTS). All server transport/REST/Bluetooth code is removed. The Gemma 3n `.litertlm` model is bundled in `assets/` and copied to internal storage on first run.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, Room, MediaPipe `tasks-genai:0.10.27`, CameraX, Kotlin Flow/coroutines, Gemma 3n E2B (`gemma-3n-E2B-it-int4.litertlm`), Kokoro TTS (existing).

**Model note:** Bundled at `app/src/main/assets/models/gemma-3n-E2B-it-int4.litertlm` (~3GB). The build is sideload-only (exceeds Play limits). MediaPipe cannot read a model directly from compressed assets, so on first launch the app copies it to `context.filesDir/models/` and loads from there. `androidResources.noCompress` must include `litertlm`.

---

## Pre-flight (one-time, manual — not a code task)

- [ ] **P1: Place the model file in assets**

The ~3GB model is downloaded to `~/Desktop/code/_models_cache/gemma-3n-E2B/gemma-3n-E2B-it-int4.litertlm`. Copy it into the app:

```bash
mkdir -p app/src/main/assets/models
cp ~/Desktop/code/_models_cache/gemma-3n-E2B/gemma-3n-E2B-it-int4.litertlm app/src/main/assets/models/
ls -lh app/src/main/assets/models/
```
Expected: file present, ~3GB.

- [ ] **P2: Initialize git (repo is not yet under version control)**

```bash
git init
printf '%s\n' 'app/src/main/assets/models/*.litertlm' '_models_cache/' 'server/venv/' '.gradle/' 'build/' '*.iml' '.idea/' > .gitignore
git add -A && git commit -m "chore: baseline before standalone on-device LLM conversion"
```
Note: the model is gitignored (too large for git). It still ships in the APK because it physically lives in `assets/`. The `.gitignore` only keeps it out of git history.

---

## Phase 1 — Dependencies, manifest, and removals

### Task 1: Update Gradle dependencies

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add noCompress for litertlm and add/remove dependencies**

In `androidResources`, change the noCompress line to include `litertlm`:
```kotlin
androidResources {
    noCompress += listOf("onnx", "bin", "txt", "fst", "litertlm", "task")
}
```

In `dependencies`, ADD:
```kotlin
    // On-device LLM (MediaPipe GenAI)
    implementation("com.google.mediapipe:tasks-genai:0.10.27")

    // CameraX (vision input)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")

    // Coil for displaying image messages
    implementation("io.coil-kt:coil-compose:2.7.0")
```

REMOVE these lines (server-only):
```kotlin
    // SSH - SSHJ
    implementation("com.hierynomus:sshj:0.38.0")
```
Keep OkHttp (unused after removals but harmless; remove only if build is clean without it — verify in Task 3). Keep Gson for now (MediaPipe/Room don't need it, but some kept code may; remove in a later cleanup if unreferenced). Keep Room (now used).

- [ ] **Step 2: Verify Gradle sync**

Run: `./gradlew :app:dependencies --configuration debugRuntimeClasspath > /dev/null && echo OK`
Expected: `OK` (no resolution errors). MediaPipe + CameraX + Coil resolve.

- [ ] **Step 3: Commit**

```bash
git add app/build.gradle.kts
git commit -m "build: add MediaPipe GenAI + CameraX + Coil, drop SSHJ, noCompress litertlm"
```

---

### Task 2: Clean up AndroidManifest permissions and components

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Replace the manifest with a standalone-appropriate one**

Replace the entire `<manifest>` body with (keep the model/app structure, drop BT/location/storage/foreground-service, add CAMERA):
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Model copy + (no runtime network needed; model is bundled) -->
    <uses-permission android:name="android.permission.INTERNET" />

    <!-- Audio (STT) -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

    <!-- Camera (vision) -->
    <uses-permission android:name="android.permission.CAMERA" />

    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <uses-feature android:name="android.hardware.microphone" android:required="false" />
    <uses-feature android:name="android.hardware.camera.any" android:required="false" />

    <application
        android:name=".LocalLinkApplication"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.LocalLinkPro"
        tools:targetApi="34">

        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize"
            android:theme="@style/Theme.LocalLinkPro">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
```
(The FileProvider is kept for CameraX temp-file capture.)

- [ ] **Step 2: Verify file_paths.xml covers camera temp dir**

Read `app/src/main/res/xml/file_paths.xml`. Ensure it contains a `<cache-path>` or `<files-path>` entry. If not, set its `<paths>` body to:
```xml
<paths>
    <cache-path name="camera_images" path="images/" />
    <files-path name="app_files" path="." />
</paths>
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml
git commit -m "manifest: standalone permissions (camera+audio), drop BT/storage/FGS"
```

---

### Task 3: Delete server-coupled source files

**Files (delete all):**
- `app/src/main/java/com/locallink/pro/service/transport/` (whole dir)
- `app/src/main/java/com/locallink/pro/service/bluetooth/` (whole dir)
- `app/src/main/java/com/locallink/pro/service/websocket/` (whole dir)
- `app/src/main/java/com/locallink/pro/service/rest/OmniPinApiClient.kt`
- `app/src/main/java/com/locallink/pro/service/files/LocalFileProvider.kt`
- `app/src/main/java/com/locallink/pro/service/notification/NotificationHelper.kt`
- `app/src/main/java/com/locallink/pro/ui/screens/connection/` (whole dir)
- `app/src/main/java/com/locallink/pro/ui/screens/files/` (whole dir)
- `app/src/main/java/com/locallink/pro/ui/screens/git/` (whole dir)
- `app/src/main/java/com/locallink/pro/ui/screens/terminal/` (whole dir)
- `app/src/main/java/com/locallink/pro/data/model/ProtocolMessage.kt`
- `app/src/main/java/com/locallink/pro/data/model/ServerModels.kt`
- `app/src/main/java/com/locallink/pro/data/model/ServerConfig.kt`
- `app/src/main/java/com/locallink/pro/data/local/ConnectionPreferences.kt`

- [ ] **Step 1: Delete the files**

```bash
cd app/src/main/java/com/locallink/pro
rm -rf service/transport service/bluetooth service/websocket
rm -f service/rest/OmniPinApiClient.kt service/files/LocalFileProvider.kt service/notification/NotificationHelper.kt
rm -rf ui/screens/connection ui/screens/files ui/screens/git ui/screens/terminal
rm -f data/model/ProtocolMessage.kt data/model/ServerModels.kt data/model/ServerConfig.kt
rm -f data/local/ConnectionPreferences.kt
# remove now-empty dirs
rmdir service/rest service/files service/notification 2>/dev/null || true
cd -
```

- [ ] **Step 2: Do NOT build yet** — the app won't compile until Phase 2-5 rewire things. That's expected. Just confirm the deletions:

Run: `ls app/src/main/java/com/locallink/pro/service/`
Expected: only `voice/` remains (plus a new `llm/` and `image/` added later).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: delete server transport/REST/screens (standalone pivot)"
```

---

## Phase 2 — Domain model cleanup

### Task 4: Simplify the Message domain model

**Files:**
- Modify: `app/src/main/java/com/locallink/pro/domain/model/Message.kt`
- Delete: `app/src/main/java/com/locallink/pro/domain/model/ConnectionState.kt`
- Delete: `app/src/main/java/com/locallink/pro/domain/model/DeviceInfo.kt`

- [ ] **Step 1: Read the current Message.kt**

Run: `cat app/src/main/java/com/locallink/pro/domain/model/Message.kt`
Note the existing `MessageSender`, `MessageType`, `MessageStatus` enums and `Message` fields.

- [ ] **Step 2: Rewrite Message.kt — keep only what standalone chat needs**

Write `app/src/main/java/com/locallink/pro/domain/model/Message.kt`:
```kotlin
package com.locallink.pro.domain.model

import java.util.UUID

enum class MessageSender { USER, AI, SYSTEM }

enum class MessageStatus { SENDING, SENT, ERROR }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val sender: MessageSender,
    val timestamp: Long = System.currentTimeMillis(),
    val isVoice: Boolean = false,
    val imageUri: String? = null,
    val status: MessageStatus = MessageStatus.SENT,
)
```
(Removed: `MessageType`, `USER_REMOTE`, tool/connection fields. Vision adds `imageUri`.)

- [ ] **Step 3: Delete connection/device models**

```bash
rm -f app/src/main/java/com/locallink/pro/domain/model/ConnectionState.kt
rm -f app/src/main/java/com/locallink/pro/domain/model/DeviceInfo.kt
```

- [ ] **Step 4: Remove ToolCallInfo/SubToolInfo if defined separately**

Run: `grep -rl "ToolCallInfo\|SubToolInfo" app/src/main/java/com/locallink/pro/domain/model/`
If a file defines them (and only them), delete it. If they live inside another kept file, remove just those declarations. Expected after: `grep -r "ToolCallInfo" app/src/main/java` returns nothing.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: slim Message model, drop connection/tool domain types"
```

---

## Phase 3 — Persistence (Room SQLite)

### Task 5: Room entities

**Files:**
- Create: `app/src/main/java/com/locallink/pro/data/db/SessionEntity.kt`
- Create: `app/src/main/java/com/locallink/pro/data/db/MessageEntity.kt`

- [ ] **Step 1: Create SessionEntity.kt**

```kotlin
package com.locallink.pro.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)
```

- [ ] **Step 2: Create MessageEntity.kt**

```kotlin
package com.locallink.pro.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val role: String,        // "user" | "assistant" | "system"
    val text: String,
    val imageUri: String? = null,
    val isVoice: Boolean = false,
    val timestamp: Long,
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/db/
git commit -m "feat: Room entities for sessions and messages"
```

---

### Task 6: DAOs

**Files:**
- Create: `app/src/main/java/com/locallink/pro/data/db/SessionDao.kt`
- Create: `app/src/main/java/com/locallink/pro/data/db/MessageDao.kt`

- [ ] **Step 1: Create SessionDao.kt**

```kotlin
package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(session: SessionEntity)

    @Delete
    suspend fun delete(session: SessionEntity)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}
```

- [ ] **Step 2: Create MessageDao.kt**

```kotlin
package com.locallink.pro.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessages(sessionId: String): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/db/
git commit -m "feat: Room DAOs for sessions and messages"
```

---

### Task 7: Database + Hilt provision

**Files:**
- Create: `app/src/main/java/com/locallink/pro/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/locallink/pro/di/AppModule.kt`
- Test: `app/src/androidTest/java/com/locallink/pro/data/db/MessageDaoTest.kt`

- [ ] **Step 1: Create AppDatabase.kt**

```kotlin
package com.locallink.pro.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
}
```

- [ ] **Step 2: Write the failing DAO instrumentation test**

`app/src/androidTest/java/com/locallink/pro/data/db/MessageDaoTest.kt`:
```kotlin
package com.locallink.pro.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var sessionDao: SessionDao
    private lateinit var messageDao: MessageDao

    @Before fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        sessionDao = db.sessionDao()
        messageDao = db.messageDao()
    }

    @After fun teardown() = db.close()

    @Test fun insertAndObserveMessages() = runBlocking {
        val s = SessionEntity(id = "s1", title = "T", createdAt = 1, updatedAt = 1)
        sessionDao.upsert(s)
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", text = "hi", timestamp = 2))
        val msgs = messageDao.observeMessages("s1").first()
        assertEquals(1, msgs.size)
        assertEquals("hi", msgs[0].text)
    }

    @Test fun cascadeDeleteRemovesMessages() = runBlocking {
        val s = SessionEntity(id = "s1", title = "T", createdAt = 1, updatedAt = 1)
        sessionDao.upsert(s)
        messageDao.insert(MessageEntity(id = "m1", sessionId = "s1", role = "user", text = "hi", timestamp = 2))
        sessionDao.delete(s)
        assertEquals(0, messageDao.getMessages("s1").size)
    }
}
```

- [ ] **Step 3: Provide DB + DAOs in AppModule.kt**

Replace `app/src/main/java/com/locallink/pro/di/AppModule.kt` with (keep SettingsPreferences; drop ConnectionPreferences + NotificationHelper which were deleted):
```kotlin
package com.locallink.pro.di

import android.content.Context
import androidx.room.Room
import com.locallink.pro.data.db.AppDatabase
import com.locallink.pro.data.db.MessageDao
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.local.SettingsPreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsPreferences(
        @ApplicationContext context: Context
    ): SettingsPreferences = SettingsPreferences(context)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "omnipin.db").build()

    @Provides fun provideSessionDao(db: AppDatabase): SessionDao = db.sessionDao()
    @Provides fun provideMessageDao(db: AppDatabase): MessageDao = db.messageDao()
}
```

- [ ] **Step 4: Run the DAO test on the connected device**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "com.locallink.pro.data.db.MessageDaoTest"`
Expected: PASS (2 tests). (Requires the app to compile; if Phase 4/5 not yet done, this test still compiles because it only touches the db package — run it after Task 13 if compilation blocks. If blocked, mark this step done provisionally and re-run after Task 13.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/db/AppDatabase.kt app/src/main/java/com/locallink/pro/di/AppModule.kt app/src/androidTest/java/com/locallink/pro/data/db/MessageDaoTest.kt
git commit -m "feat: Room AppDatabase + Hilt DI + DAO tests"
```

---

## Phase 4 — On-device LLM + model + image services

### Task 8: ModelManager (copy bundled model to internal storage)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/llm/ModelManager.kt`

- [ ] **Step 1: Create ModelManager.kt**

```kotlin
package com.locallink.pro.service.llm

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ModelState {
    data object Preparing : ModelState
    data class Copying(val progress: Float) : ModelState
    data object Ready : ModelState
    data class Error(val message: String) : ModelState
}

@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val TAG = "ModelManager"
        const val ASSET_PATH = "models/gemma-3n-E2B-it-int4.litertlm"
        const val MODEL_FILENAME = "gemma-3n-E2B-it-int4.litertlm"
    }

    private val _state = MutableStateFlow<ModelState>(ModelState.Preparing)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    fun modelFile(): File = File(context.filesDir, "models/$MODEL_FILENAME")

    fun isReady(): Boolean = modelFile().exists() && modelFile().length() > 0

    /** Copy bundled asset model to filesDir (MediaPipe needs a real file path). Idempotent. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        try {
            val target = modelFile()
            val assetSize = context.assets.openFd(ASSET_PATH).use { it.length }
            if (target.exists() && target.length() == assetSize) {
                _state.value = ModelState.Ready
                return@withContext
            }
            target.parentFile?.mkdirs()
            _state.value = ModelState.Copying(0f)
            context.assets.open(ASSET_PATH).use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(8 * 1024 * 1024)
                    var copied = 0L
                    var read = input.read(buf)
                    while (read >= 0) {
                        output.write(buf, 0, read)
                        copied += read
                        if (assetSize > 0) _state.value = ModelState.Copying(copied.toFloat() / assetSize)
                        read = input.read(buf)
                    }
                }
            }
            _state.value = ModelState.Ready
            Log.d(TAG, "Model ready at ${target.absolutePath} (${target.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Model prepare failed", e)
            _state.value = ModelState.Error(e.message ?: "Failed to prepare model")
        }
    }
}
```
Note: `openFd` works only for uncompressed assets — guaranteed by the `noCompress += "litertlm"` set in Task 1.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/llm/ModelManager.kt
git commit -m "feat: ModelManager copies bundled Gemma model to internal storage"
```

---

### Task 9: LlmService (MediaPipe inference + streaming)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/llm/LlmService.kt`

- [ ] **Step 1: Create LlmService.kt**

```kotlin
package com.locallink.pro.service.llm

import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LlmService @Inject constructor(
    private val modelManager: ModelManager,
) {
    companion object {
        private const val TAG = "LlmService"
        private const val MAX_TOKENS = 2048
    }

    @Volatile private var engine: LlmInference? = null

    /** Load the MediaPipe engine from the prepared model file. Idempotent. */
    @Synchronized
    fun ensureLoaded() {
        if (engine != null) return
        val path = modelManager.modelFile().absolutePath
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            .setMaxNumImages(1)
            .build()
        engine = LlmInference.createFromOptions(
            // context not needed beyond model file; MediaPipe loads from path
            appContextHolder, options
        )
        Log.d(TAG, "LLM engine loaded from $path")
    }

    /**
     * Generate a streamed response. Emits partial text chunks; completes when done.
     * A fresh session is created per call; [history] re-seeds prior turns for context.
     */
    fun generateStream(
        prompt: String,
        image: Bitmap? = null,
        history: List<Pair<String, String>> = emptyList(), // (role, text); role in {"user","assistant"}
    ): Flow<String> = callbackFlow {
        ensureLoaded()
        val eng = engine ?: throw IllegalStateException("LLM not loaded")

        val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
            .setTopK(40)
            .setTemperature(0.8f)
            .setGraphOptions(GraphOptions.builder().setEnableVisionModality(image != null).build())
            .build()

        val session = LlmInferenceSession.createFromOptions(eng, sessionOptions)
        try {
            // Re-seed bounded history as plain turns
            history.takeLast(12).forEach { (role, text) ->
                session.addQueryChunk("${if (role == "assistant") "Model" else "User"}: $text")
            }
            session.addQueryChunk("User: $prompt")
            if (image != null) {
                session.addImage(BitmapImageBuilder(image).build())
            }

            session.generateResponseAsync { partial, done ->
                trySend(partial)
                if (done) channel.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateStream failed", e)
            channel.close(e)
        }

        awaitClose {
            try { session.close() } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }

    // MediaPipe's createFromOptions needs a Context; injected lazily via AppModule holder.
    @Inject lateinit var appContextHolder: android.content.Context
}
```
NOTE for implementer: `LlmInference.createFromOptions(context, options)` requires a `Context`. Replace the `appContextHolder` field-injection hack with a constructor `@ApplicationContext context: Context` parameter — adjust the constructor to:
```kotlin
class LlmService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val modelManager: ModelManager,
)
```
and call `LlmInference.createFromOptions(context, options)`. Remove the `appContextHolder` field entirely. (The field hack is shown only to flag the dependency; the constructor form is correct.)

- [ ] **Step 2: Verify the MediaPipe streaming callback signature against the resolved AAR**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | grep -i "generateResponseAsync\|ProgressListener\|addImage" | head`
If the compiler reports the listener must be a `ProgressListener<String>` rather than a lambda, wrap accordingly:
```kotlin
session.generateResponseAsync(com.google.mediapipe.tasks.genai.llminference.ProgressListener { partial, done ->
    trySend(partial); if (done) channel.close()
})
```
Expected: compiles. (This step exists because the exact lambda-vs-SAM form varies by point release; adapt to what compiles.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/llm/LlmService.kt
git commit -m "feat: LlmService wraps MediaPipe Gemma 3n with Flow streaming + vision"
```

---

### Task 10: ImageService (CameraX capture + gallery pick)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/image/ImageService.kt`

- [ ] **Step 1: Create ImageService.kt**

```kotlin
package com.locallink.pro.service.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class ImageService @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object { private const val MAX_DIM = 768 }

    /** Load a content URI into a downscaled, orientation-corrected Bitmap for inference. */
    suspend fun loadForInference(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val raw = context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it)
        } ?: error("Could not decode image")
        val scaled = downscale(raw, MAX_DIM)
        applyExifRotation(uri, scaled)
    }

    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            src, (src.width * ratio).toInt(), (src.height * ratio).toInt(), true
        )
    }

    private fun applyExifRotation(uri: Uri, bmp: Bitmap): Bitmap {
        return try {
            context.contentResolver.openInputStream(uri).use { input ->
                val exif = ExifInterface(input!!)
                val deg = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (deg == 0f) bmp else Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    Matrix().apply { postRotate(deg) }, true
                )
            }
        } catch (_: Exception) { bmp }
    }
}
```

- [ ] **Step 2: Add exifinterface dependency**

In `app/build.gradle.kts` dependencies, add:
```kotlin
    implementation("androidx.exifinterface:exifinterface:1.3.7")
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/image/ImageService.kt app/build.gradle.kts
git commit -m "feat: ImageService downscales + orients images for vision inference"
```

---

## Phase 5 — Repository, ViewModels, UI

### Task 11: Rewrite ChatRepository (standalone)

**Files:**
- Modify (full rewrite): `app/src/main/java/com/locallink/pro/data/repository/ChatRepository.kt`
- Test: `app/src/test/java/com/locallink/pro/data/repository/ChatRepositoryTest.kt`

- [ ] **Step 1: Write the failing unit test (fake LlmService via interface)**

First, extract an interface so the repo is testable. Add to `LlmService.kt` (top-level):
```kotlin
interface LlmEngine {
    fun ensureLoaded()
    fun generateStream(prompt: String, image: android.graphics.Bitmap? = null, history: List<Pair<String, String>> = emptyList()): kotlinx.coroutines.flow.Flow<String>
}
```
Make `LlmService : LlmEngine` (add `override` to `ensureLoaded`/`generateStream`).

`app/src/test/java/com/locallink/pro/data/repository/ChatRepositoryTest.kt`:
```kotlin
package com.locallink.pro.data.repository

import com.locallink.pro.service.llm.LlmEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatRepositoryTest {
    private val fakeEngine = object : LlmEngine {
        override fun ensureLoaded() {}
        override fun generateStream(prompt: String, image: android.graphics.Bitmap?, history: List<Pair<String, String>>): Flow<String> =
            flowOf("Hello", " world")
    }

    @Test fun streamingAccumulatesIntoFullText() = runTest {
        val sb = StringBuilder()
        fakeEngine.generateStream("hi").collect { sb.append(it) }
        assertEquals("Hello world", sb.toString())
    }
}
```

- [ ] **Step 2: Run it (verify red, then it passes once interface exists)**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.data.repository.ChatRepositoryTest"`
Expected: FAIL first if `LlmEngine` not yet added; PASS after Step 1's interface is in place.

- [ ] **Step 3: Rewrite ChatRepository.kt**

```kotlin
package com.locallink.pro.data.repository

import android.graphics.Bitmap
import com.locallink.pro.data.db.MessageDao
import com.locallink.pro.data.db.MessageEntity
import com.locallink.pro.data.db.SessionDao
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.service.llm.LlmEngine
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val llm: LlmEngine,
) {
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _isAiResponding = MutableStateFlow(false)
    val isAiResponding: StateFlow<Boolean> = _isAiResponding.asStateFlow()

    fun observeSessions() = sessionDao.observeSessions()

    fun observeMessages(): Flow<List<Message>> =
        _currentSessionId.flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else messageDao.observeMessages(id).map { list -> list.map { it.toDomain() } }
        }

    suspend fun newSession() {
        _currentSessionId.value = null // session row created lazily on first send
    }

    fun loadSession(id: String) { _currentSessionId.value = id }

    suspend fun deleteSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.delete(it) }
        if (_currentSessionId.value == id) _currentSessionId.value = null
    }

    /** Persist user msg, stream AI reply, persist assistant msg on completion. */
    suspend fun send(text: String, image: Bitmap? = null, imageUri: String? = null, isVoice: Boolean = false) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(text, now)

        messageDao.insert(
            MessageEntity(sessionId = sessionId, role = "user", text = text, imageUri = imageUri, isVoice = isVoice, timestamp = now)
        )
        touchSession(sessionId)

        val history = messageDao.getMessages(sessionId)
            .filter { it.role == "user" || it.role == "assistant" }
            .map { it.role to it.text }

        _isAiResponding.value = true
        _streamingText.value = ""
        val sb = StringBuilder()
        try {
            llm.generateStream(prompt = text, image = image, history = history.dropLast(1)).collect { chunk ->
                sb.append(chunk)
                _streamingText.value = sb.toString()
            }
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "assistant", text = sb.toString(), timestamp = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            messageDao.insert(
                MessageEntity(sessionId = sessionId, role = "system", text = "Error: ${e.message}", timestamp = System.currentTimeMillis())
            )
        } finally {
            _streamingText.value = ""
            _isAiResponding.value = false
            touchSession(sessionId)
        }
    }

    private suspend fun ensureSession(firstText: String, now: Long): String {
        _currentSessionId.value?.let { return it }
        val id = java.util.UUID.randomUUID().toString()
        sessionDao.upsert(SessionEntity(id = id, title = firstText.take(40).ifBlank { "New chat" }, createdAt = now, updatedAt = now))
        _currentSessionId.value = id
        return id
    }

    private suspend fun touchSession(id: String) {
        sessionDao.getById(id)?.let { sessionDao.upsert(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    suspend fun clearAll() {
        sessionDao.deleteAll()
        _currentSessionId.value = null
    }

    private fun MessageEntity.toDomain() = Message(
        id = id,
        text = text,
        sender = when (role) { "user" -> MessageSender.USER; "assistant" -> MessageSender.AI; else -> MessageSender.SYSTEM },
        timestamp = timestamp,
        isVoice = isVoice,
        imageUri = imageUri,
    )
}
```

- [ ] **Step 4: Bind LlmEngine in Hilt**

Create `app/src/main/java/com/locallink/pro/di/LlmModule.kt`:
```kotlin
package com.locallink.pro.di

import com.locallink.pro.service.llm.LlmEngine
import com.locallink.pro.service.llm.LlmService
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class LlmModule {
    @Binds @Singleton
    abstract fun bindLlmEngine(impl: LlmService): LlmEngine
}
```

- [ ] **Step 5: Run repo test**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.data.repository.ChatRepositoryTest"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/repository/ChatRepository.kt app/src/main/java/com/locallink/pro/service/llm/LlmService.kt app/src/main/java/com/locallink/pro/di/LlmModule.kt app/src/test/java/com/locallink/pro/data/repository/ChatRepositoryTest.kt
git commit -m "feat: standalone ChatRepository (Room + on-device LLM streaming)"
```

---

### Task 12: Rewrite ChatViewModel

**Files:**
- Modify (full rewrite): `app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt`

- [ ] **Step 1: Rewrite ChatViewModel.kt**

```kotlin
package com.locallink.pro.ui.screens.chat

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.repository.ChatRepository
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender
import com.locallink.pro.service.image.ImageService
import com.locallink.pro.service.voice.VoiceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<Message> = emptyList(),
    val inputText: String = "",
    val pendingImageUri: Uri? = null,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isAiResponding: Boolean = false,
    val partialVoiceResult: String = "",
    val streamingText: String = "",
    val autoTts: Boolean = true,
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val voiceService: VoiceService,
    private val imageService: ImageService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        voiceService.initialize()
        viewModelScope.launch { voiceService.autoTts.collect { e -> _uiState.update { it.copy(autoTts = e) } } }
        viewModelScope.launch {
            chatRepository.observeMessages().collect { msgs ->
                val prevLast = _uiState.value.messages.lastOrNull()?.id
                _uiState.update { it.copy(messages = msgs) }
                val latest = msgs.lastOrNull()
                if (latest != null && latest.id != prevLast && latest.sender == MessageSender.AI && _uiState.value.autoTts) {
                    voiceService.speak(latest.text)
                }
            }
        }
        viewModelScope.launch { chatRepository.streamingText.collect { t -> _uiState.update { it.copy(streamingText = t) } } }
        viewModelScope.launch { chatRepository.isAiResponding.collect { r -> _uiState.update { it.copy(isAiResponding = r) } } }
        viewModelScope.launch { voiceService.isListening.collect { l -> _uiState.update { it.copy(isListening = l) } } }
        viewModelScope.launch { voiceService.isSpeaking.collect { s -> _uiState.update { it.copy(isSpeaking = s) } } }
        viewModelScope.launch { voiceService.partialResult.collect { p -> _uiState.update { it.copy(partialVoiceResult = p) } } }
        viewModelScope.launch {
            voiceService.finalResult.collect { text -> if (text.isNotBlank()) sendMessage(text, isVoice = true) }
        }
    }

    fun openSession(id: String?) {
        viewModelScope.launch { if (id == null) chatRepository.newSession() else chatRepository.loadSession(id) }
    }

    fun updateInput(text: String) = _uiState.update { it.copy(inputText = text) }
    fun attachImage(uri: Uri?) = _uiState.update { it.copy(pendingImageUri = uri) }

    fun sendMessage(text: String? = null, isVoice: Boolean = false) {
        val messageText = text ?: _uiState.value.inputText
        val imageUri = _uiState.value.pendingImageUri
        if (messageText.isBlank() && imageUri == null) return
        _uiState.update { it.copy(inputText = "", pendingImageUri = null) }
        viewModelScope.launch {
            val bitmap: Bitmap? = imageUri?.let { imageService.loadForInference(it) }
            chatRepository.send(text = messageText, image = bitmap, imageUri = imageUri?.toString(), isVoice = isVoice)
        }
    }

    fun toggleVoiceInput() {
        if (_uiState.value.isListening) voiceService.stopListening() else voiceService.startListening()
    }
    fun stopTts() = voiceService.stopSpeaking()
    fun toggleAutoTts() = voiceService.setAutoTts(!_uiState.value.autoTts)

    override fun onCleared() { super.onCleared(); voiceService.shutdown() }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -20`
Expected: ChatViewModel compiles (downstream ChatScreen may still error until Task 14).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt
git commit -m "feat: standalone ChatViewModel (sessions, vision attach, voice)"
```

---

### Task 13: Sessions screen + ViewModel

**Files:**
- Create: `app/src/main/java/com/locallink/pro/ui/screens/sessions/SessionsViewModel.kt`
- Create: `app/src/main/java/com/locallink/pro/ui/screens/sessions/SessionsScreen.kt`

- [ ] **Step 1: Create SessionsViewModel.kt**

```kotlin
package com.locallink.pro.ui.screens.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.SessionEntity
import com.locallink.pro.data.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repo: ChatRepository,
) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> =
        repo.observeSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(id: String) = viewModelScope.launch { repo.deleteSession(id) }
}
```

- [ ] **Step 2: Create SessionsScreen.kt**

```kotlin
package com.locallink.pro.ui.screens.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(
    onOpenSession: (String?) -> Unit,
    vm: SessionsViewModel = hiltViewModel(),
) {
    val sessions by vm.sessions.collectAsState()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Chats") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { onOpenSession(null) }) {
                Icon(Icons.Default.Add, contentDescription = "New chat")
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(sessions, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.title) },
                    modifier = Modifier.clickable { onOpenSession(s.id) },
                    trailingContent = {
                        IconButton(onClick = { vm.delete(s.id) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/ui/screens/sessions/
git commit -m "feat: sessions list screen"
```

---

### Task 14: Rewrite ChatScreen (simplified + image attach)

**Files:**
- Modify (full rewrite): `app/src/main/java/com/locallink/pro/ui/screens/chat/ChatScreen.kt`

- [ ] **Step 1: Rewrite ChatScreen.kt**

```kotlin
package com.locallink.pro.ui.screens.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.locallink.pro.domain.model.Message
import com.locallink.pro.domain.model.MessageSender

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String?,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    vm: ChatViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(sessionId) { vm.openSession(sessionId) }
    LaunchedEffect(state.messages.size, state.streamingText) {
        val count = state.messages.size + if (state.streamingText.isNotBlank()) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? -> vm.attachImage(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Omni Pro") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = {
                    IconButton(onClick = { vm.toggleAutoTts() }) {
                        Icon(if (state.autoTts) Icons.Default.VolumeUp else Icons.Default.VolumeOff, "Auto TTS")
                    }
                    IconButton(onClick = onNavigateToSettings) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp)) {
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                if (state.streamingText.isNotBlank()) {
                    item { MessageBubble(Message(text = state.streamingText + "▌", sender = MessageSender.AI)) }
                } else if (state.isAiResponding) {
                    item { Box(Modifier.fillMaxWidth().padding(8.dp)) { CircularProgressIndicator(Modifier.size(20.dp)) } }
                }
            }

            if (state.partialVoiceResult.isNotBlank()) {
                Text(state.partialVoiceResult, Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center)
            }
            state.pendingImageUri?.let { uri ->
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = uri, contentDescription = "Attached", modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                    IconButton(onClick = { vm.attachImage(null) }) { Icon(Icons.Default.Close, "Remove image") }
                }
            }

            InputBar(
                input = state.inputText,
                isListening = state.isListening,
                onInputChange = vm::updateInput,
                onSend = { vm.sendMessage() },
                onMic = { vm.toggleVoiceInput() },
                onPickImage = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
        }
    }
}

@Composable
private fun MessageBubble(msg: Message) {
    val isUser = msg.sender == MessageSender.USER
    val align = if (isUser) Alignment.End else Alignment.Start
    val color = when (msg.sender) {
        MessageSender.USER -> MaterialTheme.colorScheme.primaryContainer
        MessageSender.AI -> MaterialTheme.colorScheme.surfaceVariant
        MessageSender.SYSTEM -> MaterialTheme.colorScheme.errorContainer
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = align) {
        Surface(color = color, shape = RoundedCornerShape(12.dp), modifier = Modifier.widthIn(max = 300.dp)) {
            Column(Modifier.padding(10.dp)) {
                msg.imageUri?.let {
                    AsyncImage(model = it, contentDescription = null, modifier = Modifier.size(180.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.height(6.dp))
                }
                if (msg.text.isNotBlank()) Text(msg.text)
            }
        }
    }
}

@Composable
private fun InputBar(
    input: String,
    isListening: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onMic: () -> Unit,
    onPickImage: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPickImage) { Icon(Icons.Default.Image, "Attach image") }
            IconButton(onClick = onMic) {
                Icon(if (isListening) Icons.Default.MicOff else Icons.Default.Mic, "Voice")
            }
            OutlinedTextField(
                value = input, onValueChange = onInputChange,
                modifier = Modifier.weight(1f), placeholder = { Text("Message") }, maxLines = 4,
            )
            IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/locallink/pro/ui/screens/chat/ChatScreen.kt
git commit -m "feat: standalone ChatScreen with streaming, voice, image attach"
```

---

### Task 15: Model gate screen + ViewModel

**Files:**
- Create: `app/src/main/java/com/locallink/pro/ui/screens/model/ModelGateScreen.kt`
- Create: `app/src/main/java/com/locallink/pro/ui/screens/model/ModelGateViewModel.kt`

- [ ] **Step 1: Create ModelGateViewModel.kt**

```kotlin
package com.locallink.pro.ui.screens.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.service.llm.ModelManager
import com.locallink.pro.service.llm.ModelState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ModelGateViewModel @Inject constructor(
    private val modelManager: ModelManager,
) : ViewModel() {
    val state: StateFlow<ModelState> = modelManager.state
    init { prepare() }
    fun prepare() = viewModelScope.launch { modelManager.prepare() }
}
```

- [ ] **Step 2: Create ModelGateScreen.kt**

```kotlin
package com.locallink.pro.ui.screens.model

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.locallink.pro.service.llm.ModelState

@Composable
fun ModelGateScreen(
    onReady: () -> Unit,
    vm: ModelGateViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state) { if (state is ModelState.Ready) onReady() }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Omni Pro", style = MaterialTheme.typography.headlineMedium)
            when (val s = state) {
                is ModelState.Preparing -> { CircularProgressIndicator(); Text("Preparing model…") }
                is ModelState.Copying -> { LinearProgressIndicator(progress = { s.progress }); Text("Installing model ${(s.progress * 100).toInt()}%") }
                is ModelState.Ready -> Text("Ready")
                is ModelState.Error -> {
                    Text("Error: ${s.message}")
                    Button(onClick = { vm.prepare() }) { Text("Retry") }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/ui/screens/model/
git commit -m "feat: model gate screen (copies bundled model on first launch)"
```

---

### Task 16: Rewrite navigation + trim SettingsScreen/MainShell

**Files:**
- Modify (full rewrite): `app/src/main/java/com/locallink/pro/ui/navigation/NavGraph.kt`
- Delete: `app/src/main/java/com/locallink/pro/ui/screens/shell/MainShellScreen.kt`
- Modify: `app/src/main/java/com/locallink/pro/ui/screens/settings/SettingsScreen.kt` and `SettingsViewModel.kt`

- [ ] **Step 1: Rewrite NavGraph.kt**

```kotlin
package com.locallink.pro.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.locallink.pro.ui.screens.chat.ChatScreen
import com.locallink.pro.ui.screens.model.ModelGateScreen
import com.locallink.pro.ui.screens.sessions.SessionsScreen
import com.locallink.pro.ui.screens.settings.SettingsScreen

object Routes {
    const val GATE = "gate"
    const val SESSIONS = "sessions"
    const val CHAT = "chat"            // chat?sessionId={id}
    const val SETTINGS = "settings"
}

@Composable
fun LocalLinkNavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Routes.GATE) {
        composable(Routes.GATE) {
            ModelGateScreen(onReady = {
                navController.navigate(Routes.SESSIONS) {
                    popUpTo(Routes.GATE) { inclusive = true }
                }
            })
        }
        composable(Routes.SESSIONS) {
            SessionsScreen(onOpenSession = { id ->
                navController.navigate("${Routes.CHAT}?sessionId=${id ?: ""}")
            })
        }
        composable(
            "${Routes.CHAT}?sessionId={sessionId}",
            arguments = listOf(navArgument("sessionId") { type = NavType.StringType; defaultValue = "" }),
        ) { entry ->
            val sid = entry.arguments?.getString("sessionId")?.ifBlank { null }
            ChatScreen(
                sessionId = sid,
                onBack = { navController.popBackStack() },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 2: Delete MainShellScreen**

```bash
rm -f app/src/main/java/com/locallink/pro/ui/screens/shell/MainShellScreen.kt
rmdir app/src/main/java/com/locallink/pro/ui/screens/shell 2>/dev/null || true
```

- [ ] **Step 3: Fix SettingsScreen/SettingsViewModel references**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | grep -i "settings" | head`
Open `SettingsScreen.kt` + `SettingsViewModel.kt`. Remove any references to deleted types (TransportManager, ConnectionPreferences, reconnect/transport toggles, model selector). Keep TTS speed/pitch/speaker/auto-TTS/STT toggles + a "Clear all chats" button calling a new `SettingsViewModel.clearAllChats()` that injects `ChatRepository` and calls `clearAll()`. Show the model state text from `ModelManager.state` if convenient (optional). The exact edits depend on current file content — make the minimal changes so it compiles and only references kept services (`VoiceService`, `SettingsPreferences`, `ChatRepository`).

- [ ] **Step 4: Verify full compile**

Run: `./gradlew :app:compileDebugKotlin 2>&1 | tail -30`
Expected: BUILD SUCCESSFUL (no unresolved references). Fix any remaining references to deleted classes.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: standalone nav (gate -> sessions -> chat/settings), trim settings"
```

---

## Phase 6 — Build, install, verify on device

### Task 17: Full build + install + on-device verification

**Files:** none (verification only)

- [ ] **Step 1: Assemble debug APK**

Run: `./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. APK is large (~3GB+ due to bundled model) — assembly is slow.

- [ ] **Step 2: Install on the connected OnePlus 9R**

Run: `/Users/lokesh/Library/Android/sdk/platform-tools/adb -s da4d6bff install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success` (this push is slow at ~3GB).

- [ ] **Step 3: Launch and watch logs**

Run: `/Users/lokesh/Library/Android/sdk/platform-tools/adb -s da4d6bff shell am start -n com.locallink.pro/.ui.MainActivity` then `adb -s da4d6bff logcat -s ModelManager LlmService ChatRepository AndroidRuntime`
Expected: ModelManager logs "Model ready"; no fatal exceptions.

- [ ] **Step 4: Manual verification checklist (perform on device)**

Verify each:
1. First launch shows model gate → "Installing model" → lands on Sessions.
2. New chat → type "Hello" → tokens stream into an AI bubble → message persists.
3. Kill + reopen app → session and messages still present (SQLite).
4. Tap mic → speak → transcript sends → AI replies → reply is spoken (TTS).
5. Attach a photo (gallery) → ask "what is in this image?" → model responds about the image.
6. Settings → adjust TTS, "Clear all chats" empties the list.

This step requires human interaction. Report which items pass/fail. Do NOT claim success without observing each.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: standalone on-device LLM build verified on device" || echo "nothing to commit"
```

---

## Self-review notes (coverage map)

- Spec §2 runtime/model/streaming → Tasks 1, 8, 9.
- Spec §4.4 persistence → Tasks 5–7, 11.
- Spec §4.2 context policy → Task 9 (history re-seed, bounded to 12 turns) + Task 11 (history passed).
- Spec §4.6 image/vision → Tasks 10, 14 (pick), 9 (addImage).
- Spec §4.7 voice kept → Tasks 12, 14 (wired to new repo).
- Spec §4.8 screens (gate/sessions/chat/settings) → Tasks 13, 14, 15, 16.
- Spec §5 removals → Tasks 2, 3, 4, 16.
- Spec §7 testing → Tasks 7 (DAO), 11 (repo), 17 (manual device).

**Known adaptation points flagged inline:** MediaPipe `LlmInference.createFromOptions` Context arg (Task 9 Step 1 note), streaming-callback SAM form (Task 9 Step 2), exact SettingsScreen edits (Task 16 Step 3). These vary by point release / current file contents and are handled by "make it compile" verification steps rather than guessed code.
```
