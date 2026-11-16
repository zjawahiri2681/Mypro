package com.mypro.rfidguard.core

import com.mypro.rfidguard.core.mifare.Classic
import com.mypro.rfidguard.core.ndef.NdefCodec
import com.mypro.rfidguard.core.ndef.NdefContent
import com.mypro.rfidguard.core.ndef.NdefContents
import com.mypro.rfidguard.core.ndef.NdefFormatException
import com.mypro.rfidguard.core.ndef.NdefRecordData
import com.mypro.rfidguard.core.ndef.UriRisk
import com.mypro.rfidguard.core.security.Originality
import com.mypro.rfidguard.core.security.Severity
import com.mypro.rfidguard.core.security.TagAudit
import com.mypro.rfidguard.core.security.TagSigner
import com.mypro.rfidguard.core.type2.Type2
import com.mypro.rfidguard.core.vault.AeadBox
import com.mypro.rfidguard.core.vault.ExportCodec
import com.mypro.rfidguard.core.vault.VaultData
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    // ---- hex ----
    @Test fun hexRoundTrip() {
        assertEquals("04A1FF", Hex.encode(byteArrayOf(4, 0xA1.toByte(), -1)))
        assertContentEquals(byteArrayOf(4, 0xA1.toByte(), -1), Hex.decode("04:a1 ff"))
        assertNull(Hex.decodeOrNull("ABC"))
        assertNull(Hex.decodeOrNull("ZZ"))
    }

    // ---- NDEF ----
    @Test fun uriRecordMatchesNfcForumExample() {
        // Well-known example: https://www. prefix (0x02) + "example.com"
        val bytes = NdefCodec.encode(listOf(NdefContents.uri("https://www.example.com")))
        assertEquals("D1010C55026578616D706C652E636F6D", bytes.toHex())
        val back = NdefContents.decode(NdefCodec.parse(bytes).single())
        assertEquals(NdefContent.Uri("https://www.example.com"), back)
    }

    @Test fun textRecordRoundTripAndKnownBytes() {
        val bytes = NdefCodec.encode(listOf(NdefContents.text("Hello", "en")))
        assertEquals("D1010854" + "02656E" + "48656C6C6F", bytes.toHex())
        assertEquals(NdefContent.Text("en", "Hello"), NdefContents.decode(NdefCodec.parse(bytes).single()))
        val arabic = NdefContents.decode(NdefCodec.parse(NdefCodec.encode(listOf(NdefContents.text("مرحبا", "ar")))).single())
        assertEquals(NdefContent.Text("ar", "مرحبا"), arabic)
    }

    @Test fun multiRecordAndLongPayload() {
        val big = ByteArray(700) { it.toByte() }
        val recs = listOf(NdefContents.text("a"), NdefContents.mime("application/octet-stream", big), NdefContents.androidApp("com.example.app"))
        val parsed = NdefCodec.parse(NdefCodec.encode(recs))
        assertEquals(3, parsed.size)
        assertContentEquals(big, parsed[1].payload)
        assertEquals(NdefContent.AndroidApp("com.example.app"), NdefContents.decode(parsed[2]))
    }

    @Test fun emptyMessageAndMalformedInput() {
        assertEquals("D00000", NdefCodec.encode(emptyList()).toHex())
        assertIs<NdefContent.Empty>(NdefContents.decode(NdefCodec.parse(Hex.decode("D00000")).single()))
        assertFailsWith<NdefFormatException> { NdefCodec.parse(Hex.decode("D1010C5502")) }
        assertFailsWith<NdefFormatException> { NdefCodec.parse(Hex.decode("C101FFFFFFFF55")) }
        assertFailsWith<NdefFormatException> { NdefCodec.parse(Hex.decode("B10100")) }
        // Never throws on random bytes other than NdefFormatException
        val rnd = SecureRandom()
        repeat(2000) {
            val b = ByteArray(rnd.nextInt(40)).also(rnd::nextBytes)
            try { NdefCodec.parse(b).forEach(NdefContents::decode) } catch (_: NdefFormatException) {}
        }
    }

    @Test fun wifiAndVcardAndSmartPoster() {
        val wifi = NdefContents.decode(NdefContents.wifi("Guest Net", "s3cret-pass"))
        assertEquals(NdefContent.Wifi("Guest Net", "WPA2-Personal", "s3cret-pass"), wifi)
        assertEquals(NdefContent.Wifi("Cafe", "Open", ""), NdefContents.decode(NdefContents.wifi("Cafe", "")))
        val v = NdefContents.decode(NdefContents.vcard("A; B", "+966500000000", "a@b.c", "Org"))
        assertIs<NdefContent.VCard>(v)
        assertTrue(v.text.contains("FN:A\\; B") && v.text.contains("TEL:+966500000000"))
        val inner = NdefCodec.encode(listOf(NdefContents.uri("https://example.org/x"), NdefContents.text("Title")))
        val sp = NdefContents.decode(NdefRecordData(NdefRecordData.TNF_WELL_KNOWN, "Sp".toByteArray(), payload = inner))
        assertEquals(NdefContent.SmartPoster("https://example.org/x", "Title"), sp)
    }

    // ---- link risk ----
    @Test fun uriRisk() {
        assertTrue(UriRisk.assess("https://www.example.com/path?q=1").isEmpty())
        assertEquals(Severity.MEDIUM, UriRisk.worst(UriRisk.assess("http://example.com")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("https://paypal.com@evil.example/login")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("https://xn--pypal-4ve.com/")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("https://раураl.com/")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("javascript:alert(1)")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("intent://x#Intent;scheme=http;end")))
        assertEquals(Severity.HIGH, UriRisk.worst(UriRisk.assess("https://example.com/‮gpj.exe")))
        assertEquals(Severity.MEDIUM, UriRisk.worst(UriRisk.assess("https://192.168.1.10/admin")))
        assertEquals(Severity.MEDIUM, UriRisk.worst(UriRisk.assess("https://bit.ly/abc")))
        assertEquals(Severity.MEDIUM, UriRisk.worst(UriRisk.assess("tel:+966500000000")))
        assertEquals(Severity.LOW, UriRisk.worst(UriRisk.assess("https://example.com:8443/")))
        assertTrue(UriRisk.assess("https://example.com:443/").isEmpty())
        assertEquals(Severity.MEDIUM, UriRisk.worst(UriRisk.assess("https://[::1]/")))
    }

    // ---- MIFARE Classic ----
    @Test fun classicLayout() {
        assertEquals(16, Classic.sectorCount(1024)); assertEquals(40, Classic.sectorCount(4096))
        assertEquals(3, Classic.trailerBlock(0)); assertEquals(63, Classic.trailerBlock(15))
        assertEquals(128, Classic.firstBlock(32)); assertEquals(143, Classic.trailerBlock(32)); assertEquals(255, Classic.trailerBlock(39))
        assertEquals(39, Classic.sectorOfBlock(255)); assertEquals(31, Classic.sectorOfBlock(127))
        assertTrue(Classic.isTrailer(7)); assertFalse(Classic.isTrailer(8)); assertTrue(Classic.isTrailer(143)); assertFalse(Classic.isTrailer(131))
    }

    @Test fun classicAccessBits() {
        val transport = Hex.decode("FFFFFFFFFFFF" + "FF078069" + "FFFFFFFFFFFF")
        assertEquals(listOf(0, 0, 0, 1), Classic.accessBits(transport))
        assertNull(Classic.validateTrailerForWrite(transport))
        // NDEF-formatted sector: 7F 07 88 -> data blocks 000, trailer 011
        assertEquals(listOf(0, 0, 0, 3), Classic.accessBits(Hex.decode("D3F7D3F7D3F7" + "7F078840" + "FFFFFFFFFFFF")))
        // MAD sector: 78 77 88 -> data blocks 100, trailer 011
        assertEquals(listOf(4, 4, 4, 3), Classic.accessBits(Hex.decode("A0A1A2A3A4A5" + "787788C1" + "FFFFFFFFFFFF")))
        assertNull(Classic.accessBits(Hex.decode("FFFFFFFFFFFF" + "FF078169" + "FFFFFFFFFFFF")))
        assertNotNull(Classic.validateTrailerForWrite(Hex.decode("FFFFFFFFFFFF" + "00000000" + "FFFFFFFFFFFF")))
        // 110 for the trailer (C1=1,C2=1,C3=0 on group 3) freezes it: bytes 7F 87 08? build programmatically instead
        assertNotNull(Classic.validateTrailerForWrite(trailerWith(listOf(0, 0, 0, 0b110))))
        assertNotNull(Classic.validateTrailerForWrite(trailerWith(listOf(0b111, 0, 0, 1))))
        assertNull(Classic.validateTrailerForWrite(trailerWith(listOf(0b100, 0b100, 0b100, 0b011))))
        assertEquals(listOf(0b100, 0b110, 0b001, 0b011), Classic.accessBits(trailerWith(listOf(0b100, 0b110, 0b001, 0b011))))
    }

    private fun trailerWith(groups: List<Int>): ByteArray {
        var c1 = 0; var c2 = 0; var c3 = 0
        for ((i, g) in groups.withIndex()) {
            c1 = c1 or (((g ushr 2) and 1) shl i); c2 = c2 or (((g ushr 1) and 1) shl i); c3 = c3 or ((g and 1) shl i)
        }
        val b6 = ((c2.inv() and 0xF) shl 4) or (c1.inv() and 0xF)
        val b7 = (c1 shl 4) or (c3.inv() and 0xF)
        val b8 = (c3 shl 4) or c2
        return ByteArray(6) { -1 } + byteArrayOf(b6.toByte(), b7.toByte(), b8.toByte(), 0x69) + ByteArray(6) { -1 }
    }

    @Test fun classicValueBlock() {
        assertEquals(1234567, Classic.valueBlock(Hex.decode("87D61200" + "7829EDFF" + "87D61200" + "11EE11EE")))
        assertNull(Classic.valueBlock(Hex.decode("87D61200" + "7829EDFF" + "87D61201" + "11EE11EE")))
        assertNull(Classic.valueBlock(ByteArray(16)))
    }

    // ---- Type 2 ----
    @Test fun type2Specs() {
        val ntag213 = Type2.specFromVersion(Hex.decode("0004040201000F03"))!!
        assertEquals("NTAG213", ntag213.name); assertEquals(45, ntag213.totalPages); assertEquals(0x29, ntag213.configPage)
        assertEquals(0x83, Type2.specFromVersion(Hex.decode("0004040201001103"))!!.configPage)
        val n216 = Type2.specFromVersion(Hex.decode("0004040201001303"))!!
        assertEquals(0xE3, n216.configPage); assertEquals(888, n216.userBytes)
        assertEquals(0x10, Type2.specFromVersion(Hex.decode("0004030101000B03"))!!.configPage)
        assertEquals(0x25, Type2.specFromVersion(Hex.decode("0004030101000E03"))!!.configPage)
        assertNull(Type2.specFromVersion(Hex.decode("0005040201000F03")))
        assertNull(Type2.specFromVersion(null))
        for (s in listOf("0004040201000F03", "0004040201001103", "0004040201001303", "0004040101000B03", "0004040101000E03")) {
            val spec = Type2.specFromVersion(Hex.decode(s))!!
            assertEquals(spec.totalPages - 4, spec.configPage)
            assertTrue(4 + spec.userBytes / 4 <= spec.configPage!!)
        }
        assertEquals(16, Type2.pagesFromCapabilityContainer(Hex.decode("E1100600")))
        assertEquals(40, Type2.pagesFromCapabilityContainer(Hex.decode("E1101200")))
        assertNull(Type2.pagesFromCapabilityContainer(Hex.decode("00000000")))
        assertTrue(Type2.ccReadOnly(Hex.decode("E110120F"))); assertFalse(Type2.ccReadOnly(Hex.decode("E1101200")))
        val cfg = Type2.config(Hex.decode("04000004"), Hex.decode("80050000"))!!
        assertTrue(cfg.readProtected); assertFalse(cfg.configLocked); assertTrue(cfg.protectionActive(45)); assertEquals(0, cfg.authLimit)
        assertFalse(Type2.config(Hex.decode("040000FF"), Hex.decode("00050000"))!!.protectionActive(45))
    }

    @Test fun type2Tlv() {
        // NTAG213 factory content: lock control TLV, then empty NDEF TLV, then terminator
        val factory = Hex.decode("0103A00C340300FE") + ByteArray(136)
        val loc = Type2.findNdefTlv(factory)!!
        assertEquals(5, loc.tlvOffset); assertEquals(0, loc.length)
        val msg = NdefCodec.encode(listOf(NdefContents.uri("https://example.com")))
        val area = Type2.buildNdefArea(factory, msg, 144)!!
        assertEquals(0, area.size % 4)
        assertEquals("0103A00C34", area.copyOf(5).toHex())
        assertContentEquals(msg, Type2.extractNdef(area + ByteArray(144 - area.size)))
        // Long form length
        val long = ByteArray(300) { 7 }
        val area2 = Type2.buildNdefArea(ByteArray(504), long, 504)!!
        assertEquals("03FF012C", area2.copyOf(4).toHex())
        assertContentEquals(long, Type2.extractNdef(area2))
        // Does not fit
        assertNull(Type2.buildNdefArea(factory, ByteArray(140), 144))
        // Exact fit without terminator
        assertNotNull(Type2.buildNdefArea(ByteArray(48), ByteArray(46), 48))
        assertNull(Type2.findNdefTlv(Hex.decode("FE000000")))
        assertNull(Type2.findNdefTlv(Hex.decode("0310AABB")))
    }

    // ---- authenticity seal ----
    @Test fun tagSigner() {
        val key = ByteArray(32) { (it * 7).toByte() }
        val uid = Hex.decode("04A1B2C3D4E5F6")
        val recs = listOf(NdefContents.uri("https://example.com"), NdefContents.text("hi"))
        val signed = TagSigner.sign(key, uid, recs)
        assertEquals(3, signed.size)
        assertEquals(TagSigner.Status.VALID, TagSigner.verify(key, uid, signed))
        // survives the wire format
        val wire = NdefCodec.parse(NdefCodec.encode(signed))
        assertEquals(TagSigner.Status.VALID, TagSigner.verify(key, uid, wire))
        assertIs<NdefContent.GuardSignature>(NdefContents.decode(wire.last()))
        // copied to another tag
        assertEquals(TagSigner.Status.INVALID, TagSigner.verify(key, Hex.decode("04A1B2C3D4E5F7"), wire))
        // content edited
        val edited = listOf(NdefContents.uri("https://evil.example"), wire[1], wire[2])
        assertEquals(TagSigner.Status.INVALID, TagSigner.verify(key, uid, edited))
        // record appended after the seal, or records reordered
        assertEquals(TagSigner.Status.INVALID, TagSigner.verify(key, uid, wire + NdefContents.text("x")))
        assertEquals(TagSigner.Status.INVALID, TagSigner.verify(key, uid, listOf(wire[1], wire[0], wire[2])))
        assertEquals(TagSigner.Status.OTHER_KEY, TagSigner.verify(ByteArray(32) { 1 }, uid, wire))
        assertEquals(TagSigner.Status.NONE, TagSigner.verify(key, uid, recs))
        // re-signing replaces the old seal
        assertEquals(3, TagSigner.sign(key, uid, signed).size)
    }

    // ---- NXP originality (secp128r1) ----
    @Test fun curveConstantsAreConsistent() {
        assertTrue(Originality.onCurve(Originality.G))
        assertNull(Originality.multiply(Originality.N, Originality.G))
        assertTrue(Originality.P.isProbablePrime(64)); assertTrue(Originality.N.isProbablePrime(64))
        for (k in Originality.NXP_PUBLIC_KEYS.values) assertTrue(Originality.onCurve(k), "published NXP key must lie on secp128r1")
    }

    @Test fun ecdsaVerify() {
        val rnd = SecureRandom()
        val d = BigInteger(120, rnd).add(BigInteger.TWO)
        val pub = Originality.multiply(d, Originality.G)!!
        val uid = Hex.decode("04A1B2C3D4E5F6")
        val e = BigInteger(1, uid)
        var r: BigInteger; var s: BigInteger
        do {
            val k = BigInteger(120, rnd).add(BigInteger.TWO)
            r = Originality.multiply(k, Originality.G)!!.x.mod(Originality.N)
            s = k.modInverse(Originality.N).multiply(e.add(r.multiply(d))).mod(Originality.N)
        } while (r.signum() == 0 || s.signum() == 0)
        fun fixed(v: BigInteger) = v.toByteArray().let { b -> ByteArray(16).also { o -> val src = b.takeLast(16).toByteArray(); src.copyInto(o, 16 - src.size) } }
        val sig = fixed(r) + fixed(s)
        assertTrue(Originality.verify(pub, uid, sig))
        assertFalse(Originality.verify(pub, Hex.decode("04A1B2C3D4E5F7"), sig))
        sig[20] = (sig[20].toInt() xor 1).toByte()
        assertFalse(Originality.verify(pub, uid, sig))
        assertFalse(Originality.verify(pub, uid, ByteArray(32)))
        assertNull(Originality.verifiedFamily(uid, ByteArray(32) { 0x11 }))
    }

    // ---- vault crypto ----
    @Test fun aeadBox() {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val box = AeadBox.seal(key, "secret".toByteArray(), "aad".toByteArray())
        assertContentEquals("secret".toByteArray(), AeadBox.open(key, box, "aad".toByteArray()))
        assertFailsWith<GeneralSecurityException> { AeadBox.open(key, box, "other".toByteArray()) }
        box[box.size - 1] = (box[box.size - 1].toInt() xor 1).toByte()
        assertFailsWith<GeneralSecurityException> { AeadBox.open(key, box, "aad".toByteArray()) }
        assertFailsWith<GeneralSecurityException> { AeadBox.open(key, ByteArray(5), "aad".toByteArray()) }
        assertFalse(AeadBox.seal(key, ByteArray(8), ByteArray(0)).contentEquals(AeadBox.seal(key, ByteArray(8), ByteArray(0))))
    }

    @Test fun exportCodec() {
        val data = VaultData(signingKeyHex = "00".repeat(32))
        val file = ExportCodec.encrypt("correct horse battery".toCharArray(), VaultData.encode(data), iterations = 100_000)
        assertEquals(data, VaultData.decode(ExportCodec.decrypt("correct horse battery".toCharArray(), file)))
        assertFailsWith<GeneralSecurityException> { ExportCodec.decrypt("wrong passphrase!!".toCharArray(), file) }
        val tampered = file.copyOf(); tampered[21] = 0x7F  // iteration count is authenticated / range-checked
        assertFailsWith<GeneralSecurityException> { ExportCodec.decrypt("correct horse battery".toCharArray(), tampered) }
        assertFailsWith<GeneralSecurityException> { ExportCodec.decrypt("x".toCharArray(), "nope".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { ExportCodec.encrypt("short".toCharArray(), ByteArray(1)) }
        assertFalse(String(file, Charsets.ISO_8859_1).contains("signingKeyHex"))
    }

    // ---- audit ----
    @Test fun auditFlagsWeaknesses() {
        val key = ByteArray(32) { 9 }
        val uid = "04A1B2C3D4E5F6"
        val recs = listOf(NdefContents.uri("http://bit.ly/x"), NdefContents.wifi("Home", "hunter2hunter2"))
        val open = TagSnapshot(uid, listOf("NfcA", "Ndef"), 0, "NTAG213", ndef = NdefInfo("Type 2", 137, true, true, NdefCodec.encode(recs).toHex()))
        val f = TagAudit.assess(open, key)
        assertEquals(Severity.HIGH, TagAudit.worst(f))
        assertTrue(f.any { it.title.contains("Wi-Fi password") }); assertTrue(f.any { it.title.contains("overwrite") })
        assertTrue(f.any { it.title == "Shortened link" })
        assertEquals(f.sortedByDescending { it.severity }, f)

        val sealed = TagSigner.sign(key, Hex.decode(uid), listOf(NdefContents.text("ok")))
        val locked = open.copy(ndef = NdefInfo("Type 2", 137, false, false, NdefCodec.encode(sealed).toHex()))
        val g = TagAudit.assess(locked, key)
        assertTrue(g.any { it.title == "Authenticity seal valid" }); assertTrue(g.any { it.title == "Read-only" })
        assertEquals(Severity.INFO, TagAudit.worst(g))
        assertTrue(TagAudit.assess(locked.copy(uidHex = "04A1B2C3D4E5F7"), key).any { it.title == "Authenticity seal broken" })

        val classic = TagSnapshot("A1B2C3D4", listOf("NfcA", "MifareClassic"), 0, "MIFARE Classic 1K", classic = ClassicDump(1024, List(16) {
            ClassicSector(it, it * 4, listOf(null, null, null, "000000000000FF078069FFFFFFFFFFFF"), keyAHex = if (it < 15) "FFFFFFFFFFFF" else null)
        }))
        val c = TagAudit.assess(classic, null)
        assertTrue(c.first().title.contains("broken")); assertTrue(c.any { it.detail.startsWith("15 of 16") }); assertTrue(c.any { it.title == "Sectors with unknown keys" })

        val pages = MutableList<String?>(45) { "00000000" }
        pages[3] = "E1101200"; pages[41] = "04000004"; pages[42] = "80050000"
        val protectedTag = TagSnapshot(uid, listOf("NfcA"), 0, "NTAG213", type2 = Type2Dump("0004040201000F03", pages, signatureHex = "11".repeat(32)))
        val p = TagAudit.assess(protectedTag, null)
        assertTrue(p.any { it.title == "Password protection is on" }); assertTrue(p.any { it.title == "Originality not confirmed" })
        assertTrue(p.any { it.title == "Unlimited password attempts" })
    }

    @Test fun vaultRoundTripAndAuditCap() {
        var v = VaultData(signingKeyHex = "AB".repeat(32))
        repeat(VaultData.MAX_AUDIT + 20) { v = v.withAudit(com.mypro.rfidguard.core.vault.AuditEntry(it.toLong(), "00", "write", true)) }
        assertEquals(VaultData.MAX_AUDIT, v.audit.size)
        assertEquals((VaultData.MAX_AUDIT + 19).toLong(), v.audit.first().atEpochMs)
        assertEquals(v, VaultData.decode(VaultData.encode(v)))
        assertEquals("NXP Semiconductors", TagIdentity.manufacturer(Hex.decode("04A1B2C3D4E5F6"), false))
        assertEquals("STMicroelectronics", TagIdentity.manufacturer(Hex.decode("0102030405" + "0002E0").let { Hex.decode("1122334455" + "6602E0") }, true))
        assertEquals("MIFARE DESFire", TagIdentity.typeFromSak(0x20, Hex.decode("4403")))
    }
}
