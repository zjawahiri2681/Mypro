package com.mypro.rfidguard.ui

import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.ndef.NdefContent
import java.text.DateFormat
import java.util.Date

fun formatUid(hex: String): String = hex.chunked(2).joinToString(":")

fun formatTime(epochMs: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))

private fun ascii(bytes: ByteArray): String = String(CharArray(bytes.size) { i ->
    val c = bytes[i].toInt() and 0xFF
    if (c in 0x20..0x7E) c.toChar() else '.'
})

private fun spaced(hex: String): String = hex.chunked(2).joinToString(" ")

/** Short kind and readable text for a decoded record. Secrets are masked unless [reveal]. */
fun describeRecord(content: NdefContent, reveal: Boolean): Pair<String, String> = when (content) {
    is NdefContent.Text -> "Text" + (if (content.language.isNotEmpty()) " (${content.language})" else "") to content.text
    is NdefContent.Uri -> "Link" to content.uri
    is NdefContent.SmartPoster -> "Smart poster" to listOfNotNull(content.title, content.uri).joinToString("\n")
    is NdefContent.Wifi -> "Wi-Fi network" to "Network: ${content.ssid}\nSecurity: ${content.auth}\nPassword: " +
        (if (content.password.isEmpty()) "(none)" else if (reveal) content.password else "••••••••")
    is NdefContent.VCard -> "Contact card" to content.text.trim()
    is NdefContent.Mime -> "Data (${content.mimeType})" to previewBytes(content.bytes)
    is NdefContent.AndroidApp -> "App launch" to content.packageName
    is NdefContent.External -> "Custom (${content.type})" to previewBytes(content.bytes)
    is NdefContent.GuardSignature -> "Authenticity seal" to "RFID Guard seal, ${content.bytes.size} bytes"
    is NdefContent.Empty -> "Empty" to "The tag is formatted and holds no content."
    is NdefContent.Unknown -> "Unknown record" to "Type ${content.type.ifEmpty { "(none)" }}, format ${content.tnf}"
}

private fun previewBytes(bytes: ByteArray): String {
    val head = bytes.copyOf(minOf(bytes.size, 96))
    val printable = head.count { (it.toInt() and 0xFF) in 0x20..0x7E || it.toInt() == 0x0A || it.toInt() == 0x0D }
    val body = if (head.isNotEmpty() && printable * 10 >= head.size * 9) String(head, Charsets.UTF_8) else spaced(Hex.encode(head))
    return body + if (bytes.size > head.size) " … (${bytes.size} bytes)" else ""
}

/** Monospace memory listing for the technical view. */
fun memoryDump(tag: TagSnapshot): String? {
    val sb = StringBuilder()
    tag.type2?.let { d ->
        for ((i, p) in d.pagesHex.withIndex()) {
            sb.append(i.toString().padStart(3)).append("  ")
            if (p == null) sb.append("-- -- -- --  protected") else sb.append(spaced(p)).append("  ").append(ascii(Hex.decodeOrNull(p) ?: ByteArray(0)))
            sb.append('\n')
        }
    }
    tag.classic?.let { d ->
        for (s in d.sectors) {
            sb.append("Sector ").append(s.index)
            sb.append(when {
                s.keyAHex != null && s.keyBHex != null -> "  keys A+B known"
                s.keyAHex != null -> "  key A known"
                s.keyBHex != null -> "  key B known"
                else -> "  locked"
            }).append('\n')
            for ((i, b) in s.blocksHex.withIndex()) {
                sb.append((s.firstBlock + i).toString().padStart(3)).append("  ")
                sb.append(if (b == null) "unreadable" else b.chunked(8).joinToString(" "))
                sb.append('\n')
            }
            s.blocksHex.lastOrNull()?.let(Hex::decodeOrNull)?.let { trailer ->
                val bits = Classic.accessBits(trailer)
                if (bits == null) sb.append("     access bits corrupt\n")
                else {
                    sb.append("     data: ").append(Classic.describeDataAccess(bits[0])).append('\n')
                    sb.append("     trailer: ").append(Classic.describeTrailerAccess(bits[3])).append('\n')
                }
            }
        }
    }
    tag.nfcV?.let { d ->
        for ((i, b) in d.blocksHex.withIndex()) {
            sb.append(i.toString().padStart(3)).append("  ")
            if (b == null) sb.append("unreadable") else sb.append(spaced(b)).append("  ").append(ascii(Hex.decodeOrNull(b) ?: ByteArray(0)))
            sb.append('\n')
        }
    }
    return sb.toString().trimEnd().ifEmpty { null }
}
