package org.onekash.kashcal.domain.catalog

import android.content.Context
import androidx.annotation.RawRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.text.containsCaseInsensitive

/**
 * A subscribable holiday calendar from the bundled catalog: a display name and the public feed
 * URL it points at. The catalog holds no calendar data, only pointers to externally hosted feeds.
 */
@Serializable
data class HolidayCatalogEntry(
    val name: String,
    val url: String,
)

/** A catalog entry paired with whether the user is already subscribed to it. */
data class MarkedHolidayEntry(
    val entry: HolidayCatalogEntry,
    val alreadyAdded: Boolean,
)

/**
 * Shape of the bundled catalog file. Only [entries] is read; the file's source and license
 * metadata is skipped by `ignoreUnknownKeys` in [HolidayCatalogJson].
 */
@Serializable
private data class HolidayCatalogFile(
    @SerialName("entries") val entries: List<HolidayCatalogEntry> = emptyList(),
)

private val HolidayCatalogJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Parses the catalog JSON into entries sorted case-insensitively by name, dropping entries with
 * a blank name or URL.
 *
 * Never throws: malformed or empty input, or a file without an entries array, yields an empty
 * list, so a corrupt bundled asset shows an empty picker instead of crashing.
 */
fun parseHolidayCatalog(json: String): List<HolidayCatalogEntry> {
    return try {
        HolidayCatalogJson.decodeFromString<HolidayCatalogFile>(json)
            .entries
            .filter { it.name.isNotBlank() && it.url.isNotBlank() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Returns the entries whose name contains [query], ignoring case. The query is trimmed first,
 * so a blank query returns all entries and a stray leading or trailing space drops no match.
 */
fun filterCatalog(
    entries: List<HolidayCatalogEntry>,
    query: String,
): List<HolidayCatalogEntry> {
    val trimmed = query.trim()
    return entries.filter { it.name.containsCaseInsensitive(trimmed) }
}

/**
 * Pairs each entry with whether [subscribedUrls] already contains its URL, by trimmed equality.
 * That suffices because catalog URLs are always https, and a subscription added in the app is
 * stored trimmed with webcal rewritten to https.
 */
fun markAlreadyAdded(
    entries: List<HolidayCatalogEntry>,
    subscribedUrls: Set<String>,
): List<MarkedHolidayEntry> {
    val normalized = subscribedUrls.mapTo(HashSet()) { it.trim() }
    return entries.map { MarkedHolidayEntry(it, it.url.trim() in normalized) }
}

/**
 * Loads and parses the bundled holiday catalog from `res/raw`. Returns an empty list on any read
 * or parse failure ([parseHolidayCatalog]).
 */
fun loadHolidayCatalog(
    context: Context,
    @RawRes resId: Int = R.raw.holiday_catalog,
): List<HolidayCatalogEntry> =
    try {
        val json = context.resources.openRawResource(resId)
            .bufferedReader()
            .use { it.readText() }
        parseHolidayCatalog(json)
    } catch (e: Exception) {
        emptyList()
    }
