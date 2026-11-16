package com.mypro.rfidguard.core.security

/** Ordered from reassuring to alarming so that max() gives the overall verdict. */
enum class Severity { GOOD, INFO, LOW, MEDIUM, HIGH }

data class Finding(val severity: Severity, val title: String, val detail: String)
