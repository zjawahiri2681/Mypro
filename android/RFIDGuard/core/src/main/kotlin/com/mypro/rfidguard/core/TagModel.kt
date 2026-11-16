package com.mypro.rfidguard.core

import kotlinx.serialization.Serializable

/** Everything the app learned about one tag during a scan. Stored only inside the encrypted vault. */
@Serializable
data class TagSnapshot(
    val uidHex: String,
    val techs: List<String>,
    val readAtEpochMs: Long,
    val typeName: String,
    val manufacturer: String? = null,
    val atqaHex: String? = null,
    val sak: Int? = null,
    val atsHex: String? = null,
    val ndef: NdefInfo? = null,
    val type2: Type2Dump? = null,
    val classic: ClassicDump? = null,
    val nfcV: NfcVDump? = null,
    val felica: FelicaInfo? = null,
    val notes: List<String> = emptyList(),
)

@Serializable
data class NdefInfo(
    val tagType: String,
    val maxSize: Int,
    val writable: Boolean,
    val canMakeReadOnly: Boolean,
    val messageHex: String? = null,
)

@Serializable
data class Type2Dump(
    val versionHex: String? = null,
    /** One entry per page; null where the page could not be read (password protected). */
    val pagesHex: List<String?>,
    val signatureHex: String? = null,
    val passwordUsed: Boolean = false,
)

@Serializable
data class ClassicDump(val sizeBytes: Int, val sectors: List<ClassicSector>)

@Serializable
data class ClassicSector(
    val index: Int,
    val firstBlock: Int,
    val blocksHex: List<String?>,
    val keyAHex: String? = null,
    val keyBHex: String? = null,
)

@Serializable
data class NfcVDump(
    val dsfid: Int? = null,
    val afi: Int? = null,
    val icReference: Int? = null,
    val blockSize: Int,
    val blocksHex: List<String?>,
)

@Serializable
data class FelicaInfo(val pmmHex: String, val systemCodeHex: String)

object TagIdentity {
    private val MANUFACTURERS = mapOf(
        0x01 to "Motorola", 0x02 to "STMicroelectronics", 0x03 to "Hitachi", 0x04 to "NXP Semiconductors",
        0x05 to "Infineon", 0x06 to "Cylink", 0x07 to "Texas Instruments", 0x08 to "Fujitsu",
        0x09 to "Matsushita", 0x0A to "NEC", 0x0B to "Oki", 0x0C to "Toshiba", 0x0D to "Mitsubishi",
        0x0E to "Samsung", 0x0F to "Hynix", 0x10 to "LG", 0x16 to "EM Microelectronic",
        0x1C to "Shanghai Belling", 0x1D to "Shanghai Fudan", 0x28 to "Giesecke & Devrient", 0x39 to "Silicon Craft",
    )

    /** [uid] as returned by Android. ISO 15693 UIDs arrive least-significant byte first. */
    fun manufacturer(uid: ByteArray, isNfcV: Boolean): String? {
        val code = when {
            isNfcV && uid.size == 8 -> uid[6].toInt() and 0xFF
            !isNfcV && (uid.size == 7 || uid.size == 10) -> uid[0].toInt() and 0xFF
            else -> return null
        }
        return MANUFACTURERS[code]
    }

    /** Best-effort chip family from the ISO 14443-A select answer. */
    fun typeFromSak(sak: Int, atqa: ByteArray?): String = when (sak and 0xFF) {
        0x00 -> "NFC Type 2 (MIFARE Ultralight / NTAG)"
        0x08 -> "MIFARE Classic 1K"
        0x09 -> "MIFARE Mini"
        0x18 -> "MIFARE Classic 4K"
        0x19 -> "MIFARE Classic 2K"
        0x10 -> "MIFARE Plus 2K (SL2)"
        0x11 -> "MIFARE Plus 4K (SL2)"
        0x20 -> if (atqa != null && atqa.size == 2 && atqa[0].toInt() == 0x44 && atqa[1].toInt() == 0x03) "MIFARE DESFire" else "ISO 14443-4 smart card"
        0x28 -> "Smart card with MIFARE Classic 1K emulation"
        0x38 -> "Smart card with MIFARE Classic 4K emulation"
        0x88 -> "MIFARE Classic 1K (Infineon)"
        else -> "ISO 14443-A tag"
    }
}
