package com.mypro.rfidguard.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.ndef.NdefCodec
import com.mypro.rfidguard.core.ndef.NdefRecordData
import com.mypro.rfidguard.core.security.TagSigner
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.type2.Type2
import com.mypro.rfidguard.core.type2.Type2Spec
import com.mypro.rfidguard.core.vault.TagPassword
import com.mypro.rfidguard.core.vault.VaultData
import java.io.IOException

/** An operation armed by the user and carried out on the next tag presented. */
sealed class WriteOp(val title: String, val requiredUid: String?) {
    class WriteNdef(val records: List<NdefRecordData>, val sign: Boolean) : WriteOp("Write content", null)
    class Erase(uid: String?) : WriteOp("Erase content", uid)
    class MakeReadOnly(uid: String) : WriteOp("Lock permanently", uid)
    class SetPassword(uid: String, val password: TagPassword, val protectRead: Boolean) : WriteOp("Set tag password", uid)
    class RemovePassword(uid: String, val password: TagPassword) : WriteOp("Remove tag password", uid)
    class ClassicWrite(uid: String, val block: Int, val data: ByteArray, val key: ByteArray, val useKeyB: Boolean, val allowTrailer: Boolean) : WriteOp("Write MIFARE block", uid)
    class Restore(uid: String?, val snapshot: TagSnapshot) : WriteOp("Restore saved copy", uid)
}

class WriteResult(val ok: Boolean, val message: String, val forgetPasswordFor: String? = null, val wrongTag: Boolean = false)

/** Carries out write operations. Every write is read back and compared before success is reported. */
object TagWriter {
    private class Refused(message: String) : Exception(message)

    fun execute(tag: Tag, op: WriteOp, vault: VaultData): WriteResult {
        val uid = tag.id ?: ByteArray(0)
        val uidHex = uid.toHex()
        if (op.requiredUid != null && op.requiredUid != uidHex) {
            return WriteResult(false, "This is a different tag (${formatUid(uidHex)}). Nothing was written. Present tag ${formatUid(op.requiredUid)}.", wrongTag = true)
        }
        return try {
            when (op) {
                is WriteOp.WriteNdef -> {
                    val records = if (op.sign) TagSigner.sign(Hex.decode(vault.signingKeyHex), uid, op.records) else op.records
                    val size = writeNdef(tag, NdefCodec.encode(records), vault.tagPasswords[uidHex])
                    WriteResult(true, "Written and verified: $size bytes" + if (op.sign) ", with authenticity seal." else ".")
                }
                is WriteOp.Erase -> {
                    writeNdef(tag, NdefCodec.encode(emptyList()), vault.tagPasswords[uidHex])
                    WriteResult(true, "Content erased. The tag now holds an empty message.")
                }
                is WriteOp.MakeReadOnly -> makeReadOnly(tag)
                is WriteOp.SetPassword -> setPassword(tag, op)
                is WriteOp.RemovePassword -> removePassword(tag, op, uidHex)
                is WriteOp.ClassicWrite -> classicWrite(tag, op)
                is WriteOp.Restore -> restore(tag, op, vault)
            }
        } catch (e: Refused) {
            WriteResult(false, e.message ?: "Refused")
        } catch (e: IOException) {
            WriteResult(false, "The tag moved away or stopped answering. Hold it still and try again; the content may be incomplete.")
        } catch (e: android.nfc.FormatException) {
            WriteResult(false, "The tag rejected the data format.")
        } catch (e: RuntimeException) {
            WriteResult(false, "Unexpected error: ${e.javaClass.simpleName}")
        }
    }

    fun formatUid(hex: String): String = hex.chunked(2).joinToString(":")

    // ---- NDEF ---------------------------------------------------------------------------

