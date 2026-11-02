package com.mypro.minutes.core

import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * All edits to a meeting go through these pure functions, so the rules (stable IDs, replies only
 * point backwards, nothing left dangling after a delete) hold no matter which screen made the change.
 */
object MeetingOps {

    /** Consecutive speech chunks from the same turn closer than this are merged into one entry. */
    const val SPEECH_MERGE_GAP_MS = 10_000L

    fun newMeeting(id: String, title: String, now: Long): Meeting =
        Meeting(id = id, title = clean(title).ifBlank { "Meeting" }, createdAt = now)

    // ---- participants -------------------------------------------------------------------------

    fun hasParticipantNamed(m: Meeting, name: String, exceptId: String? = null): Boolean {
        val n = clean(name)
        return m.participants.any { it.id != exceptId && it.name.equals(n, ignoreCase = true) }
    }

    /** Returns the meeting unchanged if the name is blank or already used (names must be unambiguous). */
    fun addParticipant(m: Meeting, name: String, role: String = "", organization: String = ""): Meeting {
        val n = clean(name)
        if (n.isBlank() || hasParticipantNamed(m, n)) return m
        val seq = m.participantSeq + 1
        val p = Participant(id = "P$seq", name = n, role = clean(role), organization = clean(organization))
        return m.copy(participants = m.participants + p, participantSeq = seq)
    }

    fun updateParticipant(m: Meeting, p: Participant): Meeting {
        val n = clean(p.name)
        if (n.isBlank() || hasParticipantNamed(m, n, exceptId = p.id)) return m
        val fixed = p.copy(name = n, role = clean(p.role), organization = clean(p.organization))
        return m.copy(participants = m.participants.map { if (it.id == p.id) fixed else it })
    }

    fun isParticipantReferenced(m: Meeting, id: String): Boolean =
        m.chairId == id || m.entries.any { it.speakerId == id || it.actionOwnerId == id }

    /** Someone who has spoken cannot be removed (their entries would lose their speaker); mark them absent instead. */
    fun removeParticipant(m: Meeting, id: String): Meeting =
        if (isParticipantReferenced(m, id)) m else m.copy(participants = m.participants.filterNot { it.id == id })

    // ---- topics -------------------------------------------------------------------------------

    fun addTopic(m: Meeting, title: String): Meeting {
        val t = clean(title)
        if (t.isBlank()) return m
        val seq = m.topicSeq + 1
        return m.copy(topics = m.topics + Topic("T$seq", t), topicSeq = seq)
    }

    fun renameTopic(m: Meeting, id: String, title: String): Meeting {
        val t = clean(title)
        if (t.isBlank()) return m
        return m.copy(topics = m.topics.map { if (it.id == id) it.copy(title = t) else it })
    }

    fun isTopicReferenced(m: Meeting, id: String): Boolean = m.entries.any { it.topicId == id }

    fun removeTopic(m: Meeting, id: String): Meeting =
        if (isTopicReferenced(m, id)) m else m.copy(topics = m.topics.filterNot { it.id == id })

    // ---- entries ------------------------------------------------------------------------------

    /**
     * The entry a new reply most likely answers: the latest entry in the same topic by someone other
     * than the speaker (notes excluded). Falls back to the latest entry in that topic.
     */
    fun defaultReplyTarget(m: Meeting, speakerId: String?, topicId: String?): Entry? {
        val inTopic = m.entries.filter { it.topicId == topicId && it.type != EntryType.NOTE }
        return inTopic.lastOrNull { it.speakerId != speakerId || speakerId == null }
            ?: inTopic.lastOrNull()
    }

    /** Entries a given entry may reply to: strictly earlier ones. */
    fun replyCandidates(m: Meeting, entryId: String? = null): List<Entry> {
        if (entryId == null) return m.entries
        val idx = m.entries.indexOfFirst { it.id == entryId }
        return if (idx < 0) m.entries else m.entries.subList(0, idx)
    }

