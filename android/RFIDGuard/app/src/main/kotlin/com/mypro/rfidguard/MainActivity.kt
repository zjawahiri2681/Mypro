package com.mypro.rfidguard

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.SystemClock
import android.provider.Settings
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mypro.rfidguard.core.update.UpdateInfo
import com.mypro.rfidguard.core.vault.ExportCodec
import com.mypro.rfidguard.core.vault.VaultData
import com.mypro.rfidguard.security.Auth
import com.mypro.rfidguard.security.DeviceStatus
import com.mypro.rfidguard.security.KeystoreVault
import com.mypro.rfidguard.ui.AppRoot
import com.mypro.rfidguard.update.Updater
import com.mypro.rfidguard.ui.RfidTheme
import java.security.GeneralSecurityException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

enum class NfcState { ABSENT, OFF, ON }

/** What the screens may ask the activity to do. */
interface Host {
    val nfcState: NfcState
    val deviceStatus: DeviceStatus
    fun unlock()
    fun resetVault()
    fun confirmIdentity(reason: String, onConfirmed: () -> Unit)
    fun exportBackup(passphrase: CharArray)
    fun importBackup(passphrase: CharArray)
    fun upgradeProtection()
    fun openLink(uri: String)
    fun openNfcSettings()
    fun copySensitive(label: String, text: String)
    fun checkForUpdate()
    fun installUpdate(info: UpdateInfo)
}

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback, Host {
    private val model: AppModel by viewModels()
    private var nfc: NfcAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private val lockRunnable = Runnable { model.lock() }
    private var stoppedAt = 0L
    private var pickerOpen = false
    private var pendingPassphrase: CharArray? = null

    override var nfcState by mutableStateOf(NfcState.ON)
        private set
    override var deviceStatus by mutableStateOf(DeviceStatus(secureLock = true, rootSuspected = false, usbDebugging = false, debugBuild = false))
        private set

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = model.lock()
    }

    private val nfcChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshNfcState()
    }

    private fun refreshNfcState() {
        val adapter = nfc
        nfcState = when {
            adapter == null -> NfcState.ABSENT
            !adapter.isEnabled -> NfcState.OFF
            else -> NfcState.ON
        }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        pickerOpen = false
        val pass = pendingPassphrase
        pendingPassphrase = null
        val data = model.data
        if (uri == null || pass == null) { pass?.fill('\u0000'); return@registerForActivityResult }
        if (data == null) { pass.fill('\u0000'); model.lockScreenError = "The vault locked before the backup was written. Unlock and export again."; return@registerForActivityResult }
        model.busy = true
        thread {
            val error = try {
                val plain = VaultData.encode(data)
                val file = try { ExportCodec.encrypt(pass, plain) } finally { plain.fill(0) }
                (contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("no stream")).use { it.write(file) }
                null
            } catch (e: Exception) {
                e.message ?: e.javaClass.simpleName
            } finally {
                pass.fill('\u0000')
            }
            runOnUiThread {
                model.busy = false
                model.message = if (error == null) "Encrypted backup written. Without the passphrase it cannot be opened - there is no recovery." else "Backup failed: $error"
            }
        }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerOpen = false
        val pass = pendingPassphrase
        pendingPassphrase = null
        if (uri == null || pass == null) { pass?.fill('\u0000'); return@registerForActivityResult }
        if (model.data == null) { pass.fill('\u0000'); model.lockScreenError = "The vault locked before the backup was read. Unlock and import again."; return@registerForActivityResult }
        model.busy = true
        thread {
            var imported: VaultData? = null
            val error = try {
                val bytes = (contentResolver.openInputStream(uri) ?: throw java.io.IOException("no stream")).use { input ->
                    val buf = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        buf.write(chunk, 0, n)
                        if (buf.size() > MAX_IMPORT_BYTES) throw java.io.IOException("file too large")
                    }
                    buf.toByteArray()
                }
                val plain = ExportCodec.decrypt(pass, bytes)
                imported = try { VaultData.decode(plain) } finally { plain.fill(0) }
                null
            } catch (e: GeneralSecurityException) {
                "Wrong passphrase, or the file is not an intact RFID Guard backup."
            } catch (e: Exception) {
                "The backup could not be read (${e.javaClass.simpleName})."
            } finally {
                pass.fill('\u0000')
            }
            runOnUiThread {
                model.busy = false
                val v = imported
                if (error != null || v == null) { model.message = error ?: "The backup could not be read."; return@runOnUiThread }
                // Settings stay as they are, and tag passwords set since the backup was made are kept:
                // losing one would leave that tag locked for good.
                if (model.update { now -> v.copy(settings = now.settings, tagPasswords = v.tagPasswords + now.tagPasswords, audit = now.audit) }) {
                    model.lastScan = null
                    model.viewing = null
                    model.message = "Backup restored: ${v.savedTags.size} saved tags, ${v.classicKeys.size} keys, ${v.tagPasswords.size} tag passwords."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No screenshots, no screen recording, blank preview in the recent-apps list.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // Ignore touches that arrive through another app's overlay (tap-jacking).
        window.decorView.filterTouchesWhenObscured = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        nfc = NfcAdapter.getDefaultAdapter(this)
        ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, nfcChanged, IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent { RfidTheme { AppRoot(model, this) } }
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        unregisterReceiver(nfcChanged)
        handler.removeCallbacks(lockRunnable)
        super.onDestroy()
    }

    private fun autoLockMillis(): Long {
        val seconds = model.data?.settings?.autoLockSeconds ?: 0
        // A system file picker hides the app; give it a short grace period even with "immediately".
        return (if (pickerOpen) maxOf(seconds, PICKER_GRACE_SECONDS) else seconds) * 1000L
    }

    override fun onStart() {
        super.onStart()
        handler.removeCallbacks(lockRunnable)
        if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= autoLockMillis()) model.lock()
        stoppedAt = 0L
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations || model.data == null) return
        stoppedAt = SystemClock.elapsedRealtime()
        val delay = autoLockMillis()
        if (delay <= 0L) { model.lock(); stoppedAt = 0L } else handler.postDelayed(lockRunnable, delay)
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfc
        refreshNfcState()
        deviceStatus = DeviceStatus.probe(this)
        // Reader mode keeps every tag inside this app while it is in front: no other app is launched,
        // and nothing is dispatched to this app while it is closed.
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V
        runCatching { adapter?.enableReaderMode(this, this, flags, Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 500) }) }
    }

    override fun onPause() {
        super.onPause()
        runCatching { nfc?.disableReaderMode(this) }
    }

    override fun onTagDiscovered(tag: Tag?) {
        if (tag != null) model.onTag(tag)
    }

    // ---- Host -----------------------------------------------------------------------------

    override fun unlock() {
        val vault = model.vault
        val pending: KeystoreVault.Pending
        try {
            pending = if (vault.exists) vault.beginUnlock() else vault.beginCreate()
        } catch (e: KeyPermanentlyInvalidatedException) {
            model.vaultBroken = true
            model.lockScreenError = "The phone's screen lock was removed or reset, which permanently destroyed the vault key. The stored data cannot be recovered. Reset the vault, then restore an encrypted backup if you have one."
            return
        } catch (e: Exception) {
            model.vaultBroken = vault.exists
            model.lockScreenError = "The vault could not be opened (${e.javaClass.simpleName})."
            return
        }
        val creating = !vault.exists
        val complete: () -> Unit = {
            try {
                model.onUnlocked(if (creating) vault.finishCreate(pending) else vault.finishUnlock(pending))
                // A vault made before the phone had a screen lock is bound to it as soon as one exists.
                if (!vault.authBound && vault.deviceSecure()) upgradeProtection()
            } catch (e: Exception) {
                if (creating) vault.discard(pending)
                model.vaultBroken = !creating
                model.lockScreenError = "The vault could not be opened (${e.javaClass.simpleName}). " + if (creating) "Try again." else "Its files may be damaged."
            }
        }
        if (!pending.authBound) { complete(); return }
        Auth.prompt(this, if (creating) "Create your vault" else "Unlock RFID Guard", "Confirm it is you", pending.cipher, complete) { error ->
            if (creating) vault.discard(pending)
            if (error.isNotEmpty()) model.lockScreenError = error
        }
    }

    override fun resetVault() {
        model.lock()
        model.vault.wipe()
        model.vaultBroken = false
        model.lockScreenError = null
    }

    override fun confirmIdentity(reason: String, onConfirmed: () -> Unit) {
        pickerOpen = true // the system credential screen may cover the app; do not auto-lock underneath it
        Auth.prompt(this, "Confirm it is you", reason, null, {
            pickerOpen = false
            if (model.data != null) onConfirmed()
        }) { error ->
            pickerOpen = false
            if (error.isNotEmpty()) model.message = error
        }
    }

    override fun exportBackup(passphrase: CharArray) {
        confirmIdentity("Export an encrypted backup") {
            pendingPassphrase = passphrase
            pickerOpen = true
            val name = "rfidguard-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".rgx"
            try { exportLauncher.launch(name) } catch (e: ActivityNotFoundException) { pickerOpen = false; model.message = "No file picker is available on this phone." }
        }
    }

    override fun importBackup(passphrase: CharArray) {
        confirmIdentity("Replace the vault with a backup") {
            pendingPassphrase = passphrase
            pickerOpen = true
            try { importLauncher.launch(arrayOf("*/*")) } catch (e: ActivityNotFoundException) { pickerOpen = false; model.message = "No file picker is available on this phone." }
        }
    }

    override fun upgradeProtection() {
        val vault = model.vault
        if (!vault.deviceSecure()) { model.message = "Set a screen lock (PIN, pattern or password) in the phone settings first."; return }
        val pending = try { vault.beginRewrap() } catch (e: Exception) { model.message = "Could not prepare a protected key (${e.javaClass.simpleName})."; return }
        Auth.prompt(this, "Protect the vault", "Bind the vault to your screen lock", pending.cipher, {
            try {
                vault.finishRewrap(pending)
                model.message = "The vault key now needs your biometrics or screen lock for every unlock."
                model.vaultRevision++
            } catch (e: Exception) {
                vault.discard(pending)
                model.message = "Could not protect the vault (${e.javaClass.simpleName}). Nothing was changed."
            }
        }) { error ->
            vault.discard(pending)
            if (error.isNotEmpty()) model.message = error
        }
    }

    override fun openLink(uri: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri.trim()).normalizeScheme()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (e: Exception) {
            model.message = "No app on this phone can open that link."
        }
    }

    override fun openNfcSettings() {
        try { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) } catch (e: Exception) { model.message = "Open the phone settings and turn on NFC." }
    }

    override fun copySensitive(label: String, text: String) {
        val clip = ClipData.newPlainText(label, text)
        // Keeps the value out of clipboard previews and keyboard suggestions (Android 13+).
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(clip)
        handler.postDelayed({
            runCatching {
                // Only clear what this app put there; the owner may have copied something else since.
                if (clipboard.primaryClipDescription?.label?.toString() == label) clipboard.clearPrimaryClip()
            }
        }, CLIPBOARD_CLEAR_MS)
        model.message = "Copied. The clipboard is cleared again in ${CLIPBOARD_CLEAR_MS / 1000} seconds."
    }

    override fun checkForUpdate() {
        if (model.busy) return
        model.busy = true
        thread {
            val result = runCatching { Updater.check(BuildConfig.VERSION_CODE) }
            runOnUiThread {
                model.busy = false
                if (model.data == null) return@runOnUiThread
                val info = result.getOrNull()
                if (result.isFailure) model.message = "Could not check for updates. Check the internet connection and try again."
                else if (info == null) model.message = "You have the latest build (${BuildConfig.VERSION_CODE})."
                else model.availableUpdate = info
            }
        }
    }

    override fun installUpdate(info: UpdateInfo) {
        if (model.busy) return
        model.busy = true
        thread {
            val result = runCatching { Updater.download(applicationContext, info, BuildConfig.VERSION_CODE) }
            runOnUiThread {
                model.busy = false
                val apk = result.getOrNull()
                val error = result.exceptionOrNull()
                if (apk == null) {
                    model.message = if (error is Updater.Rejected) error.message else "The download failed. Check the internet connection and try again."
                    return@runOnUiThread
                }
                try {
                    startActivity(Updater.installIntent(this, apk))
                } catch (e: Exception) {
                    model.message = "Android's installer could not be opened."
                }
            }
        }
    }

    private companion object {
        const val PICKER_GRACE_SECONDS = 180
        const val MAX_IMPORT_BYTES = 32 * 1024 * 1024
        const val CLIPBOARD_CLEAR_MS = 30_000L
    }
}
