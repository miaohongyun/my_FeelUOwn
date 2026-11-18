package org.feeluown.mobile

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sends UPnP AVTransport commands to a renderer over SOAP.
 *
 * The renderer is a device on the local network, so its control endpoint is plain HTTP on a
 * private address. The app's network security config only allows cleartext for the provider
 * hosts, so the command goes straight to the socket instead of loosening that policy for the
 * whole app.
 */
class AndroidCastTransport : CastTransportPort {
    override suspend fun setUri(device: CastDevice, uri: String, metadata: String) {
        call(
            device = device,
            action = "SetAVTransportURI",
            params = mapOf(
                "InstanceID" to "0",
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata,
            ),
        )
    }

    override suspend fun play(device: CastDevice) {
        call(device = device, action = "Play", params = mapOf("InstanceID" to "0", "Speed" to "1"))
    }

    override suspend fun pause(device: CastDevice) {
        call(device = device, action = "Pause", params = mapOf("InstanceID" to "0"))
    }

    override suspend fun stop(device: CastDevice) {
        call(device = device, action = "Stop", params = mapOf("InstanceID" to "0"))
    }

    override suspend fun transportState(device: CastDevice): String {
        val body = call(device = device, action = "GetTransportInfo", params = mapOf("InstanceID" to "0"))
        return DlnaProtocol.extractTagText(body, "CurrentTransportState")
    }

    /**
     * Reads the state and, when the device answers, the position of the current track.
     *
     * `GetPositionInfo` is the only way to notice a renderer that plays to the very end and then
     * keeps reporting `PLAYING`; a device that does not implement it simply leaves the position
     * empty and the controller falls back to the state transition.
     */
    override suspend fun transportSnapshot(device: CastDevice): CastTransportSnapshot {
        val state = transportState(device)
        val position = runCatching {
            call(device = device, action = "GetPositionInfo", params = mapOf("InstanceID" to "0"))
        }.getOrNull() ?: return CastTransportSnapshot(transportState = state)
        return CastTransportSnapshot(
            transportState = state,
            positionMs = DlnaProtocol.parseTimecodeMillis(DlnaProtocol.extractTagText(position, "RelTime")),
            durationMs = DlnaProtocol.parseTimecodeMillis(DlnaProtocol.extractTagText(position, "TrackDuration")),
        )
    }

    private suspend fun call(
        device: CastDevice,
        action: String,
        params: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val service = DlnaProtocol.AVTRANSPORT_SERVICE
        val payload = DlnaProtocol.buildSoapEnvelope(action, service, params).toByteArray(Charsets.UTF_8)
        val response = if (device.controlUrl.startsWith("https://", ignoreCase = true)) {
            httpsCall(device.controlUrl, payload, service, action)
        } else {
            AndroidLanHttp.post(
                url = device.controlUrl,
                headers = mapOf(
                    "Content-Type" to "text/xml; charset=\"utf-8\"",
                    "SOAPACTION" to DlnaProtocol.soapActionHeader(service, action),
                ),
                body = payload,
                timeoutMs = TIMEOUT_MS,
            )
        }
        if (response.status !in 200..299) {
            val content = response.text()
            val code = DlnaProtocol.extractTagText(content, "errorCode")
            val description = DlnaProtocol.extractTagText(content, "errorDescription")
            val detail = listOf(code, description).filter { it.isNotBlank() }.joinToString(" ")
            throw IllegalStateException("投屏设备返回 ${response.status} ${detail.trim()}")
        }
        response.text()
    }

    /** HTTPS renderers are rare but supported, and the system trust store is used. */
    private fun httpsCall(
        url: String,
        payload: ByteArray,
        service: String,
        action: String,
    ): LanHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", DlnaProtocol.soapActionHeader(service, action))
            setRequestProperty("Content-Length", payload.size.toString())
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Connection", "close")
        }
        try {
            connection.outputStream.use { it.write(payload) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.readBytes() ?: ByteArray(0)
            return LanHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val USER_AGENT = "FeelUOwn-Cast/0.1 UPnP/1.0"
        const val TIMEOUT_MS = 6_000
    }
}
