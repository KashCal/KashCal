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
 * WorkManager worker for contact birthday sync.
 *
 * Handles:
 * - Syncing birthdays from phone contacts
 * - Triggered by ContentObserver when contacts change
 * - One-shot sync for user-initiated actions
 *
 * Uses Hilt for dependency injection.
 */
@HiltWorker
class ContactBirthdayWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: ContactBirthdayRepository,
    private val dataStore: KashCalDataStore
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "ContactBirthdayWorker"

        // Work names
        const val SYNC_WORK = "contact_birthday_sync"

        // Output data keys
        const val KEY_BIRTHDAYS_ADDED = "birthdays_added"
        const val KEY_BIRTHDAYS_UPDATED = "birthdays_updated"
        const val KEY_BIRTHDAYS_DELETED = "birthdays_deleted"
        const val KEY_ERROR_MESSAGE = "error_message"

        // Tags
        const val TAG_BIRTHDAY = "contact_birthday"

        // Retry
        private const val MAX_RETRY_ATTEMPTS = 3

        /**
         * Request immediate sync of contact birthdays.
         *
         * Used when:
         * - User enables contact birthdays
         * - ContentObserver detects contact changes
         */
        fun requestImmediateSync(context: Context): java.util.UUID {
            Log.i(TAG, "Requesting immediate contact birthday sync")

            val work = OneTimeWorkRequestBuilder<ContactBirthdayWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_BIRTHDAY)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                SYNC_WORK,
                ExistingWorkPolicy.REPLACE,
                work
            )

            return work.id
        }

        /**
         * Cancel any pending birthday sync work.
         */
        fun cancelSync(context: Context) {
            Log.i(TAG, "Cancelling contact birthday sync")
            WorkManager.getInstance(context).cancelUniqueWork(SYNC_WORK)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting contact birthday sync, attempt=${runAttemptCount + 1}")

        // Check if feature is still enabled
        if (!dataStore.getContactBirthdaysEnabled()) {
            Log.i(TAG, "Contact birthdays disabled, skipping sync")
            return@withContext Result.success()
        }

        try {
            val result = repository.syncBirthdays()

            when (result) {
                is ContactBirthdayRepository.SyncResult.Success -> {
                    // Update last sync time
                    dataStore.setContactBirthdaysLastSync(System.currentTimeMillis())

                    Log.i(TAG, "Contact birthday sync complete: " +
                            "${result.added} added, ${result.updated} updated, ${result.deleted} deleted")

                    Result.success(
                        Data.Builder()
                            .putInt(KEY_BIRTHDAYS_ADDED, result.added)
                            .putInt(KEY_BIRTHDAYS_UPDATED, result.updated)
                            .putInt(KEY_BIRTHDAYS_DELETED, result.deleted)
                            .build()
                    )
                }

                is ContactBirthdayRepository.SyncResult.Error -> {
                    Log.e(TAG, "Contact birthday sync failed: ${result.message}")

                    if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                        Result.retry()
                    } else {
                        Result.failure(
                            Data.Builder()
                                .putString(KEY_ERROR_MESSAGE, result.message)
                                .build()
                        )
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied reading contacts", e)
            Result.failure(
                Data.Builder()
                    .putString(KEY_ERROR_MESSAGE, "Contacts permission denied")
                    .build()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Contact birthday sync failed with exception", e)

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(
                    Data.Builder()
                        .putString(KEY_ERROR_MESSAGE, e.message ?: "Unknown error")
                        .build()
                )
            }
        }
    }
}
