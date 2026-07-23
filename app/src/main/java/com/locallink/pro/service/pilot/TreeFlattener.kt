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
    val enabled: Boolean get() = true
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
                        enabled = n.enabled,
                    )
                )
            }
            n.children.forEach { walk(it) }
        }
        walk(root)
        return out
    }
}
