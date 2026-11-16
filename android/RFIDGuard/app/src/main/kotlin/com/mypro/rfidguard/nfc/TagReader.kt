package com.mypro.rfidguard.nfc

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import com.mypro.rfidguard.core.ClassicDump
import com.mypro.rfidguard.core.ClassicSector
import com.mypro.rfidguard.core.FelicaInfo
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.NdefInfo
import com.mypro.rfidguard.core.NfcVDump
import com.mypro.rfidguard.core.TagIdentity
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.Type2Dump
import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.type2.Type2
import com.mypro.rfidguard.core.vault.TagPassword
import java.io.IOException

/** Reads everything the phone is allowed to see on a tag. Runs on the NFC reader thread. */
object TagReader {
    private const val MAX_CLASSIC_KEYS = 40
    private const val MAX_NFCV_BLOCKS = 256

    fun read(tag: Tag, classicKeys: List<ByteArray>, passwords: Map<String, TagPassword>): TagSnapshot {
        val uid = tag.id ?: ByteArray(0)
        val uidHex = uid.toHex()
        val techs = tag.techList.map { it.substringAfterLast('.') }
        val notes = ArrayList<String>()

        val nfcA = NfcA.get(tag)
        val atqa = nfcA?.atqa
        val sak = nfcA?.sak?.toInt()?.and(0xFF)
        val isoDep = IsoDep.get(tag)
        val ats = isoDep?.historicalBytes ?: isoDep?.hiLayerResponse
        val isNfcV = "NfcV" in techs

        var typeName = when {
            sak != null -> TagIdentity.typeFromSak(sak, atqa)
            isNfcV -> "ISO 15693 (NFC Type 5) tag"
            "NfcF" in techs -> "FeliCa (NFC Type 3) tag"
            "NfcB" in techs -> "ISO 14443-B card"
            else -> "Unknown tag"
        }

        val ndef = readNdef(tag, notes)

        var type2: Type2Dump? = null
        if (nfcA != null && sak == 0x00 && isoDep == null) {
            try {
                type2 = readType2(nfcA, passwords[uidHex], notes)
                Type2.specFromVersion(type2.versionHex?.let(Hex::decodeOrNull))?.let { typeName = it.name }
            } catch (e: IOException) {
                notes += "Memory read stopped: the tag moved away."
            } catch (e: RuntimeException) {
                notes += "Memory read failed: ${e.javaClass.simpleName}."
            }
        }

        var classic: ClassicDump? = null
        val mc = MifareClassic.get(tag)
        if (mc != null) {
            try {
                classic = readClassic(mc, classicKeys.take(MAX_CLASSIC_KEYS))
            } catch (e: IOException) {
                notes += "MIFARE Classic read stopped: the card moved away."
            } catch (e: RuntimeException) {
                notes += "MIFARE Classic read failed: ${e.javaClass.simpleName}."
            }
        } else if (sak == 0x08 || sak == 0x18 || sak == 0x09) {
            notes += "This phone's NFC chip cannot talk to MIFARE Classic cards, so only the identifier is shown."
        }

        var nfcV: NfcVDump? = null
        if (isNfcV) {
            try {
                nfcV = NfcV.get(tag)?.let { readNfcV(it, uid) }
            } catch (e: IOException) {
                notes += "ISO 15693 read stopped: the tag moved away."
            } catch (e: RuntimeException) {
                notes += "ISO 15693 read failed: ${e.javaClass.simpleName}."
            }
        }

        val felica = NfcF.get(tag)?.let { FelicaInfo(it.manufacturer?.toHex() ?: "", it.systemCode?.toHex() ?: "") }

        return TagSnapshot(
            uidHex = uidHex,
            techs = techs,
            readAtEpochMs = System.currentTimeMillis(),
            typeName = typeName,
            manufacturer = TagIdentity.manufacturer(uid, isNfcV),
            atqaHex = atqa?.toHex(),
            sak = sak,
            atsHex = ats?.toHex(),
            ndef = ndef,
            type2 = type2,
            classic = classic,
            nfcV = nfcV,
            felica = felica,
            notes = notes,
        )
    }

    private fun readNdef(tag: Tag, notes: MutableList<String>): NdefInfo? {
        val ndef = Ndef.get(tag) ?: return null
        return try {
            NdefInfo(
                tagType = ndef.type?.substringAfterLast('.') ?: "NDEF",
                maxSize = ndef.maxSize,
                writable = ndef.isWritable,
                canMakeReadOnly = runCatching { ndef.canMakeReadOnly() }.getOrDefault(false),
                messageHex = ndef.cachedNdefMessage?.toByteArray()?.toHex(),
            )
        } catch (e: RuntimeException) {
            notes += "NDEF information could not be read."
            null
        }
    }

