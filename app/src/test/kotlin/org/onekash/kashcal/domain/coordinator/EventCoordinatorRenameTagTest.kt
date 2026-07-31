package org.onekash.kashcal.domain.coordinator

import io.mockk.MockKAnnotations
import io.mockk.coEvery
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.onekash.kashcal.data.contacts.ContactAnniversaryRepository
import org.onekash.kashcal.data.contacts.ContactBirthdayRepository
import org.onekash.kashcal.data.ics.IcsSubscriptionRepository
import org.onekash.kashcal.data.repository.AccountRepository
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Renaming a tag re-uploads every affected syncable event, but the drain must
 * run once for the whole batch — a rename touching 300 events should request a
 * single expedited sync, not 300 — and must request none when nothing syncable
 * was touched. The per-event mark-and-queue lives in [EventWriter]; the
 * coordinator only orchestrates and fires the single sync on the returned count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class EventCoordinatorRenameTagTest {

    @MockK private lateinit var eventWriter: EventWriter
    @MockK private lateinit var eventReader: EventReader
    @MockK private lateinit var occurrenceGenerator: OccurrenceGenerator
    @MockK private lateinit var localCalendarInitializer: LocalCalendarInitializer
    @MockK private lateinit var icsSubscriptionRepository: IcsSubscriptionRepository
    @MockK private lateinit var contactBirthdayRepository: ContactBirthdayRepository
    @MockK private lateinit var contactAnniversaryRepository: ContactAnniversaryRepository
    @MockK private lateinit var accountRepository: AccountRepository
    @MockK private lateinit var syncScheduler: SyncScheduler
    @MockK private lateinit var reminderScheduler: ReminderScheduler
    @MockK private lateinit var widgetUpdateManager: org.onekash.kashcal.widget.WidgetUpdateManager

    private lateinit var coordinator: EventCoordinator

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxed = true)
        coordinator = EventCoordinator(
            eventWriter,
            eventReader,
            occurrenceGenerator,
            localCalendarInitializer,
            icsSubscriptionRepository,
            contactBirthdayRepository,
            contactAnniversaryRepository,
            accountRepository,
            syncScheduler,
            reminderScheduler,
            widgetUpdateManager,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `a rename touching syncable events requests exactly one expedited sync`() = runBlocking {
        // 300 events queued -> one drain, not 300 syncs.
        coEvery { eventWriter.renameCategory("Work", "Job") } returns 300

        coordinator.renameTag("Work", "Job")

        verify(exactly = 1) { syncScheduler.requestExpeditedSync(forceFullSync = false) }
    }

    @Test
    fun `a rename touching no syncable events requests no sync`() = runBlocking {
        coEvery { eventWriter.renameCategory("Work", "Job") } returns 0

        coordinator.renameTag("Work", "Job")

        verify(exactly = 0) { syncScheduler.requestExpeditedSync(any()) }
    }
}
