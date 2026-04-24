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
