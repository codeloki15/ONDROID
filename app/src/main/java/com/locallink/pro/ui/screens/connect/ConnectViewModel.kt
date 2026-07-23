package com.locallink.pro.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.local.SettingsPreferences
import com.locallink.pro.service.llm.ComposioApp
import com.locallink.pro.service.llm.ComposioClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConnectUiState(
    val hasKey: Boolean = false,
    /** The BYO Composio API key as typed (mirrors DataStore; edited on this screen). */
    val apiKey: String = "",
    /** Composio user id the connections are keyed by (default "default"). */
    val userId: String = "default",
    val loading: Boolean = false,
    val apps: List<ComposioApp> = emptyList(),
    val search: String = "",
    val error: String? = null,
    val connectingSlug: String? = null,
    val removingSlug: String? = null,
    /** Set when an OAuth URL should be opened in a Custom Tab. */
    val openUrl: String? = null,
)

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val composio: ComposioClient,
    private val settings: SettingsPreferences,
) : ViewModel() {

    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()

    private var pendingCaId: String? = null

    init {
        viewModelScope.launch {
            val key = settings.loadComposioApiKey()
            val user = settings.loadComposioUserId()
            _ui.update { it.copy(apiKey = key, userId = user, hasKey = key.isNotBlank()) }
            if (key.isNotBlank()) load()
        }
    }

    fun setApiKey(key: String) {
        _ui.update { it.copy(apiKey = key) }
        settings.saveComposioApiKey(key)
    }

    fun setUserId(id: String) {
        _ui.update { it.copy(userId = id) }
        settings.saveComposioUserId(id)
    }

    /**
     * Called from the setup card's CTA. Key/user-id saves are fire-and-forget (async DataStore)
     * — settle briefly so ComposioClient reads the fresh key, then flip [ConnectUiState.hasKey]
     * and fetch the app grid.
     */
    fun saveAndLoad() {
        viewModelScope.launch {
            delay(250)
            val has = settings.loadComposioApiKey().isNotBlank()
            _ui.update { it.copy(hasKey = has, error = if (has) null else "Paste a Composio API key first") }
            if (has) load()
        }
    }

    fun setSearch(q: String) {
        _ui.update { it.copy(search = q) }
    }

    fun load() {
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            composio.listApps(_ui.value.search).fold(
                onSuccess = { apps -> _ui.update { it.copy(apps = apps, loading = false) } },
                onFailure = { e -> _ui.update { it.copy(loading = false, error = e.message) } },
            )
        }
    }

    fun connect(app: ComposioApp) {
        if (app.connected || app.noAuth) return // no-auth toolkits need no connection
        viewModelScope.launch {
            _ui.update { it.copy(connectingSlug = app.slug, error = null) }
            composio.initiateConnect(app.slug).fold(
                onSuccess = { (caId, url) ->
                    pendingCaId = caId
                    _ui.update { it.copy(openUrl = url) }
                },
                onFailure = { e -> _ui.update { it.copy(connectingSlug = null, error = e.message) } },
            )
        }
    }

    /** Disconnect a connected app so it can be re-added. */
    fun disconnect(app: ComposioApp) {
        val caId = app.connectedAccountId ?: return
        viewModelScope.launch {
            _ui.update { it.copy(removingSlug = app.slug, error = null) }
            composio.disconnect(caId).fold(
                onSuccess = { _ui.update { it.copy(removingSlug = null) }; load() },
                onFailure = { e -> _ui.update { it.copy(removingSlug = null, error = e.message) } },
            )
        }
    }

    fun urlOpened() {
        _ui.update { it.copy(openUrl = null) }
    }

    /** Called on deep-link return AND on screen resume — poll until the pending account is ACTIVE. */
    fun pollPendingConnection() {
        val caId = pendingCaId ?: return
        viewModelScope.launch {
            repeat(30) {
                if (composio.isConnected(caId)) {
                    pendingCaId = null
                    _ui.update { it.copy(connectingSlug = null) }
                    load() // refresh badges
                    return@launch
                }
                delay(1000)
            }
            // gave up
            _ui.update { it.copy(connectingSlug = null) }
        }
    }
}
