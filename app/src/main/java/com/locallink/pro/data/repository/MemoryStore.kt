package com.locallink.pro.data.repository

import android.util.Log
import com.locallink.pro.data.db.MemoryFactDao
import com.locallink.pro.data.db.MemoryFactEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent user facts ("wife_phone", "home_address", …) amalgamated across sessions.
 * [promptBlock] is injected into every model prompt; [maybeExtract] mines new facts from
 * user messages in the background so Omni stops re-asking for the same details.
 */
@Singleton
class MemoryStore @Inject constructor(
    private val dao: MemoryFactDao,
) {
    companion object {
        private const val TAG = "MemoryStore"
        private const val MAX_FACTS_IN_PROMPT = 24
        // Cheap gate so we don't pay an LLM call for every message. Extraction only
        // runs when the user plausibly stated a durable personal fact.
        private val TRIGGER = Regex(
            "\\bremember\\b|\\bmy \\w+[’']?s? (number|phone|name|address|email|birthday|" +
                "anniversary)\\b|\\bmy (wife|husband|mom|dad|mother|father|boss|home|work|office)\\b|" +
                "\\bi (live|work) (in|at)\\b|\\bmy name is\\b",
            RegexOption.IGNORE_CASE,
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun observeAll(): Flow<List<MemoryFactEntity>> = dao.observeAll()

    suspend fun remember(key: String, value: String, source: String = "user") {
        val k = key.trim().lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        if (k.isBlank() || value.isBlank()) return
        val now = System.currentTimeMillis()
        val existing = dao.byKey(k)
        dao.insert(
            MemoryFactEntity(
                id = existing?.id ?: 0, key = k, value = value.trim(), source = source,
                createdAt = existing?.createdAt ?: now, updatedAt = now,
            )
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clear() = dao.deleteAll()
    suspend fun count(): Int = dao.all().size

    /** "Known facts about the user" block for system prompts; "" when nothing is stored. */
    suspend fun promptBlock(): String {
        val facts = dao.all().take(MAX_FACTS_IN_PROMPT)
        if (facts.isEmpty()) return ""
        return buildString {
            append("\n\nKnown facts about the user (use them instead of asking again):\n")
            for (f in facts) append("- ${f.key.replace('_', ' ')}: ${f.value}\n")
        }
    }

    /**
     * Fire-and-forget fact mining from a user message. [llm] is any cheap completion
     * function (the plain chat call). Never blocks or fails the calling turn.
     */
    fun maybeExtract(userText: String, llm: suspend (String) -> String) {
        if (!TRIGGER.containsMatchIn(userText)) return
        scope.launch {
            runCatching {
                val reply = llm(
                    "Extract durable personal facts the user states about themselves or their " +
                        "world (contacts, numbers, addresses, names, preferences, dates). " +
                        "Ignore one-off requests and anything transient. Reply with ONLY JSON " +
                        "{\"facts\":[{\"key\":\"snake_case_key\",\"value\":\"the value\"}]} — " +
                        "empty list if none.\n\nUser message: \"$userText\""
                )
                val start = reply.indexOf('{'); val end = reply.lastIndexOf('}')
                if (start < 0 || end <= start) return@launch
                val arr = JSONObject(reply.substring(start, end + 1)).optJSONArray("facts") ?: return@launch
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    remember(o.optString("key"), o.optString("value"), source = "chat")
                }
                if (arr.length() > 0) Log.i(TAG, "extracted ${arr.length()} fact(s)")
            }.onFailure { Log.w(TAG, "extraction failed", it) }
        }
    }
}
