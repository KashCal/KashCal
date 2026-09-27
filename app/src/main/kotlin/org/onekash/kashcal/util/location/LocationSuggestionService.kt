package org.onekash.kashcal.util.location

import android.content.Context
import android.location.Geocoder
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.onekash.kashcal.di.IoDispatcher
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Suggests addresses for a location query through Android's Geocoder, using the async API on
 * API 33+ and the blocking call below it.
 */
@Singleton
class LocationSuggestionService @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {
    private val geocoder: Geocoder? = if (Geocoder.isPresent()) {
        Geocoder(context, Locale.getDefault())
    } else null

    /**
     * Returns "Feature, Address", or the address line alone when the feature name is blank, all
     * digits (a street number) or already starts the address line.
     */
    private fun formatDisplayName(featureName: String?, addressLine: String?): String {
        val address = addressLine.orEmpty()
        val feature = featureName?.trim()

        if (feature.isNullOrBlank() ||
            feature.all { it.isDigit() } ||
            address.startsWith(feature, ignoreCase = true)
        ) {
            return address
        }

        return "$feature, $address"
    }

    /**
     * Returns up to [maxResults] suggestions for [query].
     *
     * Returns an empty list when the device has no Geocoder, [query] is under 5 characters, or
     * the Geocoder call throws. On API 33+ a failure reported through the listener's `onError`
     * isn't handled, so the call stays suspended until the caller is cancelled.
     */
    suspend fun getSuggestions(query: String, maxResults: Int = 5): List<AddressSuggestion> {
        if (geocoder == null || query.length < 5) return emptyList()

        return withContext(ioDispatcher) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { continuation ->
                        geocoder.getFromLocationName(query, maxResults) { addresses ->
                            val suggestions = addresses.map { addr ->
                                AddressSuggestion(
                                    displayName = formatDisplayName(addr.featureName, addr.getAddressLine(0)),
                                    latitude = addr.latitude,
                                    longitude = addr.longitude
                                )
                            }
                            continuation.resume(suggestions)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, maxResults).orEmpty()
                    addresses.map { addr ->
                        AddressSuggestion(
                            displayName = formatDisplayName(addr.featureName, addr.getAddressLine(0)),
                            latitude = addr.latitude,
                            longitude = addr.longitude
                        )
                    }
                }
            } catch (_: Exception) {
                // Geocoder throws when, for example, its network or backend is unavailable.
                emptyList()
            }
        }
    }
}

/**
 * An address suggestion from Geocoder.
 *
 * @param displayName the address line, prefixed with the place name when it adds one
 * @param latitude always set by [LocationSuggestionService.getSuggestions]
 * @param longitude always set by [LocationSuggestionService.getSuggestions]
 */
data class AddressSuggestion(
    val displayName: String,
    val latitude: Double?,
    val longitude: Double?
)
