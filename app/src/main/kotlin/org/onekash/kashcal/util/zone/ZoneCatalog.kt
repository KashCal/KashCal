package org.onekash.kashcal.util.zone

import android.icu.text.TimeZoneFormat
import android.icu.text.TimeZoneNames
import android.icu.util.TimeZone
import org.onekash.kashcal.util.TimezoneUtils
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Compares, lists, labels and searches time zones for the UI, from Android's ICU (CLDR) data.
 *
 * Stored zone ids are never rewritten: two spellings of one zone (Asia/Kolkata, Asia/Calcutta)
 * compare equal through [sameZone] and get the same label. What may be stored is decided by
 * [TimezoneUtils.resolveZoneOrNull]; the catalog lists only ids that rule accepts.
 *
 * Nothing here touches android.icu at class load, and [sameZone] falls back to string equality
 * when ICU returns nothing, so plain JVM tests (where android.icu returns default values) can
 * call it.
 */
object ZoneCatalog {

    /** The id of the list's single UTC row. */
    const val UTC_ID = "UTC"

    /**
     * A zone as the UI shows it, at one date.
     *
     * @param id the list's id: a CLDR canonical location id, [UTC_ID], or for an offset-only
     *   id the id itself
     * @param city localized city, "UTC" for the UTC row, empty for an offset-only id
     * @param country localized country, or null (UTC, offset ids, region "001")
     * @param zoneName localized zone name ("Central European Time"), or null
     * @param abbreviation CLDR short name in the user's language or the zone's country's
     *   ("CET"), or null; never repeats [city] or [offset]
     * @param offset localized GMT offset at the date ("GMT+01:00", "UTC+00.00")
     */
    data class Entry(
        val id: String,
        val city: String,
        val country: String?,
        val zoneName: String?,
        val abbreviation: String?,
        val offset: String,
        val offsetSeconds: Int,
        internal val windowsId: String? = null,
        internal val ids: List<String> = emptyList(),
        internal val region: String? = null,
        /** The short name before the display rule drops it (London's "GMT"), for search. */
        internal val searchAbbreviation: String? = null,
    ) {
        /** The fields search compares, normalized once per entry (entries are cached). */
        internal val keys: SearchKeys by lazy(LazyThreadSafetyMode.PUBLICATION) {
            SearchKeys(
                city = normalize(city),
                zoneName = zoneName?.let(::normalize).orEmpty(),
                country = country?.let(::normalize).orEmpty(),
                abbreviation = (searchAbbreviation ?: abbreviation)?.let(::normalize),
                windowsId = windowsId?.let(::normalize),
                ids = ids.map(::normalize),
                countryAliases = region?.let { countryAliases[it] }.orEmpty().map(::normalize),
            )
        }
    }

    internal class SearchKeys(
        val city: String,
        val zoneName: String,
        val country: String,
        val abbreviation: String?,
        val windowsId: String?,
        val ids: List<String>,
        val countryAliases: List<String>,
    )

    /** Returns true when `ZoneId.of` accepts [id]: what the sheet may store or list. */
    fun isZoneId(id: String): Boolean = try {
        ZoneId.of(id)
        true
    } catch (_: Exception) {
        false
    }

    /** Returns the zone ICU gives for a Windows id ("Eastern Standard Time"), or null. */
    fun windowsZone(id: String): ZoneId? = try {
        TimeZone.getIDForWindowsID(id, null)?.let { ZoneId.of(it) }
    } catch (_: Exception) {
        null
    }

