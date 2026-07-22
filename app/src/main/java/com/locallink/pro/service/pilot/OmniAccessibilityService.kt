package com.locallink.pro.service.pilot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.locallink.pro.service.llm.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicBoolean

class OmniAccessibilityService : AccessibilityService() {

    val cancelFlag = AtomicBoolean(false)
    private var overlay: PilotOverlay? = null

    // A scope tied to the SERVICE, not the UI. The Pilot loop runs here so it SURVIVES the app
    // going to the background — moving to another app used to cancel viewModelScope and kill the
    // loop ("stops as soon as it leaves the chat screen"). The accessibility service is long-lived.
    private val pilotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Run a Pilot [flow] to completion in the service's own scope, forwarding each [AgentEvent]
     * to [onEvent]. Returns immediately; the loop keeps running even if the chat UI backgrounds.
     * [onComplete] is always invoked at the end (normal finish, error, or cancel) with the error
     * if one occurred — so callers can reset UI state and surface failures even when no Final
     * event was emitted.
     */
    fun runPilotFlow(
        flow: Flow<AgentEvent>,
        onEvent: suspend (AgentEvent) -> Unit,
        onComplete: suspend (Throwable?) -> Unit = {},
    ) {
        showStop()
        flow.onEach { onEvent(it) }
            .onCompletion { cause -> hideStop(); onComplete(cause) }
            .launchIn(pilotScope)
    }

    override fun onServiceConnected() {
        instance = this
        overlay = PilotOverlay(this) { cancelFlag.set(true); hideStop() }
    }

    override fun onDestroy() {
        instance = null
        pilotScope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* perception is pull-based */ }
    override fun onInterrupt() {}

    fun showStop() { cancelFlag.set(false); overlay?.show() }
    fun hideStop() { overlay?.hide() }

