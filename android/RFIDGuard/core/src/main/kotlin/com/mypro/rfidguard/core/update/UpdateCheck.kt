package com.mypro.rfidguard.core.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class UpdateInfo(val build: Int, val versionName: String, val apkUrl: String, val shaUrl: String, val sizeBytes: Long)

/**
 * Finds a newer RFID Guard build in the project's GitHub release list. The release list is
 * untrusted input: only download links that point into this project's own release folder are
 * accepted, and the app verifies checksum, package name, version and signing certificate before
 * it hands anything to Android's installer.
 */
object UpdateCheck {
    const val RELEASES_URL = "https://api.github.com/repos/zjawahiri2681/Mypro/releases?per_page=50"
    const val DOWNLOAD_PREFIX = "https://github.com/zjawahiri2681/Mypro/releases/download/"
    const val MAX_APK_BYTES = 80L * 1024 * 1024
    private val TAG = Regex("^rfidguard-v([0-9][0-9A-Za-z.]{0,20})-build([0-9]{1,9})$")
    private val SHA = Regex("^[0-9a-fA-F]{64}")

    private fun acceptableUrl(url: String, tag: String, suffix: String): Boolean =
        url.startsWith("$DOWNLOAD_PREFIX$tag/") && url.endsWith(suffix) &&
            url.substring(DOWNLOAD_PREFIX.length + tag.length + 1).all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } &&
            !url.contains("..")

    /** The highest published build, or null when the list holds no usable RFID Guard release. */
    fun latest(json: String): UpdateInfo? {
        val releases = try { Json.parseToJsonElement(json).jsonArray } catch (e: Exception) { return null }
        var best: UpdateInfo? = null
        for (element in releases) {
            try {
                val r = element as? JsonObject ?: continue
                if (r["draft"]?.jsonPrimitive?.content == "true" || r["prerelease"]?.jsonPrimitive?.content == "true") continue
                val tag = r["tag_name"]?.jsonPrimitive?.content ?: continue
                val m = TAG.matchEntire(tag) ?: continue
                val build = m.groupValues[2].toInt()
                var apk: String? = null; var sha: String? = null; var size = 0L
                for (a in r["assets"]?.jsonArray ?: continue) {
                    val url = a.jsonObject["browser_download_url"]?.jsonPrimitive?.content ?: continue
                    if (acceptableUrl(url, tag, ".apk.sha256")) sha = url
                    else if (acceptableUrl(url, tag, ".apk")) { apk = url; size = a.jsonObject["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }
                }
                if (apk == null || sha == null || size <= 0 || size > MAX_APK_BYTES) continue
                if (best == null || build > best.build) best = UpdateInfo(build, m.groupValues[1], apk, sha, size)
            } catch (e: Exception) {
                continue
            }
        }
        return best
    }

    fun newerThan(json: String, currentBuild: Int): UpdateInfo? = latest(json)?.takeIf { it.build > currentBuild }

    /** First field of a `sha256sum` line, lower-cased. */
    fun parseSha256(text: String): String? = SHA.find(text.trim())?.value?.lowercase()
}
