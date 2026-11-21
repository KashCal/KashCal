package org.onekash.kashcal.domain.coordinator

import android.app.AlarmManager
import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.onekash.kashcal.data.db.KashCalDatabase
import org.onekash.kashcal.data.db.PendingOperationTriggers
import org.onekash.kashcal.data.db.entity.Account
import org.onekash.kashcal.data.db.entity.Calendar
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.data.repository.AccountRepositoryImpl
import org.onekash.kashcal.domain.generator.OccurrenceGenerator
import org.onekash.kashcal.domain.initializer.LocalCalendarException
import org.onekash.kashcal.domain.initializer.LocalCalendarInitializer
import org.onekash.kashcal.domain.initializer.LocalCalendarRefusal
import org.onekash.kashcal.domain.model.AccountProvider
import org.onekash.kashcal.domain.reader.EventReader
import org.onekash.kashcal.domain.writer.EventWriter
import org.onekash.kashcal.reminder.receiver.ReminderAlarmReceiver
import org.onekash.kashcal.reminder.scheduler.ReminderScheduler
import org.onekash.kashcal.sync.scheduler.SyncScheduler
import org.onekash.kashcal.testutil.TestDataStoreFactory
import org.onekash.kashcal.testutil.TestDatabases
import org.onekash.kashcal.widget.WidgetUpdateManager
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.io.File

/**
 * A real [EventCoordinator] for offline calendar management: in-memory Room with the
 * pending-operation trigger, the real [LocalCalendarInitializer], writer and reader, a real
 * [ReminderScheduler] over Robolectric's AlarmManager, and a real [KashCalDataStore] over a
 * temporary file. [widgets] and [syncScheduler] are relaxed mocks of Unit side effects, for
 * verify.
 *
 * [onQuery] sees every SQL statement Room runs, on the thread that runs it, so a test can act at
 * a chosen statement. Call [close] in `@After`.
 */
class OfflineCalendarFixture(private val context: Context) {

    @Volatile
    var onQuery: ((String) -> Unit)? = null

    val db: KashCalDatabase = TestDatabases.inMemoryObserving(context) { sql -> onQuery?.invoke(sql) }
        .addCallback(PendingOperationTriggers.callback())
        .allowMainThreadQueries()
        .build()

    private val dataStoreScope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val dataStoreFile = File(context.filesDir, "offline_cal_${System.nanoTime()}.preferences_pb")

    /** The DataStore behind [dataStore], for writing keys it has no setter for. */
    val preferences = PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
    val dataStore = KashCalDataStore(context, preferences)

    val generator = OccurrenceGenerator(db, db.occurrencesDao(), db.eventsDao(), TestDataStoreFactory.createDefault())
    val initializer = LocalCalendarInitializer(db)
    val reader = EventReader(db)
    val syncScheduler: SyncScheduler = mockk(relaxed = true)
    val widgets: WidgetUpdateManager = mockk(relaxed = true)

    val scheduler = ReminderScheduler(
        context = context,
        scheduledRemindersDao = db.scheduledRemindersDao(),
        eventReader = reader,
        channels = mockk(relaxed = true),
        attendeesDao = db.attendeesDao(),
        accountsDao = db.accountsDao(),
        calendarsDao = db.calendarsDao(),
        occurrencesDao = db.occurrencesDao(),
    )

    private val accounts = AccountRepositoryImpl(
        accountsDao = db.accountsDao(),
        addressBookDao = db.addressBookDao(),
        calendarsDao = db.calendarsDao(),
        eventsDao = db.eventsDao(),
        pendingOperationsDao = db.pendingOperationsDao(),
        credentialManager = mockk(),
        reminderScheduler = mockk(relaxed = true),
        workManager = mockk(relaxed = true),
        contactSystemAccountRegistrar = mockk(relaxed = true),
        contactsProviderRepository = mockk(),
    )

    val coordinator = EventCoordinator(
        eventWriter = EventWriter(db, generator),
        eventReader = reader,
        occurrenceGenerator = generator,
        localCalendarInitializer = initializer,
        icsSubscriptionRepository = mockk(),
        contactBirthdayRepository = mockk(),
        contactAnniversaryRepository = mockk(),
        accountRepository = accounts,
        syncScheduler = syncScheduler,
        reminderScheduler = scheduler,
        widgetUpdateManager = widgets,
        inviteNotifier = mockk(relaxed = true),
        icsRefreshScheduleReconciler = mockk(relaxed = true),
        dataStore = dataStore,
    )

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    init {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    /** Adds a calendar under a new account of [provider] and returns its id. */
    suspend fun insertCalendarUnder(provider: AccountProvider, caldavUrl: String, name: String): Long {
        val accountId = db.accountsDao().insert(
            Account(provider = provider, email = "${provider.name.lowercase()}-$name@example.test")
        )
        return db.calendarsDao().insert(
            Calendar(accountId = accountId, caldavUrl = caldavUrl, displayName = name, color = 0)
        )
    }

    /** Reminder ids with an alarm currently set, mapped to the alarm's trigger time. */
    fun armedReminders(): Map<Long, Long> =
        shadowOf(alarmManager).scheduledAlarms.mapNotNull { alarm ->
            val intent = alarm.operation?.let { shadowOf(it).savedIntent } ?: return@mapNotNull null
            if (intent.component?.className != ReminderAlarmReceiver::class.java.name) return@mapNotNull null
            intent.getLongExtra(ReminderScheduler.EXTRA_REMINDER_ID, -1L) to alarm.triggerAtMs
        }.toMap()

    /** Returns the [LocalCalendarRefusal] a failed [result] carries, or null. */
    fun refusalOf(result: Result<*>): LocalCalendarRefusal? =
        (result.exceptionOrNull() as? LocalCalendarException)?.refusal

    /** Returns the other calendar's name a [LocalCalendarRefusal.SIMILAR_NAME] carries, or null. */
    fun conflictOf(result: Result<*>): String? =
        (result.exceptionOrNull() as? LocalCalendarException)?.conflictingName

    fun close() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }
}
