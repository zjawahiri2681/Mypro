package com.mypro.minutes.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mypro.minutes.R
import com.mypro.minutes.core.Attendance
import com.mypro.minutes.core.CaptureMode
import com.mypro.minutes.core.Entry
import com.mypro.minutes.core.EntryType
import com.mypro.minutes.core.Meeting
import com.mypro.minutes.core.MeetingOps
import com.mypro.minutes.core.Source
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(vm: AppViewModel) {
    val m = vm.meeting ?: return
    val context = LocalContext.current
    val rec by vm.recorder.collectAsStateWithLifecycle()

    // The recogniser only works while the app is in front and the screen is on.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.stopListening() }

    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startListening() else vm.problem = Problem("Microphone permission was refused. You can still type entries.", false)
    }
    val recordPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
        else vm.problem = Problem("Microphone permission was refused, so audio cannot be recorded. You can still type entries.", false)
    }
    fun toggleMic() {
        if (vm.listening || hasMic()) vm.toggleListening() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun startRecording() {
        if (hasMic()) vm.startRecording()
        else recordPermission.launch(
            if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            else arrayOf(Manifest.permission.RECORD_AUDIO),
        )
    }

    // Automatic mode: begin hands-free recording as soon as the meeting opens (once), via the
    // permission flow above. If it was already recorded and stopped, don't silently restart.
    LaunchedEffect(m.id) {
        if (m.captureMode == CaptureMode.AUTOMATIC && !rec.active && m.audioFiles.isEmpty()) startRecording()
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }

    var showEnd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Entry?>(null) }
    var addSpeaker by remember { mutableStateOf(false) }
    var addTopic by remember { mutableStateOf(false) }
    var pickReply by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val elapsed = clock(now - (m.startedAt ?: now))
                        val status = when {
                            m.captureMode.recordsAudio && rec.waitingForVoice -> "● Listening for voice"
                            m.captureMode.recordsAudio && rec.recording ->
                                if (rec.paused) "Recording paused" else "● Recording ${clock(rec.positionMs())}"
                            m.captureMode == CaptureMode.LIVE_TRANSCRIPT && vm.listening -> "● Listening"
                            m.captureMode == CaptureMode.LIVE_TRANSCRIPT -> "Not listening"
                            else -> "Not recording"
                        }
                        Text("$elapsed · ${m.entries.size} entries · $status", style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = { IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back (meeting stays open)") } },
                actions = {
                    IconButton(onClick = vm::editDetails) { Icon(Icons.Filled.Edit, contentDescription = "Meeting details") }
                    TextButton(onClick = { showEnd = true }) { Text("End") }
                },
            )
        },
        bottomBar = {
            Composer(
                vm = vm, m = m,
                onAddSpeaker = { addSpeaker = true },
                onPickReply = { pickReply = true },
                onMic = ::toggleMic,
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // Topic selector.
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(selected = vm.topicId == null, onClick = { vm.selectTopic(null) }, label = { Text("General") }) }
                items(m.topics, key = { it.id }) { t ->
                    FilterChip(selected = vm.topicId == t.id, onClick = { vm.selectTopic(t.id) }, label = { Text(t.title, maxLines = 1) })
                }
                item { AssistChip(onClick = { addTopic = true }, label = { Text("Topic") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }) }
            }

            vm.problem?.let { p ->
                Card(
                    Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(p.message, color = MaterialTheme.colorScheme.onErrorContainer)
                        Row {
                            if (p.offerOnline) TextButton(onClick = vm::allowOnlineAndRetry) { Text("Allow online recognition") }
                            TextButton(onClick = { vm.problem = null }) { Text("Dismiss") }
                        }
                    }
                }
            }
            rec.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) }

            if (m.captureMode.recordsAudio) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!rec.active) {
                            Button(onClick = ::startRecording) {
                                Icon(painterResource(R.drawable.ic_record), null, Modifier.size(18.dp), tint = Color(0xFFE53935))
                                Spacer(Modifier.width(6.dp))
                                Text(if (m.audioFiles.isEmpty()) "Start recording" else "Record new segment")
                            }
                        } else {
                            OutlinedButton(onClick = vm::pauseOrResumeRecording, enabled = rec.recording) {
                                Icon(painterResource(if (rec.paused) R.drawable.ic_record else R.drawable.ic_pause), null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (rec.paused) "Resume" else "Pause")
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                when {
                                    rec.waitingForVoice -> "Listening… recording starts when a voice is heard"
                                    rec.paused -> "Paused at ${clock(rec.positionMs())}"
                                    else -> "Recording ${clock(rec.positionMs())}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    if (rec.active && !rec.paused) {
                        LinearProgressIndicator(
                            progress = { rec.level / 100f },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            val listState = rememberLazyListState()
            val lastText = m.entries.lastOrNull()?.text
            LaunchedEffect(m.entries.size, lastText, vm.partial.isNotEmpty()) {
                val count = m.entries.size + if (vm.partial.isNotEmpty()) 1 else 0
                if (count > 0) listState.animateScrollToItem(count - 1)
            }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                if (m.entries.isEmpty() && vm.partial.isEmpty()) {
                    item {
                        Text(
                            when (m.captureMode) {
                                CaptureMode.LIVE_TRANSCRIPT ->
                                    "Tap who is speaking, then the microphone. When the voice changes, tap the new speaker. Use Reply / Question / Decision / Action to label what is said. Tap any entry to correct it."
                                CaptureMode.AUTOMATIC ->
                                    "Recording hands-free in the background — you can lock the screen. It keeps the audio; type key points now or leave it and fill in the title, people and notes after the meeting. Each note you type is linked to its time in the audio."
                                CaptureMode.AUDIO_AND_NOTES ->
                                    "Start the recording, then type key points. Tap who said it and label it Reply / Question / Decision / Action. Each note is linked to its time in the audio."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(m.entries, key = { it.id }) { e ->
                    EntryCard(m, e, onEdit = { editing = e }, onReply = { vm.replyTo(e) })
                }
                if (vm.partial.isNotEmpty()) {
                    item {
                        Text(
                            "${speakerName(m, vm.speakerId)}: ${vm.partial}",
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }

    if (showEnd) {
        var next by remember { mutableStateOf(m.nextMeeting) }
        AlertDialog(
            onDismissRequest = { showEnd = false },
            title = { Text("End meeting?") },
            text = {
                Column {
                    Text("Listening and recording stop. You can reopen the meeting later.")
                    OutlinedTextField(next, { next = it }, label = { Text("Next meeting (optional)") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { showEnd = false; vm.endMeeting(next) }) { Text("End meeting") } },
            dismissButton = { TextButton(onClick = { showEnd = false }) { Text("Cancel") } },
        )
    }
    if (addSpeaker) {
        PersonDialog("Add participant", onDismiss = { addSpeaker = false }, onSave = { n, r, o ->
            val err = vm.addParticipant(n, r, o)
            if (err == null) vm.meeting?.participants?.lastOrNull()?.let { vm.selectSpeaker(it.id) }
            err
        })
    }
    if (addTopic) {
        TextDialog("Add topic", "Topic", onDismiss = { addTopic = false }, onConfirm = { vm.addTopic(it) })
    }
    if (pickReply) {
        ReplyPicker(m, candidates = m.entries, current = vm.replyToId, onDismiss = { pickReply = false }, onPick = { vm.setReplyTarget(it) })
    }
    // Always edit the latest version (speech may have appended to it since the tap).
    editing?.let { e -> m.entry(e.id) }?.let { latest ->
        EditEntryDialog(vm, m, latest, onDismiss = { editing = null })
    }
}

@Composable
private fun Composer(vm: AppViewModel, m: Meeting, onAddSpeaker: () -> Unit, onPickReply: () -> Unit, onMic: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)).padding(vertical = 6.dp)) {
            // Who is speaking.
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(m.participants.filter { it.attendance == Attendance.PRESENT }, key = { it.id }) { p ->
                    FilterChip(selected = vm.speakerId == p.id, onClick = { vm.selectSpeaker(p.id) }, label = { Text(p.name, maxLines = 1) })
                }
                item { FilterChip(selected = vm.speakerId == null, onClick = { vm.selectSpeaker(null) }, label = { Text("Unknown") }) }
                item { AssistChip(onClick = onAddSpeaker, label = { Text("Person") }, leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(18.dp)) }) }
            }
            // What kind of statement.
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(EntryType.entries) { t ->
                    FilterChip(selected = vm.entryType == t, onClick = { vm.selectType(t) }, label = { Text(t.label) })
                }
            }
            if (vm.entryType == EntryType.REPLY || vm.replyToId != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val target = m.entry(vm.replyToId)
                    Text(
                        if (target != null) "↳ re ${entrySummary(m, target, 50)}" else "↳ replying to: nothing yet (tap to choose)",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).clickable(onClick = onPickReply).padding(vertical = 6.dp),
                    )
                    IconButton(onClick = { vm.setReplyTarget(null) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.Close, "Clear reply", Modifier.size(18.dp)) }
                }
            }
            if (vm.entryType == EntryType.ACTION) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Picker(
                        "Owner",
                        listOf<Pair<String?, String>>(null to "Unassigned") + m.participants.map { it.id to it.name },
                        vm.actionOwnerId, vm::setActionOwner, Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        vm.actionDue, vm::updateActionDue,
                        label = { Text("Due yyyy-mm-dd") }, singleLine = true,
                        isError = !MeetingOps.isValidDue(vm.actionDue),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.captureMode == CaptureMode.LIVE_TRANSCRIPT) {
                    FilledIconButton(
                        onClick = onMic,
                        colors = if (vm.listening) IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFD32F2F)) else IconButtonDefaults.filledIconButtonColors(),
                        modifier = Modifier.size(52.dp),
                    ) {
                        Icon(painterResource(if (vm.listening) R.drawable.ic_stop else R.drawable.ic_mic), contentDescription = if (vm.listening) "Stop listening" else "Start listening")
                    }
                    Spacer(Modifier.width(8.dp))
                }
                OutlinedTextField(
                    value = vm.input, onValueChange = { vm.input = it },
                    placeholder = { Text(if (m.captureMode == CaptureMode.LIVE_TRANSCRIPT) "…or type" else "Type what was said") },
                    maxLines = 4,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                )
                IconButton(onClick = vm::sendTyped, enabled = vm.input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Add entry")
                }
            }
        }
    }
}

