package com.mypro.iptvbox.util

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import java.net.Inet4Address
import java.net.NetworkInterface

object Net {
    /** Best-effort LAN IPv4 of this device (Wi-Fi or Ethernet). */
    fun localIpv4(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.name.startsWith("dummy") && !it.name.startsWith("rmnet") }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }
}

object Files {
    data class Info(val name: String, val size: Long)

    fun info(resolver: ContentResolver, uri: Uri): Info {
        if (uri.scheme == "file") {
            val f = java.io.File(uri.path ?: "")
            return Info(f.name, f.length())
        }
        var name = uri.lastPathSegment ?: "video"
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) c.getString(ni)?.let { name = it }
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        return Info(name, size)
    }

    fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

/** Parses "Key=Value" lines into a header map. */
fun String.toHeaderMap(): Map<String, String> =
    lines().mapNotNull { l -> val i = l.indexOf('='); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim() }.toMap()
