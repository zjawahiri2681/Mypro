package com.mypro.minutes.core

import kotlinx.serialization.Serializable

/** What an entry is, in minute-taking terms. The label is what appears in the Markdown. */
@Serializable
enum class EntryType(val label: String) {
    DISCUSSION("Discussion"),
    REPLY("Reply"),
    QUESTION("Question"),
    DECISION("Decision"),
    ACTION("Action"),
    NOTE("Note"),
}

/** Where the text came from. Speech text is unverified machine output; typed text was written by the note-taker. */
@Serializable
enum class Source { SPEECH, TYPED }

@Serializable
enum class Attendance(val label: String) { PRESENT("Present"), APOLOGIES("Apologies"), ABSENT("Absent") }

/**
 * LIVE_TRANSCRIPT: on-screen speech-to-text, entries attributed to the speaker you tap.
 * AUDIO_AND_NOTES: an audio file is recorded (works with the screen off) and entries are typed,
 * each stamped with its position in the audio. Android cannot reliably feed the microphone to both
 * the recorder and the speech recogniser at once, so a meeting uses one mode at a time.
 */
@Serializable
enum class CaptureMode(val label: String) { LIVE_TRANSCRIPT("Live transcript"), AUDIO_AND_NOTES("Audio recording + notes") }

@Serializable
data class Participant(
    val id: String,
    val name: String,
    val role: String = "",
    val organization: String = "",
    val attendance: Attendance = Attendance.PRESENT,
)

@Serializable
data class Topic(val id: String, val title: String)

@Serializable
data class Entry(
    /** Stable reference, e.g. E-007. Never reused, even after deletion. */
    val id: String,
    /** Wall-clock time the entry was captured (epoch millis). */
    val atMillis: Long,
    /** When the last speech chunk was appended to this entry (live transcript only). */
    val lastChunkAtMillis: Long? = null,
    val speakerId: String? = null,
    val topicId: String? = null,
    val type: EntryType = EntryType.DISCUSSION,
    val text: String,
    /** The earlier entry this one answers. Always points backwards in time. */
    val replyToId: String? = null,
    val source: Source = Source.TYPED,
    /** Recogniser confidence 0..1 when the engine reports one. */
    val confidence: Float? = null,
    val actionOwnerId: String? = null,
    /** Due date for actions, ISO yyyy-MM-dd. */
    val actionDue: String? = null,
    val audioFile: String? = null,
    val audioOffsetMillis: Long? = null,
    /** True once a person has corrected the text or labels. */
    val edited: Boolean = false,
)

@Serializable
data class Meeting(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val title: String,
    val project: String = "",
    /** Contract / specification reference, if the minutes go to a client. */
    val reference: String = "",
    val location: String = "",
    val chairId: String? = null,
    /** BCP-47 tag for speech recognition; blank = device default. */
    val language: String = "",
    val captureMode: CaptureMode = CaptureMode.LIVE_TRANSCRIPT,
    /** Keep speech recognition on the device (no audio leaves the phone). */
    val preferOffline: Boolean = true,
    val consentConfirmed: Boolean = false,
    val createdAt: Long,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val participants: List<Participant> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val entries: List<Entry> = emptyList(),
    val nextMeeting: String = "",
    val audioFiles: List<String> = emptyList(),
    /** Counters so IDs are never reused after a deletion. */
    val entrySeq: Int = 0,
    val participantSeq: Int = 0,
    val topicSeq: Int = 0,
) {
    val status: Status
        get() = when {
            startedAt == null -> Status.DRAFT
            endedAt == null -> Status.IN_PROGRESS
            else -> Status.ENDED
        }

    fun participant(id: String?): Participant? = id?.let { pid -> participants.firstOrNull { it.id == pid } }
    fun topic(id: String?): Topic? = id?.let { tid -> topics.firstOrNull { it.id == tid } }
    fun entry(id: String?): Entry? = id?.let { eid -> entries.firstOrNull { it.id == eid } }

    enum class Status(val label: String) { DRAFT("Draft"), IN_PROGRESS("In progress"), ENDED("Ended") }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/** What the person has selected in the composer before text arrives. */
data class EntryDraft(
    val text: String,
    val speakerId: String? = null,
    val topicId: String? = null,
    val type: EntryType = EntryType.DISCUSSION,
    val replyToId: String? = null,
    val source: Source = Source.TYPED,
    val confidence: Float? = null,
    val actionOwnerId: String? = null,
    val actionDue: String? = null,
    val audioFile: String? = null,
    val audioOffsetMillis: Long? = null,
)