@Composable
private fun EntryCard(m: Meeting, e: Entry, onEdit: () -> Unit, onReply: () -> Unit) {
    val accent = when (e.type) {
        EntryType.DECISION -> MaterialTheme.colorScheme.tertiary
        EntryType.ACTION -> MaterialTheme.colorScheme.primary
        EntryType.QUESTION -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp).clickable(onClick = onEdit)) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${e.id} · ${speakerName(m, e.speakerId)}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text(formatTime(e.atMillis), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 8.dp))
            }
            val meta = buildString {
                append(e.type.label)
                e.replyToId?.let { append(" ↳ re ").append(it) }
                if (e.type == EntryType.ACTION) {
                    append(" · owner ").append(m.participant(e.actionOwnerId)?.name ?: "unassigned")
                    append(" · due ").append(e.actionDue ?: "—")
                }
                m.topic(e.topicId)?.let { append(" · ").append(it.title) }
                e.audioOffsetMillis?.let { append(" · audio ").append(clock(it)) }
                if (e.source == Source.SPEECH) append(" · speech")
                if (e.edited) append(" · edited")
            }
            Text(meta, style = MaterialTheme.typography.labelMedium, color = accent)
            Text(e.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onReply) { Text("Reply") }
            }
        }
    }
}

@Composable
fun ReplyPicker(m: Meeting, candidates: List<Entry>, current: String?, onDismiss: () -> Unit, onPick: (String?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replying to") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text("No reply link", Modifier.fillMaxWidth().clickable { onPick(null); onDismiss() }.padding(vertical = 10.dp))
                    HorizontalDivider()
                }
                items(candidates.asReversed().take(100), key = { it.id }) { e ->
                    Text(
                        entrySummary(m, e, 80),
                        fontStyle = if (e.id == current) FontStyle.Italic else FontStyle.Normal,
                        color = if (e.id == current) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(e.id); onDismiss() }.padding(vertical = 10.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EditEntryDialog(vm: AppViewModel, m: Meeting, e: Entry, onDismiss: () -> Unit) {
    var text by remember(e.id) { mutableStateOf(e.text) }
    var speaker by remember(e.id) { mutableStateOf(e.speakerId) }
    var topic by remember(e.id) { mutableStateOf(e.topicId) }
    var type by remember(e.id) { mutableStateOf(e.type) }
    var replyTo by remember(e.id) { mutableStateOf(e.replyToId) }
    var owner by remember(e.id) { mutableStateOf(e.actionOwnerId) }
    var due by remember(e.id) { mutableStateOf(e.actionDue.orEmpty()) }
    var pickReply by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val people = listOf<Pair<String?, String>>(null to "Unknown") + m.participants.map { it.id to it.name }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${e.id}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Text") }, modifier = Modifier.fillMaxWidth())
                Picker("Speaker", people, speaker, { speaker = it }, Modifier.fillMaxWidth())
                Picker("Topic", listOf<Pair<String?, String>>(null to "General") + m.topics.map { it.id to it.title }, topic, { topic = it }, Modifier.fillMaxWidth())
                Picker("Type", EntryType.entries.map { it to it.label }, type, { type = it }, Modifier.fillMaxWidth())
                OutlinedButton(onClick = { pickReply = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Reply to: " + (m.entry(replyTo)?.let { entrySummary(m, it, 30) } ?: "none"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (type == EntryType.ACTION) {
                    Picker("Owner", people.drop(1).let { listOf<Pair<String?, String>>(null to "Unassigned") + it }, owner, { owner = it }, Modifier.fillMaxWidth())
                    OutlinedTextField(due, { due = it }, label = { Text("Due yyyy-mm-dd") }, singleLine = true, isError = !MeetingOps.isValidDue(due), modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.updateEntry(e.copy(text = text, speakerId = speaker, topicId = topic, type = type, replyToId = replyTo, actionOwnerId = owner, actionDue = due))
                onDismiss()
            }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
    if (pickReply) {
        ReplyPicker(m, MeetingOps.replyCandidates(m, e.id), replyTo, onDismiss = { pickReply = false }, onPick = { replyTo = it })
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete ${e.id}?", "Replies to it will be linked to what it was replying to.", "Delete",
            onDismiss = { confirmDelete = false },
            onConfirm = { vm.deleteEntry(e.id); onDismiss() },
        )
    }
}
