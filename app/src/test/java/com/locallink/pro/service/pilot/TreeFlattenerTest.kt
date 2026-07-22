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
