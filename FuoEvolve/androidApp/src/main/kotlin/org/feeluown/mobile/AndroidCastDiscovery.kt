package org.feeluown.mobile

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds DLNA renderers on the local network.
 *
 * The handshake is SSDP `M-SEARCH` over multicast, followed by a plain HTTP GET of the device
 * description document the renderer points at.
 */
class AndroidCastDiscovery(private val context: Context) : CastDiscoveryPort {
    override suspend fun discover(): List<CastDevice> = withContext(Dispatchers.IO) {
        val locations = searchLocations()
        val devices = LinkedHashMap<String, CastDevice>()
        locations.forEach { location ->
            val device = runCatching { fetchDevice(location) }
                .onFailure { AppLogger.d(TAG, "读取设备描述失败 $location: ${it.message}") }
                .getOrNull() ?: return@forEach
            devices.putIfAbsent(device.id, device)
        }
        devices.values.toList()
    }

    /** Sends the search requests and collects the `LOCATION` headers the renderers answer with. */
    private fun searchLocations(): List<String> {
        val locations = LinkedHashSet<String>()
        val lock = acquireMulticastLock()
        val socket = DatagramSocket()
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            val target = InetSocketAddress(SsdpProtocol.MULTICAST_ADDRESS, SsdpProtocol.MULTICAST_PORT)
            SsdpProtocol.SEARCH_TARGETS.forEach { searchTarget ->
                val payload = SsdpProtocol.buildSearchRequest(searchTarget).toByteArray(Charsets.US_ASCII)
                runCatching { socket.send(DatagramPacket(payload, payload.size, target)) }
                    .onFailure { AppLogger.d(TAG, "SSDP 发送失败($searchTarget): ${it.message}") }
            }
            val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_MS
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (timeout: SocketTimeoutException) {
                    continue
                } catch (error: Exception) {
                    AppLogger.d(TAG, "SSDP 接收失败: ${error.message}")
                    break
                }
                val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                SsdpProtocol.parseLocation(text)?.let { locations.add(it) }
            }
        } finally {
            runCatching { socket.close() }
            lock?.let { runCatching { it.release() } }
        }
        return locations.toList()
    }

    /** Reads the device description document and turns it into a castable device. */
    private fun fetchDevice(location: String): CastDevice? {
        val response = AndroidLanHttp.get(location, DESCRIPTION_TIMEOUT_MS)
        if (response.status !in 200..299) {
            AppLogger.d(TAG, "设备描述返回 ${response.status}: $location")
            return null
        }
        return DeviceDescriptionParser.parse(decodeXml(response.rawBody), location)
    }

    /** Device descriptions are not always UTF-8, so the declared encoding wins. */
    private fun decodeXml(raw: ByteArray): String {
        val head = String(raw, 0, minOf(raw.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding\\s*=\\s*[\"']([\\w-]+)[\"']").find(head)?.groupValues?.get(1)
        return try {
            when (declared?.lowercase()) {
                null, "", "utf-8", "utf8" -> String(raw, Charsets.UTF_8)
                "gb2312", "gbk" -> String(raw, Charset.forName("GB18030"))
                else -> String(raw, Charset.forName(declared))
            }
        } catch (error: Exception) {
            String(raw, Charsets.UTF_8)
        }
    }

    /**
     * Android drops multicast traffic while the device is idle, so the Wi-Fi multicast lock has
     * to be held while the search runs.
     */
    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return null
        return try {
            wifiManager.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (error: Exception) {
            AppLogger.d(TAG, "无法获取组播锁: ${error.message}")
            null
        }
    }

    private companion object {
        const val TAG = "CastDiscovery"
        const val USER_AGENT = "FeelUOwn-Cast/0.1 UPnP/1.0"
        const val MULTICAST_LOCK_TAG = "fuo-cast-ssdp"
        const val SEARCH_TIMEOUT_MS = 3_000L
        const val READ_TIMEOUT_MS = 500
        const val DESCRIPTION_TIMEOUT_MS = 4_000
        const val MAX_DESCRIPTION_BYTES = 512 * 1024
    }
}
