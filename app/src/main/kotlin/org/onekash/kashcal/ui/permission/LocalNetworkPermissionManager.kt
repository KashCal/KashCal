package org.onekash.kashcal.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Reads the ACCESS_LOCAL_NETWORK permission state for LAN servers.
 *
 * Android 17 (API 37) blocks local-network socket traffic, the OkHttp connections used for
 * CalDAV included, unless this runtime permission (part of the NEARBY_DEVICES group) is granted.
 * On older OS versions apps with INTERNET keep implicit local-network access, so this reports
 * the permission as not required and always granted.
 *
 * Like [NotificationPermissionManager] it is constructed at the call site, not Hilt-injected;
 * the rationale reads take an Activity per call. The state derivation is
 * [resolveLocalNetworkPermissionState]; this class only supplies the framework readings.
 */
class LocalNetworkPermissionManager(
    private val context: Context,
) {

    /** Returns true on Android 17 and later, where the runtime permission is enforced. */
    fun isPermissionRequired(): Boolean =
        Build.VERSION.SDK_INT >= LOCAL_NETWORK_PERMISSION_MIN_SDK

    /** Returns whether local-network access is available now; always true below API 37. */
    fun isPermissionGranted(): Boolean {
        if (!isPermissionRequired()) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolves the live state, including a grant or revoke made in system settings. Returns
     * [LocalNetworkPermissionState.NotRequired] below API 37.
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

    /** Samples `shouldShowRequestPermissionRationale` for [classifyLocalNetworkAfterRequest]. */
    fun shouldShowRationale(activity: Activity): Boolean =
        isPermissionRequired() && ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_LOCAL_NETWORK,
        )

    companion object {
        /** Android 17 is API level 37. Hardcoded until a named constant ships in the SDK. */
        const val LOCAL_NETWORK_PERMISSION_MIN_SDK = 37
    }
}
