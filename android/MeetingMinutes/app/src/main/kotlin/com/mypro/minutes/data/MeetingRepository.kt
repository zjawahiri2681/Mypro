package com.mypro.minutes.data

import android.content.Context
import com.mypro.minutes.core.Meeting
import com.mypro.minutes.core.MeetingJson
import java.io.File

/**
 * Meetings live as JSON in app-private storage (not readable by other apps, excluded from backup).
 * Every change is written to a temp file and renamed over the old one, so a crash mid-write can
 * never leave a half-written meeting.
 */
class MeetingRepository(context: Context) {
    private val dir = File(context.filesDir, "meetings").apply { mkdirs() }
    val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
    val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("minutes", Context.MODE_PRIVATE)

    /** Meetings that could not be read are skipped (and left on disk untouched). */
    fun list(): List<Meeting> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.mapNotNull { f -> runCatching { MeetingJson.decode(f.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun load(id: String): Meeting? = runCatching { MeetingJson.decode(File(dir, "$id.json").readText()) }.getOrNull()

    @Synchronized
    fun save(m: Meeting) {
        val target = File(dir, "${m.id}.json")
        val tmp = File(dir, "${m.id}.json.tmp")
        tmp.writeText(MeetingJson.encode(m))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun delete(m: Meeting) {
        File(dir, "${m.id}.json").delete()
        for (a in m.audioFiles) File(audioDir, a).delete()
        if (activeMeetingId == m.id) activeMeetingId = null
    }

    fun audioFile(name: String): File = File(audioDir, name)

    /** The meeting that was open on the live screen, so it reopens after the app is killed. */
    var activeMeetingId: String?
        get() = prefs.getString("active", null)
        set(v) { prefs.edit().apply { if (v == null) remove("active") else putString("active", v) }.apply() }
}
