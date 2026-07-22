package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom "duotone amber glass" icon set — each glyph is a solid amber base shape
 * plus a translucent frosted overlay offset on top, matching the project's icon
 * sheet. Built as [ImageVector]s (24dp grid) so they stay crisp at any size and
 * recolor via [base]/[over].
 *
 * Convention: [base] is the warm amber fill, [over] is the frosted grey/white
 * overlay (semi-transparent so the base shows through where they overlap).
 */
object OmniIcons {

    private const val GRID = 24f
    private val GlassOver = Color(0x80AEB4C2) // frosted cool-grey overlay (duotone top layer)

    private fun icon(name: String, builder: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = GRID, viewportHeight = GRID)
            .apply(builder).build()

    /** Paper-plane send (amber body + frosted upper wing). */
    fun send(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniSend") {
        // amber lower body
        path(fill = SolidColor(base)) {
            moveTo(3f, 11.5f); lineTo(20.5f, 4f); lineTo(13f, 12f); lineTo(20.5f, 20f); lineTo(3f, 12.5f);
            lineTo(9f, 12f); close()
        }
        // frosted upper wing
        path(fill = SolidColor(over)) {
            moveTo(20.5f, 4f); lineTo(9f, 12f); lineTo(13f, 12f); close()
        }
    }

    /** Two speech bubbles (amber front + frosted back) — messaging / empty state. */
    fun messaging(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniMessaging") {
        // frosted back bubble (upper right)
        path(fill = SolidColor(over)) {
            moveTo(11f, 4f); lineTo(20f, 4f); arcTo(2f, 2f, 0f, false, true, 22f, 6f); lineTo(22f, 12f)
            arcTo(2f, 2f, 0f, false, true, 20f, 14f); lineTo(18f, 14f); lineTo(18f, 17f); lineTo(14f, 14f)
            lineTo(11f, 14f); arcTo(2f, 2f, 0f, false, true, 9f, 12f); lineTo(9f, 6f)
            arcTo(2f, 2f, 0f, false, true, 11f, 4f); close()
        }
        // amber front bubble (lower left) with tail
        path(fill = SolidColor(base)) {
            moveTo(4f, 8f); lineTo(12f, 8f); arcTo(2f, 2f, 0f, false, true, 14f, 10f); lineTo(14f, 16f)
            arcTo(2f, 2f, 0f, false, true, 12f, 18f); lineTo(9f, 18f); lineTo(9f, 21.5f); lineTo(6f, 18f)
            lineTo(4f, 18f); arcTo(2f, 2f, 0f, false, true, 2f, 16f); lineTo(2f, 10f)
            arcTo(2f, 2f, 0f, false, true, 4f, 8f); close()
        }
    }