    @Throws(IOException::class)
    private fun readType2(nfcA: NfcA, password: TagPassword?, notes: MutableList<String>): Type2Dump {
        val link = Type2Link(nfcA)
        link.open()
        try {
            val version = link.version()
            val spec = Type2.specFromVersion(version)
            val signature = if (spec?.hasOriginalitySignature == true) link.signature() else null
            var authenticated = false
            if (password != null && spec?.configPage != null) {
                authenticated = link.authenticate(password)
                if (authenticated && !link.packMatched) notes += "The tag accepted the stored password but answered with an unexpected acknowledge code."
            }

            val pages: Array<String?>
            if (spec != null) {
                pages = arrayOfNulls(spec.totalPages)
                var p = 0
                while (p < spec.totalPages) {
                    val end = minOf(p + 3, spec.totalPages - 1)
                    val chunk = link.fastRead(p, end)
                    if (chunk != null) {
                        for (i in 0..(end - p)) pages[p + i] = chunk.copyOfRange(i * 4, i * 4 + 4).toHex()
                        p = end + 1
                        continue
                    }
                    // A refused chunk: find the exact boundary page by page. Protection always runs to the end of memory.
                    if (authenticated) link.authenticate(password!!)
                    var stopped = false
                    for (q in p..end) {
                        val one = link.fastRead(q, q)
                        if (one == null) { stopped = true; break }
                        pages[q] = one.toHex()
                    }
                    if (stopped) break
                    p = end + 1
                }
                // PWD and PACK always read back as zeros; do not present that as real content.
                spec.configPage?.let { cfg -> if (cfg + 3 < pages.size) { pages[cfg + 2] = null; pages[cfg + 3] = null } }
            } else {
                val first = link.read(0) ?: return Type2Dump(null, emptyList(), null, false)
                val total = (Type2.pagesFromCapabilityContainer(first.copyOfRange(12, 16)) ?: 16).coerceIn(4, 256)
                pages = arrayOfNulls(total)
                var p = 0
                while (p < total) {
                    val chunk = (if (p == 0) first else link.read(p)) ?: break
                    for (i in 0 until 4) if (p + i < total) pages[p + i] = chunk.copyOfRange(i * 4, i * 4 + 4).toHex()
                    p += 4
                }
            }
            return Type2Dump(version?.toHex(), pages.toList(), signature?.toHex(), authenticated)
        } finally {
            link.close()
        }
    }

    @Throws(IOException::class)
    private fun readClassic(mc: MifareClassic, userKeys: List<ByteArray>): ClassicDump {
        val keys = (Classic.WELL_KNOWN_KEYS.map(Hex::decode) + userKeys).filter { it.size == 6 }.distinctBy { it.toHex() }
        mc.connect()
        try {
            mc.timeout = 600
            val sectors = ArrayList<ClassicSector>()
            for (s in 0 until mc.sectorCount) {
                val first = mc.sectorToBlock(s)
                val count = mc.getBlockCountInSector(s)
                val blocks = arrayOfNulls<String>(count)

                fun auth(key: ByteArray, useB: Boolean): Boolean = try {
                    if (useB) mc.authenticateSectorWithKeyB(s, key) else mc.authenticateSectorWithKeyA(s, key)
                } catch (e: android.nfc.TagLostException) {
                    throw e
                } catch (e: IOException) {
                    false
                }

                fun readAll() {
                    for (i in 0 until count) {
                        if (blocks[i] != null) continue
                        try {
                            blocks[i] = mc.readBlock(first + i).takeIf { it.size == 16 }?.toHex()
                        } catch (e: android.nfc.TagLostException) {
                            throw e
                        } catch (e: IOException) {
                            // not readable with this key; authentication is lost after an error
                            return
                        }
                    }
                }

                val keyA = keys.firstOrNull { auth(it, false) }
                if (keyA != null) readAll()
                val keyB = keys.firstOrNull { auth(it, true) }
                if (keyB != null && blocks.any { it == null }) readAll()
                if (keyA != null && blocks.any { it == null }) {
                    // one unreadable block aborts the pass; give the remaining blocks one more try per key
                    if (auth(keyA, false)) for (i in 0 until count) if (blocks[i] == null) {
                        try { blocks[i] = mc.readBlock(first + i).toHex() } catch (e: android.nfc.TagLostException) { throw e } catch (e: IOException) { if (!auth(keyA, false)) break }
                    }
                }
                sectors += ClassicSector(s, first, blocks.toList(), keyA?.toHex(), keyB?.toHex())
            }
            return ClassicDump(mc.size, sectors)
        } finally {
            try { mc.close() } catch (_: IOException) {}
        }
    }

    @Throws(IOException::class)
    private fun readNfcV(v: NfcV, uid: ByteArray): NfcVDump? {
        if (uid.size != 8) return null
        v.connect()
        try {
            fun cmd(code: Int, vararg params: Int): ByteArray? = try {
                // addressed mode, high data rate
                val frame = byteArrayOf(0x22, code.toByte()) + uid + ByteArray(params.size) { params[it].toByte() }
                v.transceive(frame).takeIf { it.isNotEmpty() && it[0].toInt() and 0x01 == 0 }
            } catch (e: android.nfc.TagLostException) {
                throw e
            } catch (e: IOException) {
                null
            }

            var dsfid: Int? = null; var afi: Int? = null; var icRef: Int? = null
            var blockCount = 0; var blockSize = 0
            cmd(0x2B)?.let { info ->
                if (info.size >= 10) {
                    val flags = info[1].toInt() and 0xFF
                    var pos = 10
                    if (flags and 0x01 != 0 && pos < info.size) dsfid = info[pos++].toInt() and 0xFF
                    if (flags and 0x02 != 0 && pos < info.size) afi = info[pos++].toInt() and 0xFF
                    if (flags and 0x04 != 0 && pos + 1 < info.size) {
                        blockCount = (info[pos].toInt() and 0xFF) + 1
                        blockSize = (info[pos + 1].toInt() and 0x1F) + 1
                        pos += 2
                    }
                    if (flags and 0x08 != 0 && pos < info.size) icRef = info[pos].toInt() and 0xFF
                }
            }
            val limit = if (blockCount > 0) minOf(blockCount, MAX_NFCV_BLOCKS) else 64
            val blocks = ArrayList<String?>()
            for (b in 0 until limit) {
                val r = cmd(0x20, b)
                if (r == null) {
                    if (blockCount == 0) break
                    blocks += null
                    continue
                }
                val data = r.copyOfRange(1, r.size)
                if (blockSize == 0) blockSize = data.size
                blocks += data.toHex()
            }
            return NfcVDump(dsfid, afi, icRef, blockSize, blocks)
        } finally {
            try { v.close() } catch (_: IOException) {}
        }
    }
}
