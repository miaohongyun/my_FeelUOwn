package org.feeluown.mobile

/**
 * The wire format of UPnP AVTransport: DIDL-Lite metadata, SOAP envelopes and DLNA headers.
 *
 * Everything here is pure string handling so the protocol can be unit tested without a device.
 */
object DlnaProtocol {
    const val SOAP_ENVELOPE = "http://schemas.xmlsoap.org/soap/envelope/"
    const val SOAP_ENCODING = "http://schemas.xmlsoap.org/soap/encoding/"
    const val AVTRANSPORT_SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL_SERVICE = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val MEDIA_RENDERER_DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val DLNA_FLAGS = "01700000000000000000000000000000"

    private val MIME_BY_EXTENSION = mapOf(
        ".mp3" to "audio/mpeg",
        ".m4a" to "audio/mp4",
        ".mp4" to "audio/mp4",
        ".aac" to "audio/aac",
        ".flac" to "audio/flac",
        ".wav" to "audio/wav",
        ".ogg" to "audio/ogg",
        ".oga" to "audio/ogg",
        ".opus" to "audio/ogg",
        ".wma" to "audio/x-ms-wma",
        ".ape" to "audio/x-ape",
    )

    private val DLNA_PROFILE_BY_MIME = mapOf(
        "audio/mpeg" to "MP3",
        "audio/mp4" to "AAC_ISO_320",
        "audio/aac" to "AAC_ISO",
        "audio/flac" to "FLAC",
        "audio/wav" to "LPCM",
        "audio/x-ms-wma" to "WMABASE",
    )

    private val EXTENSION_BY_MIME = mapOf(
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/aac" to "aac",
        "audio/flac" to "flac",
        "audio/wav" to "wav",
        "audio/ogg" to "ogg",
        "audio/x-ms-wma" to "wma",
    )

    /** Guesses the mime type from the URL path, ignoring the query string. */
    fun guessMimeType(url: String, default: String = "audio/mpeg"): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        MIME_BY_EXTENSION.entries.firstOrNull { path.endsWith(it.key) }?.let { return it.value }
        return default
    }

    /** The file extension that should be used when serving [mimeType] over the local proxy. */
    fun extensionForMime(mimeType: String): String = EXTENSION_BY_MIME[mimeType] ?: "mp3"

    /** The `contentFeatures.dlna.org` header value that advertises the supported profile. */
    fun dlnaContentFeatures(mimeType: String): String {
        val profile = DLNA_PROFILE_BY_MIME[mimeType] ?: "*"
        return "DLNA.ORG_PN=$profile;DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$DLNA_FLAGS"
    }

    /** Formats a duration as the `HH:MM:SS.mmm` form DIDL-Lite expects. */
    fun formatDuration(durationMs: Long?): String {
        val totalMs = durationMs?.takeIf { it > 0 } ?: return "00:00:00.000"
        val totalSeconds = totalMs / 1000
        val millis = totalMs % 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d.%03d".format(hours, minutes, seconds, millis)
    }

    /**
     * Parses the `HH:MM:SS` timecodes that `GetPositionInfo` returns.
     *
     * Devices answer `NOT_IMPLEMENTED`, an empty string, or a plain zero when they cannot report a
     * position, so anything unparsable becomes `null` instead of a bogus zero.
     */
    fun parseTimecodeMillis(value: String?): Long? {
        val text = value?.trim().orEmpty()
        if (text.isEmpty() || text.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        val parts = text.split(':')
        if (parts.size !in 2..3) return null
        val numbers = parts.map { it.replace(',', '.').toDoubleOrNull() ?: return null }
        val seconds = when (numbers.size) {
            2 -> numbers[0] * 60 + numbers[1]
            else -> numbers[0] * 3600 + numbers[1] * 60 + numbers[2]
        }
        if (seconds <= 0.0) return null
        return (seconds * 1000).toLong()
    }

    /** Escapes the five XML entities so metadata values cannot break the document. */
    fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Builds the `CurrentURIMetaData` document.
     *
     * Many renderers accept the command but stay silent when the metadata is empty or
     * incomplete, so title, artist, album, class and protocolInfo are all filled in.
     */
    fun buildDidlItem(
        url: String,
        title: String = "",
        artist: String = "",
        album: String = "",
        durationMs: Long? = null,
        mimeType: String = "audio/mpeg",
        itemId: String = "fuo-cast-0",
    ): String {
        val protocolInfo = "http-get:*:$mimeType:${dlnaContentFeatures(mimeType)}"
        return buildString {
            append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
            append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
            append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
            append("<item id=\"${escapeXml(itemId)}\" parentID=\"-1\" restricted=\"1\">")
            append("<dc:title>${escapeXml(title.ifBlank { "FeelUOwn" })}</dc:title>")
            append("<dc:creator>${escapeXml(artist)}</dc:creator>")
            append("<upnp:artist>${escapeXml(artist)}</upnp:artist>")
            append("<upnp:album>${escapeXml(album)}</upnp:album>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("<res duration=\"${formatDuration(durationMs)}\" ")
            append("protocolInfo=\"${escapeXml(protocolInfo)}\">")
            append(escapeXml(url))
            append("</res>")
            append("</item></DIDL-Lite>")
        }
    }

    /** Builds a SOAP 1.1 envelope for an UPnP action. */
    fun buildSoapEnvelope(action: String, service: String, params: Map<String, String>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"$SOAP_ENVELOPE\" s:encodingStyle=\"$SOAP_ENCODING\">")
        append("<s:Body><u:$action xmlns:u=\"$service\">")
        params.forEach { (key, value) ->
            append("<$key>${escapeXml(value)}</$key>")
        }
        append("</u:$action></s:Body></s:Envelope>")
    }

    /** The `SOAPACTION` header value UPnP renderers require. */
    fun soapActionHeader(service: String, action: String): String = "\"$service#$action\""

    /**
     * Reads the text of [tag] out of an XML document.
     *
     * UPnP answers are small and flat, so a targeted scan is enough to read the few elements the
     * cast feature cares about.
     */
    fun extractTagText(xml: String, tag: String): String {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open)
        if (start < 0) return ""
        val end = xml.indexOf(close, start + open.length)
        if (end < 0) return ""
        return xml.substring(start + open.length, end).trim()
    }
}
