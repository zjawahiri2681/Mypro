package com.mypro.minutes.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import com.mypro.minutes.BuildConfig
import com.mypro.minutes.audio.RecorderService
import com.mypro.minutes.core.CaptureMode
import com.mypro.minutes.core.Entry
import com.mypro.minutes.core.EntryDraft
import com.mypro.minutes.core.EntryType
import com.mypro.minutes.core.FileNames
import com.mypro.minutes.core.MarkdownExporter
import com.mypro.minutes.core.Meeting
import com.mypro.minutes.core.MeetingOps
import com.mypro.minutes.core.Participant
import com.mypro.minutes.core.Source
import com.mypro.minutes.data.MeetingRepository
import com.mypro.minutes.speech.LiveTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class Screen { HOME, SETUP, LIVE, EXPORT }

data class Problem(val message: String, val offerOnline: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app), LiveTranscriber.Listener {

    private val repo = MeetingRepository(app)
    // One writer, in order, outliving the ViewModel so the last save is never cancelled.
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val transcriber = LiveTranscriber(app, this)
    private val exporter = MarkdownExporter(appVersion = BuildConfig.VERSION_NAME)

    var screen by mutableStateOf(Screen.HOME); private set
    var meetings by mutableStateOf<List<Meeting>>(emptyList()); private set
    var meeting by mutableStateOf<Meeting?>(null); private set
    private var setupIsNew = false
    private var setupReturn = Screen.HOME

    // Composer state (what the next entry will be labelled with).
    var speakerId by mutableStateOf<String?>(null); private set
    var topicId by mutableStateOf<String?>(null); private set
    var entryType by mutableStateOf(EntryType.DISCUSSION); private set
    var replyToId by mutableStateOf<String?>(null); private set
    var actionOwnerId by mutableStateOf<String?>(null); private set
    var actionDue by mutableStateOf(""); private set
    var input by mutableStateOf("")
    var partial by mutableStateOf(""); private set
    var listening by mutableStateOf(false); private set
    var problem by mutableStateOf<Problem?>(null)
    var includeChronological by mutableStateOf(false)

    val recorder: StateFlow<RecorderService.State> = RecorderService.state

    init {
        refresh()
        // Reopen a meeting that was live when the app was last killed.
        repo.activeMeetingId?.let { id -> repo.load(id) }?.takeIf { it.status == Meeting.Status.IN_PROGRESS }?.let {
            meeting = it
            resetComposer(it)
            screen = Screen.LIVE
        }
    }

    fun refresh() {
        meetings = repo.list()
    }

    // ---- navigation -----------------------------------------------------------------------

    fun back() {
        when (screen) {
            Screen.SETUP -> {
                val m = meeting
                if (m != null && (!setupIsNew || m.title.isNotBlank() || m.participants.isNotEmpty())) persist(m)
                if (setupReturn != Screen.HOME && m != null) screen = setupReturn else goHome()
            }
            Screen.LIVE -> { stopListening(); goHome() } // meeting stays in progress; audio keeps recording
            Screen.EXPORT -> goHome()
            Screen.HOME -> {}
        }
    }

    private fun goHome() {
        meeting = null
        refresh()
        screen = Screen.HOME
    }

    fun newMeeting() {
        val m = MeetingOps.newMeeting(UUID.randomUUID().toString(), "", System.currentTimeMillis()).copy(title = "")
        meeting = m
        setupIsNew = true
        setupReturn = Screen.HOME
        screen = Screen.SETUP
    }

    fun open(m: Meeting) {
        meeting = m
        setupIsNew = false
        setupReturn = Screen.HOME
        when (m.status) {
            Meeting.Status.DRAFT -> screen = Screen.SETUP
            Meeting.Status.IN_PROGRESS -> { resetComposer(m); repo.activeMeetingId = m.id; screen = Screen.LIVE }
            Meeting.Status.ENDED -> screen = Screen.EXPORT
        }
    }

    fun editDetails() {
        stopListening()
        setupIsNew = false
        setupReturn = screen
        screen = Screen.SETUP
    }

    fun delete(m: Meeting) {
        val recordingName = RecorderService.state.value.file?.let { File(it).name }
        if (recordingName != null && m.audioFiles.contains(recordingName)) RecorderService.command(getApplication(), RecorderService.ACTION_STOP)
        repo.delete(m)
        refresh()
    }

    // ---- setup ----------------------------------------------------------------------------

