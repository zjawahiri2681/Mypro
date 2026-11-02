package com.mypro.minutes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mypro.minutes.core.Attendance
import com.mypro.minutes.core.CaptureMode
import com.mypro.minutes.core.Participant

val LANGUAGES = listOf(
    "" to "Phone default",
    "en-US" to "English (US)",
    "en-GB" to "English (UK)",
    "ar-SA" to "Arabic (Saudi Arabia)",
    "ur-PK" to "Urdu",
    "hi-IN" to "Hindi",
    "fr-FR" to "French",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(vm: AppViewModel) {
    val m = vm.meeting ?: return
    var error by remember { mutableStateOf<String?>(null) }
    var addPerson by remember { mutableStateOf(false) }
    var editPerson by remember { mutableStateOf<Participant?>(null) }
    var newTopic by remember { mutableStateOf("") }
    val started = m.startedAt != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (started) "Meeting details" else "New meeting") },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(12.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); Spacer(Modifier.height(6.dp)) }
                    Button(
                        onClick = { if (started) vm.back() else error = vm.startMeeting() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (started) "Done" else "Start meeting") }
                }
            }
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(m.title, { t -> vm.updateMeeting { it.copy(title = t) }; error = null }, label = { Text("Title *") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(m.project, { t -> vm.updateMeeting { it.copy(project = t) } }, label = { Text("Project (e.g. PROJECT - Name)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(m.reference, { t -> vm.updateMeeting { it.copy(reference = t) } }, label = { Text("Reference (contract / specification)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(m.location, { t -> vm.updateMeeting { it.copy(location = t) } }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Section("Participants")
            if (m.participants.isEmpty()) Text("Add everyone expected, so you can tap who is speaking.", style = MaterialTheme.typography.bodySmall)
            for (p in m.participants) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.updateMeeting { it.copy(chairId = if (it.chairId == p.id) null else p.id) } }) {
                        Icon(Icons.Filled.Star, contentDescription = "Chair", tint = if (m.chairId == p.id) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("${p.id} · ${p.name}", style = MaterialTheme.typography.bodyLarge)
                        val sub = listOf(p.role, p.organization).filter { it.isNotBlank() }.joinToString(", ")
                        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                    }
                    Picker("", Attendance.entries.map { it to it.label }, p.attendance, { a -> vm.updateParticipant(p.copy(attendance = a)) }, Modifier.width(130.dp))
                    IconButton(onClick = { editPerson = p }) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
                    IconButton(onClick = { error = vm.removeParticipant(p.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
                }
            }
            OutlinedButton(onClick = { addPerson = true }) { Text("Add participant") }
            if (m.participants.isNotEmpty()) Text("★ marks the chair.", style = MaterialTheme.typography.bodySmall)

            Section("Agenda topics")
            m.topics.forEachIndexed { i, t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${t.title}", Modifier.weight(1f))
                    IconButton(onClick = { error = vm.removeTopic(t.id) }) { Icon(Icons.Filled.Close, contentDescription = "Remove topic") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newTopic, { newTopic = it }, label = { Text("New topic") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.addTopic(newTopic); newTopic = "" }, enabled = newTopic.isNotBlank()) { Text("Add") }
            }

            Section("Capture")
            for (mode in CaptureMode.entries) {
                Row(
                    Modifier.fillMaxWidth().selectable(selected = m.captureMode == mode, onClick = { vm.updateMeeting { it.copy(captureMode = mode) } }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = m.captureMode == mode, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(mode.label)
                        Text(
                            when (mode) {
                                CaptureMode.LIVE_TRANSCRIPT -> "Speech becomes text as people talk. Keep the screen on and the phone near the speakers."
                                CaptureMode.AUDIO_AND_NOTES -> "Records an audio file (works with the screen off). You type the key points; each is linked to its time in the audio."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (m.captureMode == CaptureMode.LIVE_TRANSCRIPT) {
                Picker("Speech language", LANGUAGES, m.language, { l -> vm.updateMeeting { it.copy(language = l) } }, Modifier.fillMaxWidth())
                CheckRow(
                    checked = m.preferOffline,
                    text = "Keep speech on this phone (offline recognition)",
                    sub = "Recommended for confidential meetings. Needs the offline language pack (Google app > Settings > Voice). If off, audio is sent to the phone's speech service (usually Google) to be transcribed.",
                    onChange = { v -> vm.updateMeeting { it.copy(preferOffline = v) } },
                )
            }

            Section("Consent")
            CheckRow(
                checked = m.consentConfirmed,
                text = "Everyone has been told this meeting is being recorded and transcribed",
                sub = "Recording people without telling them may be unlawful. This is noted in the transcript.",
                onChange = { v -> vm.updateMeeting { it.copy(consentConfirmed = v) }; error = null },
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (addPerson) {
        PersonDialog("Add participant", onDismiss = { addPerson = false }, onSave = { n, r, o -> vm.addParticipant(n, r, o) })
    }
    editPerson?.let { p ->
        PersonDialog(
            "Edit participant", p.name, p.role, p.organization,
            onDismiss = { editPerson = null },
            onSave = { n, r, o -> vm.updateParticipant(p.copy(name = n, role = r, organization = o)) },
        )
    }
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CheckRow(checked: Boolean, text: String, sub: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
        Column {
            Text(text)
            Text(sub, style = MaterialTheme.typography.bodySmall)
        }
    }
}
