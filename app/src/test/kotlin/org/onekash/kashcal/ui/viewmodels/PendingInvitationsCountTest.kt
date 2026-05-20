package org.onekash.kashcal.ui.viewmodels

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.onekash.kashcal.data.db.entity.Event
import org.onekash.kashcal.domain.reader.PendingInvitation

/**
 * Pins the count-derivation policy used by HomeViewModel.pendingInvitationsCount.
 *
 * The single-source-of-truth Flow is built as
 *     getPendingInvitations().map { it.size }.distinctUntilChanged()
 * so the AppBar badge and the Invites menu item never disagree, even
 * when sync churn re-emits the same list twice in a row.
 */
class PendingInvitationsCountTest {

    private fun stub(id: Long) = PendingInvitation(
        event = Event(
            id = id,
            calendarId = 1L,
            uid = "uid-$id",
            title = "evt-$id",
            startTs = 0L,
            endTs = 0L,
            dtstamp = 0L
        ),
        occurrenceStartTs = 0L,
        occurrenceEndTs = 0L,
        accountId = 1L,
        calendarColor = 0,
        organizerLabel = "org"
    )

    @Test
    fun `empty list emits zero`() = runTest {
        val source = MutableStateFlow<List<PendingInvitation>>(emptyList())
        source.map { it.size }.distinctUntilChanged().test {
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `same-size emissions collapse via distinctUntilChanged`() = runTest {
        val source = MutableStateFlow(listOf(stub(1L), stub(2L), stub(3L)))
        source.map { it.size }.distinctUntilChanged().test {
            assertEquals(3, awaitItem())
            // Same size, different identity — should NOT re-emit
            source.value = listOf(stub(4L), stub(5L), stub(6L))
            source.value = listOf(stub(7L), stub(8L), stub(9L))
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `count flicker 3 to 0 to 3 drives all transitions`() = runTest {
        val source = MutableStateFlow(listOf(stub(1L), stub(2L), stub(3L)))
        source.map { it.size }.distinctUntilChanged().test {
            assertEquals(3, awaitItem())
            source.value = emptyList()
            assertEquals(0, awaitItem())
            source.value = listOf(stub(10L), stub(11L), stub(12L))
            assertEquals(3, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `single addition emits incremental count`() = runTest {
        val source = MutableStateFlow<List<PendingInvitation>>(emptyList())
        source.map { it.size }.distinctUntilChanged().test {
            assertEquals(0, awaitItem())
            source.value = listOf(stub(1L))
            assertEquals(1, awaitItem())
            source.value = listOf(stub(1L), stub(2L))
            assertEquals(2, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
