package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadRangeValidationTest {
    @Test
    fun acceptsOnlyRangesStartingAtTheExistingByteOffset() {
        assertTrue(isExpectedPartialContentRange("bytes 1024-2047/4096", 1024L))
        assertTrue(isExpectedPartialContentRange("bytes 1024-2047/*", 1024L))
        assertFalse(isExpectedPartialContentRange("bytes 0-1023/4096", 1024L))
        assertFalse(isExpectedPartialContentRange("bytes 1024-2047/2047", 1024L))
        assertFalse(isExpectedPartialContentRange("bytes 1024-2047/4096", 0L))
        assertFalse(isExpectedPartialContentRange("not-a-range", 1024L))
        assertFalse(isExpectedPartialContentRange(null, 1024L))
    }
}
