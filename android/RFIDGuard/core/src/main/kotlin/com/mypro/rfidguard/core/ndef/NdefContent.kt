package com.mypro.rfidguard.core.ndef

import java.io.ByteArrayOutputStream

/** Human-level meaning of a record. */
sealed interface NdefContent {
    data class Text(val language: String, val text: String) : NdefContent
    data class Uri(val uri: String) : NdefContent
    data class SmartPoster(val uri: String?, val title: String?) : NdefContent
    data class Wifi(val ssid: String, val auth: String, val password: String) : NdefContent
    data class VCard(val text: String) : NdefContent
    data class Mime(val mimeType: String, val bytes: ByteArray) : NdefContent
    data class AndroidApp(val packageName: String) : NdefContent
    data class External(val type: String, val bytes: ByteArray) : NdefContent
    /** RFID Guard's own authenticity record (see TagSigner). */
    data class GuardSignature(val bytes: ByteArray) : NdefContent
    data object Empty : NdefContent
    data class Unknown(val tnf: Int, val type: String) : NdefContent
}

object NdefContents {
    const val WIFI_MIME = "application/vnd.wfa.wsc"
    const val AAR_TYPE = "android.com:pkg"
    const val SIGNATURE_TYPE = "rfidguard.local:sig"

    private val URI_PREFIXES = arrayOf(
        "", "http://www.", "https://www.", "http://", "https://", "tel:", "mailto:",
        "ftp://anonymous:anonymous@", "ftp://ftp.", "ftps://", "sftp://", "smb://", "nfs://", "ftp://",
        "dav://", "news:", "telnet://", "imap:", "rtsp://", "urn:", "pop:", "sip:", "sips:", "tftp:",
        "btspp://", "btl2cap://", "btgoep://", "tcpobex://", "irdaobex://", "file://", "urn:epc:id:",
        "urn:epc:tag:", "urn:epc:pat:", "urn:epc:raw:", "urn:epc:", "urn:nfc:",
    )

    fun decode(r: NdefRecordData): NdefContent {
        val type = String(r.type, Charsets.US_ASCII)
        return try {
            when (r.tnf) {
                NdefRecordData.TNF_EMPTY -> NdefContent.Empty
                NdefRecordData.TNF_WELL_KNOWN -> when (type) {
                    "T" -> decodeText(r.payload)
                    "U" -> NdefContent.Uri(decodeUri(r.payload))
                    "Sp" -> decodeSmartPoster(r.payload)
                    else -> NdefContent.Unknown(r.tnf, type)
                }
                NdefRecordData.TNF_MIME -> when {
                    type.equals(WIFI_MIME, true) -> decodeWifi(r.payload)
                    type.equals("text/vcard", true) || type.equals("text/x-vcard", true) ->
                        NdefContent.VCard(String(r.payload, Charsets.UTF_8))
                    else -> NdefContent.Mime(type, r.payload)
                }
                NdefRecordData.TNF_ABSOLUTE_URI -> NdefContent.Uri(type)
                NdefRecordData.TNF_EXTERNAL -> when {
                    type.equals(AAR_TYPE, true) -> NdefContent.AndroidApp(String(r.payload, Charsets.UTF_8))
                    type.equals(SIGNATURE_TYPE, true) -> NdefContent.GuardSignature(r.payload)
                    else -> NdefContent.External(type, r.payload)
                }
                else -> NdefContent.Unknown(r.tnf, type)
            }
        } catch (e: RuntimeException) {
            NdefContent.Unknown(r.tnf, type)
        }
    }

    private fun decodeText(p: ByteArray): NdefContent {
        if (p.isEmpty()) return NdefContent.Text("", "")
        val status = p[0].toInt() and 0xFF
        val langLen = status and 0x3F
        if (1 + langLen > p.size) throw IllegalArgumentException("bad text record")
        val lang = String(p, 1, langLen, Charsets.US_ASCII)
        val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
        return NdefContent.Text(lang, String(p, 1 + langLen, p.size - 1 - langLen, charset))
    }

    private fun decodeUri(p: ByteArray): String {
        if (p.isEmpty()) return ""
        val code = p[0].toInt() and 0xFF
        val prefix = if (code < URI_PREFIXES.size) URI_PREFIXES[code] else ""
        return prefix + String(p, 1, p.size - 1, Charsets.UTF_8)
    }

    private fun decodeSmartPoster(p: ByteArray): NdefContent {
        var uri: String? = null
        var title: String? = null
        for (inner in NdefCodec.parse(p)) {
            when (val c = decode(inner)) {
                is NdefContent.Uri -> if (uri == null) uri = c.uri
                is NdefContent.Text -> if (title == null) title = c.text
                else -> {}
            }
        }
        return NdefContent.SmartPoster(uri, title)
    }

