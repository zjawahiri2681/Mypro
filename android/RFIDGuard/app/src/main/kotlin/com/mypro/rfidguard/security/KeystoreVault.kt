package com.mypro.rfidguard.security

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.AtomicFile
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.vault.AeadBox
import com.mypro.rfidguard.core.vault.VaultData
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted storage for everything the app keeps.
 *
 * Layout: a random 256-bit data key encrypts the vault file (AES-256-GCM). The data key itself
 * is stored wrapped by a non-exportable AES key inside the Android Keystore (StrongBox when the
 * phone has one). When the phone has a screen lock, that Keystore key demands a fresh biometric
 * or screen-lock authentication for every single use, so the vault cannot be opened by code
 * alone - not even by this app - without the owner present. While unlocked the data key lives
 * only in memory and is zeroed on lock.
 */
class KeystoreVault(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("vault_meta", Context.MODE_PRIVATE)
    private val keyFile = File(app.filesDir, "vault.key")
    private val dataFile = File(app.filesDir, "vault.bin")
    private var dataKey: ByteArray? = null

    /** A Keystore cipher waiting for the user's authentication. */
    class Pending internal constructor(
        val cipher: Cipher,
        internal val alias: String,
        val authBound: Boolean,
        internal val strongBox: Boolean,
        internal val creating: Boolean,
    )

    val exists: Boolean get() = keyFile.exists() && prefs.contains(PREF_ALIAS)
    val authBound: Boolean get() = prefs.getBoolean(PREF_AUTH, false)
    val strongBox: Boolean get() = prefs.getBoolean(PREF_STRONGBOX, false)
    val unlocked: Boolean get() = dataKey != null

    fun deviceSecure(): Boolean = app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun keystore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Creates a Keystore key and reports whether it ended up in StrongBox. */
    private fun generateKey(alias: String, requireAuth: Boolean): Boolean {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
            if (requireAuth) {
                b.setUserAuthenticationRequired(true)
                // 0 = every use needs its own authentication, bound to the cipher operation.
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                // The screen lock is always accepted, so a newly enrolled fingerprint gains nothing
                // its owner could not already do; keeping the key avoids losing the vault.
                b.setInvalidatedByBiometricEnrollment(false)
            }
            if (strongBox) b.setIsStrongBoxBacked(true)
            return b.build()
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        try {
            generator.init(spec(true))
            generator.generateKey()
            return true
        } catch (e: StrongBoxUnavailableException) {
            // fall through to the TEE-backed keystore
        } catch (e: GeneralSecurityException) {
            // some devices report StrongBox problems with a generic provider error
        } catch (e: RuntimeException) {
            // ditto
        }
        runCatching { keystore().deleteEntry(alias) }
        generator.init(spec(false))
        generator.generateKey()
        return false
    }

    private fun beginWrap(creating: Boolean): Pending {
        val alias = ALIAS_PREFIX + System.currentTimeMillis()
        val auth = deviceSecure()
        val strong = generateKey(alias, auth)
        val key = keystore().getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Pending(cipher, alias, auth, strong, creating)
    }

    /** First run: prepares a new Keystore key. Complete with [finishCreate] after authentication. */
    fun beginCreate(): Pending = beginWrap(true)

    /** Re-protects an already unlocked vault with a new Keystore key (after a screen lock was added). */
    fun beginRewrap(): Pending {
        check(unlocked) { "Vault is locked" }
        return beginWrap(false)
    }

    /** Throws KeyPermanentlyInvalidatedException when the screen lock was removed since the vault was made. */
    fun beginUnlock(): Pending {
        val alias = prefs.getString(PREF_ALIAS, null) ?: throw GeneralSecurityException("No vault")
        val wrapped = keyFile.readBytes()
        if (wrapped.size < IV_SIZE + 16) throw GeneralSecurityException("Vault key file is damaged")
        val key = keystore().getKey(alias, null) as? SecretKey ?: throw GeneralSecurityException("Vault key is missing from the Keystore")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, IV_SIZE))
        return Pending(cipher, alias, authBound, strongBox, false)
    }

    /** Drops a prepared key when the user cancelled authentication. */
    fun discard(p: Pending) {
        if (p.alias != prefs.getString(PREF_ALIAS, null)) runCatching { keystore().deleteEntry(p.alias) }
    }

    private fun storeWrapped(p: Pending, key: ByteArray) {
        val wrapped = p.cipher.iv + p.cipher.doFinal(key)
        writeAtomically(keyFile, wrapped)
        val previous = prefs.getString(PREF_ALIAS, null)
        prefs.edit().putString(PREF_ALIAS, p.alias).putBoolean(PREF_AUTH, p.authBound).putBoolean(PREF_STRONGBOX, p.strongBox).commit()
        if (previous != null && previous != p.alias) runCatching { keystore().deleteEntry(previous) }
    }

    fun finishCreate(p: Pending): VaultData {
        require(p.creating)
        val random = SecureRandom()
        val key = ByteArray(32).also(random::nextBytes)
        val signing = ByteArray(32).also(random::nextBytes)
        val data = VaultData(signingKeyHex = signing.toHex())
        signing.fill(0)
        // Write the data file before the wrapped key becomes the active one.
        dataKey = key
        try {
            save(data)
            storeWrapped(p, key)
        } catch (e: Exception) {
            lock()
            throw e
        }
        return data
    }

    fun finishRewrap(p: Pending) {
        require(!p.creating)
        storeWrapped(p, dataKey ?: throw IllegalStateException("Vault is locked"))
    }

    fun finishUnlock(p: Pending): VaultData {
        val wrapped = keyFile.readBytes()
        val key = p.cipher.doFinal(wrapped, IV_SIZE, wrapped.size - IV_SIZE)
        try {
            val blob = dataFile.readBytes()
            if (blob.size < MAGIC.size || !blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw GeneralSecurityException("Vault file is damaged")
            val plain = AeadBox.open(key, blob.copyOfRange(MAGIC.size, blob.size), MAGIC)
            val data = try {
                VaultData.decode(plain)
            } catch (e: RuntimeException) {
                throw GeneralSecurityException("Vault content could not be read")
            } finally {
                plain.fill(0)
            }
            dataKey = key
            return data
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
    }

    @Synchronized
    fun save(data: VaultData) {
        val key = dataKey ?: throw IllegalStateException("Vault is locked")
        val plain = VaultData.encode(data)
        try {
            writeAtomically(dataFile, MAGIC + AeadBox.seal(key, plain, MAGIC))
        } finally {
            plain.fill(0)
        }
    }

    @Synchronized
    fun lock() {
        dataKey?.fill(0)
        dataKey = null
    }

    /** Destroys the vault and its keys. Nothing can be recovered afterwards. */
    fun wipe() {
        lock()
        runCatching {
            val ks = keystore()
            for (alias in ks.aliases().toList()) if (alias.startsWith(ALIAS_PREFIX)) ks.deleteEntry(alias)
        }
        for (f in listOf(dataFile, keyFile)) {
            // Overwrite before deleting. Flash storage may keep old blocks, but the wrapping key is gone anyway.
            runCatching { if (f.exists()) f.writeBytes(ByteArray(f.length().toInt().coerceIn(0, 1 shl 20))) }
            f.delete()
            File(f.path + ".new").delete()
            File(f.path + ".bak").delete()
        }
        prefs.edit().clear().commit()
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (e: Exception) {
            atomic.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "rfidguard_master_"
        const val PREF_ALIAS = "alias"
        const val PREF_AUTH = "authBound"
        const val PREF_STRONGBOX = "strongBox"
        const val IV_SIZE = 12
        val MAGIC = "RGV1".toByteArray(Charsets.US_ASCII)
    }
}