    private fun writeNdef(tag: Tag, message: ByteArray, password: TagPassword?): Int {
        if (password != null) {
            val nfcA = NfcA.get(tag)
            if (nfcA != null) writeNdefAuthenticated(nfcA, message, password)?.let { return it }
        }
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.connect()
            try {
                if (!ndef.isWritable) throw Refused("This tag is read-only.")
                if (message.size > ndef.maxSize) throw Refused("Content needs ${message.size} bytes but the tag holds ${ndef.maxSize}.")
                ndef.writeNdefMessage(NdefMessage(message))
                val back = ndef.ndefMessage?.toByteArray()
                if (back == null || !back.contentEquals(message)) throw Refused("The tag did not keep the data (verification failed). It may be protected or worn out.")
                return message.size
            } finally {
                try { ndef.close() } catch (_: IOException) {}
            }
        }
        val formatable = NdefFormatable.get(tag) ?: throw Refused("This tag type cannot hold NDEF content.")
        formatable.connect()
        try {
            formatable.format(NdefMessage(message))
        } finally {
            try { formatable.close() } catch (_: IOException) {}
        }
        return message.size
    }

    /** NDEF write through raw page commands after PWD_AUTH. Returns null when the password was not accepted. */
    private fun writeNdefAuthenticated(nfcA: NfcA, message: ByteArray, password: TagPassword): Int? {
        val link = Type2Link(nfcA)
        link.open()
        try {
            val spec = Type2.specFromVersion(link.version()) ?: return null
            if (!link.authenticate(password)) return null
            val cc = link.fastRead(3, 3) ?: return null
            if (cc[0].toInt() and 0xFF != 0xE1) throw Refused("The tag is not NDEF formatted.")
            if (Type2.ccReadOnly(cc)) throw Refused("This tag is read-only.")
            val capacity = minOf((cc[2].toInt() and 0xFF) * 8, spec.userBytes)
            val lastPage = Type2.FIRST_USER_PAGE + capacity / 4 - 1
            val area = link.fastReadRange(Type2.FIRST_USER_PAGE, lastPage) ?: throw Refused("The tag memory could not be read.")
            val updated = Type2.buildNdefArea(area, message, capacity)
                ?: throw Refused("Content needs ${message.size} bytes but the tag holds about ${capacity - 3}.")
            writePages(link, Type2.FIRST_USER_PAGE, area, updated)
            val back = link.fastReadRange(Type2.FIRST_USER_PAGE, Type2.FIRST_USER_PAGE + updated.size / 4 - 1)
            if (back == null || !back.contentEquals(updated)) throw Refused("The tag did not keep the data (verification failed).")
            return message.size
        } finally {
            link.close()
        }
    }

    /** Writes [updated] from [firstPage], skipping pages that already hold the right bytes. */
    private fun writePages(link: Type2Link, firstPage: Int, current: ByteArray?, updated: ByteArray): Int {
        var written = 0
        for (i in 0 until updated.size / 4) {
            val page = updated.copyOfRange(i * 4, i * 4 + 4)
            if (current != null && current.size >= i * 4 + 4 && current.copyOfRange(i * 4, i * 4 + 4).contentEquals(page)) continue
            if (!link.write(firstPage + i, page)) throw Refused("The tag refused a write at page ${firstPage + i}. The content is incomplete; write it again.")
            written++
        }
        return written
    }

    private fun makeReadOnly(tag: Tag): WriteResult {
        val ndef = Ndef.get(tag) ?: throw Refused("Only NDEF tags can be locked.")
        ndef.connect()
        try {
            if (!ndef.isWritable) return WriteResult(true, "The tag is already read-only.")
            if (!ndef.canMakeReadOnly()) throw Refused("This tag does not support permanent locking.")
            if (!ndef.makeReadOnly()) throw Refused("The tag refused to lock. If it has a password, remove the password first.")
            return WriteResult(true, "Locked. The content of this tag can never be changed again.")
        } finally {
            try { ndef.close() } catch (_: IOException) {}
        }
    }

    // ---- NTAG / Ultralight EV1 password ---------------------------------------------------

    private class Config(val spec: Type2Spec, val page: Int, val cfg0: ByteArray, val cfg1: ByteArray)

    private fun openConfig(link: Type2Link, password: TagPassword?): Config {
        val spec = Type2.specFromVersion(link.version())
            ?: throw Refused("This chip has no password feature. NTAG210/212/213/215/216 and MIFARE Ultralight EV1 do.")
        val page = spec.configPage ?: throw Refused("This chip has no password feature.")
        if (password != null) link.authenticate(password)
        val cfg = link.fastRead(page, page + 1)
            ?: throw Refused("The tag is protected by a password that is not in this vault.")
        return Config(spec, page, cfg.copyOfRange(0, 4), cfg.copyOfRange(4, 8))
    }

    private fun setPassword(tag: Tag, op: WriteOp.SetPassword): WriteResult {
        val nfcA = NfcA.get(tag) ?: throw Refused("This tag type has no password feature.")
        val pwd = Hex.decode(op.password.pwdHex)
        val pack = Hex.decode(op.password.packHex)
        if (pwd.size != 4 || pack.size != 2) throw Refused("Internal error: malformed password.")
        val link = Type2Link(nfcA)
        link.open()
        try {
            val c = openConfig(link, op.password)
            val current = Type2.config(c.cfg0, c.cfg1)!!
            if (current.configLocked) throw Refused("The configuration of this tag is permanently locked.")
            // Order matters: protection only switches on with the last write (AUTH0), so an
            // interruption before that leaves the tag open rather than locked with unknown values.
            if (!link.write(c.page + 2, pwd)) throw Refused("The tag refused the password. Nothing was changed.")
            if (!link.write(c.page + 3, byteArrayOf(pack[0], pack[1], 0, 0))) throw Refused("The tag refused the acknowledge code. Protection is not active.")
            val access = (c.cfg1[0].toInt() and 0x7F) or (if (op.protectRead) 0x80 else 0x00)
            if (!link.write(c.page + 1, byteArrayOf(access.toByte(), c.cfg1[1], c.cfg1[2], c.cfg1[3]))) throw Refused("The tag refused the access setting. Protection is not active.")
            if (!link.write(c.page, byteArrayOf(c.cfg0[0], c.cfg0[1], c.cfg0[2], Type2.FIRST_USER_PAGE.toByte()))) throw Refused("The tag refused the final step. Protection is not active.")
            // Fresh session: the password must now be accepted and the configuration must read back.
            link.close(); link.open()
            if (!link.authenticate(op.password)) throw Refused("The tag did not accept the new password on verification. Scan the tag to see its state.")
            val check = link.fastRead(c.page, c.page + 1)?.let { Type2.config(it.copyOfRange(0, 4), it.copyOfRange(4, 8)) }
            if (check == null || check.auth0 != Type2.FIRST_USER_PAGE || check.readProtected != op.protectRead) throw Refused("Verification of the protection settings failed. Scan the tag to see its state.")
            val scope = if (op.protectRead) "Reading and writing" else "Writing"
            return WriteResult(true, "$scope the user memory now needs the password, which is stored in your vault. Export a backup so it cannot be lost.")
        } finally {
            link.close()
        }
    }

    private fun removePassword(tag: Tag, op: WriteOp.RemovePassword, uidHex: String): WriteResult {
        val nfcA = NfcA.get(tag) ?: throw Refused("This tag type has no password feature.")
        val link = Type2Link(nfcA)
        link.open()
        try {
            val spec = Type2.specFromVersion(link.version()) ?: throw Refused("This chip has no password feature.")
            val page = spec.configPage ?: throw Refused("This chip has no password feature.")
            val authed = link.authenticate(op.password)
            val cfg = link.fastRead(page, page + 1)
            if (cfg == null) throw Refused("The tag did not accept the stored password.")
            val current = Type2.config(cfg.copyOfRange(0, 4), cfg.copyOfRange(4, 8))!!
            if (!authed && current.protectionActive(spec.totalPages)) throw Refused("The tag did not accept the stored password.")
            if (current.configLocked) throw Refused("The configuration of this tag is permanently locked.")
            if (!link.write(page, byteArrayOf(cfg[0], cfg[1], cfg[2], 0xFF.toByte()))) throw Refused("The tag refused the change. The password is still active.")
            link.write(page + 1, byteArrayOf((cfg[4].toInt() and 0x7F).toByte(), cfg[5], cfg[6], cfg[7]))
            link.write(page + 2, ByteArray(4) { 0xFF.toByte() })
            link.write(page + 3, ByteArray(4))
            val check = link.fastRead(page, page)
            if (check == null || check[3].toInt() and 0xFF != 0xFF) throw Refused("Verification failed. Scan the tag to see its state.")
            return WriteResult(true, "Password protection removed. The tag is open again.", forgetPasswordFor = uidHex)
        } finally {
            link.close()
        }
    }

    // ---- MIFARE Classic -------------------------------------------------------------------

    private fun classicWrite(tag: Tag, op: WriteOp.ClassicWrite): WriteResult {
        val mc = MifareClassic.get(tag) ?: throw Refused("This is not a MIFARE Classic card, or this phone cannot talk to one.")
        if (op.data.size != 16 || op.key.size != 6) throw Refused("A block is 16 bytes and a key is 6 bytes.")
        if (op.block == 0) throw Refused("Block 0 holds the manufacturer data and identifier. RFID Guard never writes it.")
        mc.connect()
        try {
            if (op.block < 0 || op.block >= mc.blockCount) throw Refused("Block ${op.block} does not exist on this card.")
            if (Classic.isTrailer(op.block)) {
                if (!op.allowTrailer) throw Refused("Block ${op.block} is a sector trailer (keys and access bits). Turn on expert mode to write it.")
                Classic.validateTrailerForWrite(op.data)?.let { throw Refused("$it.") }
            }
            val sector = mc.blockToSector(op.block)
            val ok = if (op.useKeyB) mc.authenticateSectorWithKeyB(sector, op.key) else mc.authenticateSectorWithKeyA(sector, op.key)
            if (!ok) throw Refused("The key was not accepted for sector $sector.")
            mc.writeBlock(op.block, op.data)
            if (!Classic.isTrailer(op.block)) {
                val back = try { mc.readBlock(op.block) } catch (e: IOException) { null }
                if (back != null && !back.contentEquals(op.data)) throw Refused("Verification failed: the block holds different data.")
                if (back == null) return WriteResult(true, "Block ${op.block} written. It could not be read back with this key, so it was not verified.")
            }
            return WriteResult(true, "Block ${op.block} written" + if (Classic.isTrailer(op.block)) ". Scan the card to confirm the new keys." else " and verified.")
        } finally {
            try { mc.close() } catch (_: IOException) {}
        }
    }

    // ---- restore a saved copy --------------------------------------------------------------

    private fun restore(tag: Tag, op: WriteOp.Restore, vault: VaultData): WriteResult {
        val snap = op.snapshot
        val uidHex = (tag.id ?: ByteArray(0)).toHex()
        snap.type2?.let { dump ->
            val nfcA = NfcA.get(tag) ?: throw Refused("The saved copy is from an NTAG / Ultralight tag; this tag is a different kind.")
            val link = Type2Link(nfcA)
            link.open()
            try {
                val version = link.version()
                val spec = Type2.specFromVersion(version)
                val savedSpec = Type2.specFromVersion(dump.versionHex?.let(Hex::decodeOrNull))
                if (spec == null || savedSpec == null || spec.name != savedSpec.name) throw Refused("The saved copy is from ${savedSpec?.name ?: "an unidentified chip"} and this tag is ${spec?.name ?: "an unidentified chip"}. Restore needs the same chip type.")
                vault.tagPasswords[uidHex]?.let { link.authenticate(it) }
                val userPages = spec.userBytes / 4
                val saved = dump.pagesHex.drop(Type2.FIRST_USER_PAGE).take(userPages).map { it?.let(Hex::decodeOrNull) }
                if (saved.size < userPages || saved.any { it == null || it.size != 4 }) throw Refused("The saved copy is incomplete (some pages were unreadable when it was made).")
                val target = saved.fold(ByteArray(0)) { acc, p -> acc + p!! }
                val current = link.fastReadRange(Type2.FIRST_USER_PAGE, Type2.FIRST_USER_PAGE + userPages - 1)
                val written = writePages(link, Type2.FIRST_USER_PAGE, current, target)
                val back = link.fastReadRange(Type2.FIRST_USER_PAGE, Type2.FIRST_USER_PAGE + userPages - 1)
                if (back == null || !back.contentEquals(target)) throw Refused("Verification failed after restoring.")
                return WriteResult(true, "User memory restored and verified ($written pages changed). Lock bits, password settings and the identifier are never copied.")
            } finally {
                link.close()
            }
        }
        snap.classic?.let { dump ->
            val mc = MifareClassic.get(tag) ?: throw Refused("The saved copy is from a MIFARE Classic card; this tag is a different kind or this phone cannot talk to it.")
            mc.connect()
            try {
                if (mc.size != dump.sizeBytes) throw Refused("The saved copy is from a ${dump.sizeBytes}-byte card and this card has ${mc.size} bytes.")
                val vaultKeys = vault.classicKeys.mapNotNull { Hex.decodeOrNull(it.keyHex) } + Classic.WELL_KNOWN_KEYS.map(Hex::decode)
                var written = 0; var skipped = 0
                for (s in dump.sectors) {
                    val first = mc.sectorToBlock(s.index)
                    val keysB = (listOfNotNull(s.keyBHex?.let(Hex::decodeOrNull)) + vaultKeys).filter { it.size == 6 }
                    val keysA = (listOfNotNull(s.keyAHex?.let(Hex::decodeOrNull)) + vaultKeys).filter { it.size == 6 }
                    for (i in 0 until s.blocksHex.size - 1) {
                        val block = first + i
                        if (block == 0) continue
                        val data = s.blocksHex[i]?.let(Hex::decodeOrNull)
                        if (data == null || data.size != 16) { skipped++; continue }
                        var done = false
                        // Authentication is lost after a refused write, so authenticate per attempt.
                        for ((useB, keys) in listOf(true to keysB, false to keysA)) {
                            if (done) break
                            for (k in keys) {
                                val ok = try { if (useB) mc.authenticateSectorWithKeyB(s.index, k) else mc.authenticateSectorWithKeyA(s.index, k) } catch (e: android.nfc.TagLostException) { throw e } catch (e: IOException) { false }
                                if (!ok) continue
                                done = try { mc.writeBlock(block, data); true } catch (e: android.nfc.TagLostException) { throw e } catch (e: IOException) { false }
                                break
                            }
                        }
                        if (done) written++ else skipped++
                    }
                }
                if (written == 0) throw Refused("No block could be written. The keys of this card are not in the vault.")
                return WriteResult(skipped == 0, "$written data blocks restored" + (if (skipped > 0) ", $skipped skipped (unreadable in the copy or not writable with known keys)" else "") + ". Block 0 and sector trailers are never copied.")
            } finally {
                try { mc.close() } catch (_: IOException) {}
            }
        }
        val msg = snap.ndef?.messageHex?.let(Hex::decodeOrNull) ?: throw Refused("The saved copy has no content that can be restored.")
        writeNdef(tag, msg, vault.tagPasswords[uidHex])
        return WriteResult(true, "Content restored and verified (${msg.size} bytes).")
    }
}
