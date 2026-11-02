package com.mypro.familytalk.core.family

/**
 * Internal numbering plan.
 *
 *   101-199  family members (101 = whoever created the family)
 *   0        ring everyone ("family call") -> conference
 *            (add more people to any call with "Add person")
 *   9<num>   external number via the SIP account (9 = "outside line", like an office PBX)
 *   +<num>   external number via the SIP account
 *   *43      echo test (checks mic / speaker / network locally)
 */
object Extensions {
    val MEMBER_RANGE = 101..199
    const val EVERYONE = "0"
    const val ECHO_TEST = "*43"
    const val OUTSIDE_LINE = '9'

    fun nextFree(taken: Collection<Int>): Int =
        MEMBER_RANGE.firstOrNull { it !in taken } ?: throw IllegalStateException("family is full (99 members)")
}
