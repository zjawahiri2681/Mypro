package com.mypro.rfidguard.core.vault

import com.mypro.rfidguard.core.TagSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SavedTag(val id: String, val name: String, val savedAtEpochMs: Long, val snapshot: TagSnapshot)

@Serializable
data class ClassicKeyEntry(val label: String, val keyHex: String)

/** Password and acknowledge bytes set on an NTAG / Ultralight EV1 by this app. */
@Serializable
data class TagPassword(val pwdHex: String, val packHex: String, val createdAtEpochMs: Long)

@Serializable
data class AuditEntry(val atEpochMs: Long, val uidHex: String, val action: String, val ok: Boolean, val detail: String = "")

@Serializable
data class AppSettings(
    val autoLockSeconds: Int = 60,
    val expertMode: Boolean = false,
    val signWrites: Boolean = false,
)

/** The whole persistent state of the app. It only ever touches disk AES-256-GCM encrypted. */
@Serializable
data class VaultData(
    val version: Int = 1,
    val signingKeyHex: String,
    val savedTags: List<SavedTag> = emptyList(),
    val classicKeys: List<ClassicKeyEntry> = emptyList(),
    val tagPasswords: Map<String, TagPassword> = emptyMap(),
    val audit: List<AuditEntry> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    fun withAudit(entry: AuditEntry): VaultData = copy(audit = (listOf(entry) + audit).take(MAX_AUDIT))

    companion object {
        const val MAX_AUDIT = 500
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun encode(data: VaultData): ByteArray = json.encodeToString(serializer(), data).toByteArray(Charsets.UTF_8)
        fun decode(bytes: ByteArray): VaultData = json.decodeFromString(serializer(), String(bytes, Charsets.UTF_8))
    }
}

/** AES-256-GCM with a random 96-bit nonce prepended to the ciphertext. */
object AeadBox {
    private const val NONCE = 12
    private val random = SecureRandom()

    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        val nonce = ByteArray(NONCE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    @Throws(GeneralSecurityException::class)
    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == 32) { "AES-256 key required" }
        if (blob.size < NONCE + 16) throw GeneralSecurityException("Ciphertext too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob, 0, NONCE))
        cipher.updateAAD(aad)
        return cipher.doFinal(blob, NONCE, blob.size - NONCE)
    }
}

/**
 * Passphrase-protected backup file: "RGX1" | salt(16) | iterations(4) | AES-256-GCM box.
 * The key comes from PBKDF2-HMAC-SHA256; the header is authenticated as associated data.
 */
object ExportCodec {
    const val MIN_PASSPHRASE = 10
    const val DEFAULT_ITERATIONS = 600_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val MIN_ITERATIONS = 100_000
    private val MAGIC = "RGX1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val HEADER = 4 + SALT + 4

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(passphrase: CharArray, plaintext: ByteArray, iterations: Int = DEFAULT_ITERATIONS): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase must be at least $MIN_PASSPHRASE characters" }
        val salt = ByteArray(SALT).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER).put(MAGIC).put(salt).putInt(iterations).array()
        val key = deriveKey(passphrase, salt, iterations)
        try {
            return header + AeadBox.seal(key, plaintext, header)
        } finally {
            key.fill(0)
        }
    }

    /** Throws [GeneralSecurityException] for a wrong passphrase or a damaged / foreign file. */
    @Throws(GeneralSecurityException::class)
    fun decrypt(passphrase: CharArray, file: ByteArray): ByteArray {
        if (file.size < HEADER || !file.copyOfRange(0, 4).contentEquals(MAGIC)) throw GeneralSecurityException("Not an RFID Guard backup")
        val buf = ByteBuffer.wrap(file, 4, HEADER - 4)
        val salt = ByteArray(SALT).also { buf.get(it) }
        val iterations = buf.int
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS) throw GeneralSecurityException("Unsupported backup parameters")
        val key = deriveKey(passphrase, salt, iterations)
        try {
            return AeadBox.open(key, file.copyOfRange(HEADER, file.size), file.copyOfRange(0, HEADER))
        } finally {
            key.fill(0)
        }
    }
}