    /**
     * Adds an entry, or for live speech merges it into the previous one when it continues the same
     * turn. Returns the updated meeting and the entry that was created or extended (null if the text
     * was blank).
     */
    fun addEntry(m: Meeting, draft: EntryDraft, now: Long): Pair<Meeting, Entry?> {
        val text = cleanMultiline(draft.text)
        if (text.isBlank()) return m to null

        val speakerId = draft.speakerId?.takeIf { m.participant(it) != null }
        val topicId = draft.topicId?.takeIf { m.topic(it) != null }
        var replyTo = draft.replyToId?.takeIf { m.entry(it) != null }
        if (draft.type == EntryType.REPLY && replyTo == null) {
            replyTo = defaultReplyTarget(m, speakerId, topicId)?.id
        }
        val due = if (draft.type == EntryType.ACTION) normalizeDue(draft.actionDue) else null
        val owner = if (draft.type == EntryType.ACTION) draft.actionOwnerId?.takeIf { m.participant(it) != null } else null

        val last = m.entries.lastOrNull()
        if (draft.source == Source.SPEECH && last != null &&
            last.source == Source.SPEECH && !last.edited &&
            last.speakerId == speakerId && last.topicId == topicId && last.type == draft.type &&
            last.replyToId == replyTo &&
            now - (last.lastChunkAtMillis ?: last.atMillis) <= SPEECH_MERGE_GAP_MS
        ) {
            val merged = last.copy(
                text = last.text + " " + text,
                lastChunkAtMillis = now,
                confidence = minConfidence(last.confidence, draft.confidence),
            )
            return m.copy(entries = m.entries.dropLast(1) + merged) to merged
        }

        val seq = m.entrySeq + 1
        val e = Entry(
            id = entryId(seq),
            atMillis = now,
            speakerId = speakerId,
            topicId = topicId,
            type = draft.type,
            text = text,
            replyToId = replyTo,
            source = draft.source,
            confidence = draft.confidence?.takeIf { it > 0f && it <= 1f },
            actionOwnerId = owner,
            actionDue = due,
            audioFile = draft.audioFile,
            audioOffsetMillis = draft.audioOffsetMillis,
        )
        return m.copy(entries = m.entries + e, entrySeq = seq) to e
    }

    /**
     * Applies an edit made by a person. Invalid references are dropped rather than stored, and a
     * reply may only point at an earlier entry (this keeps the reply graph free of cycles).
     */
    fun updateEntry(m: Meeting, updated: Entry): Meeting {
        val idx = m.entries.indexOfFirst { it.id == updated.id }
        if (idx < 0) return m
        val earlier = m.entries.subList(0, idx).map { it.id }.toSet()
        val text = cleanMultiline(updated.text)
        if (text.isBlank()) return m
        val old = m.entries[idx]
        val fixed = updated.copy(
            text = text,
            speakerId = updated.speakerId?.takeIf { m.participant(it) != null },
            topicId = updated.topicId?.takeIf { m.topic(it) != null },
            replyToId = updated.replyToId?.takeIf { it in earlier },
            actionOwnerId = if (updated.type == EntryType.ACTION) updated.actionOwnerId?.takeIf { m.participant(it) != null } else null,
            actionDue = if (updated.type == EntryType.ACTION) normalizeDue(updated.actionDue) else null,
            // Keep identity and capture facts; flag the edit.
            id = old.id, atMillis = old.atMillis, source = old.source,
            audioFile = old.audioFile, audioOffsetMillis = old.audioOffsetMillis, lastChunkAtMillis = old.lastChunkAtMillis,
            edited = old.edited || updated.text.trim() != old.text || updated.type != old.type ||
                updated.speakerId != old.speakerId || updated.topicId != old.topicId || updated.replyToId != old.replyToId,
        )
        return m.copy(entries = m.entries.toMutableList().also { it[idx] = fixed })
    }

    /** Deletes an entry. Replies to it are re-attached to what it was replying to, so threads stay connected. */
    fun deleteEntry(m: Meeting, id: String): Meeting {
        val gone = m.entry(id) ?: return m
        val entries = m.entries.filterNot { it.id == id }.map {
            if (it.replyToId == id) it.copy(replyToId = gone.replyToId) else it
        }
        return m.copy(entries = entries)
    }

    fun start(m: Meeting, now: Long): Meeting = m.copy(startedAt = m.startedAt ?: now, endedAt = null)
    fun end(m: Meeting, now: Long): Meeting = m.copy(endedAt = now)

    // ---- helpers ------------------------------------------------------------------------------

    fun entryId(seq: Int): String = "E-" + seq.toString().padStart(3, '0')

    /** Accepts yyyy-MM-dd (also yyyy/MM/dd). Anything else is kept as given so nothing the user typed is lost. */
    fun normalizeDue(raw: String?): String? {
        val s = raw?.trim()?.replace('/', '-')?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            LocalDate.parse(s).toString()
        } catch (_: DateTimeParseException) {
            clean(raw)
        }
    }

    fun isValidDue(raw: String?): Boolean {
        val s = raw?.trim()?.replace('/', '-')?.takeIf { it.isNotEmpty() } ?: return true
        return try { LocalDate.parse(s); true } catch (_: DateTimeParseException) { false }
    }

    /** Single-line field: no control characters, collapsed whitespace. */
    fun clean(s: String?): String =
        (s ?: "").map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim()

    /** Multi-line field: keeps line breaks, drops other control characters and trailing spaces. */
    fun cleanMultiline(s: String?): String =
        (s ?: "").replace("\r\n", "\n").replace('\r', '\n')
            .filter { it == '\n' || it == '\t' || !it.isISOControl() }
            .lines().joinToString("\n") { it.trimEnd() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    private fun minConfidence(a: Float?, b: Float?): Float? = when {
        a == null -> b?.takeIf { it > 0f && it <= 1f }
        b == null || b <= 0f || b > 1f -> a
        else -> minOf(a, b)
    }
}
