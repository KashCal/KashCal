package org.onekash.kashcal.data.ics

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker for ICS subscription refresh.
 *
 * Handles:
 * - Periodic background refresh of ICS subscriptions
 * - One-shot refresh for user-initiated sync
 * - Individual subscription refresh
 *
 * Uses Hilt for dependency injection.
 */
@HiltWorker
class IcsRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: IcsSubscriptionRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "IcsRefreshWorker"

        // Work names
        const val PERIODIC_REFRESH_WORK = "ics_periodic_refresh"
        const val ONE_SHOT_REFRESH_WORK = "ics_one_shot_refresh"

        // Input data keys
        const val KEY_SUBSCRIPTION_ID = "subscription_id"
        const val KEY_REFRESH_TYPE = "refresh_type"

        // Output data keys
        const val KEY_SUBSCRIPTIONS_REFRESHED = "subscriptions_refreshed"
        const val KEY_EVENTS_ADDED = "events_added"
        const val KEY_EVENTS_UPDATED = "events_updated"
        const val KEY_EVENTS_DELETED = "events_deleted"
        const val KEY_ERROR_MESSAGE = "error_message"

        // Refresh types
        const val REFRESH_TYPE_ALL = "all"
        const val REFRESH_TYPE_DUE = "due"
        const val REFRESH_TYPE_SINGLE = "single"

        // Intervals
        const val DEFAULT_REFRESH_INTERVAL_HOURS = 6L
        const val MIN_REFRESH_INTERVAL_HOURS = 1L

        // Tags
        const val TAG_ICS = "ics_refresh"

        /**
         * Schedule periodic ICS refresh.
         */
        fun schedulePeriodicRefresh(
            context: Context,
            intervalHours: Long = DEFAULT_REFRESH_INTERVAL_HOURS
        ) {
            val actualInterval = maxOf(intervalHours, MIN_REFRESH_INTERVAL_HOURS)

            Log.i(TAG, "Scheduling periodic ICS refresh every $actualInterval hours")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_REFRESH_TYPE, REFRESH_TYPE_DUE)
                .build()

            val periodicWork = PeriodicWorkRequestBuilder<IcsRefreshWorker>(
                actualInterval, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_ICS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_REFRESH_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicWork
            )
        }

        /**
         * Cancel periodic ICS refresh.
         */
        fun cancelPeriodicRefresh(context: Context) {
            Log.i(TAG, "Cancelling periodic ICS refresh")
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_REFRESH_WORK)
        }

        /**
         * Request immediate refresh of all subscriptions.
         */
        fun requestImmediateRefresh(context: Context): java.util.UUID {
            Log.i(TAG, "Requesting immediate ICS refresh")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_REFRESH_TYPE, REFRESH_TYPE_ALL)
                .build()

            val work = OneTimeWorkRequestBuilder<IcsRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_ICS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_REFRESH_WORK,
                ExistingWorkPolicy.REPLACE,
                work
            )

            return work.id
        }

        /**
         * Request refresh of a specific subscription.
         */
        fun requestSubscriptionRefresh(context: Context, subscriptionId: Long): java.util.UUID {
            Log.i(TAG, "Requesting refresh for subscription: $subscriptionId")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val inputData = Data.Builder()
                .putString(KEY_REFRESH_TYPE, REFRESH_TYPE_SINGLE)
                .putLong(KEY_SUBSCRIPTION_ID, subscriptionId)
                .build()

            val workName = "ics_refresh_$subscriptionId"

            val work = OneTimeWorkRequestBuilder<IcsRefreshWorker>()
                .setConstraints(constraints)
                .setInputData(inputData)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(TAG_ICS)
                .addTag("subscription_$subscriptionId")
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                work
            )

            return work.id
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val refreshType = inputData.getString(KEY_REFRESH_TYPE) ?: REFRESH_TYPE_DUE

        Log.i(TAG, "Starting ICS refresh: type=$refreshType, attempt=${runAttemptCount + 1}")

        try {
            val results = when (refreshType) {
                REFRESH_TYPE_ALL -> {
                    // Refresh all enabled subscriptions
                    repository.forceRefreshAll()
                }

                REFRESH_TYPE_DUE -> {
                    // Only refresh subscriptions that are due
                    repository.refreshAllDueSubscriptions()
                }

                REFRESH_TYPE_SINGLE -> {
                    val subscriptionId = inputData.getLong(KEY_SUBSCRIPTION_ID, -1)
                    if (subscriptionId == -1L) {
                        Log.e(TAG, "Single refresh requested but no subscription_id provided")
                        return@withContext Result.failure(
                            createErrorOutput("No subscription_id provided")
                        )
                    }
                    listOf(repository.refreshSubscription(subscriptionId))
                }

                else -> {
                    repository.refreshAllDueSubscriptions()
                }
            }

            // Aggregate results
            var subscriptionsRefreshed = 0
            var eventsAdded = 0
            var eventsUpdated = 0
            var eventsDeleted = 0
            val errors = mutableListOf<String>()

            for (result in results) {
                when (result) {
                    is IcsSubscriptionRepository.SyncResult.Success -> {
                        subscriptionsRefreshed++
                        eventsAdded += result.count.added
                        eventsUpdated += result.count.updated
                        eventsDeleted += result.count.deleted
                    }

                    is IcsSubscriptionRepository.SyncResult.NotModified -> {
                        subscriptionsRefreshed++
                    }

                    is IcsSubscriptionRepository.SyncResult.Skipped -> {
                        // Don't count skipped
                    }

                    is IcsSubscriptionRepository.SyncResult.Error -> {
                        errors.add(result.message)
                    }
                }
            }

            Log.i(TAG, "ICS refresh complete: $subscriptionsRefreshed subscriptions, " +
                    "$eventsAdded added, $eventsUpdated updated, $eventsDeleted deleted, " +
                    "${errors.size} errors")

            if (errors.isNotEmpty() && subscriptionsRefreshed == 0) {
                // All failed
                Result.failure(createErrorOutput(errors.first()))
            } else if (errors.isNotEmpty()) {
                // Partial success
                Result.success(
                    createSuccessOutput(
                        subscriptionsRefreshed,
                        eventsAdded,
                        eventsUpdated,
                        eventsDeleted,
                        "Partial: ${errors.size} errors"
                    )
                )
            } else {
                Result.success(
                    createSuccessOutput(
                        subscriptionsRefreshed,
                        eventsAdded,
                        eventsUpdated,
                        eventsDeleted,
                        null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ICS refresh failed with exception", e)

            if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(createErrorOutput(e.message ?: "Unknown error"))
            }
        }
    }

    private fun createSuccessOutput(
        subscriptionsRefreshed: Int,
        eventsAdded: Int,
        eventsUpdated: Int,
        eventsDeleted: Int,
        errorMessage: String?
    ): Data {
        return Data.Builder()
            .putInt(KEY_SUBSCRIPTIONS_REFRESHED, subscriptionsRefreshed)
            .putInt(KEY_EVENTS_ADDED, eventsAdded)
            .putInt(KEY_EVENTS_UPDATED, eventsUpdated)
            .putInt(KEY_EVENTS_DELETED, eventsDeleted)
            .apply {
                errorMessage?.let { putString(KEY_ERROR_MESSAGE, it) }
            }
            .build()
    }

    private fun createErrorOutput(message: String): Data {
        return Data.Builder()
            .putString(KEY_ERROR_MESSAGE, message)
            .build()
    }
}

private const val MAX_RETRY_ATTEMPTS = 3