    /** Expose this service to the Pilot loop as a [PilotActuator]. */
    fun asActuator(): PilotActuator = object : PilotActuator {
        override fun perceive() = snapshot()
        override suspend fun tap(e: PilotElement) = tapElement(e)
        override suspend fun longPress(e: PilotElement) = this@OmniAccessibilityService.longPress(e)
        override suspend fun doubleTap(e: PilotElement) = this@OmniAccessibilityService.doubleTap(e)
        override suspend fun drag(from: PilotElement, to: PilotElement) = this@OmniAccessibilityService.drag(from, to)
        override suspend fun type(e: PilotElement, text: String) = typeText(e, text)
        override fun clear(e: PilotElement) = clearText(e)
        override suspend fun swipe(direction: String) = this@OmniAccessibilityService.swipe(direction)
        override fun launchApp(app: String) = this@OmniAccessibilityService.launchApp(app)
        override fun back() = goBack()
        override fun home() = goHome()
        override fun recents() = openRecents()
        override fun notifications() = openNotifications()
        override fun quickSettings() = openQuickSettings()
        override fun cancelled() = cancelFlag.get()
    }

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
        return strokeGesture(Path().apply { moveTo(cx, cy); lineTo(cx, cy) }, durationMs = 60)
    }

    /** Type [text] into an editable node matching [element] via ACTION_SET_TEXT. */
    fun typeText(element: PilotElement, text: String): Boolean {
        if (cancelFlag.get()) return false
        val node = findNode(element) ?: return false
        val args = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Swipe/scroll across the screen center in a direction. [dir] = up|down|left|right. */
    suspend fun swipe(dir: String): Boolean {
        if (cancelFlag.get()) return false
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        val cx = w / 2f; val cy = h / 2f
        // A swipe UP moves content up (reveals what's below) — start low, end high.
        val (sx, sy, ex, ey) = when (dir) {
            "up" -> listOf(cx, h * 0.75f, cx, h * 0.25f)
            "down" -> listOf(cx, h * 0.25f, cx, h * 0.75f)
            "left" -> listOf(w * 0.8f, cy, w * 0.2f, cy)
            "right" -> listOf(w * 0.2f, cy, w * 0.8f, cy)
            else -> return false
        }
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        return strokeGesture(path, durationMs = 300)
    }

    /** Long-press the center of an element (opens context menus). */
    suspend fun longPress(e: PilotElement): Boolean {
        if (cancelFlag.get()) return false
        val cx = ((e.bounds[0] + e.bounds[2]) / 2).toFloat()
        val cy = ((e.bounds[1] + e.bounds[3]) / 2).toFloat()
        return strokeGesture(Path().apply { moveTo(cx, cy); lineTo(cx, cy) }, durationMs = 800)
    }

    /** Double-tap an element (two quick taps). */
    suspend fun doubleTap(e: PilotElement): Boolean {
        if (cancelFlag.get()) return false
        return tapElement(e) && tapElement(e)
    }

    /** Drag from one element to another (press-hold-move-release). */
    suspend fun drag(from: PilotElement, to: PilotElement): Boolean {
        if (cancelFlag.get()) return false
        val sx = ((from.bounds[0] + from.bounds[2]) / 2).toFloat()
        val sy = ((from.bounds[1] + from.bounds[3]) / 2).toFloat()
        val ex = ((to.bounds[0] + to.bounds[2]) / 2).toFloat()
        val ey = ((to.bounds[1] + to.bounds[3]) / 2).toFloat()
        // Slow stroke so the system treats it as a drag, not a fling.
        return strokeGesture(Path().apply { moveTo(sx, sy); lineTo(ex, ey) }, durationMs = 700)
    }

    /** Clear the text of an editable element. */
    fun clearText(e: PilotElement): Boolean = typeText(e, "")

    /** System Back. */
    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Home screen. */
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    /** App switcher / Overview. */
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Pull down the notification shade. */
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /** Pull down the quick-settings panel. */
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    /** Launch an app by display name or package. Robust to package-visibility limits. */
    fun launchApp(query: String): Boolean {
        val pm = packageManager
        val q = query.trim().lowercase()

        // 1) Query the actual launcher entries (respects visibility better than getInstalledApplications).
        val launchers = runCatching {
            val main = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(main, 0)
        }.getOrDefault(emptyList())

        val byLabel = launchers.map { it to pm.getApplicationLabel(it.activityInfo.applicationInfo).toString().lowercase() }
        // exact label, then startsWith, then contains — most specific first.
        val hit = byLabel.firstOrNull { it.second == q }
            ?: byLabel.firstOrNull { it.second.startsWith(q) }
            ?: byLabel.firstOrNull { it.second.contains(q) }

        if (hit != null) {
            val ai = hit.first.activityInfo
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
                .setClassName(ai.packageName, ai.name)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return true
        }

        // 2) Common system screens by action (Settings etc.) — most reliable for "open settings".
        knownActionFor(q)?.let { action ->
            val intent = android.content.Intent(action).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent); true }.getOrDefault(false)) return true
        }

        // 3) Treat the query as a package name directly.
        pm.getLaunchIntentForPackage(query)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            return runCatching { startActivity(it); true }.getOrDefault(false)
        }
        return false
    }

    /** Map common app/screen names to a system Intent action (device-independent). */
    private fun knownActionFor(q: String): String? = when {
        q.contains("setting") -> android.provider.Settings.ACTION_SETTINGS
        q.contains("battery") -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
        q.contains("wifi") || q.contains("wi-fi") -> android.provider.Settings.ACTION_WIFI_SETTINGS
        q.contains("bluetooth") -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
        q.contains("display") -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
        else -> null
    }

    private suspend fun strokeGesture(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val done = CompletableDeferred<Boolean>()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done.complete(true) }
            override fun onCancelled(d: GestureDescription?) { done.complete(false) }
        }, null)
        return if (!ok) false else done.await()
    }

    /** Find the live AccessibilityNodeInfo whose bounds match a perceived element (for typing). */
    private fun findNode(element: PilotElement): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val want = Rect(element.bounds[0], element.bounds[1], element.bounds[2], element.bounds[3])
        val stack = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (stack.isNotEmpty()) {
            val n = stack.removeLast()
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (r == want && n.isEditable) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { stack.add(it) }
        }
        return null
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
