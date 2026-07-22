package com.locallink.pro.service.llm

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FunctionGemma tool-router (on-device path). Runs FIRST on each turn and decides whether a
 * tool is needed; the chat model (Qwen/cloud) only speaks when no tool is required or to phrase
 * a tool result. See the project_fg_router design.
 *
 * FunctionGemma is its OWN MediaPipe engine (separate `.task`, separate session). It is OPTIONAL:
 * [isAvailable] is false until a FunctionGemma `.task` is pushed to the device, in which case the
 * caller falls back to the keyword gate.
 *
 * FunctionGemma expects a "developer" role to enable function calling and emits Pythonic calls
 * wrapped in <start_function_call>…<end_function_call> (parsed by [FgToolParser]).
 */
@Singleton
class FgRouterEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
) {
    companion object {
        private const val TAG = "FgRouterEngine"
        private const val MAX_TOKENS = 1024          // router only emits a short call or nothing
        private const val DECIDE_TEMPERATURE = 0.1f  // deterministic tool decisions
        private const val DEVELOPER =
            "You are a function-calling router. If the user's request maps to one of the " +
            "available functions, emit exactly one function call. If it is ordinary conversation " +
            "or needs no function, output nothing."
    }

    @Volatile private var engine: LlmInference? = null
    private val genMutex = Mutex()

    fun isAvailable(): Boolean = modelManager.isFgReady()

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (engine != null) return true
        val file = modelManager.fgModelFile() ?: return false
        return try {
            engine = LlmInference.createFromOptions(
                context,
                LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(file.absolutePath)
                    .setMaxTokens(MAX_TOKENS)
                    .setPreferredBackend(LlmInference.Backend.CPU)
                    .build(),
            )
            Log.d(TAG, "FunctionGemma router loaded from ${file.absolutePath}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "FunctionGemma load failed", e)
            false
        }
    }

    /**
     * Decide whether [userText] needs a tool. [tools] is the merged OpenAI tools[] array
     * (local + Composio) from [ToolRouter.schemas]. Returns [FgToolParser.FgDecision.NoTool]
     * if the router is unavailable or errors (caller then routes to chat / keyword gate).
     */
    suspend fun decide(userText: String, tools: JSONArray): FgToolParser.FgDecision =
        withContext(Dispatchers.Default) {
            if (!ensureLoaded()) return@withContext FgToolParser.FgDecision.NoTool
            val eng = engine ?: return@withContext FgToolParser.FgDecision.NoTool
            val prompt = buildPrompt(userText, tools)

            genMutex.withLock {
                val session = LlmInferenceSession.createFromOptions(
                    eng,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder()
                        .setTopK(1)
                        .setTemperature(DECIDE_TEMPERATURE)
                        .build(),
                )
                val sb = StringBuilder()
                val done = CompletableDeferred<Unit>()
                try {
                    session.addQueryChunk(prompt)
                    session.generateResponseAsync { partial, isDone ->
                        sb.append(partial)
                        if (isDone) done.complete(Unit)
                    }
                    done.await()
                } catch (e: Throwable) {
                    Log.e(TAG, "decide failed", e)
                    return@withLock FgToolParser.FgDecision.NoTool
                } finally {
                    try { session.close() } catch (_: Exception) {}
                }
                val raw = sb.toString()
                Log.d(TAG, "FG raw: ${raw.take(300)}")
                FgToolParser.parse(raw)
            }
        }

    /**
     * Build FunctionGemma's EXACT prompt format (replicates its chat_template.jinja). The model
     * was trained on this precise syntax — a freeform tool list yields empty output. Tools come
     * as the merged OpenAI tools[] array (local + Composio); each is rendered as a
     * <start_function_declaration>declaration:NAME{...}<end_function_declaration> block.
     *
     *   <bos> is added by MediaPipe; we start at the developer turn.
     */
    private fun buildPrompt(userText: String, tools: JSONArray): String = buildString {
        append("<start_of_turn>developer\n")
        append(DEVELOPER)
        for (i in 0 until tools.length()) {
            val tool = tools.optJSONObject(i) ?: continue
            val fn = tool.optJSONObject("function") ?: continue
            if (fn.optString("name").isBlank()) continue
            append("<start_function_declaration>")
            append(formatDeclaration(fn))
            append("<end_function_declaration>")
        }
        append("<end_of_turn>\n")
        append("<start_of_turn>user\n").append(userText).append("<end_of_turn>\n")
        append("<start_of_turn>model\n")
    }

    /** declaration:NAME{description:<escape>..<escape>,parameters:{properties:{..},required:[..],type:<escape>OBJECT<escape>}} */
    private fun formatDeclaration(fn: JSONObject): String = buildString {
        append("declaration:").append(fn.optString("name"))
        append("{description:").append(esc(fn.optString("description")))
        val params = fn.optJSONObject("parameters")
        if (params != null) {
            append(",parameters:{")
            val props = params.optJSONObject("properties")
            if (props != null && props.length() > 0) {
                append("properties:{").append(formatParameters(props)).append("},")
            }
            val required = params.optJSONArray("required")
            if (required != null && required.length() > 0) {
                append("required:[")
                for (i in 0 until required.length()) {
                    if (i > 0) append(",")
                    append(esc(required.optString(i)))
                }
                append("],")
            }
            append("type:").append(esc(params.optString("type", "object").uppercase()))
            append("}")
        }
        append("}")
    }

    /** Render a JSON-schema "properties" map into FunctionGemma's argName:{description:..,type:..} form. */
    private fun formatParameters(props: JSONObject): String = buildString {
        val keys = props.keys().asSequence().toList()
        keys.forEachIndexed { idx, key ->
            if (idx > 0) append(",")
            val p = props.optJSONObject(key) ?: JSONObject()
            append(key).append(":{description:").append(esc(p.optString("description")))
            val type = p.optString("type", "string").uppercase()
            val enum = p.optJSONArray("enum")
            if (type == "STRING" && enum != null && enum.length() > 0) {
                append(",enum:[")
                for (i in 0 until enum.length()) { if (i > 0) append(","); append(esc(enum.optString(i))) }
                append("]")
            }
            append(",type:").append(esc(type)).append("}")
        }
    }

    private fun esc(s: String): String = "<escape>$s<escape>"

    fun shutdown() {
        try { engine?.close() } catch (_: Exception) {}
        engine = null
    }
}
