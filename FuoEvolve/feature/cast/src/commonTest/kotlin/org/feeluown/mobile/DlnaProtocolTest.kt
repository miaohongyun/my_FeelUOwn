package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaProtocolTest {
    @Test
    fun mimeTypeIsGuessedFromTheUrlPath() {
        assertEquals("audio/mpeg", DlnaProtocol.guessMimeType("http://host/song.mp3?token=1"))
        assertEquals("audio/mp4", DlnaProtocol.guessMimeType("http://host/song.M4A"))
        assertEquals("audio/flac", DlnaProtocol.guessMimeType("http://host/a.flac"))
        assertEquals("audio/mpeg", DlnaProtocol.guessMimeType("http://host/stream"))
    }

    @Test
    fun extensionFollowsTheMimeType() {
        assertEquals("flac", DlnaProtocol.extensionForMime("audio/flac"))
        assertEquals("mp3", DlnaProtocol.extensionForMime("application/octet-stream"))
    }

    @Test
    fun durationUsesTheDidlFormat() {
        assertEquals("00:00:00.000", DlnaProtocol.formatDuration(null))
        assertEquals("00:00:00.000", DlnaProtocol.formatDuration(0))
        assertEquals("00:03:20.500", DlnaProtocol.formatDuration(200_500))
        assertEquals("01:01:01.001", DlnaProtocol.formatDuration(3_661_001))
    }

    @Test
    fun contentFeaturesAdvertiseTheProfile() {
        val features = DlnaProtocol.dlnaContentFeatures("audio/flac")
        assertTrue(features.contains("DLNA.ORG_PN=FLAC"))
        assertTrue(features.contains("DLNA.ORG_OP=01"))
    }

    @Test
    fun didlItemCarriesEveryFieldTheRendererNeeds() {
        val didl = DlnaProtocol.buildDidlItem(
            url = "http://192.168.1.2:8765/stream/token/sid/audio.mp3",
            title = "晴天",
            artist = "周杰伦",
            album = "叶惠美",
            durationMs = 269_000,
            mimeType = "audio/mpeg",
        )

        assertTrue(didl.startsWith("<DIDL-Lite"))
        assertTrue(didl.contains("<dc:title>晴天</dc:title>"))
        assertTrue(didl.contains("<dc:creator>周杰伦</dc:creator>"))
        assertTrue(didl.contains("<upnp:album>叶惠美</upnp:album>"))
        assertTrue(didl.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(didl.contains("duration=\"00:04:29.000\""))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/mpeg:"))
    }

    @Test
    fun didlItemEscapesXmlEntities() {
        val didl = DlnaProtocol.buildDidlItem(
            url = "http://host/a?x=1&y=2",
            title = "A & B <tag>",
        )

        assertTrue(didl.contains("A &amp; B &lt;tag&gt;"))
        assertTrue(didl.contains("x=1&amp;y=2"))
    }

    @Test
    fun soapEnvelopeUsesTheServiceNamespace() {
        val envelope = DlnaProtocol.buildSoapEnvelope(
            action = "Play",
            service = DlnaProtocol.AVTRANSPORT_SERVICE,
            params = mapOf("InstanceID" to "0", "Speed" to "1"),
        )

        assertTrue(envelope.contains("<u:Play xmlns:u=\"${DlnaProtocol.AVTRANSPORT_SERVICE}\">"))
        assertTrue(envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("<Speed>1</Speed>"))
    }

    @Test
    fun soapActionHeaderMatchesTheUpnpFormat() {
        assertEquals(
            "\"${DlnaProtocol.AVTRANSPORT_SERVICE}#SetAVTransportURI\"",
            DlnaProtocol.soapActionHeader(DlnaProtocol.AVTRANSPORT_SERVICE, "SetAVTransportURI"),
        )
    }

    @Test
    fun tagTextIsReadFromTheDocument() {
        val body = "<s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState>" +
            "</u:GetTransportInfoResponse></s:Body>"

        assertEquals("PLAYING", DlnaProtocol.extractTagText(body, "CurrentTransportState"))
        assertEquals("", DlnaProtocol.extractTagText(body, "Missing"))
    }

    @Test
    fun timecodesFromPositionInfoBecomeMilliseconds() {
        assertEquals(201_000L, DlnaProtocol.parseTimecodeMillis("00:03:21"))
        assertEquals(269_500L, DlnaProtocol.parseTimecodeMillis("0:04:29.500"))
        assertEquals(45_000L, DlnaProtocol.parseTimecodeMillis("00:45"))
        assertEquals(3_600_000L, DlnaProtocol.parseTimecodeMillis("01:00:00"))
    }

    @Test
    fun unreadableTimecodesAreRejectedInsteadOfBecomingZero() {
        assertNull(DlnaProtocol.parseTimecodeMillis("NOT_IMPLEMENTED"))
        assertNull(DlnaProtocol.parseTimecodeMillis(""))
        assertNull(DlnaProtocol.parseTimecodeMillis(null))
        assertNull(DlnaProtocol.parseTimecodeMillis("00:00:00"))
        assertNull(DlnaProtocol.parseTimecodeMillis("nonsense"))
    }

    @Test
    fun aRendererAtTheEndOfTheTrackIsDetectedFromItsPosition() {
        val stopped = CastTransportSnapshot(transportState = "STOPPED")
        val atTheEnd = CastTransportSnapshot("PLAYING", positionMs = 268_000, durationMs = 269_000)
        val stillPlaying = CastTransportSnapshot("PLAYING", positionMs = 120_000, durationMs = 269_000)
        val paused = CastTransportSnapshot("PAUSED_PLAYBACK", positionMs = 268_900, durationMs = 269_000)

        assertTrue(stopped.indicatesEndOfTrack())
        assertTrue(atTheEnd.indicatesEndOfTrack())
        assertFalse(stillPlaying.indicatesEndOfTrack())
        assertFalse(paused.indicatesEndOfTrack())
    }
}