    // ---- Wi-Fi Simple Configuration credential --------------------------------------

    private fun decodeWifi(p: ByteArray): NdefContent {
        var ssid = ""; var auth = "unknown"; var key = ""
        fun walk(b: ByteArray, depth: Int) {
            var pos = 0
            while (pos + 4 <= b.size) {
                val t = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
                val l = ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
                pos += 4
                if (pos + l > b.size) return
                val v = b.copyOfRange(pos, pos + l)
                pos += l
                when (t) {
                    0x100E -> if (depth < 2) walk(v, depth + 1)
                    0x1045 -> ssid = String(v, Charsets.UTF_8)
                    0x1027 -> key = String(v, Charsets.UTF_8)
                    0x1003 -> if (v.size == 2) auth = when (((v[0].toInt() and 0xFF) shl 8) or (v[1].toInt() and 0xFF)) {
                        0x0001 -> "Open"
                        0x0002 -> "WPA-Personal"
                        0x0020 -> "WPA2-Personal"
                        0x0022 -> "WPA/WPA2-Personal"
                        0x0008 -> "WPA-Enterprise"
                        0x0010 -> "WPA2-Enterprise"
                        else -> "unknown"
                    }
                }
            }
        }
        walk(p, 0)
        return NdefContent.Wifi(ssid, auth, key)
    }

    // ---- builders --------------------------------------------------------------------

    fun text(text: String, language: String = "en"): NdefRecordData {
        val lang = language.toByteArray(Charsets.US_ASCII)
        require(lang.size <= 63) { "Language code too long" }
        return NdefRecordData(NdefRecordData.TNF_WELL_KNOWN, "T".toByteArray(), payload = byteArrayOf(lang.size.toByte()) + lang + text.toByteArray(Charsets.UTF_8))
    }

    fun uri(uri: String): NdefRecordData {
        var code = 0
        // Longest matching prefix gives the shortest record.
        for (i in 1 until URI_PREFIXES.size) {
            if (uri.startsWith(URI_PREFIXES[i]) && URI_PREFIXES[i].length > URI_PREFIXES[code].length) code = i
        }
        val rest = uri.substring(URI_PREFIXES[code].length).toByteArray(Charsets.UTF_8)
        return NdefRecordData(NdefRecordData.TNF_WELL_KNOWN, "U".toByteArray(), payload = byteArrayOf(code.toByte()) + rest)
    }

    fun mime(mimeType: String, bytes: ByteArray): NdefRecordData =
        NdefRecordData(NdefRecordData.TNF_MIME, mimeType.toByteArray(Charsets.US_ASCII), payload = bytes)

    fun androidApp(packageName: String): NdefRecordData =
        NdefRecordData(NdefRecordData.TNF_EXTERNAL, AAR_TYPE.toByteArray(), payload = packageName.toByteArray(Charsets.UTF_8))

    fun vcard(name: String, phone: String, email: String, organisation: String): NdefRecordData {
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n")
        val sb = StringBuilder("BEGIN:VCARD\r\nVERSION:3.0\r\n")
        sb.append("FN:").append(esc(name)).append("\r\n")
        if (organisation.isNotBlank()) sb.append("ORG:").append(esc(organisation)).append("\r\n")
        if (phone.isNotBlank()) sb.append("TEL:").append(esc(phone)).append("\r\n")
        if (email.isNotBlank()) sb.append("EMAIL:").append(esc(email)).append("\r\n")
        sb.append("END:VCARD\r\n")
        return mime("text/vcard", sb.toString().toByteArray(Charsets.UTF_8))
    }

    /** [password] empty means an open network. */
    fun wifi(ssid: String, password: String): NdefRecordData {
        fun tlv(type: Int, value: ByteArray): ByteArray =
            byteArrayOf((type ushr 8).toByte(), type.toByte(), (value.size ushr 8).toByte(), value.size.toByte()) + value
        val open = password.isEmpty()
        val cred = ByteArrayOutputStream()
        cred.write(tlv(0x1026, byteArrayOf(1)))
        cred.write(tlv(0x1045, ssid.toByteArray(Charsets.UTF_8)))
        cred.write(tlv(0x1003, if (open) byteArrayOf(0, 0x01) else byteArrayOf(0, 0x20)))
        cred.write(tlv(0x100F, if (open) byteArrayOf(0, 0x01) else byteArrayOf(0, 0x08)))
        cred.write(tlv(0x1027, password.toByteArray(Charsets.UTF_8)))
        cred.write(tlv(0x1020, ByteArray(6) { 0xFF.toByte() }))
        return mime(WIFI_MIME, tlv(0x100E, cred.toByteArray()))
    }
}
