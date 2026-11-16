package com.mypro.rfidguard.core.security

import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.ndef.NdefCodec
import com.mypro.rfidguard.core.ndef.NdefContent
import com.mypro.rfidguard.core.ndef.NdefContents
import com.mypro.rfidguard.core.ndef.NdefFormatException
import com.mypro.rfidguard.core.ndef.UriRisk
import com.mypro.rfidguard.core.type2.Type2

/** Turns a scan into plain-language security findings, most serious first. */
object TagAudit {
    fun assess(tag: TagSnapshot, signingKey: ByteArray?): List<Finding> {
        val out = ArrayList<Finding>()
        val uid = Hex.decodeOrNull(tag.uidHex) ?: ByteArray(0)

        if (uid.size == 4 && uid[0].toInt() == 0x08) {
            out += Finding(Severity.INFO, "Random UID", "This chip presents a new random identifier on every tap, so the UID cannot be used to recognise it.")
        }

        var passwordProtected = false
        var readOnly = false
        tag.type2?.let { t2 ->
            val spec = Type2.specFromVersion(t2.versionHex?.let(Hex::decodeOrNull))
            val pages = t2.pagesHex.map { it?.let(Hex::decodeOrNull) }
            val unreadable = pages.count { it == null }
            val page2 = pages.getOrNull(2)
            val cc = pages.getOrNull(3)
            if ((page2 != null && Type2.staticLocked(page2)) || (cc != null && Type2.ccReadOnly(cc))) readOnly = true
            val cfgPage = spec?.configPage
            if (cfgPage != null) {
                val cfg0 = pages.getOrNull(cfgPage); val cfg1 = pages.getOrNull(cfgPage + 1)
                val cfg = if (cfg0 != null && cfg1 != null) Type2.config(cfg0, cfg1) else null
                when {
                    cfg == null -> {
                        passwordProtected = true
                        out += Finding(Severity.INFO, "Memory is read-protected", "$unreadable pages need a password this vault does not hold.")
                    }
                    cfg.protectionActive(spec.totalPages) -> {
                        passwordProtected = true
                        val scope = if (cfg.readProtected) "reading and writing" else "writing"
                        out += Finding(Severity.GOOD, "Password protection is on", "A password is needed for $scope from page ${cfg.auth0}. " +
                            (if (cfg.auth0 > 2) "The lock bits lie outside the protected area, so someone could still make the tag permanently read-only. " else "") +
                            "The chip's password is only 32 bits and travels unencrypted, so it stops casual tampering, not a determined attacker with a sniffer.")
                        if (cfg.authLimit == 0) out += Finding(Severity.LOW, "Unlimited password attempts", "The chip does not limit wrong password attempts, so the password can be brute-forced with patience.")
                    }
                    else -> {}
                }
            } else if (unreadable > 0) {
                out += Finding(Severity.INFO, "Some pages unreadable", "$unreadable pages could not be read.")
            }
            if (spec?.hasOriginalitySignature == true) {
                val sig = t2.signatureHex?.let(Hex::decodeOrNull)
                val family = if (sig != null) Originality.verifiedFamily(uid, sig) else null
                out += if (family != null) Finding(Severity.GOOD, "Genuine NXP signature", "The chip's originality signature is valid for $family. Counterfeit chips normally fail this check.")
                else Finding(Severity.LOW, "Originality not confirmed", "The NXP originality signature is missing or did not verify. The tag may be a compatible clone chip; it can still work normally.")
            }
        }

        tag.classic?.let { c ->
            out += Finding(Severity.HIGH, "MIFARE Classic encryption is broken", "The Crypto-1 cipher on this card was broken years ago: keys can be recovered and the card copied with inexpensive tools. Do not rely on it to protect access or stored value.")
            val defaults = c.sectors.count { s -> s.keyAHex in Classic.WELL_KNOWN_KEYS || s.keyBHex in Classic.WELL_KNOWN_KEYS }
            if (defaults > 0) out += Finding(Severity.MEDIUM, "Factory default keys in use", "$defaults of ${c.sectors.size} sectors open with publicly known keys, so anyone can read or change them.")
            val locked = c.sectors.count { it.keyAHex == null && it.keyBHex == null }
            if (locked > 0) out += Finding(Severity.INFO, "Sectors with unknown keys", "$locked sectors use keys that are not in this vault and were left unread.")
            var badBits = 0; var exposedKeyB = 0
            for (s in c.sectors) {
                val trailer = s.blocksHex.lastOrNull()?.let(Hex::decodeOrNull) ?: continue
                val bits = Classic.accessBits(trailer)
                if (bits == null) badBits++ else if (Classic.keyBReadable(bits[3]) && s.keyAHex !in Classic.WELL_KNOWN_KEYS) exposedKeyB++
            }
            if (badBits > 0) out += Finding(Severity.MEDIUM, "Corrupt access bits", "$badBits sector trailers fail their consistency check and may be permanently locked.")
            if (exposedKeyB > 0) out += Finding(Severity.LOW, "Key B stored as readable data", "In $exposedKeyB sectors key B can simply be read with key A, so it adds no protection.")
        }

        tag.ndef?.let { n ->
            if (!n.writable) readOnly = true
            val records = try {
                n.messageHex?.let(Hex::decodeOrNull)?.let(NdefCodec::parse).orEmpty()
            } catch (e: NdefFormatException) {
                out += Finding(Severity.MEDIUM, "Malformed NDEF data", "The stored message does not follow the NDEF format: ${e.message}.")
                emptyList()
            }
            if (n.writable && !passwordProtected) {
                out += Finding(Severity.MEDIUM, "Anyone can overwrite this tag", "The tag is writable without a password. A passer-by with a phone could replace its content, for example with a malicious link. Lock it or set a password once the content is final.")
            }
            for (r in records) {
                when (val c = NdefContents.decode(r)) {
                    is NdefContent.Uri -> out += UriRisk.assess(c.uri)
                    is NdefContent.SmartPoster -> c.uri?.let { out += UriRisk.assess(it) }
                    is NdefContent.Wifi -> if (c.password.isNotEmpty())
                        out += Finding(Severity.HIGH, "Wi-Fi password stored in clear text", "Anyone who can touch this tag can read the password of \"${c.ssid}\". Use it only for a guest network.")
                    else out += Finding(Severity.LOW, "Open Wi-Fi network", "The tag joins phones to \"${c.ssid}\", which has no password.")
                    is NdefContent.VCard -> out += Finding(Severity.LOW, "Personal contact details", "Contact data on the tag can be read by anyone within a few centimetres.")
                    is NdefContent.AndroidApp -> out += Finding(Severity.INFO, "Launches an app", "Tapping this tag opens or offers to install the app \"${c.packageName}\".")
                    else -> {}
                }
            }
            if (signingKey != null && records.isNotEmpty()) {
                when (TagSigner.verify(signingKey, uid, records)) {
                    TagSigner.Status.VALID -> out += Finding(Severity.GOOD, "Authenticity seal valid", "The content was written by this vault for this exact tag and has not been changed since.")
                    TagSigner.Status.INVALID -> out += Finding(Severity.HIGH, "Authenticity seal broken", "The tag carries an RFID Guard seal that does not match. The content was altered or copied from another tag.")
                    TagSigner.Status.OTHER_KEY -> out += Finding(Severity.INFO, "Sealed by another vault", "The tag carries an RFID Guard seal made with a different vault key, so it cannot be checked here.")
                    TagSigner.Status.NONE -> {}
                }
            }
        }
        if (readOnly) out += Finding(Severity.GOOD, "Read-only", "The tag reports that its content can no longer be changed.")

        if ("IsoDep" in tag.techs && tag.classic == null) {
            out += Finding(Severity.INFO, "Smart card", "This is a processor card (for example a bank, ID or DESFire card). RFID Guard identifies it but deliberately does not read payment or identity data.")
        }
        if (tag.nfcV != null) {
            out += Finding(Severity.INFO, "No access control", "ISO 15693 tags of this kind are usually readable and writable by any reader unless individual blocks have been locked.")
        }
        out += Finding(Severity.INFO, "The UID is not a secret", "The identifier is sent to every reader and can be imitated. Systems that grant access on the UID alone can be defeated by cloning.")
        return out.sortedByDescending { it.severity }
    }

    fun worst(findings: List<Finding>): Severity =
        findings.filter { it.severity != Severity.GOOD }.maxOfOrNull { it.severity } ?: Severity.GOOD
}
