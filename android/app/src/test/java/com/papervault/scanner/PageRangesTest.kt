package com.papervault.scanner

import com.papervault.scanner.pdf.PageRanges
import org.junit.Assert.assertEquals
import org.junit.Test

class PageRangesTest {
    @Test fun parsesGroups() {
        assertEquals(listOf(listOf(0, 1, 2), listOf(4), listOf(7, 8, 9)), PageRanges.parseGroups("1-3, 5, 8-", 10))
        assertEquals(listOf(listOf(0, 1)), PageRanges.parseGroups("-2", 10))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsOutOfRange() { PageRanges.parseGroups("0-3", 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsBackwards() { PageRanges.parseGroups("4-2", 5) }
    @Test(expected = IllegalArgumentException::class) fun rejectsGarbage() { PageRanges.parseGroups("a-b", 5) }
    @Test fun chunks() { assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PageRanges.chunks(5, 2)) }
    @Test fun flat() { assertEquals(listOf(2, 0, 1), PageRanges.parseFlat("3, 1-3", 3)) }
}
