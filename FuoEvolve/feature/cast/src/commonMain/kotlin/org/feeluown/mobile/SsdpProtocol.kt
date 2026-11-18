package org.feeluown.mobile

/**
 * The SSDP half of UPnP discovery.
 *
 * Renderers answer an `M-SEARCH` datagram with a `LOCATION` header that points at their device
 * description document; the rest of the handshake lives in [DeviceDescriptionParser].
 */
object SsdpProtocol {
    const val MULTICAST_ADDRESS = "239.255.255.250"
    const val MULTICAST_PORT = 1900

    /**
     * Renderers answer inconsistently: Android TV usually replies to `ssdp:all`, others only
     * reply to their own device or service type, so every target is queried.
     */
    val SEARCH_TARGETS = listOf(
        "ssdp:all",
        DlnaProtocol.MEDIA_RENDERER_DEVICE_TYPE,
        DlnaProtocol.AVTRANSPORT_SERVICE,
    )

    /** Builds the `M-SEARCH` request for one search target. */
    fun buildSearchRequest(searchTarget: String, mx: Int = 2): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: $searchTarget\r\n")
        append("\r\n")
    }

    /**
     * Extracts the `LOCATION` header from an SSDP response.
     *
     * Returns `null` for packets that are not a response, which keeps `NOTIFY` announcements
     * and stray traffic out of the discovery result.
     */
    fun parseLocation(response: String): String? {
        val head = response.substringBefore("\r\n\r\n")
        val lines = head.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (!first.startsWith("HTTP/1.") && !first.startsWith("NOTIFY ")) return null
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) return@forEach
            if (line.substring(0, separator).trim().equals("location", ignoreCase = true)) {
                return line.substring(separator + 1).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }
}
