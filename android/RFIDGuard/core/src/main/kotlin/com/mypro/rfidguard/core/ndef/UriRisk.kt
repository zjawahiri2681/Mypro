package com.mypro.rfidguard.core.ndef

import com.mypro.rfidguard.core.security.Finding
import com.mypro.rfidguard.core.security.Severity

/**
 * Static checks on a link found on a tag. A tag is an untrusted input: anyone can stick a
 * tag over a genuine one, so the app never opens a link without showing these results first.
 */
object UriRisk {
    private val SHORTENERS = setOf(
        "bit.ly", "tinyurl.com", "t.co", "goo.gl", "is.gd", "ow.ly", "cutt.ly", "rb.gy",
        "shorturl.at", "tiny.cc", "buff.ly", "rebrand.ly", "t.ly", "qrco.de", "s.id",
    )
    private val ACTION_SCHEMES = mapOf(
        "tel" to "Starts a phone call", "sms" to "Prepares a text message", "smsto" to "Prepares a text message",
        "mmsto" to "Prepares a message", "mailto" to "Prepares an e-mail", "geo" to "Opens a map location",
        "market" to "Opens an app store page", "wifi" to "Configures a Wi-Fi network",
    )
    private val DANGEROUS_SCHEMES = setOf("javascript", "data", "file", "content", "intent", "vbscript", "blob", "jar", "about")
    private val BIDI_OR_INVISIBLE = Regex("[\\u00AD\\u061C\\u200B-\\u200F\\u2028-\\u202E\\u2060-\\u2069\\uFEFF]")

    fun assess(uri: String): List<Finding> {
        val out = ArrayList<Finding>()
        if (uri.isBlank()) return out
        if (uri.any { it.isISOControl() } || BIDI_OR_INVISIBLE.containsMatchIn(uri)) {
            out += Finding(Severity.HIGH, "Hidden characters in link", "The link contains control or text-direction characters that can disguise where it leads.")
        }
        if (uri.contains('\\')) {
            out += Finding(Severity.MEDIUM, "Backslash in link", "Browsers treat backslashes inconsistently; this is a known trick to hide the real destination.")
        }
        val colon = uri.indexOf(':')
        val scheme = if (colon > 0 && uri.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) uri.substring(0, colon).lowercase() else ""
        when {
            scheme.isEmpty() -> out += Finding(Severity.LOW, "No scheme", "The link has no scheme (such as https:), so what opens it is unpredictable.")
            scheme in DANGEROUS_SCHEMES -> out += Finding(Severity.HIGH, "Dangerous link type ($scheme:)", "This kind of link can run code or reach local files. Do not open it.")
            scheme in ACTION_SCHEMES -> out += Finding(Severity.MEDIUM, "Action link ($scheme:)", ACTION_SCHEMES.getValue(scheme) + ". Check the number or address before continuing.")
            scheme == "http" -> out += Finding(Severity.MEDIUM, "Unencrypted connection", "http:// traffic can be read and altered on the network. Prefer https://.")
            scheme == "https" -> {}
            else -> out += Finding(Severity.MEDIUM, "App link ($scheme:)", "This link opens another app on the phone rather than a web page.")
        }
        if (scheme == "http" || scheme == "https" || scheme == "ftp") {
            val rest = uri.substring(colon + 1).trimStart('/', '\\')
            val authority = rest.takeWhile { it != '/' && it != '?' && it != '#' && it != '\\' }
            val at = authority.lastIndexOf('@')
            if (at >= 0) out += Finding(Severity.HIGH, "Disguised address", "Text before '@' is not the site. The real destination is \"${authority.substring(at + 1)}\".")
            var hostPort = authority.substring(at + 1)
            var host = hostPort
            var port: String? = null
            if (hostPort.startsWith("[")) {
                host = hostPort.substringBefore(']') + "]"
                port = hostPort.substringAfter(']', "").removePrefix(":").ifEmpty { null }
            } else if (hostPort.contains(':')) {
                host = hostPort.substringBefore(':'); port = hostPort.substringAfter(':')
            }
            host = host.lowercase().trimEnd('.')
            if (host.isEmpty()) out += Finding(Severity.MEDIUM, "No host", "The link does not name a site.")
            if (host.any { it.code > 127 }) out += Finding(Severity.HIGH, "Look-alike characters in address", "The site name uses non-Latin characters that can imitate a well-known name.")
            if (host.split('.').any { it.startsWith("xn--") }) out += Finding(Severity.HIGH, "Internationalised (punycode) address", "The site name is encoded and may display as a look-alike of a trusted name.")
            val isIp = host.startsWith("[") || Regex("^[0-9.]+$").matches(host) || Regex("^0x[0-9a-f]+$").matches(host)
            if (isIp) out += Finding(Severity.MEDIUM, "Numeric address", "The link points to a raw IP address instead of a named site.")
            if (!isIp && host in SHORTENERS) out += Finding(Severity.MEDIUM, "Shortened link", "A link shortener hides the final destination.")
            if (!isIp && host.count { it == '.' } >= 4) out += Finding(Severity.LOW, "Many sub-domains", "Long chains of sub-domains are often used to imitate a trusted name. The real site is the end of \"$host\".")
            if (port != null && !(scheme == "http" && port == "80") && !(scheme == "https" && port == "443")) {
                out += Finding(Severity.LOW, "Unusual port", "The link uses port $port.")
            }
        }
        if (uri.length > 300) out += Finding(Severity.LOW, "Very long link", "Long links can hide tracking data or the real destination.")
        return out
    }

    fun worst(findings: List<Finding>): Severity = findings.maxOfOrNull { it.severity } ?: Severity.GOOD
}
