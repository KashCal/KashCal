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
 * Service for getting address suggestions using Android's Geocoder API.
 *
 * Features:
 * - Handles API 33+ async Geocoder and legacy blocking call
 * - Graceful degradation when Geocoder unavailable
 * - Returns empty list on errors (no crashes)
 *
 * Usage:
 * ```
 * val suggestions = locationSuggestionService.getSuggestions("123 Main")
 * ```
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
     * Format display name combining place name and address.
     * Shows "Place Name, Address" when place name is meaningful.
     */
    private fun formatDisplayName(featureName: String?, addressLine: String?): String {
        val address = addressLine ?: ""
        val feature = featureName?.trim()

        // Skip featureName if:
        // - null/blank
        // - just a number (street number)
        // - already starts the address line
        if (feature.isNullOrBlank() ||
            feature.all { it.isDigit() } ||
            address.startsWith(feature, ignoreCase = true)
        ) {
            return address
        }

        return "$feature, $address"
    }

    /**
     * Get address suggestions for a query.
     *
     * @param query Search query (minimum 5 characters)
     * @param maxResults Maximum number of suggestions to return (default 5)
     * @return List of address suggestions, or empty list if unavailable
     */
    suspend fun getSuggestions(query: String, maxResults: Int = 5): List<AddressSuggestion> {
        if (geocoder == null || query.length < 5) return emptyList()

        return withContext(ioDispatcher) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: Use async callback
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
                    // API < 33: Blocking call (on IO dispatcher)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(query, maxResults) ?: emptyList()
                    addresses.map { addr ->
                        AddressSuggestion(
                            displayName = formatDisplayName(addr.featureName, addr.getAddressLine(0)),
                            latitude = addr.latitude,
                            longitude = addr.longitude
                        )
                    }
                }
            } catch (e: Exception) {
                // Geocoder can fail for various reasons (network, backend unavailable)
                // Return empty list instead of crashing
                emptyList()
            }
        }
    }
}

/**
 * Represents an address suggestion from Geocoder.
 *
 * @param displayName Full formatted address line
 * @param latitude Latitude coordinate (may be null if not available)
 * @param longitude Longitude coordinate (may be null if not available)
 */
data class AddressSuggestion(
    val displayName: String,
    val latitude: Double?,
    val longitude: Double?
)
