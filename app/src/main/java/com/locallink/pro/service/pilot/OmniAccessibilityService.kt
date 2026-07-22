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
