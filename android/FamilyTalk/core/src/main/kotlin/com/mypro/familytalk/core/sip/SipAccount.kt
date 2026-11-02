package com.mypro.familytalk.core.sip

import kotlinx.serialization.Serializable

@Serializable
enum class SipTransport { TLS, TCP, UDP }

@Serializable
enum class MediaEncryption { SRTP_MANDATORY, SRTP_OPTIONAL, NONE }

/**
 * Any standard SIP account (VoIP provider, office PBX, home Asterisk/FreeSWITCH).
 * Calls over SIP are only as private as the provider: media is encrypted on the
 * phone-to-provider hop when TLS + SRTP are used, but the provider can hear it, and
 * the mobile network leg (PSTN) is never end-to-end encrypted.
 */
@Serializable
data class SipAccount(
    val username: String,
    val domain: String,
    val password: String,
    val displayName: String = "",
    val authUsername: String? = null,
    val proxy: String? = null,
    val port: Int? = null,
    val transport: SipTransport = SipTransport.TLS,
    val mediaEncryption: MediaEncryption = MediaEncryption.SRTP_MANDATORY,
    val enabled: Boolean = true,
) {
    val identityUri: String get() = "sip:$username@$domain"

    fun uriFor(number: String): String = "sip:$number@$domain" + if (number.startsWith("+")) ";user=phone" else ""

    /** Human-readable problems; empty when the account can be registered. */
    fun problems(): List<String> = buildList {
        if (username.isBlank()) add("Username is required")
        if (username.any { it.isWhitespace() || it == '@' || it == ':' }) add("Username must not contain spaces, @ or :")
        if (!hostOk(domain)) add("Domain must be a host name like sip.example.com")
        if (proxy != null && proxy.isNotBlank() && !hostOk(proxy.removePrefix("sip:").removePrefix("sips:").substringBefore(';').substringBefore(':'))) add("Proxy is not a valid host")
        if (port != null && port !in 1..65535) add("Port must be 1-65535")
        if (password.isEmpty()) add("Password is required")
    }

    /** Things that work but weaken privacy; shown as warnings, not errors. */
    fun warnings(): List<String> = buildList {
        if (transport != SipTransport.TLS) add("Signalling is not encrypted (${transport.name}). Use TLS if your provider supports it.")
        if (mediaEncryption == MediaEncryption.NONE) add("Voice is sent unencrypted (no SRTP).")
        if (mediaEncryption == MediaEncryption.SRTP_OPTIONAL) add("Voice may fall back to unencrypted if the other side lacks SRTP.")
    }

    override fun toString(): String = "SipAccount($identityUri, $transport, $mediaEncryption)" // never log the password

    private fun hostOk(h: String) = h.length in 1..253 && Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$").matches(h)
}
