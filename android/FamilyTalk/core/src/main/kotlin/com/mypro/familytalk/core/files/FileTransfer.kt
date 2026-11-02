package com.mypro.familytalk.core.files

import com.mypro.familytalk.core.crypto.Bytes
import com.mypro.familytalk.core.protocol.FileChunk
import com.mypro.familytalk.core.protocol.FileOffer

/**
 * Splits a file into chunks that each fit in one sealed envelope and reassembles them,
 * checking size, chunk count and SHA-256 so a corrupted or tampered file is rejected.
 * Every chunk is individually end-to-end encrypted by the envelope layer.
 */
object FileTransfer {
    const val CHUNK = 48 * 1024
    const val INLINE_LIMIT = 25L * 1024 * 1024

    fun prepare(name: String, mime: String, data: ByteArray, now: Long = System.currentTimeMillis(), group: Boolean = false): Pair<FileOffer, List<FileChunk>> {
        require(data.size <= INLINE_LIMIT) { "File is larger than 25 MB" }
        val id = Bytes.b64(Bytes.random(12))
        val parts = if (data.isEmpty()) listOf(ByteArray(0)) else data.toList().chunked(CHUNK).map { it.toByteArray() }
        val offer = FileOffer(id, sanitizeName(name), mime.take(100), data.size.toLong(), Bytes.hex(Bytes.sha256(data)), parts.size, now, group)
        return offer to parts.mapIndexed { i, p -> FileChunk(id, i, Bytes.b64(p)) }
    }

    /** Strips paths and control characters so a received name can never escape the download folder. */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it.code >= 32 && it !in "<>:\"|?*" }.trim().trimStart('.')
        return clean.take(120).ifBlank { "file" }
    }

    class Assembly(val offer: FileOffer) {
        init {
            require(offer.size in 0..INLINE_LIMIT) { "bad size" }
            require(offer.chunks in 1..(INLINE_LIMIT / CHUNK + 1)) { "bad chunk count" }
        }
        private val parts = arrayOfNulls<ByteArray>(offer.chunks)
        val received: Int get() = parts.count { it != null }
        val complete: Boolean get() = received == offer.chunks

        fun add(chunk: FileChunk) {
            require(chunk.fileId == offer.fileId) { "wrong file" }
            require(chunk.index in parts.indices) { "bad index" }
            val data = Bytes.unb64(chunk.data)
            require(data.size <= CHUNK) { "chunk too big" }
            parts[chunk.index] = data
        }

        /** Returns the verified file bytes, or throws if anything does not add up. */
        fun finish(): ByteArray {
            check(complete) { "missing chunks" }
            val out = java.io.ByteArrayOutputStream(offer.size.toInt())
            parts.forEach { out.write(it!!) }
            val bytes = out.toByteArray()
            require(bytes.size.toLong() == offer.size) { "size mismatch" }
            require(Bytes.hex(Bytes.sha256(bytes)) == offer.sha256) { "checksum mismatch" }
            return bytes
        }
    }
}
