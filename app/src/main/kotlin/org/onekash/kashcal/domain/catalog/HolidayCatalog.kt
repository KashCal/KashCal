package org.onekash.kashcal.domain.catalog

import android.content.Context
import androidx.annotation.RawRes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.text.containsCaseInsensitive

/**
 * A single subscribable holiday calendar from the bundled catalog: a display
 * name plus the public feed URL it points at. The catalog hosts no calendar
 * data itself — only pointers to the externally-hosted feeds.
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
 * Deserialization shape of the bundled catalog file. Only [entries] is read;
 * the file also carries source/license metadata which [HolidayCatalogJson]
 * ignores via `ignoreUnknownKeys`.
 */
@Serializable
private data class HolidayCatalogFile(
    @SerialName("entries") val entries: List<HolidayCatalogEntry> = emptyList(),
)

private val HolidayCatalogJson: Json = Json {
    ignoreUnknownKeys = true
}

/**
 * Parse the catalog JSON into entries sorted case-insensitively by name.
 *
 * Total by design: any malformed/empty input or a file without an entries
 * array yields an empty list rather than throwing, so a corrupt bundled asset
 * degrades to an empty picker instead of crashing.
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
 * Filter entries to those whose name contains [query] (case-insensitive
 * substring). The query is trimmed first, so a blank or whitespace-only query
 * returns all entries and a stray leading/trailing space doesn't drop matches.
 */
fun filterCatalog(
    entries: List<HolidayCatalogEntry>,
    query: String,
): List<HolidayCatalogEntry> {
    val trimmed = query.trim()
    return entries.filter { it.name.containsCaseInsensitive(trimmed) }
}

/**
 * Pair each entry with whether [subscribedUrls] already contains its URL.
 * Matching is trimmed equality: catalog URLs are always https, and stored
 * subscription URLs are normalized (webcal→https) and trimmed at write time,
 * so a plain trimmed comparison is sufficient.
 */
fun markAlreadyAdded(
    entries: List<HolidayCatalogEntry>,
    subscribedUrls: Set<String>,
): List<MarkedHolidayEntry> {
    val normalized = subscribedUrls.mapTo(HashSet()) { it.trim() }
    return entries.map { MarkedHolidayEntry(it, it.url.trim() in normalized) }
}

/**
 * Load and parse the bundled holiday catalog from `res/raw`. Returns an empty
 * list on any IO/parse failure (see [parseHolidayCatalog]).
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
