package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongVersionMatcherTest {
    @Test
    fun plainStudioTitleHasNoVersionMarkers() {
        assertEquals(emptySet(), SongVersionMatcher.kindsOf("晴天"))
        assertEquals(emptySet(), SongVersionMatcher.kindsOf("Night Song", "叶惠美"))
    }

    @Test
    fun liveMarkersAreRecognizedInBothLanguages() {
        listOf("晴天 Live", "晴天 LIVE", "晴天（现场版）", "晴天 現場", "晴天 演唱会", "晴天 ライブ").forEach { title ->
            assertEquals(setOf(SongVersionKind.LIVE), SongVersionMatcher.kindsOf(title), title)
        }
    }

    @Test
    fun coverRemixAndInstrumentalMarkersAreDistinguished() {
        assertEquals(setOf(SongVersionKind.COVER), SongVersionMatcher.kindsOf("晴天 翻唱"))
        assertEquals(setOf(SongVersionKind.COVER), SongVersionMatcher.kindsOf("晴天 Cover"))
        assertEquals(setOf(SongVersionKind.REMIX), SongVersionMatcher.kindsOf("晴天 Remix"))
        assertEquals(setOf(SongVersionKind.REMIX), SongVersionMatcher.kindsOf("晴天 DJ版"))
        assertEquals(setOf(SongVersionKind.INSTRUMENTAL), SongVersionMatcher.kindsOf("晴天 伴奏"))
        assertEquals(setOf(SongVersionKind.INSTRUMENTAL), SongVersionMatcher.kindsOf("晴天 Instrumental"))
        assertEquals(setOf(SongVersionKind.INSTRUMENTAL), SongVersionMatcher.kindsOf("晴天 Off Vocal"))
    }

    @Test
    fun latinMarkersRequireWholeWords() {
        assertEquals(emptySet(), SongVersionMatcher.kindsOf("Delivery"))
        assertEquals(emptySet(), SongVersionMatcher.kindsOf("Aliveness"))
        assertEquals(emptySet(), SongVersionMatcher.kindsOf("Demolition"))
        assertEquals(setOf(SongVersionKind.LIVE, SongVersionKind.REMIX), SongVersionMatcher.kindsOf("Song Live Remix"))
    }

    @Test
    fun fullWidthCharactersAndSpacesDoNotHideMarkers() {
        assertEquals(setOf(SongVersionKind.LIVE), SongVersionMatcher.kindsOf("晴天（Ｌｉｖｅ）"))
        assertEquals(setOf(SongVersionKind.COVER), SongVersionMatcher.kindsOf("晴天　翻唱"))
    }

    @Test
    fun albumAndTagsParticipateInEvidence() {
        assertEquals(
            setOf(SongVersionKind.LIVE),
            SongVersionMatcher.kindsOf("学不会", "学不会 世界巡回演唱会"),
        )
        assertEquals(
            setOf(SongVersionKind.COVER),
            SongVersionMatcher.kindsOf("ハルジオン", "", "YOASOBI 翻唱"),
        )
    }

    @Test
    fun conflictIsSymmetric() {
        assertTrue(SongVersionMatcher.conflicts(emptySet(), setOf(SongVersionKind.LIVE)))
        assertTrue(SongVersionMatcher.conflicts(setOf(SongVersionKind.LIVE), emptySet()))
        assertTrue(
            SongVersionMatcher.conflicts(
                setOf(SongVersionKind.LIVE),
                setOf(SongVersionKind.LIVE, SongVersionKind.COVER),
            ),
        )
        assertTrue(SongVersionMatcher.conflicts(setOf(SongVersionKind.LIVE), setOf(SongVersionKind.COVER)))
        assertFalse(SongVersionMatcher.conflicts(emptySet(), emptySet()))
        assertFalse(SongVersionMatcher.conflicts(setOf(SongVersionKind.LIVE), setOf(SongVersionKind.LIVE)))
    }

    @Test
    fun requestedLiveVerseStudioCandidateConflicts() {
        val requested = SongVersionMatcher.kindsOf("晴天 Live")
        val studioCandidate = SongVersionMatcher.kindsOf("晴天")
        assertTrue(SongVersionMatcher.conflicts(requested, studioCandidate))
    }

    @Test
    fun rankKeepsLegacyOrderingSemantics() {
        val live = setOf(SongVersionKind.LIVE)
        assertEquals(3, SongVersionMatcher.rank(live, live))
        assertEquals(0, SongVersionMatcher.rank(emptySet(), live))
        assertEquals(1, SongVersionMatcher.rank(live, emptySet()))
        assertEquals(1, SongVersionMatcher.rank(live, setOf(SongVersionKind.LIVE, SongVersionKind.COVER)))
        assertEquals(0, SongVersionMatcher.rank(live, setOf(SongVersionKind.COVER)))
    }

    @Test
    fun trailingVersionMarkersAreStrippedForTitleComparison() {
        assertEquals("晴天", SongVersionMatcher.stripTrailingVersionMarkers("晴天 (Live)"))
        assertEquals("晴天", SongVersionMatcher.stripTrailingVersionMarkers("晴天【现场版】"))
        assertEquals("晴天", SongVersionMatcher.stripTrailingVersionMarkers("晴天 Live"))
        assertEquals("晴天", SongVersionMatcher.stripTrailingVersionMarkers("晴天现场版"))
        assertEquals("晴天", SongVersionMatcher.stripTrailingVersionMarkers("晴天 (Acoustic Version)"))
        assertEquals("学不会", SongVersionMatcher.stripTrailingVersionMarkers("学不会 - LIVE"))
    }

    @Test
    fun leadingVersionWordInsideTitleIsPreserved() {
        assertEquals("Live Forever", SongVersionMatcher.stripTrailingVersionMarkers("Live Forever"))
        assertEquals("Live", SongVersionMatcher.stripTrailingVersionMarkers("Live"))
        assertEquals("Alive", SongVersionMatcher.stripTrailingVersionMarkers("Alive"))
    }
}
