package io.github.hdlee73.everycorp

import io.github.hdlee73.everycorp.data.api.dto.DisclosureDto
import io.github.hdlee73.everycorp.data.watch.DisclosureDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class DisclosureWatchTest {
    private fun d(id: String) = DisclosureDto(id, "DART", "20261022", null, "공시 $id", null, null, "u", "m")

    @Test fun firstCheckOnlyRecordsBaseline() {
        val latest = listOf(d("3"), d("2"), d("1"))
        assertEquals(emptyList<DisclosureDto>(), DisclosureDiff.newItems(null, latest))
        assertEquals(setOf("3", "2", "1"), DisclosureDiff.nextSeen(null, latest))
    }

    @Test fun laterCheckReportsOnlyUnseen() {
        val latest = listOf(d("5"), d("4"), d("3"))
        val fresh = DisclosureDiff.newItems(setOf("3", "2", "1"), latest)
        assertEquals(listOf("5", "4"), fresh.map { it.id })
        assertEquals(setOf("5", "4", "3", "2", "1"), DisclosureDiff.nextSeen(setOf("3", "2", "1"), latest))
    }

    @Test fun seenListIsCapped() {
        val latest = (1..40).map { d("n$it") }
        assertEquals(DisclosureDiff.KEEP, DisclosureDiff.nextSeen(setOf("old"), latest).size)
    }
}
