package org.feeluown.mobile

import java.io.Closeable
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A small HTTP server that re-publishes the provider stream for the renderer.
 *
 * Provider URLs carry cookies and signatures the renderer cannot send, and they expire quickly,
 * so the phone has to fetch the bytes itself and hand out a plain, token protected URL.
 *
 * The server is deliberately minimal: it only answers the stream path, validates the token and
 * forwards the request upstream with the provider headers.
 */
class AndroidCastStreamServer : CastStreamPort, Closeable {
    private data class Entry(val source: CastStreamSource)

    private val token = randomToken()
    private val entries = ConcurrentHashMap<String, Entry>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var lanIp: String = ""

    override suspend fun publish(source: CastStreamSource): String = withContext(Dispatchers.IO) {
        ensureStarted()
        val sid = randomToken()
        entries[sid] = Entry(source)
        val extension = DlnaProtocol.extensionForMime(source.mimeType)
        "http://$lanIp:$boundPort/stream/$token/$sid/audio.$extension"
    }

    override suspend fun release(url: String) = withContext(Dispatchers.IO) {
        val sid = extractSid(url)
        if (sid != null) {
            entries.remove(sid)
        }
    }

    /** Starts the server on first use so an app that never casts does not hold a port. */
    private fun ensureStarted() {
        if (running.get()) return
        synchronized(this) {
            if (running.get()) return
            val socket = ServerSocket()
            try {
                socket.reuseAddress = true
                socket.bind(InetSocketAddress("0.0.0.0", preferredPort()), BACKLOG)
            } catch (error: Exception) {
                runCatching { socket.close() }
                AppLogger.w(TAG, "固定端口不可用，改用随机端口: ${error.message}")
                socket.bind(InetSocketAddress("0.0.0.0", 0), BACKLOG)
            }
            serverSocket = socket
            boundPort = socket.localPort
            lanIp = guessLanIp()
            running.set(true)
            Thread(::acceptLoop, "fuo-cast-stream").apply { isDaemon = true }.start()
            AppLogger.i(TAG, "投屏代理流已启动: $lanIp:$boundPort")
        }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (error: Exception) {
                if (running.get()) AppLogger.d(TAG, "投屏代理流接收失败: ${error.message}")
                break
            }
            Thread({ handle(client) }, "fuo-cast-connection").apply { isDaemon = true }.start()
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = reader.readLine() ?: return
                val headers = readHeaders(reader)
                val segments = requestLine.split(" ").map { it.trim() }
                if (segments.size < 2) {
                    respond(socket, 400, "text/plain", "bad request".toByteArray())
                    return
                }
                val method = segments[0].uppercase()
                val path = segments[1].substringBefore("?")
                val parts = path.split("/").filter { it.isNotEmpty() }
                if (parts.size < 3 || parts[0] != "stream") {
                    respond(socket, 404, "text/plain", "not found".toByteArray())
                    return
                }
                if (parts[1] != token) {
                    respond(socket, 403, "text/plain", "forbidden".toByteArray())
                    return
                }
                val entry = entries[parts[2]]
                if (entry == null) {
                    respond(socket, 404, "text/plain", "stream expired".toByteArray())
                    return
                }
                serveUpstream(socket, entry.source, headers, headOnly = method == "HEAD")
            } catch (error: Exception) {
                AppLogger.d(TAG, "投屏代理流处理失败: ${error.message}")
            }
        }
    }

    private fun readHeaders(reader: java.io.BufferedReader): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return headers
    }

    /** Fetches the upstream audio and pipes it to the renderer. */
    private fun serveUpstream(
        client: Socket,
        source: CastStreamSource,
        headers: Map<String, String>,
        headOnly: Boolean,
    ) {
        val upstream = (URL(source.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = UPSTREAM_TIMEOUT_MS
            readTimeout = UPSTREAM_TIMEOUT_MS
            instanceFollowRedirects = true
            source.headers.forEach { (name, value) ->
                if (name.lowercase() !in HOP_BY_HOP_HEADERS) {
                    setRequestProperty(name, value)
                }
            }
            headers["range"]?.let { setRequestProperty("Range", it) }
            headers["if-range"]?.let { setRequestProperty("If-Range", it) }
            if (headers["user-agent"] != null && source.headers.keys.none { it.lowercase() == "user-agent" }) {
                setRequestProperty("User-Agent", headers["user-agent"]!!)
            }
        }
        try {
            val status = upstream.responseCode
            val contentLength = upstream.getHeaderField("Content-Length")?.toIntOrNull() ?: -1
            val contentRange = upstream.getHeaderField("Content-Range")
            writeHead(client, status, source.mimeType, contentLength, contentRange)
            if (headOnly) return
            val body = if (status in 200..299) upstream.inputStream else upstream.errorStream
            body?.use { input ->
                client.getOutputStream().use { output ->
                    val buffer = ByteArray(CHUNK_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        output.flush()
                    }
                }
            }
        } catch (error: Exception) {
            // The renderer usually just closes the connection when it switches track.
            AppLogger.d(TAG, "投屏代理流中断: ${error.message}")
        } finally {
            upstream.disconnect()
        }
    }

    private fun writeHead(
        client: Socket,
        status: Int,
        mimeType: String,
        contentLength: Int,
        contentRange: String?,
    ) {
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $mimeType\r\n")
            if (contentLength >= 0) append("Content-Length: $contentLength\r\n")
            if (!contentRange.isNullOrBlank()) append("Content-Range: $contentRange\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("contentFeatures.dlna.org: ${DlnaProtocol.dlnaContentFeatures(mimeType)}\r\n")
            append("transferMode.dlna.org: Streaming\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.ISO_8859_1))
            flush()
        }
    }

    private fun respond(client: Socket, status: Int, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        runCatching {
            client.getOutputStream().apply {
                write(header.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        206 -> "Partial Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        416 -> "Range Not Satisfiable"
        502 -> "Bad Gateway"
        else -> "Error"
    }

    /** The renderer only needs the stream id to revoke the entry. */
    private fun extractSid(url: String): String? {
        val parts = url.substringBefore("?").split("/").filter { it.isNotEmpty() }
        val streamIndex = parts.indexOf("stream")
        if (streamIndex < 0 || parts.size <= streamIndex + 2) return null
        if (parts[streamIndex + 1] != token) return null
        return parts[streamIndex + 2]
    }

    /** The address the renderer can reach; a UDP connect does not send any traffic. */
    private fun guessLanIp(): String = try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress("223.5.5.5", 80))
            (socket.localAddress as? Inet4Address)?.hostAddress ?: "127.0.0.1"
        }
    } catch (error: Exception) {
        AppLogger.w(TAG, "无法探测局域网地址: ${error.message}")
        "127.0.0.1"
    }

    private fun preferredPort(): Int = runCatching {
        java.net.ServerSocket(PREFERRED_PORT).use { PREFERRED_PORT }
    }.getOrDefault(PREFERRED_PORT)

    private fun randomToken(): String = java.util.UUID.randomUUID()
        .toString()
        .replace("-", "")
        .take(TOKEN_LENGTH)

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        entries.clear()
    }

    private companion object {
        const val TAG = "CastStream"
        const val PREFERRED_PORT = 8765
        const val BACKLOG = 16
        const val CHUNK_SIZE = 64 * 1024
        const val TOKEN_LENGTH = 16
        const val SOCKET_TIMEOUT_MS = 30_000
        const val UPSTREAM_TIMEOUT_MS = 15_000
        val HOP_BY_HOP_HEADERS = setOf("host", "content-length", "connection", "accept-encoding", "range")
    }
}
