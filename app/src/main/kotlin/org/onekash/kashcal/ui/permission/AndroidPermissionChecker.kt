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
 * Android implementation of [PermissionChecker] backed by
 * [ContextCompat.checkSelfPermission] and [AlarmManager.canScheduleExactAlarms].
 *
 * Each method performs a fresh query — no caching. SDK-level gates preserve
 * pre-existing behavior where runtime permissions were introduced in later
 * Android versions (`POST_NOTIFICATIONS` on API 33+, exact-alarm scheduling
 * on API 31+).
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
