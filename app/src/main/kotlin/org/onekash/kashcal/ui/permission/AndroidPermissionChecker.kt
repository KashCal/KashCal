package org.onekash.kashcal.ui.permission

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implements [PermissionChecker] with [ContextCompat.checkSelfPermission] and
 * [AlarmManager.canScheduleExactAlarms].
 *
 * Each call queries afresh; nothing is cached. Below the level where a permission became a
 * runtime one (`POST_NOTIFICATIONS` on API 33, exact alarms on API 31) it reports granted. A
 * missing AlarmManager reports no exact-alarm permission.
 */
@Singleton
class AndroidPermissionChecker @Inject constructor(
    @ApplicationContext private val context: Context
) : PermissionChecker {

    override fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    override fun hasExactAlarmPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager?.canScheduleExactAlarms() ?: false
        } else {
            true
        }
    }

    override fun hasReadContactsPermission(): Boolean =
        checkGranted(Manifest.permission.READ_CONTACTS)

    override fun hasWriteContactsPermission(): Boolean =
        checkGranted(Manifest.permission.WRITE_CONTACTS)

    override fun hasCalendarReadPermission(): Boolean =
        checkGranted(Manifest.permission.READ_CALENDAR)

    override fun hasCalendarWritePermission(): Boolean =
        checkGranted(Manifest.permission.WRITE_CALENDAR)

    private fun checkGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
