package com.mypro.iptvbox.core.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Shared OkHttp client with IPTV-friendly defaults (long read timeouts, redirects, lenient TLS hostnames off). */
object Http {
    const val DEFAULT_UA = "IPTVBox/1.0 (Android; Mozilla/5.0 compatible)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    fun get(url: String, userAgent: String = "", headers: Map<String, String> = emptyMap()): Response {
        val b = Request.Builder().url(url)
            .header("User-Agent", userAgent.ifBlank { DEFAULT_UA })
            .header("Accept", "*/*")
        headers.forEach { (k, v) -> b.header(k, v) }
        val resp = client.newCall(b.build()).execute()
        if (!resp.isSuccessful) {
            val code = resp.code
            resp.close()
            throw IOException("HTTP $code for $url")
        }
        return resp
    }

    fun getString(url: String, userAgent: String = "", headers: Map<String, String> = emptyMap()): String =
        get(url, userAgent, headers).use { it.body?.string() ?: "" }

    /** Opens a stream, transparently un-gzipping .gz payloads (XMLTV guides are usually gzipped). */
    fun openStream(url: String, userAgent: String = ""): InputStream {
        val resp = get(url, userAgent)
        val raw = resp.body?.byteStream() ?: throw IOException("Empty body for $url")
        return maybeGunzip(raw, url, resp.header("Content-Type") ?: "", resp.header("Content-Encoding") ?: "")
    }

    fun maybeGunzip(input: InputStream, url: String, contentType: String = "", contentEncoding: String = ""): InputStream {
        val pb = java.io.PushbackInputStream(input, 2)
        val sig = ByteArray(2)
        val n = pb.read(sig)
        if (n > 0) pb.unread(sig, 0, n)
        val isGzipMagic = n == 2 && (sig[0].toInt() and 0xff) == 0x1f && (sig[1].toInt() and 0xff) == 0x8b
        return if (isGzipMagic) GZIPInputStream(pb, 1 shl 16) else pb
    }
}
