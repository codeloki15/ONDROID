package com.locallink.pro.service.llm

import android.util.Log
import com.locallink.pro.data.local.SettingsPreferences
import com.pusher.client.Pusher
import com.pusher.client.PusherOptions
import com.pusher.client.channel.PrivateChannelEventListener
import com.pusher.client.channel.PusherEvent
import com.pusher.client.connection.ConnectionState
import com.pusher.client.util.HttpChannelAuthorizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** One trigger event delivered by Composio. */
data class ComposioTriggerEvent(
    /** Trigger type slug, e.g. GMAIL_NEW_GMAIL_MESSAGE. */
    val slug: String,
    /** Toolkit the event came from, e.g. "gmail". */
    val toolkit: String,
    /** The trigger instance (subscription) this fired for. */
    val instanceId: String,
    /** Full payload, for template substitution into an agent task. */
    val payload: JSONObject,
)

/**
 * Receives Composio trigger events on the device, over a WebSocket.
 *
 * Composio delivers triggers by webhook, which a phone can't receive — it has no public URL.
 * Its SDKs work around that with `triggers.subscribe()`, which opens a Pusher connection
 * instead, and that a phone CAN hold. This is the same path, spoken directly:
 *
 *  1. GET /api/v3/internal/sdk/realtime/credentials -> pusher_key, pusher_cluster, project_id
 *  2. connect to Pusher, authorising the private channel against Composio's own auth endpoint
 *  3. subscribe to `private-{project_id}_triggers`
 *  4. bind `trigger_to_client` (and its chunked variant for large payloads)
 *
 * CAVEAT worth remembering: those are `/internal/` endpoints and Composio documents subscribe()
 * as a prototyping path, preferring webhooks in production. They can change without notice, so
 * every failure here degrades quietly — triggers stop arriving, nothing else breaks.
 */
@Singleton
class ComposioRealtimeClient @Inject constructor(
    private val settings: SettingsPreferences,
) {
    companion object {
        private const val TAG = "ComposioRealtime"
        private const val BASE = "https://backend.composio.dev"
        private const val CREDENTIALS_URL = "$BASE/api/v3/internal/sdk/realtime/credentials"
        private const val AUTH_URL = "$BASE/api/v3/internal/sdk/realtime/auth"
        private const val EVENT = "trigger_to_client"
        private const val EVENT_CHUNKED = "chunked-trigger_to_client"
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var pusher: Pusher? = null

    /** Reassembly buffer for `chunked-trigger_to_client`, keyed by the payload's id. */
    private val chunks = HashMap<String, StringBuilder>()

    val isConnected: Boolean
        get() = pusher?.connection?.state == ConnectionState.CONNECTED

    /**
     * Connect and start delivering events to [onEvent]. Idempotent; safe to call repeatedly.
     * Returns false when there's no key, the credentials call fails, or the socket won't open.
     */
    suspend fun connect(onEvent: (ComposioTriggerEvent) -> Unit): Boolean = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext true
        val key = settings.loadComposioApiKey()
        if (key.isBlank()) return@withContext false

        val creds = fetchCredentials(key) ?: return@withContext false
        val (pusherKey, cluster, projectId) = creds

        runCatching {
            disconnect()
            // Composio authorises the private channel itself; the API key travels as a header,
            // never in the channel name or the query string.
            //
            // ONLY x-api-key — do not set Content-Type. The authorizer posts socket_id and
            // channel_name form-encoded, so declaring application/json makes Composio try to
            // parse form data as JSON and the auth request fails.
            val authorizer = HttpChannelAuthorizer(AUTH_URL).apply {
                setHeaders(mapOf("x-api-key" to key))
            }
            val options = PusherOptions().setCluster(cluster).setChannelAuthorizer(authorizer)
            val client = Pusher(pusherKey, options)
            pusher = client
            client.connect()

            val channelName = "private-${projectId}_triggers"
            val listener = object : PrivateChannelEventListener {
                override fun onEvent(event: PusherEvent) {
                    runCatching { handle(event, onEvent) }
                        .onFailure { Log.w(TAG, "bad trigger event", it) }
                }
                override fun onSubscriptionSucceeded(channelName: String) {
                    Log.i(TAG, "subscribed to $channelName")
                }
                override fun onAuthenticationFailure(message: String, e: Exception?) {
                    Log.w(TAG, "channel auth failed: $message", e)
                }
            }
            val channel = client.subscribePrivate(channelName, listener)
            channel.bind(EVENT, listener)
            channel.bind(EVENT_CHUNKED, listener)
            Log.i(TAG, "connecting to $channelName (cluster=$cluster)")
            true
        }.onFailure { Log.w(TAG, "realtime connect failed", it) }.getOrDefault(false)
    }

    fun disconnect() {
        runCatching { pusher?.disconnect() }
        pusher = null
        synchronized(chunks) { chunks.clear() }
    }

    /** Pusher key/cluster/project for this account, or null if the internal endpoint moved. */
    private fun fetchCredentials(key: String): Triple<String, String, String>? = runCatching {
        val req = Request.Builder().url(CREDENTIALS_URL).addHeader("x-api-key", key).get().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.w(TAG, "credentials HTTP ${resp.code}: ${text.take(160)}")
                return@runCatching null
            }
            val o = JSONObject(text)
            val pk = o.optString("pusher_key")
            val cluster = o.optString("pusher_cluster")
            val project = o.optString("project_id")
            if (pk.isBlank() || cluster.isBlank() || project.isBlank()) {
                Log.w(TAG, "credentials missing fields: ${text.take(160)}")
                return@runCatching null
            }
            Triple(pk, cluster, project)
        }
    }.onFailure { Log.w(TAG, "credentials fetch failed", it) }.getOrNull()

    /**
     * Turn one Pusher frame into a [ComposioTriggerEvent].
     *
     * Large payloads arrive split across `chunked-trigger_to_client` frames carrying an id, an
     * index and a final flag; those are buffered until the last chunk lands.
     */
    private fun handle(event: PusherEvent, onEvent: (ComposioTriggerEvent) -> Unit) {
        val raw = event.data ?: return
        val body = JSONObject(raw)

        val assembled: JSONObject = if (event.eventName == EVENT_CHUNKED) {
            val id = body.optString("id")
            if (id.isBlank()) return
            val part = body.optString("chunk")
            val complete = synchronized(chunks) {
                val sb = chunks.getOrPut(id) { StringBuilder() }
                sb.append(part)
                if (!body.optBoolean("final", false)) null else chunks.remove(id)?.toString()
            } ?: return
            JSONObject(complete)
        } else body

        // The payload nests the actual event under a few possible keys depending on trigger type.
        val data = assembled.optJSONObject("payload")
            ?: assembled.optJSONObject("data")
            ?: assembled
        val slug = assembled.optString("triggerSlug")
            .ifBlank { assembled.optString("trigger_slug") }
            .ifBlank { assembled.optString("type") }
        val toolkit = assembled.optString("toolkitSlug")
            .ifBlank { assembled.optString("toolkit_slug") }
            .ifBlank { slug.substringBefore('_').lowercase() }
        val instance = assembled.optString("triggerId")
            .ifBlank { assembled.optString("trigger_id") }
            .ifBlank { assembled.optString("id") }

        if (slug.isBlank() && instance.isBlank()) {
            Log.w(TAG, "unrecognised trigger frame: ${raw.take(200)}")
            return
        }
        Log.i(TAG, "trigger event $slug ($toolkit)")
        onEvent(ComposioTriggerEvent(slug, toolkit, instance, data))
    }
}
