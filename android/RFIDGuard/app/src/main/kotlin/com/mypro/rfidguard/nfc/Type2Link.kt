package com.mypro.rfidguard.nfc

import android.nfc.tech.NfcA
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.type2.Type2
import com.mypro.rfidguard.core.vault.TagPassword
import java.io.IOException

/**
 * Raw command channel to an NFC Forum Type 2 tag (NTAG / MIFARE Ultralight).
 * A command the chip rejects drops it out of the active state, so every failure reconnects.
 */
internal class Type2Link(private val nfcA: NfcA) {
    /** False when the chip acknowledged the password with an unexpected PACK. */
    var packMatched: Boolean = true
        private set

    fun open() {
        nfcA.connect()
        nfcA.timeout = TIMEOUT_MS
    }

    fun close() {
        try { nfcA.close() } catch (_: IOException) {}
    }

    @Throws(IOException::class)
    private fun reconnect() {
        close()
        nfcA.connect()
        nfcA.timeout = TIMEOUT_MS
    }

    private fun transceive(vararg bytes: Int): ByteArray? = try {
        nfcA.transceive(ByteArray(bytes.size) { bytes[it].toByte() })
    } catch (e: IOException) {
        null
    }

    /** Runs a command and checks the answer length; reconnects and returns null when the chip refused. */
    @Throws(IOException::class)
    private fun command(expectedSize: Int, vararg bytes: Int): ByteArray? {
        val r = transceive(*bytes)
        if (r != null && r.size == expectedSize) return r
        // Some controllers report an unanswered command as a lost tag; a failed reconnect here
        // must not abort the caller, the next command simply fails as well.
        try { reconnect() } catch (_: IOException) {}
        return null
    }

    enum class Access { OPEN, AUTHENTICATED, DENIED }

    /**
     * Sends the password only when the tag really is protected. A wrong PWD_AUTH counts against the
     * chip's attempt limit when one is configured, so it is never sent to an open tag.
     */
    @Throws(IOException::class)
    fun unlockIfProtected(spec: com.mypro.rfidguard.core.type2.Type2Spec, password: TagPassword?): Access {
        val page = spec.configPage ?: return Access.OPEN
        val cfg = fastRead(page, page + 1)
        if (cfg != null) {
            val c = Type2.config(cfg.copyOfRange(0, 4), cfg.copyOfRange(4, 8))
            if (c != null && !c.protectionActive(spec.totalPages)) return Access.OPEN
        }
        if (password == null) return Access.DENIED
        return if (authenticate(password)) Access.AUTHENTICATED else Access.DENIED
    }

    @Throws(IOException::class)
    fun version(): ByteArray? = command(8, Type2.CMD_GET_VERSION)

    @Throws(IOException::class)
    fun signature(): ByteArray? = command(32, Type2.CMD_READ_SIG, 0x00)

    @Throws(IOException::class)
    fun authenticate(password: TagPassword): Boolean {
        val pwd = Hex.decodeOrNull(password.pwdHex) ?: return false
        if (pwd.size != 4) return false
        val pack = command(2, Type2.CMD_PWD_AUTH, pwd[0].toInt(), pwd[1].toInt(), pwd[2].toInt(), pwd[3].toInt()) ?: return false
        packMatched = Hex.encode(pack) == password.packHex.uppercase()
        return true
    }

    /** READ: four pages starting at [page]; wraps to page 0 past the end of memory. */
    @Throws(IOException::class)
    fun read(page: Int): ByteArray? = command(16, Type2.CMD_READ, page)

    /** FAST_READ: exactly pages [from]..[to], never wrapping. Not available on chips without GET_VERSION. */
    @Throws(IOException::class)
    fun fastRead(from: Int, to: Int): ByteArray? = command((to - from + 1) * 4, Type2.CMD_FAST_READ, from, to)

    @Throws(IOException::class)
    fun write(page: Int, data: ByteArray): Boolean {
        require(data.size == 4)
        val r = transceive(Type2.CMD_WRITE, page, data[0].toInt(), data[1].toInt(), data[2].toInt(), data[3].toInt())
        // Controllers either strip the 4-bit ACK (empty answer) or pass it through as one byte (0xA).
        val ok = r != null && (r.isEmpty() || (r.size == 1 && r[0].toInt() and 0x0F == 0x0A))
        if (!ok) reconnect()
        return ok
    }

    /** Reads pages [from]..[to] in small FAST_READ chunks; null on the first refusal. */
    @Throws(IOException::class)
    fun fastReadRange(from: Int, to: Int): ByteArray? {
        var out = ByteArray(0)
        var p = from
        while (p <= to) {
            val end = minOf(p + CHUNK_PAGES - 1, to)
            out += fastRead(p, end) ?: return null
            p = end + 1
        }
        return out
    }

    private companion object {
        const val TIMEOUT_MS = 800
        const val CHUNK_PAGES = 12
    }
}
