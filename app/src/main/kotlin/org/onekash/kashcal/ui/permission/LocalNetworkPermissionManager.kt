package org.onekash.kashcal.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Manages ACCESS_LOCAL_NETWORK permission state for LAN CalDAV sync.
 *
 * Android 17 (API 37) blocks local-network socket traffic — including OkHttp
 * connections used for CalDAV — unless this runtime permission (part of the
 * NEARBY_DEVICES group) is granted. On older OS versions apps with INTERNET
 * retain implicit local-network access, so this manager reports the permission
 * as not required and always granted.
 *
 * Mirrors [NotificationPermissionManager]: instantiated with an Activity at the
 * call site (not Hilt-injected) because the runtime request and rationale read
 * both require an Activity. Pure state derivation lives in
 * [resolveLocalNetworkPermissionState]; this class only supplies the framework
 * readings.
 */
class LocalNetworkPermissionManager(
    private val context: Context,
) {

    /** True on Android 17+ where the runtime permission is enforced. */
    fun isPermissionRequired(): Boolean =
        Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_MIN_SDK

    /** Whether local-network access is currently available (auto-true pre-37). */
    fun isPermissionGranted(): Boolean {
        if (!isPermissionRequired()) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolve the live permission state, reflecting any grant/revoke performed
     * in system Settings. Returns [LocalNetworkPermissionState.NotRequired] on
     * pre-37 OS versions.
     */
    fun resolveState(activity: Activity): LocalNetworkPermissionState {
        val required = isPermissionRequired()
        val granted = isPermissionGranted()
        val shouldShowRationale = required && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )
        return resolveLocalNetworkPermissionState(
            permissionRequired = required,
            granted = granted,
            shouldShowRationale = shouldShowRationale,
        )
    }

    /** Sample `shouldShowRequestPermissionRationale` (for the rationale-flip classify). */
    fun shouldShowRationale(activity: Activity): Boolean =
        isPermissionRequired() && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )

    companion object {
        /** Android 17 = API level 37. Hardcoded until a named constant ships in the SDK. */
        const val LOCAL_NETWORK_PERMISSION_MIN_SDK = 37
    }
}
