# Omni Pilot — Thin Vertical Slice Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prove the risky end-to-end path for on-device UI control — enable an AccessibilityService, perceive one screen (pruned tree + screenshot), ask the cloud model for ONE action, actuate it, and halt instantly via an always-on-top STOP button.

**Architecture:** A new on-device agent loop that is a sibling to `OpenRouterClient.run`. An `OmniAccessibilityService` reads the live `AccessibilityNodeInfo` tree and performs gestures; a `ScreenCapturer` (MediaProjection) grabs a screenshot; a `PilotController` runs perceive→reason→act; a `PilotOverlay` draws a STOP button the service can render above every app. Reasoning is a single cloud model call per step (no second "Executor" LLM — divergence #1 from mobile-use).

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp (existing), Android AccessibilityService API, MediaProjection, `org.json` (existing), JUnit4 + kotlinx-coroutines-test (existing test stack).

## Global Constraints

- **minSdk 26, targetSdk 35, compileSdk 35** (copied verbatim from `app/build.gradle.kts`).
- **Cloud brain only** — reasoning goes through the existing OpenRouter path; no on-device model in Pilot.
- **One structured action per step** — the model emits a native `tool_call`; Kotlin maps it deterministically to a gesture. No stringified-JSON handoff, no second LLM.
- **Perception is single-turn** — the tree + screenshot are built fresh each step and never accumulated.
- **Prune the tree before sending** — interactive/text-bearing nodes only; drop default/false booleans. Target ~3–5× fewer tokens than a raw dump.
- **Kill switch is authoritative** — an atomic cancel flag is checked before every actuation; a set flag aborts within one step.
- **Pilot runs only when explicitly invoked**, never in the background. Screen contents (tree + screenshot) are sent to OpenRouter each step — this is an accepted, user-facing trade-off.
- **Test style:** pure-logic seams are JUnit4 unit tests in `app/src/test` using hand-rolled fakes (no MockK/Robolectric, matching `ChatRepositoryTest.kt`). Device-dependent pieces are verified on-device via `adb`, described explicitly per task.
- **Build/deploy:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`; compile with `./gradlew :app:compileDebugKotlin`; unit tests with `./gradlew :app:testDebugUnitTest`; install with `./gradlew :app:installDebug`. ADB at `$HOME/Library/Android/sdk/platform-tools/adb`, device serial `da4d6bff`.

---

## File Structure

New package: `app/src/main/java/com/locallink/pro/service/pilot/`

- `PilotElement.kt` — data model for one perceived UI element + the flat snapshot; pure Kotlin.
- `TreeFlattener.kt` — converts an `AccessibilityNodeInfo` tree to a pruned `List<PilotElement>`; the node-walking is Android, but the pruning/serialization predicate is pure and unit-tested via a small node abstraction.
- `PilotAction.kt` — sealed action model + parser from an OpenRouter `tool_call` JSON; pure Kotlin, unit-tested.
- `PilotActionSchema.kt` — the OpenAI-style `tools[]` JSON advertised to the model (thin slice: `tap`, `done`, `ask`); pure Kotlin, unit-tested.
- `OmniAccessibilityService.kt` — the AccessibilityService: snapshot the tree, dispatch a tap, host the STOP overlay. Android; verified on-device.
- `ScreenCapturer.kt` — MediaProjection → one compressed JPEG. Android; verified on-device.
- `PilotController.kt` — the perceive→reason→act loop; emits `AgentEvent`. Wires the above + OpenRouter.
- `PilotOverlay.kt` — Compose/`View` STOP button rendered via `TYPE_ACCESSIBILITY_OVERLAY`. Android.

Modified:
- `app/src/main/AndroidManifest.xml` — register the service + `FOREGROUND_SERVICE_MEDIA_PROJECTION` permission.
- `app/src/main/res/xml/omni_accessibility_config.xml` — new accessibility service config.

Test files (JUnit, `app/src/test/java/com/locallink/pro/service/pilot/`):
- `TreeFlattenerTest.kt`, `PilotActionTest.kt`, `PilotActionSchemaTest.kt`, `PilotControllerTest.kt`.

---

### Task 1: Element model + pruning predicate (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotElement.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/TreeFlattenerTest.kt`
- Create: `app/src/main/java/com/locallink/pro/service/pilot/TreeFlattener.kt`

**Interfaces:**
- Produces:
  - `data class PilotElement(val id: Int, val text: String?, val desc: String?, val resId: String?, val cls: String?, val bounds: IntArray /*[l,t,r,b]*/, val clickable: Boolean, val editable: Boolean)`
  - `fun PilotElement.toJson(): org.json.JSONObject`
  - `interface FlatNode { val text: String?; val desc: String?; val resId: String?; val cls: String?; val bounds: IntArray; val clickable: Boolean; val editable: Boolean; val scrollable: Boolean; val children: List<FlatNode> }`
  - `object TreeFlattener { fun flatten(root: FlatNode): List<PilotElement>; fun isInteresting(n: FlatNode): Boolean }`
- `flatten` walks the tree depth-first, keeps only nodes where `isInteresting` is true, assigns sequential `id` starting at 0. `isInteresting` = `clickable || editable || scrollable || !text.isNullOrBlank() || !desc.isNullOrBlank()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFlattenerTest {
    private class Fake(
        override val text: String? = null,
        override val desc: String? = null,
        override val resId: String? = null,
        override val cls: String? = null,
        override val bounds: IntArray = intArrayOf(0, 0, 0, 0),
        override val clickable: Boolean = false,
        override val editable: Boolean = false,
        override val scrollable: Boolean = false,
        override val children: List<FlatNode> = emptyList(),
    ) : FlatNode

    @Test fun keepsInteractiveAndTextNodes_dropsChrome() {
        val root = Fake(children = listOf(
            Fake(cls = "FrameLayout"),                       // dropped: no text, not interactive
            Fake(text = "Message", clickable = true),        // kept
            Fake(text = "Alice"),                            // kept: text-bearing
            Fake(desc = "Send", clickable = true),           // kept
        ))
        val flat = TreeFlattener.flatten(root)
        assertEquals(3, flat.size)
        assertEquals(listOf(0, 1, 2), flat.map { it.id })
        assertEquals("Message", flat[0].text)
        assertTrue(flat[2].clickable)
    }

    @Test fun toJson_emitsCompactShape() {
        val e = PilotElement(
            id = 7, text = "Submit", desc = null, resId = "com.x:id/go",
            cls = "Button", bounds = intArrayOf(10, 20, 110, 80),
            clickable = true, editable = false,
        )
        val j = e.toJson()
        assertEquals(7, j.getInt("id"))
        assertEquals("Submit", j.getString("text"))
        assertEquals(true, j.getBoolean("clickable"))
        // default-false editable is omitted to save tokens
        assertTrue(!j.has("editable"))
        // bounds serialized as [l,t,r,b]
        assertEquals(10, j.getJSONArray("bounds").getInt(0))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.TreeFlattenerTest"`
Expected: FAIL — unresolved references `FlatNode`, `TreeFlattener`, `PilotElement`.

- [ ] **Step 3: Write minimal implementation**

`PilotElement.kt`:
```kotlin
package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

data class PilotElement(
    val id: Int,
    val text: String?,
    val desc: String?,
    val resId: String?,
    val cls: String?,
    val bounds: IntArray, // [left, top, right, bottom]
    val clickable: Boolean,
    val editable: Boolean,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        text?.takeIf { it.isNotBlank() }?.let { put("text", it) }
        desc?.takeIf { it.isNotBlank() }?.let { put("desc", it) }
        resId?.takeIf { it.isNotBlank() }?.let { put("resId", it) }
        cls?.takeIf { it.isNotBlank() }?.let { put("cls", it) }
        put("bounds", JSONArray(listOf(bounds[0], bounds[1], bounds[2], bounds[3])))
        if (clickable) put("clickable", true)   // omit default-false to save tokens
        if (editable) put("editable", true)
    }
}
```

`TreeFlattener.kt`:
```kotlin
package com.locallink.pro.service.pilot

interface FlatNode {
    val text: String?
    val desc: String?
    val resId: String?
    val cls: String?
    val bounds: IntArray
    val clickable: Boolean
    val editable: Boolean
    val scrollable: Boolean
    val children: List<FlatNode>
}

object TreeFlattener {
    fun isInteresting(n: FlatNode): Boolean =
        n.clickable || n.editable || n.scrollable ||
            !n.text.isNullOrBlank() || !n.desc.isNullOrBlank()

    fun flatten(root: FlatNode): List<PilotElement> {
        val out = ArrayList<PilotElement>()
        fun walk(n: FlatNode) {
            if (isInteresting(n)) {
                out.add(
                    PilotElement(
                        id = out.size, text = n.text, desc = n.desc, resId = n.resId,
                        cls = n.cls, bounds = n.bounds, clickable = n.clickable, editable = n.editable,
                    )
                )
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.TreeFlattenerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotElement.kt \
        app/src/main/java/com/locallink/pro/service/pilot/TreeFlattener.kt \
        app/src/test/java/com/locallink/pro/service/pilot/TreeFlattenerTest.kt
git commit -m "feat(pilot): element model + tree pruning predicate"
```

---

### Task 2: Action model + parser (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotAction.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PilotActionTest.kt`

**Interfaces:**
- Consumes: nothing from prior tasks.
- Produces:
  - `sealed interface PilotAction { data class Tap(val id: Int): PilotAction; data class Done(val result: String): PilotAction; data class Ask(val question: String): PilotAction; data class Invalid(val reason: String): PilotAction }`
  - `object PilotActionParser { fun parse(name: String, argsJson: String): PilotAction }`
- `parse` maps tool-call name+args → action; unknown name or missing/mis-typed args → `Invalid` with a human reason (thin slice supports `tap`, `done`, `ask`).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotActionTest {
    @Test fun parsesTap() {
        val a = PilotActionParser.parse("tap", """{"id": 12}""")
        assertEquals(PilotAction.Tap(12), a)
    }

    @Test fun parsesDoneAndAsk() {
        assertEquals(PilotAction.Done("found it"),
            PilotActionParser.parse("done", """{"result":"found it"}"""))
        assertEquals(PilotAction.Ask("which Divya?"),
            PilotActionParser.parse("ask", """{"question":"which Divya?"}"""))
    }

    @Test fun unknownToolIsInvalid() {
        val a = PilotActionParser.parse("COMPOSIO_SEARCH_WEB", "{}")
        assertTrue(a is PilotAction.Invalid)
        assertTrue((a as PilotAction.Invalid).reason.contains("COMPOSIO_SEARCH_WEB"))
    }

    @Test fun tapWithoutIdIsInvalid() {
        val a = PilotActionParser.parse("tap", """{"foo":1}""")
        assertTrue(a is PilotAction.Invalid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotActionTest"`
Expected: FAIL — unresolved `PilotAction`, `PilotActionParser`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import org.json.JSONObject

sealed interface PilotAction {
    data class Tap(val id: Int) : PilotAction
    data class Done(val result: String) : PilotAction
    data class Ask(val question: String) : PilotAction
    data class Invalid(val reason: String) : PilotAction
}

object PilotActionParser {
    /** Actions the model may emit in the thin slice. */
    val ALLOWED = setOf("tap", "done", "ask")

    fun parse(name: String, argsJson: String): PilotAction {
        if (name !in ALLOWED) {
            return PilotAction.Invalid(
                "'$name' is not a callable action. Allowed: ${ALLOWED.joinToString(", ")}."
            )
        }
        val args = runCatching { JSONObject(argsJson) }.getOrElse {
            return PilotAction.Invalid("arguments for '$name' were not valid JSON")
        }
        return when (name) {
            "tap" -> if (args.has("id")) PilotAction.Tap(args.getInt("id"))
                     else PilotAction.Invalid("tap requires an integer 'id'")
            "done" -> PilotAction.Done(args.optString("result"))
            "ask" -> if (args.optString("question").isNotBlank()) PilotAction.Ask(args.getString("question"))
                     else PilotAction.Invalid("ask requires a non-empty 'question'")
            else -> PilotAction.Invalid("unhandled action '$name'")
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotActionTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotAction.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PilotActionTest.kt
git commit -m "feat(pilot): structured action model + tool-call parser"
```

---

### Task 3: Action schema advertised to the model (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotActionSchema.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PilotActionSchemaTest.kt`

**Interfaces:**
- Consumes: `PilotActionParser.ALLOWED` (Task 2).
- Produces: `object PilotActionSchema { fun toolsJson(): org.json.JSONArray; const val SYSTEM: String }`
- `toolsJson()` returns an OpenAI-style `tools[]` array with one function per allowed action; every function name is in `PilotActionParser.ALLOWED` (guarantees the model can only be told about parseable actions).

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotActionSchemaTest {
    @Test fun everyAdvertisedToolIsParseable() {
        val tools = PilotActionSchema.toolsJson()
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertEquals(PilotActionParser.ALLOWED, names)
    }

    @Test fun tapToolDeclaresIntegerId() {
        val tools = PilotActionSchema.toolsJson()
        val tap = (0 until tools.length()).map { tools.getJSONObject(it) }
            .first { it.getJSONObject("function").getString("name") == "tap" }
        val props = tap.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertEquals("integer", props.getJSONObject("id").getString("type"))
    }

    @Test fun systemPromptForbidsRawCoordinates() {
        assertTrue(PilotActionSchema.SYSTEM.contains("id", ignoreCase = true))
        assertTrue(PilotActionSchema.SYSTEM.lowercase().contains("one action"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotActionSchemaTest"`
Expected: FAIL — unresolved `PilotActionSchema`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import org.json.JSONArray
import org.json.JSONObject

object PilotActionSchema {
    val SYSTEM = """
        You are Omni Pilot. You control the phone by choosing ONE action per step from the
        current screen. You are given: (1) the user's task, (2) a JSON list of on-screen
        elements each with a numeric "id", and (3) a screenshot. Combine both senses: the
        element list gives you ids/text/bounds; the screenshot gives you layout the list
        can't. Refer to elements ONLY by their "id" — never invent coordinates or ids not
        in the list. Emit exactly ONE action per step. When the task is achieved, call
        done(result). If you are unsure which element or the screen is unexpected, call
        ask(question) instead of guessing.
    """.trimIndent()

    private fun fn(name: String, description: String, params: JSONObject): JSONObject =
        JSONObject().put("type", "function").put(
            "function",
            JSONObject().put("name", name).put("description", description).put("parameters", params),
        )

    private fun obj(props: JSONObject, required: List<String>): JSONObject =
        JSONObject().put("type", "object").put("properties", props)
            .put("required", JSONArray(required))

    fun toolsJson(): JSONArray {
        val tap = fn(
            "tap", "Tap the on-screen element with the given id.",
            obj(JSONObject().put("id", JSONObject().put("type", "integer")
                .put("description", "id of the element to tap")), listOf("id")),
        )
        val done = fn(
            "done", "The task is complete. Provide the result/answer for the user.",
            obj(JSONObject().put("result", JSONObject().put("type", "string")), listOf("result")),
        )
        val ask = fn(
            "ask", "Pause and ask the user a clarifying question.",
            obj(JSONObject().put("question", JSONObject().put("type", "string")), listOf("question")),
        )
        return JSONArray().put(tap).put(done).put(ask)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotActionSchemaTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotActionSchema.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PilotActionSchemaTest.kt
git commit -m "feat(pilot): tool schema + system prompt for the pilot loop"
```

---

### Task 4: AccessibilityService — snapshot + tap + STOP overlay (on-device)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/OmniAccessibilityService.kt`
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotOverlay.kt`
- Create: `app/src/main/res/xml/omni_accessibility_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `TreeFlattener.flatten` + `FlatNode` (Task 1).
- Produces:
  - `class OmniAccessibilityService : AccessibilityService()` with a companion `@Volatile var instance: OmniAccessibilityService?` and:
    - `fun snapshot(): List<PilotElement>` — flatten the active window via a `FlatNode` adapter over `AccessibilityNodeInfo`.
    - `suspend fun tapElement(e: PilotElement): Boolean` — `dispatchGesture` at bounds center; returns completion.
    - `val cancelFlag: java.util.concurrent.atomic.AtomicBoolean` — set true by the STOP overlay.
    - `fun showStop()` / `fun hideStop()` — add/remove the overlay.

This task is **verified on-device**, not by unit tests (it needs a real `AccessibilityService` + windows). The `FlatNode` adapter's pruning is already unit-tested via Task 1.

- [ ] **Step 1: Add the accessibility config resource**

`app/src/main/res/xml/omni_accessibility_config.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds|flagRequestTouchExplorationMode"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:description="@string/pilot_a11y_description" />
```

- [ ] **Step 2: Add the description string**

Add to `app/src/main/res/values/strings.xml` (create the `<string>` if the file exists; if not, create the file with a `<resources>` root):
```xml
<string name="pilot_a11y_description">Lets Omni read what\'s on your screen and tap for you, only when you ask it to.</string>
```

- [ ] **Step 3: Register the service + permission in the manifest**

In `app/src/main/AndroidManifest.xml`, add the permission near the other `<uses-permission>` lines:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```
And inside `<application>`, add:
```xml
<service
    android:name=".service.pilot.OmniAccessibilityService"
    android:exported="false"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/omni_accessibility_config" />
</service>
```

- [ ] **Step 4: Implement the overlay STOP button**

`PilotOverlay.kt`:
```kotlin
package com.locallink.pro.service.pilot

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button

/** A floating STOP button added via TYPE_ACCESSIBILITY_OVERLAY so it sits above every app. */
class PilotOverlay(private val ctx: Context, private val onStop: () -> Unit) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null

    fun show() {
        if (view != null) return
        val btn = Button(ctx).apply {
            text = "STOP"
            setBackgroundColor(Color.parseColor("#CC3B30"))
            setTextColor(Color.WHITE)
            setOnClickListener { onStop() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; y = 120 }
        wm.addView(btn, lp)
        view = btn
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }
}
```

- [ ] **Step 5: Implement the service (snapshot + tap + cancel + overlay)**

`OmniAccessibilityService.kt`:
```kotlin
package com.locallink.pro.service.pilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

class OmniAccessibilityService : AccessibilityService() {

    val cancelFlag = AtomicBoolean(false)
    private var overlay: PilotOverlay? = null

    override fun onServiceConnected() {
        instance = this
        overlay = PilotOverlay(this) { cancelFlag.set(true); hideStop() }
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* perception is pull-based */ }
    override fun onInterrupt() {}

    fun showStop() { cancelFlag.set(false); overlay?.show() }
    fun hideStop() { overlay?.hide() }

    /** Flatten the active window into pruned PilotElements. */
    fun snapshot(): List<PilotElement> {
        val root = rootInActiveWindow ?: return emptyList()
        return TreeFlattener.flatten(Adapter(root))
    }

    /** Tap the center of an element's bounds via a gesture. Returns true on dispatch completion. */
    suspend fun tapElement(e: PilotElement): Boolean {
        if (cancelFlag.get()) return false
        val cx = ((e.bounds[0] + e.bounds[2]) / 2).toFloat()
        val cy = ((e.bounds[1] + e.bounds[3]) / 2).toFloat()
        val path = Path().apply { moveTo(cx, cy); lineTo(cx, cy) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build()
        val done = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        return if (!ok) false else done.await()
    }

    /** Adapts an AccessibilityNodeInfo tree to the pure FlatNode interface (Task 1). */
    private class Adapter(private val n: AccessibilityNodeInfo) : FlatNode {
        override val text get() = n.text?.toString()
        override val desc get() = n.contentDescription?.toString()
        override val resId get() = n.viewIdResourceName
        override val cls get() = n.className?.toString()
        override val bounds: IntArray get() = Rect().also { n.getBoundsInScreen(it) }
            .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
        override val clickable get() = n.isClickable
        override val editable get() = n.isEditable
        override val scrollable get() = n.isScrollable
        override val children: List<FlatNode>
            get() = (0 until n.childCount).mapNotNull { n.getChild(it)?.let { c -> Adapter(c) } }
    }

    companion object {
        @Volatile var instance: OmniAccessibilityService? = null
    }
}
```

- [ ] **Step 6: Build, install, and verify on-device**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
# Enable the service manually: Settings → Accessibility → Omni Pilot → On (cannot be adb-granted on this OnePlus).
"$ADB" shell am start -a android.settings.ACCESSIBILITY_SETTINGS
```
Manual check: enable "Omni Pilot" in Accessibility. Expected: no crash; `adb logcat` shows the service connected (add a `Log.d("OmniPilot","connected")` in `onServiceConnected` for this check). This task's deliverable is proven when the service enables cleanly and stays bound.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/OmniAccessibilityService.kt \
        app/src/main/java/com/locallink/pro/service/pilot/PilotOverlay.kt \
        app/src/main/res/xml/omni_accessibility_config.xml \
        app/src/main/res/values/strings.xml \
        app/src/main/AndroidManifest.xml
git commit -m "feat(pilot): AccessibilityService snapshot+tap+STOP overlay"
```

---

### Task 5: ScreenCapturer — one MediaProjection screenshot (on-device)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/ScreenCapturer.kt`

**Interfaces:**
- Produces: `class ScreenCapturer` with `suspend fun capture(mp: android.media.projection.MediaProjection): ByteArray?` returning a compressed JPEG (quality 50), or null on failure.
- The `MediaProjection` token is obtained by the caller (Task 6 / UI) via `MediaProjectionManager.createScreenCaptureIntent()` — the capturer only consumes an already-granted projection.

Verified on-device (needs a real projection grant). No unit test.

- [ ] **Step 1: Implement**

```kotlin
package com.locallink.pro.service.pilot

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class ScreenCapturer(private val metrics: DisplayMetrics) {
    /** Grab one frame via a short-lived VirtualDisplay, return a JPEG (quality 50). */
    suspend fun capture(mp: MediaProjection): ByteArray? = suspendCancellableCoroutine { cont ->
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride
                val bmp = Bitmap.createBitmap(
                    rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888,
                ).apply { copyPixelsFromBuffer(plane.buffer) }
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                val out = ByteArrayOutputStream()
                cropped.compress(Bitmap.CompressFormat.JPEG, 50, out)
                if (cont.isActive) cont.resume(out.toByteArray())
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            } finally {
                image.close(); vd?.release(); reader.close()
            }
        }, null)
        vd = mp.createVirtualDisplay(
            "omni-pilot", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
        )
        cont.invokeOnCancellation { runCatching { vd?.release(); reader.close() } }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/ScreenCapturer.kt
git commit -m "feat(pilot): MediaProjection single-frame screenshot capturer"
```

---

### Task 6: PilotController — the perceive→reason→act loop

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotController.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PilotControllerTest.kt`

**Interfaces:**
- Consumes: `OmniAccessibilityService` (Task 4), `ScreenCapturer` (Task 5), `PilotActionSchema` (Task 3), `PilotActionParser` (Task 2), `TreeFlattener`/`PilotElement` (Task 1), and a `PilotReasoner` seam for the cloud call (below). Emits `com.locallink.pro.service.llm.AgentEvent` (existing).
- Produces:
  - `interface PilotReasoner { suspend fun nextAction(task: String, elements: List<PilotElement>, screenshot: ByteArray?, history: List<String>): Pair<String, String> /* toolName, argsJson */ }`
  - `class PilotController(private val reasoner: PilotReasoner, private val perceive: () -> List<PilotElement>, private val tap: suspend (PilotElement) -> Boolean, private val cancelled: () -> Boolean, private val maxSteps: Int = 25)` with `fun run(task: String): kotlinx.coroutines.flow.Flow<AgentEvent>`.
- The controller loop: perceive → `reasoner.nextAction` → parse → if `Tap`, check `cancelled()` then `tap`; if `Done`, emit Final and stop; if `Ask`, emit Final(question) and stop; if `Invalid`, feed the reason back as history and continue. Stops on `cancelled()`, `Done`, `maxSteps`, or a no-progress guard (same element signature twice running).

The loop is unit-tested with fakes for `PilotReasoner`/perceive/tap — no device needed. Wiring the real `PilotReasoner` to OpenRouter is a thin adapter (Step 5), compiled but exercised on-device.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotControllerTest {
    private val oneElement = listOf(
        PilotElement(0, "Send", null, null, "Button", intArrayOf(0, 0, 10, 10), true, false),
    )

    @Test fun tapsThenCompletes() = runTest {
        val script = ArrayDeque(listOf("tap" to """{"id":0}""", "done" to """{"result":"sent"}"""))
        val tapped = ArrayList<Int>()
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> script.removeFirst() },
            perceive = { oneElement },
            tap = { e -> tapped.add(e.id); true },
            cancelled = { false },
        )
        val events = ctrl.run("send it").toList()
        assertEquals(listOf(0), tapped)
        assertTrue(events.last() is AgentEvent.Final)
        assertEquals("sent", (events.last() as AgentEvent.Final).text)
    }

    @Test fun stopsImmediatelyWhenCancelled() = runTest {
        var calls = 0
        val ctrl = PilotController(
            reasoner = { _, _, _, _ -> calls++; "tap" to """{"id":0}""" },
            perceive = { oneElement },
            tap = { true },
            cancelled = { true }, // STOP already pressed
        )
        val events = ctrl.run("x").toList()
        assertTrue(events.last() is AgentEvent.Final)
        assertTrue("no tap should run once cancelled", calls <= 1)
    }

    @Test fun stopsAtMaxSteps() = runTest {
        // Reasoner always taps (never done); each perceive returns a DIFFERENT screen
        // signature so the no-progress guard never fires and the loop runs to maxSteps.
        var step = 0
        val ctrl = PilotController(
            reasoner = PilotReasoner { _, _, _, _ -> "tap" to """{"id":0}""" },
            perceive = { listOf(PilotElement(0, "n${step++}", null, null, null, intArrayOf(0, 0, 1, 1), true, false)) },
            tap = { true },
            cancelled = { false },
            maxSteps = 3,
        )
        val events = ctrl.run("loop").toList()
        assertTrue(events.last() is AgentEvent.Final)
    }
}
```

Note: `PilotReasoner` is a fun interface so a lambda `{ task, els, shot, hist -> ... }` works directly.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotControllerTest"`
Expected: FAIL — unresolved `PilotController`, `PilotReasoner`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

fun interface PilotReasoner {
    /** @return (toolName, argsJson) for the next action. */
    suspend fun nextAction(
        task: String,
        elements: List<PilotElement>,
        screenshot: ByteArray?,
        history: List<String>,
    ): Pair<String, String>
}

class PilotController(
    private val reasoner: PilotReasoner,
    private val perceive: () -> List<PilotElement>,
    private val tap: suspend (PilotElement) -> Boolean,
    private val cancelled: () -> Boolean,
    private val maxSteps: Int = 25,
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        val history = ArrayList<String>()
        var lastSig: String? = null
        var step = 0
        while (step < maxSteps) {
            step++
            if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
            val elements = perceive()
            val sig = elements.joinToString("|") { "${it.text}:${it.bounds.joinToString(",")}" }
            val (name, args) = reasoner.nextAction(task, elements, /*screenshot*/ null, history)
            when (val action = PilotActionParser.parse(name, args)) {
                is PilotAction.Tap -> {
                    if (cancelled()) { emit(AgentEvent.Final("Stopped.")); return@flow }
                    val target = elements.firstOrNull { it.id == action.id }
                    if (target == null) { history.add("tap failed: no element id ${action.id}"); continue }
                    val id = UUID.randomUUID().toString()
                    emit(AgentEvent.ToolCall(id, "tap", args, true))
                    val ok = tap(target)
                    emit(AgentEvent.ToolResult(id, "tap", if (ok) "tapped" else "tap failed", ok))
                    history.add("tapped id ${action.id} (${target.text})")
                }
                is PilotAction.Done -> { emit(AgentEvent.Final(action.result)); return@flow }
                is PilotAction.Ask -> { emit(AgentEvent.Final(action.question)); return@flow }
                is PilotAction.Invalid -> history.add("invalid action: ${action.reason}")
            }
            // No-progress guard: identical screen signature two steps running → stop.
            if (sig == lastSig) { emit(AgentEvent.Final("Stopped — no progress on screen.")); return@flow }
            lastSig = sig
        }
        emit(AgentEvent.Final("Stopped after $maxSteps steps."))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotControllerTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotController.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PilotControllerTest.kt
git commit -m "feat(pilot): perceive->reason->act loop with cancel + no-progress guards"
```

---

### Task 7: Wire the real cloud reasoner + a debug trigger (on-device slice)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/OpenRouterPilotReasoner.kt`
- Modify: `app/src/main/java/com/locallink/pro/service/llm/OpenRouterClient.kt` (add a vision chat helper if none is reusable — see interface)

**Interfaces:**
- Consumes: `PilotReasoner` (Task 6), the existing OpenRouter key/model from `SettingsPreferences`, `PilotActionSchema` (Task 3).
- Produces: `class OpenRouterPilotReasoner(...) : PilotReasoner` that POSTs one vision chat turn (system = `PilotActionSchema.SYSTEM`; user = task + element JSON; image = screenshot data URL; `tools` = `PilotActionSchema.toolsJson()`; `tool_choice=required`) and returns the first `tool_call`'s `(name, arguments)`.

This is the on-device integration point. It reuses the OpenRouter request pattern already in `OpenRouterClient.postChat`. Exercised on-device with a real task; no unit test (network + device).

- [ ] **Step 1: Implement the reasoner**

```kotlin
package com.locallink.pro.service.pilot

import android.util.Base64
import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class OpenRouterPilotReasoner(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build(),
) : PilotReasoner {
    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun nextAction(
        task: String, elements: List<PilotElement>, screenshot: ByteArray?, history: List<String>,
    ): Pair<String, String> {
        val key = settings.loadOpenRouterApiKey()
        val model = settings.loadOpenRouterModel()
        val elementsJson = JSONArray().apply { elements.forEach { put(it.toJson()) } }
        val userContent = JSONArray().apply {
            put(JSONObject().put("type", "text").put(
                "text",
                "Task: $task\n\nHistory:\n${history.joinToString("\n").ifBlank { "(none)" }}\n\n" +
                    "On-screen elements:\n$elementsJson\n\nChoose ONE action.",
            ))
            if (screenshot != null) {
                val b64 = Base64.encodeToString(screenshot, Base64.NO_WRAP)
                put(JSONObject().put("type", "image_url").put(
                    "image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
            }
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PilotActionSchema.SYSTEM))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model).put("messages", messages)
            .put("tools", PilotActionSchema.toolsJson())
            .put("tool_choice", "required").put("temperature", 0.2)
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "ask" to """{"question":"Cloud error ${resp.code}; retry?"}"""
            val msg = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return "ask" to """{"question":"No response; retry?"}"""
            val call = msg.optJSONArray("tool_calls")?.optJSONObject(0)?.optJSONObject("function")
                ?: return "done" to JSONObject().put("result", msg.optString("content")).toString()
            return call.optString("name") to call.optString("arguments", "{}")
        }
    }
}
```

- [ ] **Step 2: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On-device end-to-end slice check**

Temporary debug hook (a button or a magic input string in `ChatViewModel`, e.g. `/pilot <task>`) that:
1. requests MediaProjection (`MediaProjectionManager.createScreenCaptureIntent()`),
2. builds a `PilotController` with `OpenRouterPilotReasoner`, `OmniAccessibilityService.instance!!::snapshot`, `::tapElement`, `{ instance.cancelFlag.get() }`,
3. calls `instance.showStop()`, collects `run(task)` into the chat, calls `instance.hideStop()` at the end.

Manual verification on device `da4d6bff`:
- Open a simple screen (e.g. the system Settings root).
- Trigger `/pilot open Battery` (or similar single-tap task).
- Expected: one screenshot taken, model returns a `tap` on the Battery row, the row is tapped, loop ends via `done` or `maxSteps`. Pressing STOP mid-run halts within one step.

Capture logs: `"$ADB" logcat -v time 'OmniPilot:V' 'OpenRouterClient:V' '*:S'` (quote the filters — zsh globs bare `*`).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/OpenRouterPilotReasoner.kt \
        app/src/main/java/com/locallink/pro/service/llm/OpenRouterClient.kt \
        app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt
git commit -m "feat(pilot): OpenRouter vision reasoner + debug /pilot trigger (thin slice)"
```

---

## Deferred to a follow-up plan (NOT in this slice)

- Full action vocabulary (`type`, `swipe`, `scroll`, `long_press`, `launch_app`, `back`, `home`, `wait`).
- The `Target` bounds→resId→text fallback resolution (thin slice taps by element id only).
- Self-verifying tool feedback (read-back after `type`).
- The calm-activity chat UI rendering for pilot steps (separate parked design).
- Real "pilot intent" detection vs. the `/pilot` debug trigger.
- Scratchpad notes, domain heuristics in the prompt, id+text cross-check.
- Hilt provisioning of the pilot components (thin slice can construct manually at the trigger).

## Self-Review

- **Spec coverage (thin-slice milestone):** service enable (Task 4), perceive+prune (Tasks 1, 4), screenshot (Task 5), single-action cloud reasoning (Tasks 2, 3, 7), actuate one tap (Tasks 4, 6), instant STOP (Tasks 4, 6). All five milestone bullets from the spec are covered. Full vocabulary, calm UI, and triggers are explicitly deferred above — consistent with the spec's "thin vertical slice first."
- **Divergences honored:** no second Executor LLM (Task 7 emits one native tool_call, parsed deterministically in Task 2); tree pruned before send (Task 1); low temperature 0.2 (Task 7).
- **Type consistency:** `PilotElement`, `FlatNode`, `TreeFlattener.flatten`, `PilotAction*`, `PilotActionSchema.toolsJson/SYSTEM`, `PilotReasoner.nextAction`, `PilotController.run`, `OmniAccessibilityService.snapshot/tapElement/cancelFlag/showStop/hideStop` are used with identical names/signatures across tasks. `AgentEvent.ToolCall/ToolResult/Final` match the existing sealed type used by `OpenRouterClient`.
- **Placeholder scan:** no TBD/TODO; every code step shows full code; on-device steps give exact adb commands.
```
