package com.versobit.weatherdoge.provider

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    class HttpStatusException(val code: Int, val body: String) :
        IOException("HTTP $code: $body")

    data class Response(val body: String, val code: Int)

    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "WeatherDoge/1.0")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }

            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) {
                conn.inputStream
            } else {
                conn.errorStream
            }
            val body = stream?.use { readFully(it) } ?: ""
            if (code !in 200..299) throw HttpStatusException(code, body)
            return Response(body, code)
        } finally {
            conn.disconnect()
        }
    }

    private fun readFully(stream: InputStream): String {
        val input = if (stream.markSupported()) stream else stream
        val decoded = try {
            input.mark(2)
            val buf = ByteArray(2)
            val n = input.read(buf)
            input.reset()
            if (n == 2 && buf[0] == 0x1f.toByte() && buf[1] == 0x8b.toByte()) {
                GZIPInputStream(input)
            } else {
                input
            }
        } catch (e: IOException) {
            input
        }
        return decoded.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
