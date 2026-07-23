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
    val enabled: Boolean = true,
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
        if (!enabled) put("disabled", true)     // tapping does nothing until it activates
    }
}
