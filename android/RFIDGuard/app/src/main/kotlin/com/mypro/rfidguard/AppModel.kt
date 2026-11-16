package com.mypro.rfidguard

import android.app.Application
import android.nfc.Tag
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.mypro.rfidguard.core.Hex
import com.mypro.rfidguard.core.TagSnapshot
import com.mypro.rfidguard.core.ndef.NdefRecordData
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.vault.AuditEntry
import com.mypro.rfidguard.core.vault.SavedTag
import com.mypro.rfidguard.core.vault.VaultData
import com.mypro.rfidguard.nfc.TagReader
import com.mypro.rfidguard.nfc.TagWriter
import com.mypro.rfidguard.nfc.WriteOp
import com.mypro.rfidguard.security.KeystoreVault
import java.util.concurrent.atomic.AtomicBoolean

enum class Tab { SCAN, WRITE, VAULT, SETTINGS }

/** One record waiting in the composer. */
class DraftRecord(val kind: String, val summary: String, val record: NdefRecordData)

/** App state. Holds decrypted data only while the vault is unlocked. */
class AppModel(app: Application) : AndroidViewModel(app) {
    val vault = KeystoreVault(app)

    var data by mutableStateOf<VaultData?>(null)
        private set
    var tab by mutableStateOf(Tab.SCAN)
    var lastScan by mutableStateOf<TagSnapshot?>(null)
    var viewing by mutableStateOf<SavedTag?>(null)
    var pendingOp by mutableStateOf<WriteOp?>(null)
        private set
    var busy by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    var lockScreenError by mutableStateOf<String?>(null)
    var vaultBroken by mutableStateOf(false)
    val draft = mutableStateListOf<DraftRecord>()

    private val main = Handler(Looper.getMainLooper())
    private val working = AtomicBoolean(false)
    private var armedAt = 0L

    fun onUnlocked(d: VaultData) {
        data = d
        lockScreenError = null
        vaultBroken = false
    }

    /** Applies a change and persists it. The in-memory state only changes if saving succeeded. */
    fun update(transform: (VaultData) -> VaultData): Boolean {
        val current = data ?: return false
        val next = transform(current)
        return try {
            vault.save(next)
            data = next
            true
        } catch (e: Exception) {
            message = "The vault could not be saved (${e.javaClass.simpleName}). The change was not kept."
            false
        }
    }

    fun lock() {
        vault.lock()
        data = null
        lastScan = null
        viewing = null
        pendingOp = null
        draft.clear()
        message = null
        tab = Tab.SCAN
    }

    fun arm(op: WriteOp) {
        armedAt = SystemClock.elapsedRealtime()
        pendingOp = op
    }

    fun disarm() {
        pendingOp = null
    }

    /** Called on the NFC reader thread for every tag presented while the app is in front. */
    fun onTag(tag: Tag) {
        val d = data ?: return // locked: tags are ignored entirely
        if (!working.compareAndSet(false, true)) return
        main.post { busy = true }
        try {
            val op = pendingOp
            // Keys are offered only to the card they belong to: a fake card presented by someone else
            // could otherwise harvest every stored key from the authentication attempts.
            val uidHex = (tag.id ?: ByteArray(0)).toHex()
            val keys = d.classicKeys.filter { it.uidHex == uidHex }.mapNotNull { Hex.decodeOrNull(it.keyHex) }
            if (op != null && SystemClock.elapsedRealtime() - armedAt > ARM_TIMEOUT_MS) {
                main.post { pendingOp = null; message = "The write request expired before a tag was presented. Nothing was written." }
                return
            }
            if (op == null) {
                val snap = TagReader.read(tag, keys, d.tagPasswords)
                main.post {
                    if (data != null) { lastScan = snap; viewing = null; tab = Tab.SCAN }
                }
            } else {
                val result = TagWriter.execute(tag, op, d)
                val snap = if (result.wrongTag) null else runCatching { TagReader.read(tag, keys, d.tagPasswords) }.getOrNull()
                main.post {
                    if (data == null) return@post
                    if (!result.wrongTag) pendingOp = null
                    update { v ->
                        val logged = v.withAudit(AuditEntry(System.currentTimeMillis(), uidHex, op.title, result.ok, result.message))
                        if (result.forgetPasswordFor != null) logged.copy(tagPasswords = logged.tagPasswords - result.forgetPasswordFor) else logged
                    }
                    if (snap != null) { lastScan = snap; viewing = null }
                    message = result.message
                }
            }
        } catch (e: Exception) {
            main.post { message = "The tag could not be processed (${e.javaClass.simpleName}). Hold it still against the back of the phone and try again." }
        } finally {
            working.set(false)
            main.post { busy = false }
        }
    }

    override fun onCleared() {
        vault.lock()
    }

    companion object {
        /** An armed write is dropped if no tag arrives in time, so it cannot fire by surprise later. */
        const val ARM_TIMEOUT_MS = 45_000L
    }
}