    /** Returns true when [id] names UTC: UTC, Etc/UTC, Etc/UCT, GMT, Etc/GMT, Z, +00:00. */
    fun isUtc(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        return try {
            ZoneId.of(id).normalized() == ZoneOffset.UTC
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true when [a] and [b] name the same zone: equal ids, two members of the UTC
     * family, or two ids with one ICU canonical id. Distinct zones tzdata merges
     * (Europe/Amsterdam, Europe/Brussels) stay distinct.
     */
    fun sameZone(a: String?, b: String?): Boolean {
        if (a == null || b == null) return a == b
        if (a == b) return true
        if (isUtc(a) || isUtc(b)) return isUtc(a) && isUtc(b)
        val ca = canonical(a) ?: return false
        return ca == canonical(b)
    }

    /**
     * Returns the label of [id] at [at], or null when [TimezoneUtils.resolveZoneOrNull] can't
     * read it. The UTC family gives the UTC row; an offset-only id ("-05:00", Etc/GMT+5, EST)
     * gives its offset with no city or names.
     */
    fun label(id: String?, at: Instant, locale: Locale = Locale.getDefault()): Entry? {
        if (id == null) return null
        val zone = TimezoneUtils.resolveZoneOrNull(id) ?: return null
        val text = Text(locale)
        if (isUtc(zone.id)) return utcEntry(text)
        // EST, MST and HST are fixed offsets; newer tzdata links them to a city (EST to
        // America/Panama), which must not show as that city.
        val fixed = zone.normalized() as? ZoneOffset
            ?: zone.rules.getOffset(at).takeIf { id in LEGACY_FIXED_IDS }
        if (fixed != null) {
            return Entry(
                id = id,
                city = "",
                country = null,
                zoneName = null,
                abbreviation = null,
                offset = text.offset(fixed.totalSeconds),
                offsetSeconds = fixed.totalSeconds,
            )
        }
        return regionEntry(canonical(id) ?: id, zone, at, text)
    }

    private val LEGACY_FIXED_IDS = TimezoneUtils.UNAMBIGUOUS_SHORT_IDS.keys

    private val entryCache = LinkedHashMap<Pair<Locale, Instant>, List<Entry>>()

    /**
     * Returns one entry per ICU canonical location that `ZoneId.of` accepts, plus the UTC row,
     * sorted by offset at [at], then city. Slow on first use per locale and instant: call it off
     * the main thread.
     */
    fun entries(locale: Locale, at: Instant): List<Entry> {
        val key = locale to at
        synchronized(entryCache) { entryCache[key]?.let { return it } }

        val text = Text(locale)
        val linked = linkedIds()
        val built = TimeZone.getAvailableIDs(TimeZone.SystemTimeZoneType.CANONICAL_LOCATION, null, null)
            .mapNotNull { id ->
                if (!isZoneId(id)) return@mapNotNull null
                regionEntry(id, ZoneId.of(id), at, text).copy(ids = linked[id] ?: listOf(id))
            }
            .plus(utcEntry(text))
        val collator = Collator.getInstance(locale)
        val sorted = built.sortedWith(compareBy<Entry> { it.offsetSeconds }.thenComparator { x, y ->
            collator.compare(x.city, y.city)
        })

        synchronized(entryCache) {
            if (entryCache.size >= 4) entryCache.remove(entryCache.keys.first())
            entryCache[key] = sorted
        }
        return sorted
    }

    /** Returns the entry in [entries] for [id] in any spelling, or null. */
    fun entryFor(entries: List<Entry>, id: String?): Entry? {
        if (id == null) return null
        if (isUtc(id)) return entries.firstOrNull { it.id == UTC_ID }
        if (id in LEGACY_FIXED_IDS) return null
        val c = canonical(id) ?: id
        return entries.firstOrNull { it.id == c }
    }

    // ---- search ----

    private val OFFSET_QUERY = Regex("""^(?:gmt|utc)?\s*([+-])\s*(\d{1,2})(?::?(\d{2}))?$""")
    private val UTC_TERMS = setOf("utc", "gmt", "z")

    /**
     * Returns the entries matching [query], best first, ignoring case and accents. Ranks: a UTC
     * term on the UTC row, exact city, exact country or country alias, the zone ICU gives for a
     * Windows id, city prefix, exact abbreviation, other zones of that Windows id, zone name
     * prefix, country or country alias prefix, a zone id, the offset, a city alias, then any word
     * of the city, zone name or country. Ties keep city order.
     */
    fun search(entries: List<Entry>, query: String, locale: Locale = Locale.getDefault()): List<Entry> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val queryOffset = parseOffset(q)
        // ICU matches Windows ids by exact case, so the query is matched to an entry's id first.
        val windowsPrimary = entries.firstNotNullOfOrNull { e -> e.windowsId?.takeIf { e.keys.windowsId == q } }
            ?.let { windowsZone(it) }?.id?.let { canonical(it) ?: it }
        val aliasZones = aliasIndex.filterValues { names -> names.any { it.startsWith(q) || startsAnyWord(it, q) } }.keys

        val collator = Collator.getInstance(locale)
        return entries.mapNotNull { e -> rank(e, q, queryOffset, windowsPrimary, aliasZones)?.let { it to e } }
            .sortedWith(compareBy<Pair<Int, Entry>> { it.first }.thenComparator { x, y ->
                collator.compare(x.second.city, y.second.city)
            })
            .map { it.second }
    }

    private fun rank(e: Entry, q: String, queryOffset: Int?, windowsPrimary: String?, aliasZones: Set<String>): Int? {
        val k = e.keys
        val city = k.city
        val zoneName = k.zoneName
        val country = k.country
        return when {
            e.id == UTC_ID && q in UTC_TERMS -> 0
            city == q -> 1
            country == q || q in k.countryAliases -> 2
            windowsPrimary != null && e.id == windowsPrimary -> 3
            city.startsWith(q) -> 4
            k.abbreviation == q -> 5
            k.windowsId == q -> 6
            zoneName.startsWith(q) -> 7
            country.startsWith(q) || k.countryAliases.any { it.startsWith(q) } -> 8
            k.ids.any { it.contains(q) } -> 9
            queryOffset != null && e.offsetSeconds == queryOffset -> 10
            e.id in aliasZones -> 11
            startsAnyWord(city, q) || startsAnyWord(zoneName, q) || startsAnyWord(country, q) -> 12
            else -> null
        }
    }

    private fun parseOffset(q: String): Int? {
        val m = OFFSET_QUERY.matchEntire(q) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        val hours = m.groupValues[2].toInt()
        val minutes = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (hours > 18 || minutes > 59) return null
        return sign * (hours * 3600 + minutes * 60)
    }

    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    private fun startsAnyWord(text: String, q: String) = text.split(' ').any { it.startsWith(q) }

    /** Lowercases, strips accents, and reads `_` and runs of spaces as one space. */
    private fun normalize(text: String): String =
        Normalizer.normalize(text.trim(), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
            .replace(SPACES, " ")

    // ---- building entries ----

    private fun canonical(id: String): String? = try {
        TimeZone.getCanonicalID(id)?.takeIf { it.isNotEmpty() && it != "Etc/Unknown" }
    } catch (_: Exception) {
        null
    }

    /** Every id `ZoneId` knows, grouped by its ICU canonical id, for id search. */
    private fun linkedIds(): Map<String, List<String>> =
        ZoneId.getAvailableZoneIds().groupBy { canonical(it) ?: it }

    /** Localized names and offset text for one locale. */
    private class Text(val locale: Locale) {
        val names: TimeZoneNames = TimeZoneNames.getInstance(locale)
        val format: TimeZoneFormat = TimeZoneFormat.getInstance(locale)
        private val regionNames = HashMap<String, TimeZoneNames?>()

        /** The country locale's names: abbreviations a country uses but its language doesn't. */
        fun regionNames(region: String): TimeZoneNames? = regionNames.getOrPut(region) {
            runCatching { TimeZoneNames.getInstance(Locale(locale.language, region)) }.getOrNull()
        }

        /**
         * The localized GMT offset. Below ICU 78 a zero offset formats as plain "GMT", so zero is
         * built from the locale's GMT pattern and positive offset pattern with zero digits, which
         * is what ICU 78 prints: "GMT+00:00", "UTC+00.00", Arabic-Indic digits in ar.
         */
        fun offset(seconds: Int): String {
            if (seconds != 0) return format.formatOffsetLocalizedGMT(seconds * 1000)
            val digits = format.gmtOffsetDigits.orEmpty()
            val zero = if (digits.isEmpty()) "0" else String(Character.toChars(digits.codePointAt(0)))
            val hm = format.getGMTOffsetPattern(TimeZoneFormat.GMTOffsetPatternType.POSITIVE_HM)
                .replace(Regex("H+"), zero + zero)
                .replace(Regex("m+"), zero + zero)
            return format.gmtPattern.replace("{0}", hm)
        }
    }

    private fun utcEntry(text: Text): Entry {
        val city = text.names.getDisplayName("Etc/UTC", TimeZoneNames.NameType.SHORT_STANDARD, 0L)
            ?.takeIf { it.isNotBlank() } ?: UTC_ID
        val zoneName = text.names.getDisplayName("Etc/UTC", TimeZoneNames.NameType.LONG_STANDARD, 0L)
            ?.takeIf { it.isNotBlank() && !it.equals(city, ignoreCase = true) }
        return Entry(
            id = UTC_ID,
            city = city,
            country = null,
            zoneName = zoneName,
            abbreviation = null,
            offset = text.offset(0),
            offsetSeconds = 0,
            ids = listOf("UTC", "Etc/UTC", "Etc/UCT", "GMT", "Etc/GMT"),
        )
    }

    private fun regionEntry(canonicalId: String, zone: ZoneId, at: Instant, text: Text): Entry {
        val millis = at.toEpochMilli()
        val seconds = zone.rules.getOffset(at).totalSeconds
        val daylight = zone.rules.isDaylightSavings(at)
        val offset = text.offset(seconds)
        val city = text.names.getExemplarLocationName(canonicalId)?.takeIf { it.isNotBlank() }
            ?: canonicalId.substringAfterLast('/').replace('_', ' ')
        val region = try {
            TimeZone.getRegion(canonicalId)
        } catch (_: Exception) {
            null
        }
        val country = region?.takeIf { it != "001" }
            ?.let { Locale("", it).getDisplayCountry(text.locale) }
            ?.takeIf { it.isNotBlank() && it != region }

        val shortType = if (daylight) TimeZoneNames.NameType.SHORT_DAYLIGHT else TimeZoneNames.NameType.SHORT_STANDARD
        val rawAbbreviation = (
            text.names.getDisplayName(canonicalId, shortType, millis)
                ?: region?.let { text.regionNames(it)?.getDisplayName(canonicalId, shortType, millis) }
            )?.takeIf { it.isNotBlank() }
        val abbreviation = rawAbbreviation?.takeIf { abbr ->
            abbr.isNotBlank() &&
                !abbr.equals(city, ignoreCase = true) &&
                !abbr.equals(offset, ignoreCase = true) &&
                !(seconds == 0 && (abbr.equals("GMT", ignoreCase = true) || abbr.equals("UTC", ignoreCase = true)))
        }

        val longType = if (daylight) TimeZoneNames.NameType.LONG_DAYLIGHT else TimeZoneNames.NameType.LONG_STANDARD
        val genericLocation = runCatching {
            text.format.format(TimeZoneFormat.Style.GENERIC_LOCATION, TimeZone.getTimeZone(canonicalId), millis)
        }.getOrNull()?.takeUnless { it == offset || it == text.format.formatOffsetLocalizedGMT(seconds * 1000) }
        val zoneName = (
            text.names.getDisplayName(canonicalId, TimeZoneNames.NameType.LONG_GENERIC, millis)
                ?: text.names.getDisplayName(canonicalId, longType, millis)
                ?: genericLocation
            )?.takeIf { it.isNotBlank() && !it.equals(city, ignoreCase = true) && !it.equals(offset, ignoreCase = true) }

        return Entry(
            id = canonicalId,
            city = city,
            country = country,
            zoneName = zoneName,
            abbreviation = abbreviation,
            offset = offset,
            offsetSeconds = seconds,
            windowsId = runCatching { TimeZone.getWindowsID(canonicalId) }.getOrNull(),
            region = region?.takeIf { it != "001" },
            searchAbbreviation = rawAbbreviation,
        )
    }

    // ---- city aliases ----

    /**
     * English names of major cities, by the zone they keep time with, so search finds them in any
     * locale. A city goes under its own zone when ICU lists one (Milan under Europe/Rome, not
     * Europe/Paris), so its row shows the right country; a city with its own row (Riyadh,
     * Edmonton, Brisbane, Adelaide, Accra, Monterrey) needs no alias. `ZoneCatalogTest` checks that
     * every key is a listed zone.
     */
    private val cityAliases = mapOf(
        "Asia/Kolkata" to listOf(
            "Mumbai", "Bombay", "New Delhi", "Delhi", "Bangalore", "Bengaluru", "Chennai", "Madras",
            "Hyderabad", "Pune", "Ahmedabad",
        ),
        "America/New_York" to listOf(
            "NYC", "Boston", "Philadelphia", "Atlanta", "Miami", "Orlando", "Washington DC", "Baltimore",
            "Charlotte", "Pittsburgh", "Cleveland",
        ),
        "America/Chicago" to listOf(
            "Dallas", "Houston", "Minneapolis", "St Louis", "New Orleans", "Austin", "San Antonio",
            "Nashville", "Kansas City", "Milwaukee", "Memphis", "Oklahoma City",
        ),
        "America/Denver" to listOf("Salt Lake City", "Albuquerque", "El Paso"),
        "America/Los_Angeles" to listOf(
            "LA", "San Francisco", "Seattle", "San Diego", "Portland", "Las Vegas", "San Jose", "Sacramento",
        ),
        "Europe/London" to listOf(
            "Edinburgh", "Glasgow", "Manchester", "Birmingham", "Liverpool", "Leeds", "Bristol", "Cardiff", "Belfast",
        ),
        "Europe/Paris" to listOf("Lyon", "Marseille", "Nice", "Toulouse"),
        "Europe/Madrid" to listOf("Barcelona", "Valencia", "Seville"),
        "Europe/Rome" to listOf("Milan", "Naples", "Florence", "Venice", "Turin"),
        "Europe/Berlin" to listOf("Munich", "Frankfurt", "Hamburg", "Cologne", "Stuttgart", "Dusseldorf"),
        "Asia/Shanghai" to listOf(
            "Beijing", "Peking", "Shenzhen", "Guangzhou", "Chengdu", "Hangzhou", "Wuhan", "Nanjing", "Xian",
        ),
        "Asia/Tokyo" to listOf("Osaka", "Kyoto", "Nagoya", "Yokohama", "Sapporo"),
        "Asia/Seoul" to listOf("Busan", "Incheon"),
        "Australia/Sydney" to listOf("Canberra"),
        "Asia/Dubai" to listOf("Abu Dhabi"),
        "Asia/Qatar" to listOf("Doha"),
        "Asia/Kuwait" to listOf("Kuwait City"),
        "America/Edmonton" to listOf("Calgary"),
        "Asia/Ho_Chi_Minh" to listOf("Hanoi", "Saigon"),
        "America/Sao_Paulo" to listOf("Rio de Janeiro", "Brasilia"),
        "America/Mexico_City" to listOf("Guadalajara"),
        "Africa/Johannesburg" to listOf("Cape Town", "Pretoria", "Durban"),
        "America/Toronto" to listOf("Montreal", "Ottawa", "Quebec City"),
        // Most of the province; its Mountain-time towns (Dawson Creek, Creston) have own rows.
        "America/Vancouver" to listOf("British Columbia"),
        "Europe/Moscow" to listOf("St Petersburg"),
        "Pacific/Tongatapu" to listOf("Nuku'alofa", "Nukualofa"),
    )

    /**
     * English country names people type that differ from the name ICU shows ("United Kingdom",
     * "Czechia"), by ISO 3166 region code.
     */
    private val countryAliases = mapOf(
        "GB" to listOf(
            "England", "Scotland", "Wales", "Northern Ireland", "Britain", "Great Britain", "British", "UK",
        ),
        "US" to listOf("USA", "US", "United States of America", "America"),
        "AE" to listOf("UAE"),
        "NL" to listOf("Holland", "The Netherlands"),
        "CZ" to listOf("Czech Republic"),
        "CI" to listOf("Ivory Coast"),
        "MM" to listOf("Burma"),
        "SZ" to listOf("Swaziland"),
        "TR" to listOf("Turkey"),
    )

    /** Returns the zones [cityAliases] names, as list ids. */
    internal fun aliasZoneIds(): Set<String> = aliasIndex.keys

    /**
     * [cityAliases], normalized and keyed by ICU canonical id, which is what entries carry
     * (Asia/Calcutta).
     */
    private val aliasIndex: Map<String, List<String>> by lazy {
        cityAliases.entries.associate { (id, names) -> (canonical(id) ?: id) to names.map(::normalize) }
    }
}
