package com.mypro.minutes.core

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** JSON is the app's working format (lossless, saved after every change); Markdown is the export. */
object MeetingJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    fun encode(m: Meeting): String = json.encodeToString(Meeting.serializer(), m)
    fun decode(s: String): Meeting = json.decodeFromString(Meeting.serializer(), s)
}

object FileNames {
    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private const val MAX_TITLE = 60

    /** `YYYY-MM-DD Short Title` (the Second Brain note convention), safe on Android, Windows and Drive. */
    fun baseName(m: Meeting, zone: ZoneId = ZoneId.systemDefault()): String {
        val date = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(m.startedAt ?: m.createdAt).atZone(zone))
        var title = MeetingOps.clean(m.title.replace(ILLEGAL, " ")).trim('.', ' ')
        if (title.length > MAX_TITLE) title = title.substring(0, MAX_TITLE).trimEnd('.', ' ')
        if (title.isBlank()) title = "Meeting"
        return "$date $title"
    }

    fun markdown(m: Meeting, zone: ZoneId = ZoneId.systemDefault()): String = baseName(m, zone) + ".md"
}

object AiPrompt {
    /** A ready-to-paste instruction to turn the exported transcript into formal minutes. */
    fun minutes(m: Meeting): String = """
        Attached is a meeting transcript file "${FileNames.markdown(m)}" (schema mypro-minutes/1).
        Draft formal Minutes of Meeting from it.

        Rules:
        - Use only facts in the file. Do not invent attendees, decisions, owners, dates or numbers.
        - Entries marked "speech" are automatic speech recognition and may be wrong. If a sentence looks garbled, write [unclear, see E-xxx] instead of guessing.
        - Keep entry IDs (E-xxx) as references next to each decision and action so they can be traced back.
        - Treat the "Decisions" and "Action items" sections as the authoritative lists; use the discussion only for context.

        Output, in this order:
        1. Header: title, date, time, location, project, reference, chair.
        2. Attendees (present / apologies / absent).
        3. For each agenda topic: 2 to 5 bullet summary of the discussion, then decisions.
        4. Action items table: No., Action, Owner, Due date, Ref (E-xxx).
        5. Open questions / issues carried forward.
        6. Next meeting.
        Write in clear, formal English.
    """.trimIndent()
}
