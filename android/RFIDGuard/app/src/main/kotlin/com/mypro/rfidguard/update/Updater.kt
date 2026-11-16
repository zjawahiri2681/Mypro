package com.mypro.rfidguard.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.mypro.rfidguard.core.toHex
import com.mypro.rfidguard.core.update.UpdateCheck
import com.mypro.rfidguard.core.update.UpdateInfo
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * On-demand updates. The network is touched only from here, only after the owner taps
 * "Check for updates", and only towards GitHub. Nothing from the vault is ever sent.
 */
object Updater {
    class Rejected(message: String) : Exception(message)

    private val ALLOWED_HOSTS = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    private fun open(url: String): HttpsURLConnection {
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("not https")
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "RFIDGuard-updater")
        conn.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        val code = conn.responseCode
        // Redirects are followed by the platform; make sure we ended up at GitHub, over TLS.
        if (conn.url.protocol != "https" || conn.url.host !in ALLOWED_HOSTS) { conn.disconnect(); throw IOException("unexpected host") }
        if (code != 200) { conn.disconnect(); throw IOException("server answered $code") }
        return conn
    }

    private fun fetchSmall(url: String, limit: Int): ByteArray {
        val conn = open(url)
        try {
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > limit) throw IOException("answer too large")
                }
                return out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Returns a newer build, or null when this one is current. */
    @Throws(IOException::class)
    fun check(currentBuild: Int): UpdateInfo? =
        UpdateCheck.newerThan(String(fetchSmall(UpdateCheck.RELEASES_URL, 2 * 1024 * 1024), Charsets.UTF_8), currentBuild)

    private fun signers(info: PackageInfo?): Set<String> =
        info?.signingInfo?.apkContentsSigners?.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }?.toSet().orEmpty()

    /** Downloads the APK into the app's private cache and verifies it. */
    @Throws(IOException::class, Rejected::class)
    fun download(context: Context, info: UpdateInfo, currentBuild: Int): File {
        val expected = UpdateCheck.parseSha256(String(fetchSmall(info.shaUrl, 4096), Charsets.UTF_8))
            ?: throw Rejected("The release has no valid checksum file.")
        val dir = File(context.cacheDir, "updates")
        dir.deleteRecursively()
        if (!dir.mkdirs()) throw IOException("cannot create the download folder")
        val file = File(dir, "RFIDGuard-update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = open(info.apkUrl)
        try {
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > UpdateCheck.MAX_APK_BYTES) throw IOException("download too large")
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        try {
            if (digest.digest().toHex().lowercase() != expected) throw Rejected("The download does not match its checksum. It was damaged or altered and has been deleted.")
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: throw Rejected("The download is not a valid app package.")
            if (archive.packageName != context.packageName) throw Rejected("The download is a different app (${archive.packageName}).")
            if (archive.longVersionCode <= currentBuild) throw Rejected("The download is not newer than the installed build.")
            @Suppress("DEPRECATION")
            val mine = signers(pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
            val theirs = signers(archive)
            // Android enforces this again at install time; checking here gives a clear explanation up front.
            if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) {
                throw Rejected("The new build is signed with a different key than the installed app, so Android will not install it as an update. Set up the permanent signing key (see the README), or export a backup, uninstall and install the new build.")
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        return file
    }

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun clearDownloads(context: Context) {
        File(context.cacheDir, "updates").deleteRecursively()
    }
}
