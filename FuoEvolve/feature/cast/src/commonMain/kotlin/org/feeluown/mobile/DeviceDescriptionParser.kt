package org.feeluown.mobile

/**
 * Reads a UPnP device description and picks the renderer that can play our audio.
 *
 * Only devices that expose an AVTransport service are usable, because that is the service that
 * accepts a URI and a play command.
 */
object DeviceDescriptionParser {
    /** Parses [xml], which was fetched from [location], into a castable device. */
    fun parse(xml: String, location: String): CastDevice? {
        val root = MiniXml.parse(xml) ?: return null
        val devices = root.descendants("device")
        if (devices.isEmpty()) return null

        val renderer = devices.firstOrNull {
            it.childText("deviceType") == DlnaProtocol.MEDIA_RENDERER_DEVICE_TYPE
        } ?: return null

        val services = collectServices(devices)
        val controlUrl = services[DlnaProtocol.AVTRANSPORT_SERVICE]?.let { controlUrl ->
            resolveUrl(urlBase(root, location), controlUrl)
        }?.let { controlUrl ->
            rewriteHost(controlUrl, location)
        }.orEmpty()
        if (controlUrl.isBlank()) return null

        val rootDevice = devices.first()
        val (host, port) = hostAndPort(location)
        return CastDevice(
            id = renderer.childText("UDN")
                .ifBlank { rootDevice.childText("UDN") }
                .ifBlank { location },
            name = renderer.childText("friendlyName")
                .ifBlank { rootDevice.childText("friendlyName") }
                .ifBlank { "DLNA 设备" },
            host = host,
            port = port,
            controlUrl = controlUrl,
            rendererType = renderer.childText("deviceType"),
            manufacturer = rootDevice.childText("manufacturer"),
            modelName = rootDevice.childText("modelName"),
        )
    }

    private fun urlBase(root: XmlNode, location: String): String {
        val declared = root.childText("URLBase")
        if (declared.isNotBlank()) {
            val (declaredHost, declaredPort) = hostAndPort(declared)
            val (locationHost, locationPort) = hostAndPort(location)
            // Some renderers advertise a stale URLBase. Only trust it when it points at the
            // same device we actually reached; otherwise build the base from LOCATION.
            if (declaredHost.equals(locationHost, ignoreCase = true) && declaredPort == locationPort) {
                return if (declared.endsWith("/")) declared else "$declared/"
            }
        }
        val directory = location.substringBeforeLast('/', location)
        return "$directory/"
    }

    /**
     * Rewrites the control endpoint's host to the address the description was fetched from.
     *
     * SSDP's LOCATION is the renderer's current address, while the description document may
     * embed an old IP (common after a router reassignment). Trusting the embedded address makes
     * discovery succeed and the first SOAP call fail.
     */
    internal fun rewriteHost(url: String, location: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd < 0) return url
        val scheme = url.substring(0, schemeEnd)
        val afterScheme = url.substring(schemeEnd + 3)
        val authority = afterScheme.substringBefore('/')
        val path = afterScheme.substringAfter('/', "")
        val currentHost = authority.substringBefore(':')
        val currentPort = authority.substringAfter(':', "").toIntOrNull()
        val (locationHost, locationPort) = hostAndPort(location)
        if (currentHost.equals(locationHost, ignoreCase = true) &&
            (currentPort == null || currentPort == locationPort)
        ) {
            return url
        }
        val defaultPort = if (scheme.equals("https", ignoreCase = true)) 443 else 80
        val newAuthority = if (locationPort == defaultPort) {
            locationHost
        } else {
            "$locationHost:$locationPort"
        }
        return "$scheme://$newAuthority/${path}"
    }

    private fun collectServices(devices: List<XmlNode>): Map<String, String> {
        val services = mutableMapOf<String, String>()
        devices.forEach { device ->
            device.descendants("service").forEach { service ->
                val serviceType = service.childText("serviceType")
                if (serviceType.isNotBlank() && !services.containsKey(serviceType)) {
                    services[serviceType] = service.childText("controlURL")
                }
            }
        }
        return services
    }

    /** Resolves a possibly relative `controlURL` against the description's base URL. */
    internal fun resolveUrl(base: String, url: String): String {
        val target = url.trim()
        if (target.isEmpty()) return ""
        if (target.startsWith("http://") || target.startsWith("https://")) return target

        val schemeEnd = base.indexOf("://")
        if (schemeEnd < 0) return target
        val scheme = base.substring(0, schemeEnd)
        val afterScheme = base.substring(schemeEnd + 3)
        val authority = afterScheme.substringBefore('/')
        if (target.startsWith("/")) return "$scheme://$authority$target"

        val basePath = afterScheme.substringAfter('/', "")
        val directory = basePath.substringBeforeLast('/', "")
        val combined = if (directory.isEmpty()) target else "$directory/$target"
        return "$scheme://$authority/${normalizePath(combined)}"
    }

    private fun normalizePath(path: String): String {
        val segments = mutableListOf<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun hostAndPort(url: String): Pair<String, Int> {
        val withoutScheme = url.substringAfter("://", url)
        val authority = withoutScheme.substringBefore('/')
        val host = authority.substringBefore(':')
        val port = authority.substringAfter(':', "").toIntOrNull() ?: DEFAULT_HTTP_PORT
        return host to port
    }

    private const val DEFAULT_HTTP_PORT = 80
}