    fun updateMeeting(transform: (Meeting) -> Meeting) {
        val m = meeting ?: return
        val n = transform(m)
        meeting = n
        if (!setupIsNew || screen != Screen.SETUP) persist(n)
    }

    /** Returns an error message, or null on success. */
    fun addParticipant(name: String, role: String, org: String): String? {
        val m = meeting ?: return null
        if (name.isBlank()) return "Enter a name."
        if (MeetingOps.hasParticipantNamed(m, name)) return "There is already someone called ${name.trim()}. Add a surname or initial so the transcript is unambiguous."
        updateMeeting { MeetingOps.addParticipant(it, name, role, org) }
        return null
    }

    fun updateParticipant(p: Participant): String? {
        val m = meeting ?: return null
        if (p.name.isBlank()) return "Enter a name."
        if (MeetingOps.hasParticipantNamed(m, p.name, exceptId = p.id)) return "That name is already used."
        updateMeeting { MeetingOps.updateParticipant(it, p) }
        return null
    }

    fun removeParticipant(id: String): String? {
        val m = meeting ?: return null
        if (MeetingOps.isParticipantReferenced(m, id)) return "They already have entries in this meeting. Mark them absent instead, or reassign their entries first."
        updateMeeting { MeetingOps.removeParticipant(it, id) }
        if (speakerId == id) speakerId = null
        return null
    }

    fun addTopic(title: String) {
        val before = meeting?.topics?.size ?: 0
        updateMeeting { MeetingOps.addTopic(it, title) }
        val m = meeting ?: return
        if (screen == Screen.LIVE && m.topics.size > before) topicId = m.topics.last().id
    }

    fun removeTopic(id: String): String? {
        val m = meeting ?: return null
        if (MeetingOps.isTopicReferenced(m, id)) return "This topic already has entries."
        updateMeeting { MeetingOps.removeTopic(it, id) }
        return null
    }

    /** Returns an error message, or null if the meeting started. */
    fun startMeeting(): String? {
        val m = meeting ?: return null
        if (m.title.isBlank()) return "Give the meeting a title."
        if (!m.consentConfirmed) return "Confirm that everyone has been told the meeting is being recorded."
        val started = MeetingOps.start(m.copy(title = MeetingOps.clean(m.title)), System.currentTimeMillis())
        meeting = started
        persist(started)
        setupIsNew = false
        repo.activeMeetingId = started.id
        resetComposer(started)
        screen = Screen.LIVE
        return null
    }

    // ---- live -----------------------------------------------------------------------------

    private fun resetComposer(m: Meeting) {
        val last = m.entries.lastOrNull()
        speakerId = last?.speakerId?.takeIf { m.participant(it) != null }
        topicId = last?.topicId ?: m.topics.firstOrNull()?.id
        clearLabels()
        input = ""
        partial = ""
        problem = null
    }

    private fun clearLabels() {
        entryType = EntryType.DISCUSSION
        replyToId = null
        actionOwnerId = null
        actionDue = ""
    }

    /** A new speaker starts a new turn: labels from the previous turn must not leak into it. */
    fun selectSpeaker(id: String?) {
        if (id == speakerId) return
        speakerId = id
        clearLabels()
    }

    fun selectTopic(id: String?) {
        if (id == topicId) return
        topicId = id
        replyToId = null
        if (entryType == EntryType.REPLY) entryType = EntryType.DISCUSSION
    }

    fun selectType(t: EntryType) {
        entryType = t
        val m = meeting ?: return
        when (t) {
            EntryType.REPLY -> if (replyToId == null) replyToId = MeetingOps.defaultReplyTarget(m, speakerId, topicId)?.id
            EntryType.DISCUSSION, EntryType.NOTE -> replyToId = null
            else -> {}
        }
        if (t != EntryType.ACTION) { actionOwnerId = null; actionDue = "" }
    }

    /** "Reply" on an entry card: the next entry answers that one, in its topic. */
    fun replyTo(e: Entry) {
        if (e.topicId != topicId) topicId = e.topicId
        entryType = EntryType.REPLY
        replyToId = e.id
        if (speakerId != null && speakerId == e.speakerId) speakerId = null // nobody replies to themselves; pick who answers
    }

    fun setReplyTarget(id: String?) { replyToId = id }
    fun setActionOwner(id: String?) { actionOwnerId = id }
    fun updateActionDue(s: String) { actionDue = s }

