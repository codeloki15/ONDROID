# Planning Agent v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn OmniPro into a planning agent: the user types naturally (no `/pilot`), a planner produces a channel-routed todo plan, an executor runs it dispatching each todo to chat/composio/pilot, re-plans on failure, and pauses for user input via a soothing "User Input Requested" floating overlay.

**Architecture:** A new `PilotPlanner` (one cloud model call → `Plan` of `Todo`s tagged with a `channel` + `needsInput`) and a `PlanExecutor` (loops todos, dispatches by channel to the existing pilot/composio/chat paths, re-plans on stuck, and suspends on a `CompletableDeferred` for input). Input is collected via `InputRequestOverlay`, a `TYPE_ACCESSIBILITY_OVERLAY` view like the existing STOP button. Everything runs in the AccessibilityService's coroutine scope so it survives app-backgrounding. New `AgentEvent` variants carry plan/input events into the existing chat DB + UI.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, OkHttp, `org.json`, Android AccessibilityService + overlay, JUnit4 + kotlinx-coroutines-test.

## Global Constraints

- **minSdk 26, targetSdk 35, compileSdk 35** (from `app/build.gradle.kts`).
- **Cloud-only reasoning** via OpenRouter (reuse `OpenRouterPilotReasoner`'s HTTP/header pattern and `SettingsPreferences.loadOpenRouterApiKey()/loadOpenRouterModel()`).
- **No `/pilot` prefix** — the planner is the default send path, gated behind a settings flag (`agentMode`) so the old direct chat/`/pilot` paths remain as a fallback.
- **Loop runs in the service scope** — `OmniAccessibilityService.runPilotFlow` (already exists) keeps the executor + any paused input-wait alive across app switches.
- **Input pause suspends indefinitely** on a `CompletableDeferred<String?>` — no timeout, no polling; resolved by the overlay (answer) or cancelled by STOP.
- **v1 bounded** — total step cap 25; max 3 re-plans per run; one channel per todo; text+mic input only. 1000-step scaling is out of scope.
- **AgentEvent is a shared sealed type** — new variants must not break existing consumers (`ChatRepository.runEngine`, `persistPilotEvent`, `ChatScreen`).
- **Test style:** pure-logic in `app/src/test` as JUnit4 + hand-rolled fakes + `runTest` (match `PilotControllerTest`); device-dependent pieces verified on-device via `adb`. `org.json:json:20240303` is already a test dep.
- **Build/deploy:** `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`; `./gradlew :app:testDebugUnitTest`, `:app:installDebug`. ADB `$HOME/Library/Android/sdk/platform-tools/adb`, device `da4d6bff`. If a build fails at `transformDebugClassesWithAsm` (not a compile error), run `./gradlew --stop` then retry once; if `parseDebugLocalResources` fails on a `* 2.xml`-style name, `./gradlew clean` first.

---

## File Structure

New package files under `app/src/main/java/com/locallink/pro/service/pilot/`:
- `PilotPlan.kt` — `Channel` enum, `Todo`, `Plan` data classes + JSON (de)serialization; pure Kotlin.
- `PilotPlanner.kt` — `PlanSource` fun-interface + `OpenRouterPlanner` (task → Plan via a cloud call); the parse is pure/unit-tested, the HTTP is on-device.
- `InputRequestOverlay.kt` — the floating "User Input Requested" view (Android; on-device verified).
- `PlanExecutor.kt` — orchestrates todos → channels, pause/resume, re-plan; emits `AgentEvent`.

Modified:
- `app/src/main/java/com/locallink/pro/service/llm/AgentEvent.kt` — add `Plan`, `TodoStatus`, `InputRequested` variants.
- `app/src/main/java/com/locallink/pro/service/pilot/OmniAccessibilityService.kt` — host the input overlay + expose a resume hook.
- `app/src/main/java/com/locallink/pro/data/repository/ChatRepository.kt` — `runAgent(task)` entry + persist new events.
- `app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt` — route send through the planner when `agentMode` on.
- `app/src/main/java/com/locallink/pro/data/local/SettingsPreferences.kt` — `agentMode` flag.

Test files under `app/src/test/java/com/locallink/pro/service/pilot/`:
- `PilotPlanTest.kt`, `PilotPlannerTest.kt`, `PlanExecutorTest.kt`.

---

### Task 1: Plan / Todo / Channel model (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotPlan.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PilotPlanTest.kt`

**Interfaces:**
- Produces:
  - `enum class Channel { CHAT, COMPOSIO, PILOT }`
  - `data class Todo(val text: String, val channel: Channel, val needsInput: Boolean, val inputReason: String?)`
  - `data class Plan(val todos: List<Todo>)`
  - `object PlanJson { fun parse(json: String): Plan; fun channelOf(s: String): Channel }`
- `parse` accepts `{"todos":[{"text":..,"channel":"pilot","needs_input":true,"input_reason":".."}]}`; unknown/missing channel → `CHAT`; missing `needs_input` → false. Malformed JSON → empty Plan.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PilotPlanTest {
    @Test fun parsesTodosWithChannelsAndInput() {
        val json = """
            {"todos":[
              {"text":"Open Gmail","channel":"pilot","needs_input":false},
              {"text":"Sign in","channel":"pilot","needs_input":true,"input_reason":"your password"},
              {"text":"What is 2+2","channel":"chat"}
            ]}
        """.trimIndent()
        val plan = PlanJson.parse(json)
        assertEquals(3, plan.todos.size)
        assertEquals(Channel.PILOT, plan.todos[0].channel)
        assertTrue(plan.todos[1].needsInput)
        assertEquals("your password", plan.todos[1].inputReason)
        assertFalse(plan.todos[2].needsInput)
        assertEquals(Channel.CHAT, plan.todos[2].channel)
    }

    @Test fun unknownChannelDefaultsToChat() {
        assertEquals(Channel.CHAT, PlanJson.channelOf("banana"))
        assertEquals(Channel.COMPOSIO, PlanJson.channelOf("COMPOSIO"))
    }

    @Test fun malformedJsonIsEmptyPlan() {
        assertTrue(PlanJson.parse("not json").todos.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotPlanTest"`
Expected: FAIL — unresolved `Channel`, `Todo`, `Plan`, `PlanJson`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import org.json.JSONObject

enum class Channel { CHAT, COMPOSIO, PILOT }

data class Todo(
    val text: String,
    val channel: Channel,
    val needsInput: Boolean,
    val inputReason: String?,
)

data class Plan(val todos: List<Todo>)

object PlanJson {
    fun channelOf(s: String): Channel = when (s.trim().lowercase()) {
        "pilot" -> Channel.PILOT
        "composio" -> Channel.COMPOSIO
        else -> Channel.CHAT
    }

    fun parse(json: String): Plan {
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return Plan(emptyList())
        val arr = obj.optJSONArray("todos") ?: return Plan(emptyList())
        val todos = ArrayList<Todo>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val text = t.optString("text").trim()
            if (text.isEmpty()) continue
            todos.add(
                Todo(
                    text = text,
                    channel = channelOf(t.optString("channel")),
                    needsInput = t.optBoolean("needs_input", false),
                    inputReason = t.optString("input_reason").takeIf { it.isNotBlank() },
                )
            )
        }
        return Plan(todos)
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotPlanTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotPlan.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PilotPlanTest.kt
git commit -m "feat(agent): Plan/Todo/Channel model + JSON parse"
```

---

### Task 2: AgentEvent variants for plan + input

**Files:**
- Modify: `app/src/main/java/com/locallink/pro/service/llm/AgentEvent.kt`

**Interfaces:**
- Consumes: `Todo` (Task 1).
- Produces new `AgentEvent` members:
  - `data class Plan(val todos: List<com.locallink.pro.service.pilot.Todo>) : AgentEvent`
  - `data class TodoStatus(val index: Int, val text: String, val done: Boolean) : AgentEvent`
  - `data class InputRequested(val question: String, val reason: String?) : AgentEvent`
- Existing consumers (`ChatRepository.runEngine`, `persistPilotEvent`, `ChatScreen`) use exhaustive `when` over `AgentEvent`; each must gain a branch (Tasks 6/7) — but adding variants compiles first (Kotlin `when` on a sealed type without `else` will now warn/err at those sites, which Tasks 6/7 fix).

- [ ] **Step 1: Add the variants**

In `AgentEvent.kt`, inside the `sealed interface AgentEvent`, add after `Final`:
```kotlin
    /** The agent's plan (list of todos) — render it in the chat. */
    data class Plan(val todos: List<com.locallink.pro.service.pilot.Todo>) : AgentEvent

    /** A todo started (done=false) or finished (done=true). */
    data class TodoStatus(val index: Int, val text: String, val done: Boolean) : AgentEvent

    /** The agent needs user input; show the floating overlay and wait. */
    data class InputRequested(val question: String, val reason: String?) : AgentEvent
```

- [ ] **Step 2: Compile check (expect exhaustiveness errors at existing when-sites)**

Run: `./gradlew :app:compileDebugKotlin --console=plain 2>&1 | grep -E "when|AgentEvent|error:" | head`
Expected: compile errors at `ChatRepository` `when(event)` sites about non-exhaustive `when`. That is expected here — Tasks 6/7 add the branches. Note them; do NOT fix in this task.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/llm/AgentEvent.kt
git commit -m "feat(agent): AgentEvent.Plan/TodoStatus/InputRequested variants"
```

---

### Task 3: PilotPlanner — task → Plan (parse unit-tested)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PilotPlanner.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PilotPlannerTest.kt`

**Interfaces:**
- Consumes: `Plan`/`PlanJson` (Task 1), `SettingsPreferences`.
- Produces:
  - `fun interface PlanSource { suspend fun plan(task: String, context: String): Plan }`
  - `const val PLANNER_SYSTEM: String` (routing rules for chat/composio/pilot + needs_input tagging)
  - `class OpenRouterPlanner(settings: SettingsPreferences, http: OkHttpClient = …) : PlanSource`
- `OpenRouterPlanner.plan` POSTs one chat turn (system=`PLANNER_SYSTEM`, user=task+context) asking for the todos JSON, and returns `PlanJson.parse(content)`. On HTTP failure or empty → a single-`CHAT`-todo Plan of the raw task (so the app still responds).

- [ ] **Step 1: Write the failing test** (tests the fallback + that a fake PlanSource flows)

```kotlin
package com.locallink.pro.service.pilot

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PilotPlannerTest {
    @Test fun planSourceReturnsParsedPlan() = runTest {
        val fake = PlanSource { task, _ ->
            PlanJson.parse("""{"todos":[{"text":"$task","channel":"pilot"}]}""")
        }
        val plan = fake.plan("change wallpaper", "")
        assertEquals(1, plan.todos.size)
        assertEquals(Channel.PILOT, plan.todos[0].channel)
        assertEquals("change wallpaper", plan.todos[0].text)
    }

    @Test fun systemPromptNamesAllThreeChannels() {
        val p = PLANNER_SYSTEM.lowercase()
        assertEquals(true, p.contains("chat") && p.contains("composio") && p.contains("pilot"))
        assertEquals(true, p.contains("needs_input"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotPlannerTest"`
Expected: FAIL — unresolved `PlanSource`, `PLANNER_SYSTEM`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import com.locallink.pro.data.local.SettingsPreferences
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

fun interface PlanSource {
    /** @param context prior state (completed todos, current screen) for re-planning; "" at start. */
    suspend fun plan(task: String, context: String): Plan
}

const val PLANNER_SYSTEM = """
You are the planner for a phone assistant. Break the user's request into an ordered list of todos.
For EACH todo choose a channel:
- "chat": answerable in text, no external action (facts, math, explanations).
- "composio": acts in a connected cloud app via API (send/read email, post Slack, etc.).
- "pilot": requires operating the phone's on-screen UI (change a setting, use an app with no API,
  anything visual/local on the device).
Mark "needs_input": true with a short "input_reason" for any todo needing a secret, a choice only
the user can make, confirmation of a consequential/irreversible action, or info not on the device
(passwords, "which contact?", confirm a payment).
Respond with ONLY JSON: {"todos":[{"text":"..","channel":"chat|composio|pilot","needs_input":false,"input_reason":""}]}
Keep it minimal — as few todos as truly needed.
"""

class OpenRouterPlanner(
    private val settings: SettingsPreferences,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) : PlanSource {
    private val json = "application/json; charset=utf-8".toMediaType()

    override suspend fun plan(task: String, context: String): Plan {
        val key = settings.loadOpenRouterApiKey()
        val model = settings.loadOpenRouterModel()
        val fallback = Plan(listOf(Todo(task, Channel.CHAT, false, null)))
        if (key.isBlank()) return fallback
        val user = if (context.isBlank()) "Request: $task"
                   else "Request: $task\n\nProgress so far / current state:\n$context\n\nReplan the REMAINING todos."
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", PLANNER_SYSTEM))
            .put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", messages).put("temperature", 0.3)
        val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://omnipin.app").addHeader("X-Title", "OmniPin")
            .post(body.toString().toRequestBody(json)).build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return fallback
                val content = JSONObject(text).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
                val parsed = PlanJson.parse(extractJson(content))
                if (parsed.todos.isEmpty()) fallback else parsed
            }
        }.getOrDefault(fallback)
    }

    /** Pull the first {...} block out of a possibly fenced/markdown reply. */
    private fun extractJson(s: String): String {
        val start = s.indexOf('{'); val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PilotPlannerTest"`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PilotPlanner.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PilotPlannerTest.kt
git commit -m "feat(agent): PilotPlanner — task to channel-routed todo plan"
```

---

### Task 4: InputRequestOverlay + service pause/resume (on-device)

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/InputRequestOverlay.kt`
- Modify: `app/src/main/java/com/locallink/pro/service/pilot/OmniAccessibilityService.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces:
  - `class InputRequestOverlay(ctx: Context, onSubmit: (String) -> Unit, onCancel: () -> Unit)` with `fun show(question: String, reason: String?)` and `fun hide()`.
  - On `OmniAccessibilityService`: `suspend fun requestInput(question: String, reason: String?): String?` — shows the overlay and suspends on a `CompletableDeferred<String?>`; the overlay's submit completes it with the text, cancel/STOP completes it with null.

This is Android UI verified on-device (no unit test). The overlay is a `TYPE_ACCESSIBILITY_OVERLAY` `View` (same window type as `PilotOverlay`) containing a title "User Input Requested", the question text, an `EditText`, and Submit/Cancel buttons, with a soft pulsing alpha animation.

- [ ] **Step 1: Implement the overlay**

`InputRequestOverlay.kt`:
```kotlin
package com.locallink.pro.service.pilot

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** A soothing "User Input Requested" overlay drawn above every app (accessibility overlay). */
class InputRequestOverlay(
    private val ctx: Context,
    private val onSubmit: (String) -> Unit,
    private val onCancel: () -> Unit,
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var view: View? = null
    private var pulse: ObjectAnimator? = null

    fun show(question: String, reason: String?) {
        if (view != null) hide()
        val pad = (16 * ctx.resources.displayMetrics.density).toInt()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E2A"))
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(ctx).apply {
            text = "User Input Requested"
            setTextColor(Color.parseColor("#8AB4F8")); textSize = 16f
        })
        root.addView(TextView(ctx).apply {
            text = if (reason.isNullOrBlank()) question else "$question\n($reason)"
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        val field = EditText(ctx).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = "Type your answer…"
        }
        root.addView(field)
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(ctx).apply {
            text = "Cancel"; setOnClickListener { onCancel() }
        })
        row.addView(Button(ctx).apply {
            text = "Submit"; setOnClickListener { onSubmit(field.text.toString()) }
        })
        root.addView(row)

        val lp = WindowManager.LayoutParams(
            (300 * ctx.resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        wm.addView(root, lp)
        view = root
        pulse = ObjectAnimator.ofFloat(root, "alpha", 0.75f, 1f).apply {
            duration = 900; repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE; interpolator = LinearInterpolator(); start()
        }
    }

    fun hide() {
        pulse?.cancel(); pulse = null
        view?.let { runCatching { wm.removeView(it) } }; view = null
    }
}
```

- [ ] **Step 2: Add requestInput() to the service**

In `OmniAccessibilityService.kt`, add imports `import kotlinx.coroutines.CompletableDeferred` (already present) and a field + method:
```kotlin
    private var inputOverlay: InputRequestOverlay? = null

    /** Show the input floater and suspend until the user submits (returns text) or cancels (null). */
    suspend fun requestInput(question: String, reason: String?): String? {
        val deferred = CompletableDeferred<String?>()
        // Build on the main thread; WindowManager requires it.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            inputOverlay = InputRequestOverlay(
                this@OmniAccessibilityService,
                onSubmit = { text -> if (!deferred.isCompleted) deferred.complete(text) },
                onCancel = { if (!deferred.isCompleted) deferred.complete(null) },
            ).also { it.show(question, reason) }
        }
        val result = try { deferred.await() } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                inputOverlay?.hide(); inputOverlay = null
            }
        }
        return result
    }
```
Also in `onDestroy()` add: `inputOverlay?.hide()` before `super.onDestroy()`.

- [ ] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (existing `when(event)` errors from Task 2 are fixed in Tasks 6/7; if this task is built before those, temporarily expect them — build Task 4 after 6/7 if ordering matters, but Task 4 itself introduces no `when` changes). To validate Task 4 in isolation, `./gradlew :app:compileDebugKotlin` should show no errors originating from `InputRequestOverlay.kt` or the new `requestInput` method.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/InputRequestOverlay.kt \
        app/src/main/java/com/locallink/pro/service/pilot/OmniAccessibilityService.kt
git commit -m "feat(agent): InputRequestOverlay + service requestInput() suspend"
```

---

### Task 5: PlanExecutor — dispatch channels, pause, re-plan

**Files:**
- Create: `app/src/main/java/com/locallink/pro/service/pilot/PlanExecutor.kt`
- Test: `app/src/test/java/com/locallink/pro/service/pilot/PlanExecutorTest.kt`

**Interfaces:**
- Consumes: `Plan`/`Todo`/`Channel` (Task 1), `PlanSource` (Task 3), `AgentEvent` (Task 2).
- Produces:
  - `interface ChannelRunner { suspend fun chat(todo: String): String; suspend fun composio(todo: String): String; suspend fun pilot(todo: String): Boolean; suspend fun requestInput(question: String, reason: String?): String? }`
  - `class PlanExecutor(planner: PlanSource, runner: ChannelRunner, maxSteps: Int = 25, maxReplans: Int = 3)` with `fun run(task: String): Flow<AgentEvent>`.
- `run`: get initial plan → emit `Plan` → for each todo: if `needsInput`, `requestInput` (emit `InputRequested` first) and fold the answer into context; dispatch by channel; emit `TodoStatus` started/done; on a pilot todo returning false (stuck) → re-plan remaining (bounded) ; ends with `Final`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExecutorTest {
    private class FakeRunner(
        val onInput: (String) -> String? = { "answer" },
    ) : ChannelRunner {
        val calls = ArrayList<String>()
        override suspend fun chat(todo: String): String { calls.add("chat:$todo"); return "chat-reply" }
        override suspend fun composio(todo: String): String { calls.add("composio:$todo"); return "composio-ok" }
        override suspend fun pilot(todo: String): Boolean { calls.add("pilot:$todo"); return true }
        override suspend fun requestInput(question: String, reason: String?): String? {
            calls.add("input:$question"); return onInput(question)
        }
    }

    @Test fun runsEachTodoByChannel() = runTest {
        val planner = PlanSource { _, _ -> Plan(listOf(
            Todo("say hi", Channel.CHAT, false, null),
            Todo("email bob", Channel.COMPOSIO, false, null),
            Todo("open settings", Channel.PILOT, false, null),
        )) }
        val runner = FakeRunner()
        val events = PlanExecutor(planner, runner).run("do stuff").toList()
        assertEquals(listOf("chat:say hi", "composio:email bob", "pilot:open settings"), runner.calls)
        assertTrue(events.first() is AgentEvent.Plan)
        assertTrue(events.last() is AgentEvent.Final)
    }

    @Test fun pausesForInputOnNeedsInputTodo() = runTest {
        val planner = PlanSource { _, _ -> Plan(listOf(
            Todo("sign in", Channel.PILOT, true, "your password"),
        )) }
        val runner = FakeRunner(onInput = { "hunter2" })
        val events = PlanExecutor(planner, runner).run("login").toList()
        assertTrue(runner.calls.any { it.startsWith("input:") })
        assertTrue(events.any { it is AgentEvent.InputRequested })
    }

    @Test fun replansWhenPilotTodoGetsStuck() = runTest {
        var planCount = 0
        val planner = PlanSource { _, _ ->
            planCount++
            if (planCount == 1) Plan(listOf(Todo("stuck step", Channel.PILOT, false, null)))
            else Plan(listOf(Todo("recovered", Channel.CHAT, false, null)))
        }
        val runner = object : ChannelRunner {
            val calls = ArrayList<String>()
            override suspend fun chat(todo: String): String { calls.add("chat:$todo"); return "ok" }
            override suspend fun composio(todo: String) = "ok"
            override suspend fun pilot(todo: String): Boolean { calls.add("pilot:$todo"); return false } // stuck
            override suspend fun requestInput(question: String, reason: String?): String? = null
        }
        val events = PlanExecutor(planner, runner).run("x").toList()
        assertTrue("should have replanned", planCount >= 2)
        assertTrue(events.last() is AgentEvent.Final)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PlanExecutorTest"`
Expected: FAIL — unresolved `PlanExecutor`, `ChannelRunner`.

- [ ] **Step 3: Write minimal implementation**

```kotlin
package com.locallink.pro.service.pilot

import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface ChannelRunner {
    suspend fun chat(todo: String): String
    suspend fun composio(todo: String): String
    suspend fun pilot(todo: String): Boolean   // false = stuck/failed
    suspend fun requestInput(question: String, reason: String?): String?
}

class PlanExecutor(
    private val planner: PlanSource,
    private val runner: ChannelRunner,
    private val maxSteps: Int = 25,
    private val maxReplans: Int = 3,
) {
    fun run(task: String): Flow<AgentEvent> = flow {
        var plan = planner.plan(task, "")
        emit(AgentEvent.Plan(plan.todos))
        val done = ArrayList<String>()
        var steps = 0
        var replans = 0
        var i = 0
        while (i < plan.todos.size) {
            if (steps++ >= maxSteps) { emit(AgentEvent.Final("Paused after $maxSteps steps.")); return@flow }
            val todo = plan.todos[i]
            emit(AgentEvent.TodoStatus(i, todo.text, done = false))

            var answer: String? = null
            if (todo.needsInput) {
                emit(AgentEvent.InputRequested(todo.text, todo.inputReason))
                answer = runner.requestInput(todo.text, todo.inputReason)
                if (answer == null) { emit(AgentEvent.Final("Stopped — no input provided.")); return@flow }
            }

            val ok: Boolean = when (todo.channel) {
                Channel.CHAT -> { emit(AgentEvent.Token(runner.chat(todo.text))); true }
                Channel.COMPOSIO -> { runner.composio(todo.text); true }
                Channel.PILOT -> runner.pilot(if (answer != null) "${todo.text} [user said: $answer]" else todo.text)
            }

            if (ok) {
                emit(AgentEvent.TodoStatus(i, todo.text, done = true))
                done.add(todo.text)
                i++
            } else {
                if (replans++ >= maxReplans) { emit(AgentEvent.Final("Stopped — couldn't complete after re-planning.")); return@flow }
                val ctx = "Completed: ${done.joinToString("; ")}. Stuck on: ${todo.text}."
                plan = planner.plan(task, ctx)
                emit(AgentEvent.Plan(plan.todos))
                i = 0
            }
        }
        emit(AgentEvent.Final("Done."))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.PlanExecutorTest"`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/service/pilot/PlanExecutor.kt \
        app/src/test/java/com/locallink/pro/service/pilot/PlanExecutorTest.kt
git commit -m "feat(agent): PlanExecutor — channel dispatch, input pause, re-plan"
```

---

### Task 6: Wire the executor into ChatRepository + persist new events

**Files:**
- Modify: `app/src/main/java/com/locallink/pro/data/repository/ChatRepository.kt`

**Interfaces:**
- Consumes: `PlanExecutor`, `ChannelRunner`, `OpenRouterPlanner` (Tasks 3/5), `OmniAccessibilityService.requestInput` (Task 4), existing `openRouter.run` (Composio), the pilot loop, `persistPilotEvent`.
- Produces: `suspend fun runAgent(task: String)` — builds a real `ChannelRunner` (chat=one OpenRouter reply; composio=collect `openRouter.run`; pilot=collect the pilot `PilotController.run` returning true unless it ended stuck; requestInput=`service.requestInput`), runs `PlanExecutor(...).run(task)` in the service scope via `service.runPilotFlow`, and persists every `AgentEvent` (extend `persistPilotEvent` with the 3 new variants).

- [ ] **Step 1: Extend persistPilotEvent for the new variants**

In `persistPilotEvent(sessionId, event)`'s `when`, add branches:
```kotlin
            is AgentEvent.Plan -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "🗒 Plan:\n" + event.todos.mapIndexed { i, t ->
                    "${i + 1}. ${t.text} [${t.channel.name.lowercase()}]" +
                        if (t.needsInput) " (needs input)" else ""
                }.joinToString("\n"),
                timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.TodoStatus -> if (event.done) messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "✓ ${event.text}", timestamp = System.currentTimeMillis(),
            ))
            is AgentEvent.InputRequested -> messageDao.insert(MessageEntity(
                sessionId = sessionId, role = "system",
                text = "⌨ Input requested: ${event.question}", timestamp = System.currentTimeMillis(),
            ))
```

- [ ] **Step 2: Add runAgent()**

Add to `ChatRepository`:
```kotlin
    /** Planning-agent entry: plan → route todos to chat/composio/pilot → execute, with input pauses. */
    suspend fun runAgent(task: String) {
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(task, now)
        messageDao.insert(MessageEntity(sessionId = sessionId, role = "user", text = task, timestamp = now))
        touchSession(sessionId)
        val service = OmniAccessibilityService.instance
        if (service == null) {
            messageDao.insert(MessageEntity(sessionId = sessionId, role = "system",
                text = "Error: enable Omni accessibility service in Settings → Accessibility → Omni.",
                timestamp = System.currentTimeMillis()))
            touchSession(sessionId); return
        }
        _isAiResponding.value = true
        val runner = object : ChannelRunner {
            override suspend fun chat(todo: String): String {
                val sb = StringBuilder()
                openRouter.run(emptyList(), todo) { _, _ -> true }.collect { e ->
                    if (e is AgentEvent.Final) sb.append(e.text)
                }
                return sb.toString()
            }
            override suspend fun composio(todo: String): String {
                var out = ""
                openRouter.run(emptyList(), todo) { _, _ -> true }.collect { e ->
                    if (e is AgentEvent.Final) out = e.text
                    persistPilotEvent(sessionId, e)
                }
                return out
            }
            override suspend fun pilot(todo: String): Boolean {
                var stuck = false
                val controller = PilotController(
                    reasoner = OpenRouterPilotReasoner(settings),
                    actuator = service.asActuator(),
                    screenshot = { com.locallink.pro.service.pilot.PilotProjectionHolder.capture() },
                )
                controller.run(todo).collect { e ->
                    if (e is AgentEvent.Final && e.text.startsWith("Stopped")) stuck = true
                    persistPilotEvent(sessionId, e)
                }
                return !stuck
            }
            override suspend fun requestInput(question: String, reason: String?): String? =
                service.requestInput(question, reason)
        }
        val executor = PlanExecutor(OpenRouterPlanner(settings), runner)
        service.runPilotFlow(
            flow = executor.run(task),
            onEvent = { persistPilotEvent(sessionId, it) },
            onComplete = { _ -> _isAiResponding.value = false; touchSession(sessionId) },
        )
    }
```
(If `PilotController`/`OpenRouterPilotReasoner` imports aren't present, add them.)

- [ ] **Step 3: Compile check**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (the Task 2 `when` exhaustiveness errors are now resolved by the new branches).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/repository/ChatRepository.kt
git commit -m "feat(agent): ChatRepository.runAgent — executor in service scope + persist events"
```

---

### Task 7: Route send through the planner (agentMode flag) + UI

**Files:**
- Modify: `app/src/main/java/com/locallink/pro/data/local/SettingsPreferences.kt`
- Modify: `app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt`
- Modify: `app/src/main/java/com/locallink/pro/ui/screens/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatRepository.runAgent` (Task 6), new `AgentEvent` variants (rendered as system chips — the existing `ToolOrSystemChip` already renders `system` rows, so the emoji-prefixed rows show as notes; no new composable strictly required for v1).
- Produces: `SettingsPreferences.agentMode` (default true for v1) + `loadAgentMode()`; `ChatViewModel.sendMessage` routes through `runAgent` when agentMode on (and drops the `/pilot` special-case).

- [ ] **Step 1: Add the agentMode flag**

In `SettingsPreferences.kt`, mirror an existing boolean pref (e.g. `handsFree`): add a `KEY_AGENT_MODE`, an `agentMode: Flow<Boolean>` (default true), `suspend fun loadAgentMode(): Boolean = agentMode.first()`, and `suspend fun setAgentMode(v: Boolean)`.

- [ ] **Step 2: Route sendMessage through the planner**

In `ChatViewModel.sendMessage`, replace the `/pilot` block with agent routing:
```kotlin
        _uiState.update { it.copy(inputText = "", pendingImageUri = null) }
        viewModelScope.launch {
            if (imageUri == null && chatRepository.let { true } && settings.loadAgentMode()) {
                chatRepository.runAgent(messageText)
            } else {
                val bitmap: Bitmap? = imageUri?.let { imageService.loadForInference(it) }
                chatRepository.send(text = messageText, image = bitmap, imageUri = imageUri?.toString(), isVoice = isVoice)
            }
        }
```
Inject `SettingsPreferences` into `ChatViewModel` if not already present (constructor `@Inject`). Remove the now-unused `/pilot` parsing.

- [ ] **Step 3: Compile check + full pilot test suite**

Run: `./gradlew :app:testDebugUnitTest --tests "com.locallink.pro.service.pilot.*"`
Expected: BUILD SUCCESSFUL; all agent + pilot tests pass.

- [ ] **Step 4: On-device end-to-end**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug
```
Manual on device `da4d6bff`: type a plain message ("what is 2+2") → get a 1-todo chat answer. Type an action ("open wifi settings") → see a 🗒 Plan, then it launches Settings and navigates. Type a needs-input task → the "User Input Requested" floater appears, suspends, and resumes on submit. Verify STOP still cancels. Capture logs: `"$ADB" logcat -v time 'ChatRepository:V' 'OpenRouterClient:V' 'PilotProjection:V' '*:S'` (quote the `*`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/locallink/pro/data/local/SettingsPreferences.kt \
        app/src/main/java/com/locallink/pro/ui/screens/chat/ChatViewModel.kt \
        app/src/main/java/com/locallink/pro/ui/screens/chat/ChatScreen.kt
git commit -m "feat(agent): route send through planner (agentMode default), drop /pilot"
```

---

## Deferred to Phase 2 (NOT in v1)

- ≈1000-step horizon: context windowing, plan persistence across process death, completed-todo summarization, token-budget/cost control.
- Cross-channel single todos; preset-choice buttons in the floater; parallel/branching plans.
- A dedicated rich plan/todo Compose UI (v1 renders plan + progress as system chips).

## Self-Review

- **Spec coverage:** planner/routing (Task 3, 7), no-`/pilot` default path (Task 7), channel dispatch (Task 5, 6), re-plan on failure (Task 5), input anticipation tags (Task 1, 3) + live pause (Task 4, 5), floater UX (Task 4), suspend-until-answered (Task 4), events into chat (Task 2, 6), service-scope survival (Task 6). All spec sections map to a task.
- **Type consistency:** `Channel`/`Todo`/`Plan`/`PlanJson`, `PlanSource.plan`, `PLANNER_SYSTEM`, `ChannelRunner` (chat/composio/pilot/requestInput), `PlanExecutor.run`, `AgentEvent.Plan/TodoStatus/InputRequested`, `OmniAccessibilityService.requestInput/runPilotFlow/asActuator`, `PilotController(reasoner,actuator,screenshot)`, `PilotProjectionHolder.capture` — all used with identical names/signatures across tasks and match the existing code confirmed this session.
- **Placeholder scan:** no TBD/TODO; every code step has complete code; on-device steps give exact adb.
- **Ordering note:** Task 2 intentionally leaves a non-exhaustive-`when` compile error that Task 6 resolves; Tasks 3/4/5 (new files) compile independently. If an implementer builds strictly in order, the whole-module compile is green only after Task 6 — each task's own unit tests still run since they don't touch the repository `when`.
