package org.feeluown.mobile

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

internal data class LanHttpResponse(val status: Int, val rawBody: ByteArray) {
    fun text(): String = String(rawBody, Charsets.UTF_8)
}

/**
 * A minimal HTTP client for devices on the local network.
 *
 * The app's network security config only allows cleartext for the provider hosts, while a
 * renderer lives on a private address. Writing the request straight to the socket keeps that
 * policy intact instead of allowing cleartext everywhere.
 */
internal object AndroidLanHttp {
    private const val USER_AGENT = "FeelUOwn-Cast/0.1 UPnP/1.0"
    private const val DEFAULT_HTTP_PORT = 80

    fun get(url: String, timeoutMs: Int): LanHttpResponse {
        val endpoint = URL(url)
        val host = endpoint.host
        val port = if (endpoint.port > 0) endpoint.port else DEFAULT_HTTP_PORT
        val head = buildString {
            append("GET ${endpoint.file.ifEmpty { "/" }} HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            append("User-Agent: $USER_AGENT\r\n")
            append("Accept: */*\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return exchange(host, port, head, null, timeoutMs)
    }

    fun post(
        url: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutMs: Int,
    ): LanHttpResponse {
        val endpoint = URL(url)
        val host = endpoint.host
        val port = if (endpoint.port > 0) endpoint.port else DEFAULT_HTTP_PORT
        val head = buildString {
            append("POST ${endpoint.file.ifEmpty { "/" }} HTTP/1.1\r\n")
            append("Host: $host:$port\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("User-Agent: $USER_AGENT\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return exchange(host, port, head, body, timeoutMs)
    }

    private fun exchange(
        host: String,
        port: Int,
        head: ByteArray,
        body: ByteArray?,
        timeoutMs: Int,
    ): LanHttpResponse {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            output.write(head)
            if (body != null) output.write(body)
            output.flush()
            return readResponse(socket.getInputStream())
        }
    }

    /** Reads the status line, the headers and the body of an HTTP response. */
    private fun readResponse(input: InputStream): LanHttpResponse {
        val statusLine = readLine(input)
        val status = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            val name = line.substring(0, separator).trim().lowercase()
            val value = line.substring(separator + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: -1
                "transfer-encoding" -> chunked = value.contains("chunked", ignoreCase = true)
            }
        }
        val body = when {
            chunked -> readChunked(input)
            contentLength >= 0 -> readExactly(input, contentLength)
            else -> input.readBytes()
        }
        return LanHttpResponse(status, body)
    }

    private fun readLine(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) break
            if (byte == '\n'.code) break
            if (byte != '\r'.code) buffer.write(byte)
        }
        return buffer.toString(Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(buffer, read, length - read)
            if (count <= 0) break
            read += count
        }
        return buffer.copyOf(read)
    }

    private fun readChunked(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input).substringBefore(';').trim().toIntOrNull() ?: break
            if (size == 0) break
            val chunk = ByteArray(size)
            var read = 0
            while (read < size) {
                val count = input.read(chunk, read, size - read)
                if (count <= 0) break
                read += count
            }
            output.write(chunk, 0, read)
            readLine(input)
        }
        return output.toByteArray()
    }
}