    fun sendTyped() {
        if (input.isBlank()) return
        if (add(input, Source.TYPED, null)) {
            input = ""
            clearLabels()
        }
    }

    private fun add(text: String, source: Source, confidence: Float?): Boolean {
        val m = meeting ?: return false
        val rec = RecorderService.state.value
        val recording = m.captureMode == CaptureMode.AUDIO_AND_NOTES && rec.recording && rec.file != null
        val draft = EntryDraft(
            text = text, speakerId = speakerId, topicId = topicId, type = entryType, replyToId = replyToId,
            source = source, confidence = confidence,
            actionOwnerId = actionOwnerId, actionDue = actionDue,
            audioFile = if (recording) rec.file?.let { File(it).name } else null,
            audioOffsetMillis = if (recording) rec.positionMs() else null,
        )
        val (n, e) = MeetingOps.addEntry(m, draft, System.currentTimeMillis())
        if (e == null) return false
        meeting = n
        persist(n)
        // A reply that was auto-targeted shows which entry it went to.
        if (entryType == EntryType.REPLY) replyToId = e.replyToId
        return true
    }

    fun updateEntry(e: Entry) = updateMeeting { MeetingOps.updateEntry(it, e) }

    fun deleteEntry(id: String) {
        updateMeeting { MeetingOps.deleteEntry(it, id) }
        if (replyToId == id) replyToId = null
    }

    fun toggleListening() {
        if (listening) stopListening() else startListening()
    }

    fun startListening() {
        val m = meeting ?: return
        problem = null
        transcriber.language = m.language
        transcriber.preferOffline = m.preferOffline
        transcriber.start()
    }

    fun stopListening() {
        if (transcriber.isRunning) transcriber.stop()
    }

    fun allowOnlineAndRetry() {
        updateMeeting { it.copy(preferOffline = false) }
        problem = null
        startListening()
    }

    fun startRecording() {
        val m = meeting ?: return
        if (RecorderService.state.value.recording) return
        val name = "${m.id.take(8)}-${m.audioFiles.size + 1}.aac"
        updateMeeting { it.copy(audioFiles = it.audioFiles + name) }
        RecorderService.start(getApplication(), repo.audioFile(name))
    }

    fun pauseOrResumeRecording() {
        val s = RecorderService.state.value
        RecorderService.command(getApplication(), if (s.paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE)
    }

    fun endMeeting(nextMeeting: String) {
        stopListening()
        RecorderService.command(getApplication(), RecorderService.ACTION_STOP)
        updateMeeting { MeetingOps.end(it.copy(nextMeeting = MeetingOps.clean(nextMeeting)), System.currentTimeMillis()) }
        repo.activeMeetingId = null
        screen = Screen.EXPORT
    }

    fun reopen() {
        updateMeeting { it.copy(endedAt = null) }
        meeting?.let { resetComposer(it); repo.activeMeetingId = it.id }
        screen = Screen.LIVE
    }

    // ---- export ---------------------------------------------------------------------------

    fun markdown(): String = meeting?.let { exporter.export(it, MarkdownExporter.Options(includeChronological)) }.orEmpty()

    fun fileName(): String = meeting?.let { FileNames.markdown(it) } ?: "Meeting.md"

    /** Writes the export to the share cache and returns a content:// URI other apps can read. */
    fun exportUri(context: Context): Uri {
        repo.exportDir.listFiles()?.forEach { it.delete() } // keep only the latest export around
        val f = File(repo.exportDir, fileName())
        f.writeText(markdown())
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun audioUris(context: Context): List<Uri> =
        meeting?.audioFiles.orEmpty().map { repo.audioFile(it) }.filter { it.exists() && it.length() > 0 }
            .map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }

    fun saveTo(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(markdown().toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)

    // ---- persistence ----------------------------------------------------------------------

    private fun persist(m: Meeting) {
        saver.launch { runCatching { repo.save(m) } }
    }

    // ---- LiveTranscriber.Listener (main thread) --------------------------------------------

    override fun onListening(active: Boolean) { listening = active }
    override fun onPartial(text: String) { partial = text }
    override fun onFinal(text: String, confidence: Float?) { add(text, Source.SPEECH, confidence) }
    override fun onProblem(message: String, fatal: Boolean, offlineProblem: Boolean) {
        problem = Problem(message, offlineProblem)
    }

    override fun onCleared() {
        transcriber.stop()
        super.onCleared()
    }
}
