package org.onekash.kashcal.domain.backup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A typed preference value in the backup JSON, tagged by the `type` discriminator set in
 * [BackupJson].
 */
@Serializable
sealed class BackupPreferenceValue {

    @Serializable
    @SerialName("bool")
    data class BoolPref(val value: Boolean) : BackupPreferenceValue()

    @Serializable
    @SerialName("int")
    data class IntPref(val value: Int) : BackupPreferenceValue()

    @Serializable
    @SerialName("long")
    data class LongPref(val value: Long) : BackupPreferenceValue()

    @Serializable
    @SerialName("string")
    data class StringPref(val value: String) : BackupPreferenceValue()

    @Serializable
    @SerialName("string_set")
    data class StringSetPref(val value: Set<String>) : BackupPreferenceValue()
}
