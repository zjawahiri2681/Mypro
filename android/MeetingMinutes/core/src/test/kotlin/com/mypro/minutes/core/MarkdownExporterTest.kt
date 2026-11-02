package com.mypro.minutes.core

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.BulletList
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownExporterTest {
    private val zone = ZoneId.of("Asia/Riyadh")
    private val exporter = MarkdownExporter(zone)
    private val parser = Parser.builder().extensions(listOf(TablesExtension.create())).build()
    private val t0 = 1_793_000_000_000L // 2026-10-26 10:33:20 +03:00

    private fun sample(): Meeting {
        var m = MeetingOps.newMeeting("m1", "Weekly progress", t0)
        m = MeetingOps.addParticipant(m, "Ahmed", "PM", "Contractor")
        m = MeetingOps.addParticipant(m, "Sara", "QA/QC", "Client")
        m = MeetingOps.addParticipant(m, "Omar")
        m = m.copy(chairId = "P1", project = "PROJECT - TGP Disposal Wells", reference = "Contract 6600001234", consentConfirmed = true)
        m = MeetingOps.addTopic(m, "Safety")
        m = MeetingOps.addTopic(m, "Progress")
        m = MeetingOps.start(m, t0)
        fun add(d: EntryDraft, dt: Long) { m = MeetingOps.addEntry(m, d, t0 + dt).first }
        add(EntryDraft("Are the scaffold tags up to date?", "P2", "T1", type = EntryType.QUESTION), 60_000)
        add(EntryDraft("Two towers still need inspection.", "P1", "T1", type = EntryType.REPLY), 90_000)
        add(EntryDraft("Then inspect them before Thursday.", "P2", "T1", type = EntryType.REPLY), 120_000)
        add(EntryDraft("No work on untagged scaffolds.", "P1", "T1", type = EntryType.DECISION), 150_000)
        add(EntryDraft("Inspect towers 3 and 4", "P2", "T1", type = EntryType.ACTION, actionOwnerId = "P3", actionDue = "2026-10-29"), 180_000)
        add(EntryDraft("What is the revised date for the pipe rack?", "P3", "T2", type = EntryType.QUESTION), 240_000)
        add(EntryDraft("progress is about forty percent", "P1", "T2", source = Source.SPEECH, confidence = 0.3f), 300_000)
        add(EntryDraft("Bring the photos next week.", null, null, type = EntryType.NOTE), 360_000)
        return MeetingOps.end(m, t0 + 3_600_000)
    }

    private fun parse(md: String): Node = parser.parse(md.substringAfter("\n---\n")) // body only

    private fun <T : Node> Node.all(type: Class<T>): List<T> {
        val out = mutableListOf<T>()
        accept(object : AbstractVisitor() {
            override fun visitChildren(parent: Node) {
                var n = parent.firstChild
                while (n != null) {
                    val next = n.next
                    if (type.isInstance(n)) out += type.cast(n)
                    visitChildren(n)
                    n = next
                }
            }
        })
        return out
    }

    private fun headingText(h: Heading) = h.all(Text::class.java).joinToString("") { it.literal }

    @Test fun frontMatterIsFirstAndClosed() {
        val md = exporter.export(sample())
        assertTrue(md.startsWith("---\ntype: meeting-transcript\nschema: mypro-minutes/1\n"))
        val fm = md.substring(4, md.indexOf("\n---\n", 4))
        assertTrue("title: \"Weekly progress\"" in fm)
        assertTrue("start: \"2026-10-26T10:33:20+03:00\"" in fm)
        assertTrue("duration_minutes: 60" in fm)
        assertTrue("chair: \"Ahmed\"" in fm)
        assertTrue("entry_count: 8" in fm)
        assertTrue("  - \"P2 Sara (QA/QC, Client)\"" in fm)
    }

    @Test fun headingsAreExactlyTheSchemaSections() {
        val doc = parse(exporter.export(sample(), MarkdownExporter.Options(includeChronological = true)))
        val h = doc.all(Heading::class.java).map { it.level to headingText(it) }
        assertEquals(
            listOf(
                1 to "Weekly progress", 2 to "Meeting details", 2 to "Attendees", 2 to "Agenda", 2 to "Decisions",
                2 to "Action items", 2 to "Open questions", 2 to "Discussion by topic",
                3 to "T1 — Safety", 3 to "T2 — Progress", 3 to "General (no topic)", 2 to "Chronological transcript",
            ),
            h,
        )
    }

    @Test fun everyEntryAppearsExactlyOnceInTheDiscussion() {
        val m = sample()
        val md = exporter.export(m)
        val discussion = md.substringAfter("## Discussion by topic").substringBefore("\n## ")
        for (e in m.entries) assertEquals(1, Regex("`${e.id}`").findAll(discussion).count(), e.id)
    }

    @Test fun repliesAreNestedUnderWhatTheyAnswer() {
        val md = exporter.export(sample())
        assertTrue("- `E-001` **Sara**" in md)
        assertTrue("\n  - `E-002` **Ahmed** · 10:34:50 · reply re E-001 · typed: Two towers" in md, md)
        assertTrue("\n    - `E-003` **Sara**" in md)
        // Parsed structure agrees: E-003 sits two list levels below E-001.
        val doc = parse(md)
        val item3 = doc.all(ListItem::class.java).last { li -> li.all(Text::class.java).any { "Then inspect" in it.literal } }
        var depth = 0
        var n: Node? = item3.parent
        while (n != null) { if (n is BulletList) depth++; n = n.parent }
        assertEquals(3, depth)
    }

    @Test fun derivedListsAreRight() {
        val md = exporter.export(sample())
        val decisions = md.substringAfter("## Decisions").substringBefore("\n## ")
        assertTrue("**E-004** · T1 Safety · Ahmed" in decisions)
        val actions = md.substringAfter("## Action items").substringBefore("\n## ")
        assertTrue("| E-005 | Inspect towers 3 and 4 | Omar | 2026-10-29 | Sara | T1 Safety |" in actions, actions)
        val open = md.substringAfter("## Open questions").substringBefore("\n## ")
        assertTrue("E-006" in open)
        assertFalse("E-001" in open) // answered by E-002
        assertTrue("speech, low confidence" in md)
        assertTrue("1 entry has no identified speaker." in md)
    }

    @Test fun hostileTextCannotChangeDocumentStructure() {
        var m = sample()
        val nasty = "# Fake heading\n---\n| a | b |\n|---|---|\n- fake item\n1. fake ordered\n> quote\n<script>x</script> [link](http://x) `code` *bold* ==="
        m = MeetingOps.addEntry(m, EntryDraft(nasty, "P1", "T1"), t0 + 400_000).first
        m = MeetingOps.addEntry(m, EntryDraft(nasty, "P1", "T1", type = EntryType.ACTION, actionOwnerId = "P2"), t0 + 410_000).first
        m = MeetingOps.updateParticipant(m, m.participant("P3")!!.copy(name = "Omar | \"the\" ## boss", role = "---"))
        m = m.copy(title = "Title \"quoted\" --- # x", location = "Line1\nLine2")
        val md = exporter.export(m, MarkdownExporter.Options(includeChronological = true))

        // Front matter still closes where it should and stays one key per line.
        val fm = md.substring(4, md.indexOf("\n---\n", 4))
        assertTrue(fm.lines().all { it.isEmpty() || it.startsWith("  - ") || Regex("^[a-z_]+:( |$)").containsMatchIn(it) }, fm)
        assertTrue("title: \"Title \\\"quoted\\\" --- # x\"" in fm)

        val doc = parse(md)
        // No extra headings, no thematic breaks, no HTML, no links.
        assertEquals(12, doc.all(Heading::class.java).size)
        assertEquals(0, doc.all(ThematicBreak::class.java).size)
        assertEquals(0, doc.all(HtmlBlock::class.java).size + doc.all(HtmlInline::class.java).size)
        assertEquals(0, doc.all(Link::class.java).size)
        // Exactly two tables (attendees, actions) and their row counts are unchanged.
        val tables = doc.all(TableBlock::class.java)
        assertEquals(2, tables.size)
        assertEquals(1 + 3, tables[0].all(TableRow::class.java).size)
        assertEquals(1 + 2, tables[1].all(TableRow::class.java).size)
        // The literal text survives (escaped, not lost).
        assertTrue(doc.all(Text::class.java).any { "Fake heading" in it.literal })
    }

    @Test fun arabicTextIsPreserved() {
        var m = sample()
        m = MeetingOps.addEntry(m, EntryDraft("تم الاتفاق على تسليم المخططات يوم الخميس", "P1", "T2", type = EntryType.DECISION), t0 + 500_000).first
        val md = exporter.export(m)
        assertTrue("تم الاتفاق على تسليم المخططات يوم الخميس" in md)
    }

    @Test fun emptyMeetingStillProducesAValidSkeleton() {
        val m = MeetingOps.newMeeting("m0", "", t0)
        val md = exporter.export(m)
        assertTrue("title: \"Meeting\"" in md)
        assertTrue("start: null" in md)
        assertTrue("participants: []" in md)
        assertTrue("_No entries recorded._" in md)
        assertEquals(8, parse(md).all(Heading::class.java).size)
    }

    @Test fun corruptReplyCycleFromDiskDoesNotHangOrDropEntries() {
        val m = sample()
        val cyclic = m.copy(entries = m.entries.map {
            when (it.id) {
                "E-001" -> it.copy(replyToId = "E-002")
                else -> it
            }
        })
        val md = exporter.export(cyclic)
        val discussion = md.substringAfter("## Discussion by topic").substringBefore("\n## ")
        for (e in cyclic.entries) assertEquals(1, Regex("`${e.id}`").findAll(discussion).count(), e.id)
    }

    @Test fun deepThreadsAreCappedButLinksKept() {
        var m = MeetingOps.start(MeetingOps.addParticipant(MeetingOps.addParticipant(MeetingOps.newMeeting("d", "Deep", t0), "A"), "B"), t0)
        for (i in 0 until 12) {
            m = MeetingOps.addEntry(m, EntryDraft("turn $i", if (i % 2 == 0) "P1" else "P2", type = if (i == 0) EntryType.DISCUSSION else EntryType.REPLY), t0 + i * 20_000L).first
        }
        val md = exporter.export(m)
        assertTrue(md.lines().none { it.startsWith(" ".repeat(2 * (MarkdownExporter.MAX_DEPTH + 1)) + "- ") })
        assertTrue("reply re E-011" in md)
    }

    /** Writes a sample export to build/ so the format can be eyeballed (and copied into the README). */
    @Test fun writesSampleExport() {
        val md = exporter.export(sample(), MarkdownExporter.Options(includeChronological = false))
        java.io.File("build/sample-transcript.md").writeText(md)
    }

    @Test fun automaticModeExportsVoiceActivatedCaptureAndRecordsAudio() {
        assertTrue(CaptureMode.AUTOMATIC.recordsAudio)
        assertTrue(CaptureMode.AUDIO_AND_NOTES.recordsAudio)
        assertFalse(CaptureMode.LIVE_TRANSCRIPT.recordsAudio)
        val m = sample().copy(captureMode = CaptureMode.AUTOMATIC)
        val fm = exporter.export(m).let { it.substring(4, it.indexOf("\n---\n", 4)) }
        assertTrue("capture: automatic-voice-activated-audio-recording" in fm, fm)
    }

    @Test fun aiPromptNamesTheFile() {
        val p = AiPrompt.minutes(sample())
        assertTrue("Weekly progress.md" in p)
        assertTrue("E-xxx" in p)
    }
}
