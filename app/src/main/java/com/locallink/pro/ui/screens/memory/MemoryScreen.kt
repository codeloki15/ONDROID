package com.locallink.pro.ui.screens.memory

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.MemoryFactEntity
import com.locallink.pro.data.repository.MemoryStore
import com.locallink.pro.ui.components.GradientOrb
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MemoryViewModel @Inject constructor(
    private val store: MemoryStore,
) : ViewModel() {
    val facts: StateFlow<List<MemoryFactEntity>> =
        store.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun add(key: String, value: String) = viewModelScope.launch { store.remember(key, value) }
    fun delete(id: Long) = viewModelScope.launch { store.delete(id) }
}

/**
 * What Omni knows about you — persistent facts injected into every conversation
 * and plan. Add your own; facts Omni picked up from chat are marked "from chat".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    vm: MemoryViewModel = hiltViewModel(),
) {
    val facts by vm.facts.collectAsState()
    var adding by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Omni's memory") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Outlined.Add, "Add fact", tint = OmniText)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
    ) { pad ->
        if (facts.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                GradientOrb(size = 64.dp, glow = true)
                Spacer(Modifier.height(16.dp))
                Text("Nothing remembered yet", style = MaterialTheme.typography.titleLarge, color = OmniText)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tell Omni things like “remember my wife's number is …” in any chat, " +
                        "or add facts here with +. They're used in every conversation.",
                    style = MaterialTheme.typography.bodyMedium, color = OmniTextDim,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(facts, key = { it.id }) { f ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(OmniSurface)
                            .border(1.dp, OmniBorder, RoundedCornerShape(16.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    f.key.replace('_', ' '),
                                    style = MaterialTheme.typography.titleSmall, color = OmniText,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (f.source == "chat") {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "from chat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OmniTextFaint,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(PastelMint.copy(alpha = 0.5f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(f.value, style = MaterialTheme.typography.bodyMedium, color = OmniTextDim)
                        }
                        IconButton(onClick = { vm.delete(f.id) }) {
                            Icon(Icons.Outlined.DeleteOutline, "Forget", tint = OmniTextFaint, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        var key by remember { mutableStateOf("") }
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Remember a fact") },
            text = {
                Column {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it }, singleLine = true,
                        label = { Text("What is it? (e.g. wife's phone)") },
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = value, onValueChange = { value = it }, singleLine = true,
                        label = { Text("Value") },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { vm.add(key, value); adding = false },
                    enabled = key.isNotBlank() && value.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
            containerColor = OmniSurface2,
            titleContentColor = OmniText,
        )
    }
}
