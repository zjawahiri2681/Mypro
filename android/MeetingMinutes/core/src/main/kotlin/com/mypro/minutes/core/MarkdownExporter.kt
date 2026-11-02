package com.mypro.minutes.core

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Turns a meeting into a Markdown transcript designed to be read by people and by AI models.
 *
 * Layout (schema `mypro-minutes/1`):
 *  1. YAML front matter with the facts a model needs before reading (who, when, how captured).
 *  2. A short note telling the reader how to treat the content (raw record, speech may be wrong).
 *  3. Details, attendees, agenda.
 *  4. Decisions, action items and open questions: derived lists that point at entry IDs.
 *  5. Discussion by topic: every entry exactly once, replies nested under what they answer.
 *  6. Optional chronological transcript.
 */
class MarkdownExporter(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val appVersion: String = "1.0.0",
) {
    data class Options(val includeChronological: Boolean = false)

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT)
    private val shortTimeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE
    private val longDateFmt = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)
    private val isoFmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun export(m: Meeting, options: Options = Options()): String = buildString {
        frontMatter(m)
        append("# ").append(Md.inline(m.title)).append("\n\n")
        append(
            "> **Note for AI and readers:** this is a raw meeting record captured live, not approved minutes. " +
                "Entries marked *speech* come from automatic speech recognition and may contain recognition errors; " +
                "*typed* entries were written by the note-taker; *edited* entries were corrected by a person. " +
                "Every entry has a stable ID (`E-001`, ...). A reply names the entry it answers as `re E-xxx`. " +
                "Speakers are referred to by name; their IDs (`P1`, ...) are in the Attendees table. " +
                "Use only what is written here: do not invent attendees, decisions, owners or dates.\n\n",
        )
        details(m)
        attendees(m)
        agenda(m)
        decisions(m)
        actions(m)
        openQuestions(m)
        discussion(m)
        if (options.includeChronological) chronological(m)
    }

    // ---- sections ----------------------------------------------------------------------------

    private fun StringBuilder.frontMatter(m: Meeting) {
        val start = m.startedAt ?: m.createdAt
        append("---\n")
        append("type: meeting-transcript\n")
        append("schema: mypro-minutes/").append(m.schemaVersion).append('\n')
        append("title: ").append(Md.yaml(m.title)).append('\n')
        append("date: ").append(dateFmt.format(zdt(start))).append('\n')
        append("start: ").append(m.startedAt?.let { Md.yaml(isoFmt.format(zdt(it))) } ?: "null").append('\n')
        append("end: ").append(m.endedAt?.let { Md.yaml(isoFmt.format(zdt(it))) } ?: "null").append('\n')
        durationMinutes(m)?.let { append("duration_minutes: ").append(it).append('\n') }
        append("timezone: ").append(Md.yaml(zone.id)).append('\n')
        if (m.project.isNotBlank()) append("project: ").append(Md.yaml(m.project)).append('\n')
        if (m.reference.isNotBlank()) append("reference: ").append(Md.yaml(m.reference)).append('\n')
        if (m.location.isNotBlank()) append("location: ").append(Md.yaml(m.location)).append('\n')
        m.participant(m.chairId)?.let { append("chair: ").append(Md.yaml(it.name)).append('\n') }
        append("language: ").append(Md.yaml(m.language.ifBlank { "device default" })).append('\n')
        append("capture: ").append(
            when (m.captureMode) {
                CaptureMode.LIVE_TRANSCRIPT -> if (m.preferOffline) "live-speech-to-text-on-device" else "live-speech-to-text"
                CaptureMode.AUDIO_AND_NOTES -> "audio-recording-with-typed-notes"
            },
        ).append('\n')
        append("consent_confirmed: ").append(m.consentConfirmed).append('\n')
        append("participants:")
        if (m.participants.isEmpty()) append(" []\n") else {
            append('\n')
            for (p in m.participants) append("  - ").append(Md.yaml(personLabel(p))).append('\n')
        }
        append("topics:")
        if (m.topics.isEmpty()) append(" []\n") else {
            append('\n')
            for (t in m.topics) append("  - ").append(Md.yaml("${t.id} ${t.title}")).append('\n')
        }
        append("entry_count: ").append(m.entries.size).append('\n')
        append("decision_count: ").append(m.entries.count { it.type == EntryType.DECISION }).append('\n')
        append("action_count: ").append(m.entries.count { it.type == EntryType.ACTION }).append('\n')
        if (m.audioFiles.isNotEmpty()) {
            append("audio_files:\n")
            for (f in m.audioFiles) append("  - ").append(Md.yaml(f)).append('\n')
        }
        append("status: ").append(if (m.endedAt != null) "ended" else if (m.startedAt != null) "in-progress" else "draft").append('\n')
        append("generated_by: ").append(Md.yaml("Meeting Minutes for Android $appVersion")).append('\n')
        append("---\n\n")
    }

    private fun StringBuilder.details(m: Meeting) {
        append("## Meeting details\n\n")
        val start = m.startedAt
        val date = longDateFmt.format(zdt(start ?: m.createdAt))
        append("- **Date:** ").append(date).append('\n')
        if (start != null) {
            append("- **Time:** ").append(shortTimeFmt.format(zdt(start)))
            m.endedAt?.let { append(" – ").append(shortTimeFmt.format(zdt(it))) }
            append(" (").append(zdt(start).offset.id.replace("Z", "UTC")).append(")")
            durationMinutes(m)?.let { append(", ").append(it).append(" min") }
            append('\n')
        }
        if (m.location.isNotBlank()) append("- **Location:** ").append(Md.inline(m.location)).append('\n')
        if (m.project.isNotBlank()) append("- **Project:** ").append(Md.inline(m.project)).append('\n')
        if (m.reference.isNotBlank()) append("- **Reference:** ").append(Md.inline(m.reference)).append('\n')
        m.participant(m.chairId)?.let { append("- **Chair:** ").append(Md.inline(it.name)).append('\n') }
        append("- **Captured as:** ").append(m.captureMode.label.lowercase(Locale.ROOT))
        if (m.audioFiles.isNotEmpty()) append(" (audio: ").append(m.audioFiles.joinToString(", ") { Md.inline(it) }).append(")")
        append('\n')
        append("- **Participants told about recording:** ").append(if (m.consentConfirmed) "yes" else "not confirmed").append('\n')
        if (m.nextMeeting.isNotBlank()) append("- **Next meeting:** ").append(Md.inline(m.nextMeeting)).append('\n')
        append('\n')
    }

    private fun StringBuilder.attendees(m: Meeting) {
        append("## Attendees\n\n")
        if (m.participants.isEmpty()) {
            append("_None recorded._\n\n"); return
        }
        append("| ID | Name | Role | Organization | Attendance | Entries |\n")
        append("|---|---|---|---|---|---|\n")
        for (p in m.participants) {
            val chair = if (p.id == m.chairId) " (chair)" else ""
            append("| ").append(p.id)
                .append(" | ").append(Md.cell(p.name)).append(chair)
                .append(" | ").append(Md.cell(p.role))
                .append(" | ").append(Md.cell(p.organization))
                .append(" | ").append(p.attendance.label)
                .append(" | ").append(m.entries.count { it.speakerId == p.id })
                .append(" |\n")
        }
        val unknown = m.entries.count { it.speakerId == null }
        if (unknown > 0) append("\n").append(unknown).append(" entr").append(if (unknown == 1) "y has" else "ies have").append(" no identified speaker.\n")
        append('\n')
    }

    private fun StringBuilder.agenda(m: Meeting) {
        append("## Agenda\n\n")
        if (m.topics.isEmpty()) {
            append("_No agenda topics recorded._\n\n"); return
        }
        m.topics.forEachIndexed { i, t ->
            append(i + 1).append(". **").append(t.id).append("** ").append(Md.inline(t.title)).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.decisions(m: Meeting) {
        append("## Decisions\n\n")
        val list = m.entries.filter { it.type == EntryType.DECISION }
        if (list.isEmpty()) {
            append("_None recorded._\n\n"); return
        }
        for (e in list) {
            append("- **").append(e.id).append("** · ").append(topicLabel(m, e.topicId))
                .append(" · ").append(speakerName(m, e.speakerId))
                .append(" · ").append(shortTimeFmt.format(zdt(e.atMillis)))
                .append(" — ").append(Md.block(e.text, "  ")).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.actions(m: Meeting) {
        append("## Action items\n\n")
        val list = m.entries.filter { it.type == EntryType.ACTION }
        if (list.isEmpty()) {
            append("_None recorded._\n\n"); return
        }
        append("| Ref | Action | Owner | Due | Raised by | Topic |\n")
        append("|---|---|---|---|---|---|\n")
        for (e in list) {
            append("| ").append(e.id)
                .append(" | ").append(Md.cell(e.text))
                .append(" | ").append(m.participant(e.actionOwnerId)?.let { Md.cell(it.name) } ?: "_unassigned_")
                .append(" | ").append(e.actionDue?.let { Md.cell(it) } ?: "_no date_")
                .append(" | ").append(Md.cell(speakerName(m, e.speakerId)))
                .append(" | ").append(Md.cell(topicLabel(m, e.topicId)))
                .append(" |\n")
        }
        append('\n')
    }

    private fun StringBuilder.openQuestions(m: Meeting) {
        val answered = m.entries.mapNotNull { it.replyToId }.toSet()
        val open = m.entries.filter { it.type == EntryType.QUESTION && it.id !in answered }
        append("## Open questions\n\n")
        if (open.isEmpty()) {
            append("_None: every recorded question has a reply._\n\n"); return
        }
        for (e in open) {
            append("- **").append(e.id).append("** · ").append(topicLabel(m, e.topicId))
                .append(" · ").append(speakerName(m, e.speakerId))
                .append(" — ").append(Md.block(e.text, "  ")).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.discussion(m: Meeting) {
        append("## Discussion by topic\n\n")
        if (m.entries.isEmpty()) {
            append("_No entries recorded._\n\n"); return
        }
        val byId = m.entries.associateBy { it.id }
        val order = m.entries.withIndex().associate { it.value.id to it.index }
        // A reply is nested under its parent only if the parent is earlier and in the same topic;
        // otherwise it starts its own thread and keeps the explicit "re E-xxx" link.
        fun parentOf(e: Entry): Entry? {
            val p = byId[e.replyToId] ?: return null
            return p.takeIf { it.topicId == e.topicId && (order[it.id] ?: Int.MAX_VALUE) < (order[e.id] ?: -1) }
        }
        val children = m.entries.groupBy { parentOf(it)?.id }
        val printed = HashSet<String>()

        fun render(e: Entry, depth: Int) {
            if (!printed.add(e.id)) return
            val d = minOf(depth, MAX_DEPTH)
            val indent = "  ".repeat(d)
            append(indent).append("- ").append(entryLine(m, e, indent + "  ")).append('\n')
            for (c in children[e.id].orEmpty()) render(c, depth + 1)
        }

        val sections = m.topics.map { it.id to "${it.id} — ${it.title}" } + (null to "General (no topic)")
        for ((topicId, heading) in sections) {
            val roots = m.entries.filter { it.topicId == topicId && parentOf(it) == null }
            if (roots.isEmpty()) continue
            append("### ").append(Md.inline(heading)).append("\n\n")
            for (r in roots) render(r, 0)
            append('\n')
        }
        // Safety net: anything not reached (should not happen) is still written out once.
        val missed = m.entries.filter { it.id !in printed }
        if (missed.isNotEmpty()) {
            append("### Other entries\n\n")
            for (e in missed) if (printed.add(e.id)) append("- ").append(entryLine(m, e, "  ")).append('\n')
            append('\n')
        }
    }

    private fun StringBuilder.chronological(m: Meeting) {
        append("## Chronological transcript\n\n")
        if (m.entries.isEmpty()) {
            append("_No entries recorded._\n\n"); return
        }
        for (e in m.entries) {
            append("- `").append(e.id).append("` ").append(timeFmt.format(zdt(e.atMillis)))
                .append(" · ").append(Md.inline(e.topicId ?: "—"))
                .append(" · **").append(Md.inline(speakerName(m, e.speakerId))).append("**: ")
                .append(Md.block(e.text, "  ")).append('\n')
        }
        append('\n')
    }

    // ---- pieces ------------------------------------------------------------------------------

    /** `E-002` **Sara** · 09:04:00 · reply re E-001 · speech: text */
    private fun entryLine(m: Meeting, e: Entry, indent: String): String = buildString {
        append('`').append(e.id).append("` **").append(Md.inline(speakerName(m, e.speakerId))).append("** · ")
        append(timeFmt.format(zdt(e.atMillis)))
        e.audioOffsetMillis?.let { append(" · audio ").append(clock(it)) }
        append(" · ").append(e.type.label.lowercase(Locale.ROOT))
        e.replyToId?.let { append(" re ").append(it) }
        if (e.type == EntryType.ACTION) {
            append(" (owner: ").append(m.participant(e.actionOwnerId)?.let { Md.inline(it.name) } ?: "unassigned")
            append(", due: ").append(e.actionDue?.let { Md.inline(it) } ?: "no date").append(')')
        }
        append(" · ").append(if (e.source == Source.SPEECH) "speech" else "typed")
        if (e.edited) append(", edited")
        val c = e.confidence
        if (e.source == Source.SPEECH && !e.edited && c != null && c < LOW_CONFIDENCE) append(", low confidence")
        append(": ").append(Md.block(e.text, indent))
    }

    private fun speakerName(m: Meeting, id: String?): String = m.participant(id)?.name ?: "Unknown speaker"

    private fun topicLabel(m: Meeting, id: String?): String = m.topic(id)?.let { "${it.id} ${it.title}" } ?: "General"

    private fun personLabel(p: Participant): String {
        val extra = listOf(p.role, p.organization).filter { it.isNotBlank() }
        val base = if (extra.isEmpty()) p.name else "${p.name} (${extra.joinToString(", ")})"
        return "${p.id} $base" + if (p.attendance != Attendance.PRESENT) " [${p.attendance.label.lowercase(Locale.ROOT)}]" else ""
    }

    private fun durationMinutes(m: Meeting): Long? {
        val s = m.startedAt ?: return null
        val e = m.endedAt ?: return null
        return ((e - s).coerceAtLeast(0) + 30_000) / 60_000
    }

    private fun zdt(millis: Long): ZonedDateTime = Instant.ofEpochMilli(millis).atZone(zone)

    companion object {
        const val MAX_DEPTH = 6
        const val LOW_CONFIDENCE = 0.5f

        fun clock(ms: Long): String {
            val total = ms.coerceAtLeast(0) / 1000
            return "%02d:%02d:%02d".format(Locale.ROOT, total / 3600, (total % 3600) / 60, total % 60)
        }
    }
}
