package org.onekash.kashcal.domain.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BackupEnvelope(
    @SerialName("file_format_version") val fileFormatVersion: Int,
    @SerialName("app_version") val appVersion: String,
    @SerialName("exported_at") val exportedAt: String,
    val preferences: Map<String, BackupPreferenceValue>,
    val subscriptions: List<BackupSubscription>,
    // Additive field, defaulted so envelopes written before tags existed (and
    // any that omit it) still parse. Only tags with a user-chosen color are
    // carried; see CategoryDao.getColoredOnce.
    val categories: List<BackupCategory> = emptyList(),
)

@Serializable
data class BackupSubscription(
    val url: String,
    val name: String,
    val color: Int,
    val syncIntervalHours: Int,
    val enabled: Boolean,
    val username: String? = null,
)

@Serializable
data class BackupCategory(
    val name: String,
    val color: Int,
    @SerialName("last_used_at") val lastUsedAt: Long,
)
