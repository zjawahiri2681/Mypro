package com.mypro.familytalk.core.dial

import com.mypro.familytalk.core.family.Extensions
import com.mypro.familytalk.core.family.Member
import com.mypro.familytalk.core.family.Roster
import com.mypro.familytalk.core.sip.SipAccount

sealed interface DialTarget {
    data class FamilyMember(val member: Member) : DialTarget
    data object Everyone : DialTarget
    /** [uri] is a full sip: URI ready for the SIP engine. */
    data class External(val uri: String, val display: String) : DialTarget
    data object EchoTest : DialTarget
    data class Invalid(val reason: String) : DialTarget
}

object DialPlan {
    private val sipUri = Regex("^sips?:[^@\\s]+@[A-Za-z0-9.-]+(:\\d{1,5})?(;[^\\s]*)?$")

    fun resolve(input: String, roster: Roster?, sip: SipAccount?, myExt: Int? = null): DialTarget {
        val raw = input.trim()
        if (raw.isEmpty()) return DialTarget.Invalid("Enter a number")
        if (raw.startsWith("sip:", true) || raw.startsWith("sips:", true)) {
            if (!sipUri.matches(raw)) return DialTarget.Invalid("Not a valid SIP address")
            return if (sip == null) DialTarget.Invalid("Add a SIP account in Settings to call SIP addresses")
            else DialTarget.External(raw, raw.substringAfter(':'))
        }
        val digits = raw.filter { !it.isWhitespace() && it != '-' && it != '(' && it != ')' }
        if (digits == Extensions.ECHO_TEST) return DialTarget.EchoTest
        if (digits == Extensions.EVERYONE) return DialTarget.Everyone
        if (digits.startsWith("+")) return external(digits, sip)
        if (!digits.all { it.isDigit() }) return DialTarget.Invalid("Use digits, + or a sip: address")
        val n = digits.toIntOrNull()
        if (digits.length == 3 && n != null) {
            if (n in Extensions.MEMBER_RANGE) {
                if (n == myExt) return DialTarget.Invalid("That is your own extension")
                val m = roster?.byExt(n) ?: return DialTarget.Invalid("No one has extension $n")
                return DialTarget.FamilyMember(m)
            }
        }
        if (digits.length > 3 && digits[0] == Extensions.OUTSIDE_LINE) return external(digits.substring(1), sip)
        return DialTarget.Invalid("Family extensions are 101-199. For outside numbers dial 9 first or use +")
    }

    private fun external(number: String, sip: SipAccount?): DialTarget {
        if (sip == null) return DialTarget.Invalid("Outside calls need a SIP account (Settings > SIP)")
        val n = number.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '+') }
        if (n.count { it.isDigit() } < 3 || n.length > 20) return DialTarget.Invalid("Number too short or too long")
        return DialTarget.External(sip.uriFor(n), n)
    }
}
