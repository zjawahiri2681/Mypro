package com.mypro.minutes.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingOpsTest {
    private val t0 = 1_793_000_000_000L

    private fun base(): Meeting {
        var m = MeetingOps.newMeeting("m1", "Weekly progress", t0)
        m = MeetingOps.addParticipant(m, "Ahmed", "PM", "Contractor")
        m = MeetingOps.addParticipant(m, "Sara", "QA/QC", "Client")
        m = MeetingOps.addTopic(m, "Safety")
        m = MeetingOps.addTopic(m, "Progress")
        return MeetingOps.start(m, t0)
    }

    private fun Meeting.add(d: EntryDraft, at: Long): Meeting = MeetingOps.addEntry(this, d, at).first

    @Test fun idsAreSequentialAndNeverReused() {
        var m = base()
        m = m.add(EntryDraft("one", "P1", "T1"), t0 + 1000)
        m = m.add(EntryDraft("two", "P2", "T1"), t0 + 2000)
        m = MeetingOps.deleteEntry(m, "E-002")
        m = m.add(EntryDraft("three", "P2", "T1"), t0 + 3000)
        assertEquals(listOf("E-001", "E-003"), m.entries.map { it.id })
    }

    @Test fun duplicateAndBlankParticipantsAreRejected() {
        var m = base()
        m = MeetingOps.addParticipant(m, "  ahmed ")
        m = MeetingOps.addParticipant(m, "   ")
        assertEquals(2, m.participants.size)
    }

    @Test fun replyDefaultsToLatestEntryByAnotherSpeakerInSameTopic() {
        var m = base()
        m = m.add(EntryDraft("Scaffold tags missing", "P1", "T1"), t0 + 1000)
        m = m.add(EntryDraft("Progress is 40%", "P1", "T2"), t0 + 2000)
        m = m.add(EntryDraft("We will retag today", "P2", "T1", type = EntryType.REPLY), t0 + 3000)
        assertEquals("E-001", m.entries.last().replyToId)
    }

    @Test fun deletingParentReattachesRepliesToGrandparent() {
        var m = base()
        m = m.add(EntryDraft("Q", "P1", "T1", type = EntryType.QUESTION), t0 + 1000)
        m = m.add(EntryDraft("A", "P2", "T1", type = EntryType.REPLY, replyToId = "E-001"), t0 + 2000)
        m = m.add(EntryDraft("A2", "P1", "T1", type = EntryType.REPLY, replyToId = "E-002"), t0 + 3000)
        m = MeetingOps.deleteEntry(m, "E-002")
        assertEquals("E-001", m.entry("E-003")?.replyToId)
    }

    @Test fun editCannotPointReplyForwardOrAtItself() {
        var m = base()
        m = m.add(EntryDraft("first", "P1", "T1"), t0 + 1000)
        m = m.add(EntryDraft("second", "P2", "T1"), t0 + 20_000)
        m = MeetingOps.updateEntry(m, m.entry("E-001")!!.copy(replyToId = "E-002"))
        assertNull(m.entry("E-001")?.replyToId)
        m = MeetingOps.updateEntry(m, m.entry("E-002")!!.copy(replyToId = "E-002"))
        assertNull(m.entry("E-002")?.replyToId)
    }

    @Test fun speechChunksOfSameTurnMergeButNewSpeakerSplits() {
        var m = base()
        m = m.add(EntryDraft("we need", "P1", "T1", source = Source.SPEECH, confidence = 0.9f), t0 + 1000)
        m = m.add(EntryDraft("two more cranes", "P1", "T1", source = Source.SPEECH, confidence = 0.4f), t0 + 6000)
        m = m.add(EntryDraft("agreed", "P2", "T1", source = Source.SPEECH), t0 + 8000)
        assertEquals(2, m.entries.size)
        assertEquals("we need two more cranes", m.entries[0].text)
        assertEquals(0.4f, m.entries[0].confidence)
        // A long pause starts a new entry even for the same speaker.
        m = m.add(EntryDraft("later", "P2", "T1", source = Source.SPEECH), t0 + 60_000)
        assertEquals(3, m.entries.size)
    }

    @Test fun typedEntriesNeverMerge() {
        var m = base()
        m = m.add(EntryDraft("a", "P1", "T1"), t0 + 1000)
        m = m.add(EntryDraft("b", "P1", "T1"), t0 + 1500)
        assertEquals(2, m.entries.size)
    }

    @Test fun actionFieldsOnlyKeptForActions() {
        var m = base()
        m = m.add(EntryDraft("Send method statement", "P2", "T2", type = EntryType.ACTION, actionOwnerId = "P1", actionDue = "2026/11/09"), t0 + 1000)
        m = m.add(EntryDraft("Not an action", "P2", "T2", actionOwnerId = "P1", actionDue = "2026-11-09"), t0 + 2000)
        assertEquals("2026-11-09", m.entries[0].actionDue)
        assertEquals("P1", m.entries[0].actionOwnerId)
        assertNull(m.entries[1].actionDue)
        assertNull(m.entries[1].actionOwnerId)
    }

    @Test fun invalidDueIsKeptAsTypedButFlagged() {
        assertEquals("next Tuesday", MeetingOps.normalizeDue("next Tuesday"))
        assertFalse(MeetingOps.isValidDue("2026-02-30"))
        assertTrue(MeetingOps.isValidDue(""))
    }

    @Test fun speakersWhoSpokeCannotBeRemoved() {
        var m = base()
        m = m.add(EntryDraft("x", "P1", "T1"), t0 + 1000)
        assertEquals(2, MeetingOps.removeParticipant(m, "P1").participants.size)
        assertEquals(1, MeetingOps.removeParticipant(m, "P2").participants.size)
        assertEquals(2, MeetingOps.removeTopic(m, "T1").topics.size)
    }

    @Test fun blankTextIsIgnoredAndControlCharsStripped() {
        var m = base()
        val (m2, e) = MeetingOps.addEntry(m, EntryDraft(" \n\t "), t0)
        assertNull(e)
        assertEquals(0, m2.entries.size)
        m = m.add(EntryDraft("a\u0007b\r\nc"), t0)
        assertEquals("ab\nc", m.entries[0].text)
    }

    @Test fun jsonRoundTripIsLosslessAndToleratesUnknownFields() {
        var m = base()
        m = m.add(EntryDraft("سلامة الموقع أولاً", "P1", "T1", source = Source.SPEECH, confidence = 0.8f), t0 + 1000)
        val back = MeetingJson.decode(MeetingJson.encode(m))
        assertEquals(m, back)
        val future = MeetingJson.encode(m).replaceFirst("{", "{\"someFutureField\":42,")
        assertNotNull(MeetingJson.decode(future))
    }

    @Test fun fileNameFollowsDateTitleConventionAndIsSafe() {
        val m = base().copy(title = "Site / QA: \"walk\"?  <TGP>")
        val name = FileNames.markdown(m, java.time.ZoneId.of("Asia/Riyadh"))
        assertTrue(name.matches(Regex("\\d{4}-\\d{2}-\\d{2} .+\\.md")), name)
        assertFalse(name.substring(11).any { it in "\\/:*?\"<>|" }, name)
        assertEquals("2026-10-26 Site QA walk TGP.md", name)
    }
}