    /** Microphone (amber capsule + frosted stand/base). */
    fun mic(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniMic") {
        // frosted stand + base
        path(fill = SolidColor(over)) {
            moveTo(6f, 10.5f); lineTo(8f, 10.5f); lineTo(8f, 11.5f); arcTo(4f, 4f, 0f, false, false, 16f, 11.5f)
            lineTo(16f, 10.5f); lineTo(18f, 10.5f); lineTo(18f, 11.5f); arcTo(6f, 6f, 0f, false, true, 13f, 17.4f)
            lineTo(13f, 20f); lineTo(11f, 20f); lineTo(11f, 17.4f); arcTo(6f, 6f, 0f, false, true, 6f, 11.5f); close()
        }
        // amber capsule
        path(fill = SolidColor(base)) {
            moveTo(12f, 3f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11.5f)
            arcTo(3f, 3f, 0f, false, true, 9f, 11.5f); lineTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 12f, 3f); close()
        }
    }

    /** Image / mountains (amber sun + frosted peaks). */
    fun image(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniImage") {
        // amber sun
        path(fill = SolidColor(base)) { moveTo(16f, 9.5f); arcTo(2.4f, 2.4f, 0f, true, true, 15.99f, 9.49f); close() }
        // frosted mountains
        path(fill = SolidColor(over)) {
            moveTo(3f, 19f); lineTo(9.5f, 9f); lineTo(13.5f, 15f); lineTo(15.5f, 12.5f); lineTo(21f, 19f); close()
        }
        // amber baseline
        path(fill = SolidColor(base)) { moveTo(3f, 19f); lineTo(21f, 19f); lineTo(21f, 20.5f); lineTo(3f, 20.5f); close() }
    }

    /** Person (amber body + frosted head). */
    fun user(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniUser") {
        path(fill = SolidColor(over)) { moveTo(12f, 3.5f); arcTo(3.8f, 3.8f, 0f, true, true, 11.99f, 3.49f); close() }
        path(fill = SolidColor(base)) {
            moveTo(12f, 13f); arcTo(7f, 7f, 0f, false, true, 19f, 20f); lineTo(19f, 21f); lineTo(5f, 21f)
            lineTo(5f, 20f); arcTo(7f, 7f, 0f, false, true, 12f, 13f); close()
        }
    }

    /** Lightning bolt (amber + frosted half) — "Zap"/fast/accent mark. */
    fun zap(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniZap") {
        path(fill = SolidColor(base)) {
            moveTo(13f, 2f); lineTo(4f, 13f); lineTo(11f, 13f); lineTo(11f, 22f); lineTo(20f, 11f); lineTo(13f, 11f); close()
        }
        path(fill = SolidColor(over)) {
            moveTo(11f, 11f); lineTo(20f, 11f); lineTo(11f, 22f); close()
        }
    }

    /** Check in a rounded square (amber square + frosted tick). */
    fun check(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniCheck") {
        path(fill = SolidColor(base)) {
            moveTo(6f, 3.5f); lineTo(18f, 3.5f); arcTo(2.5f, 2.5f, 0f, false, true, 20.5f, 6f); lineTo(20.5f, 18f)
            arcTo(2.5f, 2.5f, 0f, false, true, 18f, 20.5f); lineTo(6f, 20.5f); arcTo(2.5f, 2.5f, 0f, false, true, 3.5f, 18f)
            lineTo(3.5f, 6f); arcTo(2.5f, 2.5f, 0f, false, true, 6f, 3.5f); close()
        }
        path(fill = SolidColor(over)) {
            moveTo(7.5f, 12.5f); lineTo(10.5f, 15.5f); lineTo(17f, 7f); lineTo(18.8f, 8.6f); lineTo(10.5f, 18f)
            lineTo(5.7f, 13.2f); close()
        }
    }

    /** Settings gear — amber gear + frosted center (for the "settings outside" entry). */
    fun settings(base: Color = OmniAccent, over: Color = GlassOver): ImageVector = icon("OmniSettings") {
        // amber gear body (octa-toothed)
        path(fill = SolidColor(base)) {
            moveTo(10.4f, 2.5f); lineTo(13.6f, 2.5f); lineTo(14.1f, 4.9f); lineTo(16.2f, 5.8f); lineTo(18.3f, 4.5f)
            lineTo(20.5f, 6.7f); lineTo(19.2f, 8.8f); lineTo(20.1f, 10.9f); lineTo(22.5f, 11.4f); lineTo(22.5f, 14.6f)
            lineTo(20.1f, 15.1f); lineTo(19.2f, 17.2f); lineTo(20.5f, 19.3f); lineTo(18.3f, 21.5f); lineTo(16.2f, 20.2f)
            lineTo(14.1f, 21.1f); lineTo(13.6f, 23.5f); lineTo(10.4f, 23.5f); lineTo(9.9f, 21.1f); lineTo(7.8f, 20.2f)
            lineTo(5.7f, 21.5f); lineTo(3.5f, 19.3f); lineTo(4.8f, 17.2f); lineTo(3.9f, 15.1f); lineTo(1.5f, 14.6f)
            lineTo(1.5f, 11.4f); lineTo(3.9f, 10.9f); lineTo(4.8f, 8.8f); lineTo(3.5f, 6.7f); lineTo(5.7f, 4.5f)
            lineTo(7.8f, 5.8f); lineTo(9.9f, 4.9f); close()
        }
        // frosted center hub
        path(fill = SolidColor(over)) { moveTo(12f, 8.2f); arcTo(3.8f, 3.8f, 0f, true, true, 11.99f, 8.19f); close() }
    }
}
