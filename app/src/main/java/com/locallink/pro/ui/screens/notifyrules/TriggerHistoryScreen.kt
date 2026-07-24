package com.locallink.pro.ui.screens.notifyrules

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.locallink.pro.data.db.TriggerRunDao
import com.locallink.pro.data.db.TriggerRunEntity
import com.locallink.pro.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class TriggerHistoryViewModel @Inject constructor(
    private val dao: TriggerRunDao,
) : ViewModel() {
    val runs: StateFlow<List<TriggerRunEntity>> =
        dao.observeRecent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun clear() = viewModelScope.launch { dao.clear() }
}

/** Every trigger execution with its outcome — the automation audit trail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TriggerHistoryScreen(
    onBack: () -> Unit,
    vm: TriggerHistoryViewModel = hiltViewModel(),
) {
    val runs by vm.runs.collectAsState()

    Scaffold(
        containerColor = OmniBg,
        topBar = {
            TopAppBar(
                title = { Text("Trigger history") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = OmniText)
                    }
                },
                actions = {
                    if (runs.isNotEmpty()) IconButton(onClick = vm::clear) {
                        Icon(Icons.Outlined.DeleteSweep, "Clear history", tint = OmniTextDim)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = OmniBg, titleContentColor = OmniText,
                ),
            )
        },
    ) { pad ->
        if (runs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No trigger runs yet. When a trigger fires, it shows up here with its outcome.",
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
                items(runs, key = { it.id }) { run -> RunRow(run) }
            }
        }
    }
}

@Composable
private fun RunRow(run: TriggerRunEntity) {
    val (chipColor, chipText) = when (run.status) {
        "success" -> OmniSuccess to "Success"
        "failed" -> OmniError to "Failed"
        else -> AuroraViolet to "Running"
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(OmniSurface)
            .border(1.dp, OmniBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(chipColor))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                run.description,
                style = MaterialTheme.typography.titleSmall, color = OmniText,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(SimpleDateFormat("MMM d, HH:mm", Locale.US).format(Date(run.startedAt)))
                    if (run.detail.isNotBlank()) append(" · ${run.detail}")
                },
                style = MaterialTheme.typography.bodySmall, color = OmniTextFaint,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            chipText,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(chipColor)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
