package com.mypro.familytalk.core

import com.mypro.familytalk.core.crypto.IdentityKeys
import com.mypro.familytalk.core.dial.DialPlan
import com.mypro.familytalk.core.dial.DialTarget
import com.mypro.familytalk.core.family.FamilyAdmin
import com.mypro.familytalk.core.family.RosterCodec
import com.mypro.familytalk.core.sip.MediaEncryption
import com.mypro.familytalk.core.sip.SipAccount
import com.mypro.familytalk.core.sip.SipTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DialPlanTest {
    private val dad = IdentityKeys.generate()
    private val roster = RosterCodec.verify(FamilyAdmin(dad).create("F", "Dad"), null, dad.public)
    private val sip = SipAccount("1001", "sip.example.com", "pw")

    @Test fun familyExtensions() {
        assertIs<DialTarget.FamilyMember>(DialPlan.resolve("101", roster, null))
        assertIs<DialTarget.Invalid>(DialPlan.resolve("101", roster, null, myExt = 101))
        assertIs<DialTarget.Invalid>(DialPlan.resolve("150", roster, null))
        assertEquals(DialTarget.Everyone, DialPlan.resolve("0", roster, null))
        assertIs<DialTarget.Invalid>(DialPlan.resolve("800", roster, null))
        assertEquals(DialTarget.EchoTest, DialPlan.resolve("*43", roster, null))
    }

    @Test fun outsideNumbersNeedSip() {
        assertIs<DialTarget.Invalid>(DialPlan.resolve("+966501234567", roster, null))
        val t = DialPlan.resolve("+966 50-123-4567", roster, sip)
        assertEquals(DialTarget.External("sip:+966501234567@sip.example.com;user=phone", "+966501234567"), t)
        assertEquals("sip:0501234567@sip.example.com", (DialPlan.resolve("90501234567", roster, sip) as DialTarget.External).uri)
        assertEquals("sip:bob@pbx.home.lan", (DialPlan.resolve("sip:bob@pbx.home.lan", roster, sip) as DialTarget.External).uri)
        assertIs<DialTarget.Invalid>(DialPlan.resolve("sip:bad uri", roster, sip))
        assertIs<DialTarget.Invalid>(DialPlan.resolve("12", roster, sip))
    }

    @Test fun sipAccountValidation() {
        assertTrue(sip.problems().isEmpty())
        assertTrue(sip.warnings().isEmpty())
        assertTrue(SipAccount("a b", "bad host!", "").problems().size >= 3)
        assertEquals(2, sip.copy(transport = SipTransport.UDP, mediaEncryption = MediaEncryption.NONE).warnings().size)
        assertTrue(!sip.toString().contains("pw"))
    }
}
