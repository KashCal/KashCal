package org.onekash.kashcal.data.contacts

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.onekash.kashcal.data.preferences.KashCalDataStore
import java.util.concurrent.TimeUnit

/**
 * Syncs whichever of the birthday and anniversary calendars is enabled from the phone's
 * contacts.
 *
 * Enqueued by [ContactEventManager] when a feature is enabled and on each contacts change. A
 * failed sync, an error result or an exception, retries up to [MAX_RETRY_ATTEMPTS] times,
 * then fails with [KEY_ERROR_MESSAGE]; a [SecurityException] fails at once.
 */
@HiltWorker
class ContactEventSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val birthdayRepository: ContactBirthdayRepository,
    private val anniversaryRepository: ContactAnniversaryRepository,
    private val dataStore: KashCalDataStore
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ContactEventSyncWorker"

        const val SYNC_WORK = "contact_event_sync"

        // Output data keys - birthdays
        const val KEY_BIRTHDAYS_ADDED = "birthdays_added"
        const val KEY_BIRTHDAYS_UPDATED = "birthdays_updated"
        const val KEY_BIRTHDAYS_DELETED = "birthdays_deleted"

        // Output data keys - anniversaries
        const val KEY_ANNIVERSARIES_ADDED = "anniversaries_added"
        const val KEY_ANNIVERSARIES_UPDATED = "anniversaries_updated"
        const val KEY_ANNIVERSARIES_DELETED = "anniversaries_deleted"

        const val KEY_ERROR_MESSAGE = "error_message"

        const val TAG_CONTACT_EVENT = "contact_event"

        private const val MAX_RETRY_ATTEMPTS = 3

        /** Enqueues a sync, replacing any queued or running one, and returns the request ID. */
        fun requestImmediateSync(context: Context): java.util.UUID {
            Log.i(TAG, "Requesting immediate contact event sync")

            val work = OneTimeWorkRequestBuilder<ContactEventSyncWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_CONTACT_EVENT)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                work
            )

            return work.id
        }

        /** Cancels queued or running contact event sync work. */
        fun cancelSync(context: Context) {
            Log.i(TAG, "Cancelling contact event sync")
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting contact event sync, attempt=${runAttemptCount + 1}")

        val birthdaysEnabled = dataStore.getContactBirthdaysEnabled()
        val anniversariesEnabled = dataStore.getContactAnniversariesEnabled()

        if (!birthdaysEnabled && !anniversariesEnabled) {
            Log.i(TAG, "Both contact features disabled, skipping sync")
            return@withContext Result.success()
        }

        try {
            val outputBuilder = Data.Builder()
            var hasError = false
            var errorMessage: String? = null

            if (birthdaysEnabled) {
                when (val result = birthdayRepository.syncEvents()) {
                    is ContactEventSyncResult.Success -> {
                        dataStore.setContactBirthdaysLastSync(System.currentTimeMillis())
                        outputBuilder
                            .putInt(KEY_BIRTHDAYS_ADDED, result.added)
                            .putInt(KEY_BIRTHDAYS_UPDATED, result.updated)
                            .putInt(KEY_BIRTHDAYS_DELETED, result.deleted)
                        Log.i(TAG, "Birthday sync complete: ${result.added} added, ${result.updated} updated, ${result.deleted} deleted")
                    }
                    is ContactEventSyncResult.Error -> {
                        Log.e(TAG, "Birthday sync failed: ${result.message}")
                        hasError = true
                        errorMessage = result.message
                    }
                }
            }

            if (anniversariesEnabled) {
                when (val result = anniversaryRepository.syncEvents()) {
                    is ContactEventSyncResult.Success -> {
                        dataStore.setContactAnniversariesLastSync(System.currentTimeMillis())
                        outputBuilder
                            .putInt(KEY_ANNIVERSARIES_ADDED, result.added)
                            .putInt(KEY_ANNIVERSARIES_UPDATED, result.updated)
                            .putInt(KEY_ANNIVERSARIES_DELETED, result.deleted)
                        Log.i(TAG, "Anniversary sync complete: ${result.added} added, ${result.updated} updated, ${result.deleted} deleted")
                    }
                    is ContactEventSyncResult.Error -> {
                        Log.e(TAG, "Anniversary sync failed: ${result.message}")
                        hasError = true
                        errorMessage = (errorMessage?.let { "$it; " }.orEmpty()) + result.message
                    }
                }
            }

            if (hasError) {
                if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                    Result.retry()
                } else {
                    outputBuilder.putString(KEY_ERROR_MESSAGE, errorMessage)
                    Result.failure(outputBuilder.build())
                }
            } else {
                Result.success(outputBuilder.build())
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading contacts", e)
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, "Contacts permission denied")
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Contact event sync failed with exception", e)

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, e.message ?: e.javaClass.simpleName)
                        .build()
                )
            }
        }
    }
}
