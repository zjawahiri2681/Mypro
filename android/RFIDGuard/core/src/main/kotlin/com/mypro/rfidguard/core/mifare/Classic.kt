package com.mypro.rfidguard.core.mifare

/** MIFARE Classic memory layout, access conditions and value blocks. */
object Classic {
    /** Publicly documented transport / application keys (the same three ship in the Android SDK). */
    val WELL_KNOWN_KEYS: List<String> = listOf("FFFFFFFFFFFF", "A0A1A2A3A4A5", "D3F7D3F7D3F7")

    fun sectorCount(sizeBytes: Int): Int = when (sizeBytes) {
        320 -> 5; 1024 -> 16; 2048 -> 32; 4096 -> 40
        else -> 16
    }
    fun blocksInSector(sector: Int): Int = if (sector < 32) 4 else 16
    fun firstBlock(sector: Int): Int = if (sector < 32) sector * 4 else 128 + (sector - 32) * 16
    fun sectorOfBlock(block: Int): Int = if (block < 128) block / 4 else 32 + (block - 128) / 16
    fun trailerBlock(sector: Int): Int = firstBlock(sector) + blocksInSector(sector) - 1
    fun isTrailer(block: Int): Boolean = block == trailerBlock(sectorOfBlock(block))

    /** Access bits C1 C2 C3 for the four access groups of a sector, or null when the redundancy check fails. */
    fun accessBits(trailer: ByteArray): List<Int>? {
        if (trailer.size != 16) return null
        val b6 = trailer[6].toInt() and 0xFF
        val b7 = trailer[7].toInt() and 0xFF
        val b8 = trailer[8].toInt() and 0xFF
        val c1 = b7 ushr 4
        val c2 = b8 and 0x0F
        val c3 = b8 ushr 4
        val nc1 = b6 and 0x0F
        val nc2 = b6 ushr 4
        val nc3 = b7 and 0x0F
        if (c1 xor nc1 != 0x0F || c2 xor nc2 != 0x0F || c3 xor nc3 != 0x0F) return null
        return (0..3).map { i -> (((c1 ushr i) and 1) shl 2) or (((c2 ushr i) and 1) shl 1) or ((c3 ushr i) and 1) }
    }

    fun describeDataAccess(c: Int): String = when (c) {
        0b000 -> "read/write/increment/decrement with key A or B (transport setting)"
        0b010 -> "read with key A or B, never writable"
        0b100 -> "read with key A or B, write with key B"
        0b110 -> "value block: read A or B, write/increment B, decrement A or B"
        0b001 -> "value block: read and decrement with A or B, no write"
        0b011 -> "read and write with key B only"
        0b101 -> "read with key B only, never writable"
        else -> "no access"
    }

    fun describeTrailerAccess(c: Int): String = when (c) {
        0b000 -> "key A changes keys; access bits fixed; key B readable with key A"
        0b010 -> "keys and access bits permanently fixed; key B readable with key A"
        0b100 -> "key B changes keys; access bits fixed"
        0b110 -> "keys and access bits permanently fixed"
        0b001 -> "key A changes everything; key B readable with key A (transport setting)"
        0b011 -> "key B changes keys and access bits"
        0b101 -> "key B changes access bits; keys fixed"
        else -> "keys and access bits permanently fixed"
    }

    /** True when key B is stored as readable data and therefore gives no protection. */
    fun keyBReadable(trailerAccess: Int): Boolean = trailerAccess == 0b000 || trailerAccess == 0b010 || trailerAccess == 0b001

    /** True when this trailer setting can never be changed again once written. */
    fun trailerIsPermanent(trailerAccess: Int): Boolean = trailerAccess == 0b010 || trailerAccess == 0b110 || trailerAccess == 0b111

    /** Returns a problem description, or null when [trailer] is safe to write. */
    fun validateTrailerForWrite(trailer: ByteArray): String? {
        if (trailer.size != 16) return "A sector trailer is exactly 16 bytes"
        val bits = accessBits(trailer) ?: return "Access bits fail their redundancy check; writing this would lock the sector permanently"
        if (trailer.copyOfRange(0, 6).all { it.toInt() == 0 }) return "Key A is all zeros. A trailer read from a card shows key A as zeros - enter the real key A"
        if (trailerIsPermanent(bits[3])) return "These access bits freeze the sector trailer forever"
        if (bits[3] == 0b000 || bits[3] == 0b100) return "These access bits can never be changed again once written"
        if (bits.take(3).any { it == 0b111 }) return "These access bits make a data block permanently inaccessible"
        return null
    }

    /** Decodes a value block, or null if the block is not in value format. */
    fun valueBlock(block: ByteArray): Int? {
        if (block.size != 16) return null
        fun int(o: Int) = (block[o].toInt() and 0xFF) or ((block[o + 1].toInt() and 0xFF) shl 8) or
            ((block[o + 2].toInt() and 0xFF) shl 16) or ((block[o + 3].toInt() and 0xFF) shl 24)
        val v = int(0)
        if (int(4) != v.inv() || int(8) != v) return null
        val a = block[12].toInt() and 0xFF
        if (block[14].toInt() and 0xFF != a) return null
        if (block[13].toInt() and 0xFF != a xor 0xFF || block[15].toInt() and 0xFF != a xor 0xFF) return null
        return v
    }
}
