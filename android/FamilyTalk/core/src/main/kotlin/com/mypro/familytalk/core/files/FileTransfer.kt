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
    /**
     * Sent chunk size. Chosen so one sealed chunk (two base64 layers + headers) fits in the
     * 64 KiB padding bucket instead of spilling into the 128 KiB one: ~2x less data on the wire.
     */
    const val CHUNK = 34 * 1024
    /** Largest chunk accepted (older versions sent 48 KiB). */
    const val MAX_CHUNK = 48 * 1024
    const val INLINE_LIMIT = 25L * 1024 * 1024

    fun prepare(name: String, mime: String, data: ByteArray, now: Long = System.currentTimeMillis(), group: Boolean = false, viewOnce: Boolean = false): Pair<FileOffer, List<FileChunk>> {
        require(data.size <= INLINE_LIMIT) { "File is larger than 25 MB" }
        val id = Bytes.b64(Bytes.random(12))
        val offer = FileOffer(id, sanitizeName(name), mime.take(100), data.size.toLong(), Bytes.hex(Bytes.sha256(data)), chunkCount(data.size), now, group, viewOnce)
        return offer to chunks(id, data)
    }

    private fun countFor(size: Long, chunk: Int): Int = if (size == 0L) 1 else ((size + chunk - 1) / chunk).toInt()

    fun chunkCount(size: Int) = if (size == 0) 1 else (size + CHUNK - 1) / CHUNK

    /** Chunks of a stored file (all, or only [only]) for sending or re-sending. */
    fun chunks(fileId: String, data: ByteArray, only: Collection<Int>? = null): List<FileChunk> =
        (0 until chunkCount(data.size)).filter { only == null || it in only }.map { i ->
            FileChunk(fileId, i, Bytes.b64(data.copyOfRange(minOf(i * CHUNK, data.size), minOf((i + 1) * CHUNK, data.size))))
        }

    /** Strips paths and control characters so a received name can never escape the download folder. */
    fun sanitizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val clean = base.filter { it.code >= 32 && it !in "<>:\"|?*" }.trim().trimStart('.').trimEnd('.', ' ')
        return clean.take(120).ifBlank { "file" }
    }

    class Assembly(val offer: FileOffer) {
        init {
            require(offer.size in 0..INLINE_LIMIT) { "bad size" }
            require(offer.chunks in 1..(INLINE_LIMIT / 1024 + 1)) { "bad chunk count" }
            // Exactly the count our (or an older 48 KiB) sender produces: no oversized assemblies.
            require(offer.chunks == countFor(offer.size, CHUNK) || offer.chunks == countFor(offer.size, MAX_CHUNK)) { "bad chunk count" }
        }
        private val parts = arrayOfNulls<ByteArray>(offer.chunks)
        private var bytes = 0L
        val received: Int get() = parts.count { it != null }
        val complete: Boolean get() = received == offer.chunks
        /** Chunk indices still missing. */
        fun missing(): List<Int> = parts.indices.filter { parts[it] == null }

        fun add(chunk: FileChunk) {
            require(chunk.fileId == offer.fileId) { "wrong file" }
            require(chunk.index in parts.indices) { "bad index" }
            val data = Bytes.unb64(chunk.data)
            require(data.size <= MAX_CHUNK) { "chunk too big" }
            val old = parts[chunk.index]?.size ?: 0
            require(bytes - old + data.size <= offer.size) { "more data than announced" }
            bytes += data.size - old
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
