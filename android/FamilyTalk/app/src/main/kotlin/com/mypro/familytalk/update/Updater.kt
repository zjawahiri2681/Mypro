package com.mypro.familytalk.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.mypro.familytalk.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val build: Int, val version: String, val apkUrl: String, val shaUrl: String?, val sizeBytes: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-update from this project's public GitHub Releases. Safety:
 *  - only HTTPS to github.com / objects.githubusercontent.com,
 *  - the APK must match the SHA-256 published next to it,
 *  - the APK must be signed with the same certificate as the installed app (Android
 *    enforces this too; we check first so the message is clear).
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val lock = Mutex()
    var onReady: (UpdateInfo) -> Unit = {}

    val currentBuild: Int get() = BuildConfig.VERSION_CODE
    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Check, and if [download] download + verify in the background. */
    fun check(download: Boolean) = scope.launch {
        lock.withLock {
            val s = _state.value
            if (s is UpdateState.Downloading || s is UpdateState.Installing) return@withLock
            if (s is UpdateState.Ready) { if (s.info.build > currentBuild) return@withLock }
            _state.value = UpdateState.Checking
            val info = runCatching { latest() }.getOrElse {
                _state.value = UpdateState.Failed("Couldn't check for updates: ${it.message}"); return@withLock
            }
            if (info == null || info.build <= currentBuild) { _state.value = UpdateState.UpToDate; return@withLock }
            _state.value = UpdateState.Available(info)
            if (download) downloadNow(info)
        }
    }

    fun download(info: UpdateInfo) = scope.launch { lock.withLock { downloadNow(info) } }

    private suspend fun latest(): UpdateInfo? = withContext(Dispatchers.IO) {
        val body = get("https://api.github.com/repos/$REPO/releases?per_page=30")
        val releases = Json.parseToJsonElement(body).jsonArray
        releases.mapNotNull { r ->
            val o = r.jsonObject
            if (o["draft"]?.jsonPrimitive?.content == "true") return@mapNotNull null
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val m = TAG.matchEntire(tag) ?: return@mapNotNull null
            val assets = (o["assets"] as? JsonArray)?.map { it.jsonObject } ?: return@mapNotNull null
            val apk = assets.firstOrNull { it.name().endsWith(".apk") } ?: return@mapNotNull null
            val sha = assets.firstOrNull { it.name().endsWith(".apk.sha256") }
            UpdateInfo(m.groupValues[2].toInt(), m.groupValues[1], apk.url(), sha?.url(), apk["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0)
        }.maxByOrNull { it.build }
    }

    private suspend fun downloadNow(info: UpdateInfo) {
        try {
            val dir = File(context.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
            val file = File(dir, "FamilyTalk-${info.build}.apk")
            _state.value = UpdateState.Downloading(info, 0f)
            val expected = withContext(Dispatchers.IO) { info.shaUrl?.let { get(it).trim().substringBefore(' ').lowercase() } }
                ?: throw IllegalStateException("release has no checksum file")
            val actual = withContext(Dispatchers.IO) { fetchTo(info.apkUrl, file) { p -> _state.value = UpdateState.Downloading(info, p) } }
            if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                file.delete(); throw IllegalStateException("download is damaged (checksum mismatch)")
            }
            if (!sameSigner(file)) {
                file.delete()
                throw IllegalStateException("this build is signed with a different key, so Android can't install it over your app. Set up the signing key (see README), or uninstall and install the new APK by hand.")
            }
            _state.value = UpdateState.Ready(info, file)
            onReady(info)
        } catch (e: Exception) {
            Log.w(TAG_LOG, "update failed", e)
            _state.value = UpdateState.Failed("Update failed: ${e.message}")
        }
    }

    /** Hands the verified APK to Android's installer. Silent on Android 12+ once FamilyTalk installed itself before. */
    fun install() {
        val ready = _state.value as? UpdateState.Ready ?: return
        _state.value = UpdateState.Installing
        try {
            val pi = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = pi.createSession(params)
            pi.openSession(id).use { session ->
                session.openWrite("base.apk", 0, ready.file.length()).use { out ->
                    ready.file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("Couldn't start the installer: ${e.message}")
        }
    }

    internal fun installFinished(ok: Boolean, message: String?) {
        if (!ok) _state.value = UpdateState.Failed("Install was not completed${message?.let { ": $it" } ?: ""}")
    }

    @Suppress("DEPRECATION")
    private fun sameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        if (archive.packageName != context.packageName) return false
        val installed = pm.getPackageInfo(context.packageName, flags)
        fun certs(p: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures)?.map { it.toCharsString() }?.toSet().orEmpty()
        val a = certs(archive)
        return a.isNotEmpty() && a == certs(installed)
    }

    private fun get(url: String): String {
        val c = open(url)
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Streams to [file] and returns its SHA-256 hex. */
    private fun fetchTo(url: String, file: File, progress: (Float) -> Unit): String {
        val c = open(url)
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: -1
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var lastReport = 0L
            c.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); md.update(buf, 0, n); done += n
                        if (total > 0 && done - lastReport > 512 * 1024) { lastReport = done; progress(done.toFloat() / total) }
                    }
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        } finally { c.disconnect() }
    }

    private fun open(url: String): HttpURLConnection {
        var current = URL(url)
        repeat(5) {
            require(current.protocol == "https" && ALLOWED_HOSTS.any { h -> current.host == h || current.host.endsWith(".$h") }) { "unexpected host ${current.host}" }
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
            c.setRequestProperty("User-Agent", "FamilyTalk/${BuildConfig.VERSION_NAME}")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IllegalStateException("redirect without location")
                c.disconnect(); current = URL(current, loc); return@repeat
            }
            if (code != 200) { c.disconnect(); throw IllegalStateException("HTTP $code") }
            return c
        }
        throw IllegalStateException("too many redirects")
    }

    private fun JsonObject.name() = this["name"]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.url() = this["browser_download_url"]?.jsonPrimitive?.content.orEmpty()

    companion object {
        const val REPO = "zjawahiri2681/Mypro"
        private val TAG = Regex("familytalk-v([0-9.]+)-build([0-9]+)")
        private val ALLOWED_HOSTS = listOf("github.com", "api.github.com", "githubusercontent.com")
        private const val TAG_LOG = "Updater"
    }
}

/** Receives the installer's answer; asks the user to confirm when Android requires it. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = com.mypro.familytalk.FamilyTalkApp.instance
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT))
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> app.updater.installFinished(true, null)
            else -> app.updater.installFinished(false, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }
}
