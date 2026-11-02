package com.mypro.minutes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.mypro.minutes.core.EntryType
import com.mypro.minutes.core.Meeting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    var confirmDelete by remember { mutableStateOf<Meeting?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    var confirmQuick by remember { mutableStateOf(false) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Meeting Minutes") }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = { confirmQuick = true },
                    icon = { Icon(painterResource(com.mypro.minutes.R.drawable.ic_mic), contentDescription = null) },
                    text = { Text("Record now") },
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                )
                ExtendedFloatingActionButton(
                    onClick = vm::newMeeting,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New meeting") },
                )
            }
        },
    ) { pad ->
        if (vm.meetings.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("No meetings yet.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "1. Add the participants and agenda topics.\n" +
                        "2. During the meeting tap who is speaking, then talk (live transcript) or type.\n" +
                        "3. Mark replies, questions, decisions and actions as they happen.\n" +
                        "4. End the meeting and share the .md transcript with your AI to draft the minutes.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 88.dp)) {
                items(vm.meetings, key = { it.id }) { m ->
                    ListItem(
                        headlineContent = { Text(m.title.ifBlank { "Untitled meeting" }) },
                        supportingContent = {
                            val decisions = m.entries.count { it.type == EntryType.DECISION }
                            val actions = m.entries.count { it.type == EntryType.ACTION }
                            Text("${formatDateTime(m.startedAt ?: m.createdAt)} · ${m.status.label} · ${m.entries.size} entries · $decisions decisions · $actions actions")
                        },
                        trailingContent = {
                            IconButton(onClick = { confirmDelete = m }) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
                        },
                        modifier = Modifier.clickable { vm.open(m) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmQuick) {
        ConfirmDialog(
            title = "Record now, hands-free?",
            message = "Recording starts when a voice is heard and keeps going in the background with the screen off. " +
                "By starting, you confirm everyone present has been told the meeting is being recorded. " +
                "You can fill in the title, people and notes afterwards.",
            confirm = "Start recording",
            onDismiss = { confirmQuick = false },
            onConfirm = { vm.quickStart() },
        )
    }

    confirmDelete?.let { m ->
        ConfirmDialog(
            title = "Delete meeting?",
            message = "\"${m.title.ifBlank { "Untitled meeting" }}\" and its audio will be removed from this phone. Exported files you already shared are not affected.",
            confirm = "Delete",
            onDismiss = { confirmDelete = null },
            onConfirm = { vm.delete(m) },
        )
    }
}
