package org.onekash.icaldav.parser

import org.onekash.icaldav.model.ICalDateTime
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Rewrites, in unfolded iCalendar text before ical4j reads it, two zone forms that would
 * otherwise be read in the device zone (#441):
 * - `TZID=UTC` on DTSTART, DTEND, DUE, RECURRENCE-ID, EXDATE and RDATE date-times: ical4j drops
 *   the parameter and returns floating time. The values get a `Z` instead, the form RFC 5545
 *   §3.2.19 requires ("MUST NOT be applied to DATE-TIME ... values specified in UTC").
 * - A TZID that [ICalDateTime.resolveTimezoneOrNull] can't resolve, such as the Windows display
 *   name `(UTC+08:00) Beijing, Chongqing, Hong Kong, Urumqi` that Exchange writes: every reference
 *   to it, and its VTIMEZONE's TZID line, is renamed to an IANA zone found, in order, from the
 *   Windows display name table ical4j ships, from the feed's own VTIMEZONE (a fixed offset, or
 *   one STANDARD and one DAYLIGHT observance with a yearly BYMONTH/BYDAY rule, the shape Exchange
 *   writes), or, when the feed has no VTIMEZONE for it (iCloud drops one whose TZID escapes
 *   commas), from the city names after a `(UTC±hh:mm)` prefix. A VTIMEZONE is the zone's
 *   definition (RFC 5545 §3.6.5): a name match must agree with it, and a TZID whose VTIMEZONE
 *   can't be read keeps its name.
 *
 * A zone that already resolves is never renamed. A zone no rule decides, or whose check throws,
 * keeps its name and is read in the device zone; if the rewrite itself throws, the text comes
 * back unchanged. The stored body is never changed: the parser feeds only this copy to ical4j.
 */
internal object TzidRewrite {

    private val DATE_PROPERTIES = setOf("DTSTART", "DTEND", "DUE", "RECURRENCE-ID", "EXDATE", "RDATE")
    private val DATE_TIME = Regex("""\d{8}T\d{6}Z?""")
    private val DISPLAY_NAME = Regex("""^\(UTC(?:([+-])(\d{2}):(\d{2}))?\)\s*(.*)$""")
    private val OFFSET = Regex("""^([+-])(\d{2})(\d{2})(\d{2})?$""")
    private val BYDAY = Regex("""^([+-]?\d)?(SU|MO|TU|WE|TH|FR|SA)$""")

    /** Zone ID prefixes that only alias a region zone; never picked as a match. */
    private val ALIAS_PREFIXES = listOf("US/", "Canada/", "Brazil/", "Mexico/", "Chile/", "SystemV/", "Etc/")

    private val DAYS = mapOf(
        "SU" to DayOfWeek.SUNDAY, "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY, "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
    )

    /** Returns [data] with the rewrites applied, or [data] itself when nothing applies. */
    fun apply(data: String): String = try {
        rewrite(data)
    } catch (_: Exception) {
        data
    }

    private fun rewrite(data: String): String {
        if (!data.contains("TZID", ignoreCase = true)) return data

        val lines = data.split("\n")

        val vtimezones = mutableMapOf<String, List<String>>()
        val vtimezoneTzidLines = mutableMapOf<Int, String>()
        val referenced = mutableSetOf<String>()
        val utcLines = mutableSetOf<Int>()

        var vtimezoneStart = -1
        for ((index, raw) in lines.withIndex()) {
            if (vtimezoneStart < 0 && !raw.contains("TZID", ignoreCase = true) &&
                !raw.startsWith("BEGIN:VTIMEZONE", ignoreCase = true)
            ) {
                continue
            }
            val content = raw.removeSuffix("\r")
            if (content.equals("BEGIN:VTIMEZONE", ignoreCase = true)) {
                vtimezoneStart = index
                continue
            }
            if (vtimezoneStart >= 0) {
                if (content.equals("END:VTIMEZONE", ignoreCase = true)) {
                    val body = lines.subList(vtimezoneStart + 1, index).map { it.removeSuffix("\r") }
                    val tzidIndex = (vtimezoneStart + 1 until index)
                        .firstOrNull { lines[it].startsWith("TZID:", ignoreCase = true) }
                    if (tzidIndex != null) {
                        val tzid = unescape(lines[tzidIndex].removeSuffix("\r").substring(5).trim())
                        vtimezones[tzid] = body
                        vtimezoneTzidLines[tzidIndex] = tzid
                    }
                    vtimezoneStart = -1
                }
                continue
            }
            val line = split(content) ?: continue
            val tzid = tzidOf(line) ?: continue
            referenced += tzid
            if (line.name.uppercase(Locale.ROOT) in DATE_PROPERTIES && tzid.equals("UTC", ignoreCase = true) &&
                line.value.split(",").all { DATE_TIME.matches(it.trim()) }
            ) {
                utcLines += index
            }
        }

        val renamed = mutableMapOf<String, String>()
        for (tzid in referenced) {
            if (tzid.equals("UTC", ignoreCase = true)) continue
            if (ICalDateTime.resolveTimezoneOrNull(tzid) != null) continue
            val zone = try {
                decide(tzid, vtimezones[tzid])
            } catch (_: Exception) {
                null
            }
            if (zone != null) renamed[tzid] = zone.id
        }
        if (renamed.isEmpty() && utcLines.isEmpty()) return data

        val out = StringBuilder(data.length + 64)
        for ((index, raw) in lines.withIndex()) {
            if (index > 0) out.append('\n')
            val rewritten = when {
                index in utcLines -> split(raw.removeSuffix("\r"))?.let { toUtc(it) }
                index in vtimezoneTzidLines -> renamed[vtimezoneTzidLines.getValue(index)]?.let { "TZID:$it" }
                renamed.isEmpty() || !raw.contains("TZID=", ignoreCase = true) -> null
                else -> split(raw.removeSuffix("\r"))?.let { line ->
                    tzidOf(line)?.let { renamed[it] }?.let { zone -> join(line.withTzid(zone)) }
                }
            }
            if (rewritten == null) {
                out.append(raw)
            } else {
                out.append(rewritten)
                if (raw.endsWith("\r")) out.append('\r')
            }
        }
        return out.toString()
    }

    // ---- content lines ----

    private class Line(val name: String, val params: List<String>, val value: String) {
        fun withTzid(zone: String?) = Line(
            name,
            params.mapNotNull { if (it.startsWith("TZID=", ignoreCase = true)) zone?.let { "TZID=$it" } else it },
            value,
        )
    }

    /** Splits a content line at the `;` and the first `:` outside quotes, or returns null. */
    private fun split(content: String): Line? {
        var inQuotes = false
        var nameEnd = -1
        val separators = mutableListOf<Int>()
        for (i in content.indices) {
            when (content[i]) {
                '"' -> inQuotes = !inQuotes
                ';' -> if (!inQuotes) {
                    if (nameEnd < 0) nameEnd = i
                    separators += i
                }
                ':' -> if (!inQuotes) {
                    if (nameEnd < 0) nameEnd = i
                    val params = separators.mapIndexed { k, start ->
                        content.substring(start + 1, separators.getOrElse(k + 1) { i })
                    }
                    return Line(content.substring(0, nameEnd), params, content.substring(i + 1))
                }
            }
        }
        return null
    }

    private fun join(line: Line) = buildString {
        append(line.name)
        line.params.forEach { append(';').append(it) }
        append(':').append(line.value)
    }

    private fun tzidOf(line: Line): String? = line.params
        .firstOrNull { it.startsWith("TZID=", ignoreCase = true) }
        ?.substring(5)?.trim()?.removeSurrounding("\"")

    private fun toUtc(line: Line): String = join(
        Line(
            line.name,
            line.withTzid(null).params,
            line.value.split(",").joinToString(",") { it.trim().let { v -> if (v.endsWith("Z")) v else "${v}Z" } },
        )
    )

    /** Undoes TEXT escapes in a VTIMEZONE's TZID value (`\,` `\;` `\\`). */
    private fun unescape(value: String) = value
        .replace("\\\\", "\u0000").replace("\\,", ",").replace("\\;", ";").replace("\u0000", "\\")

    // ---- deciding a zone ----

    /**
     * The years a candidate zone must match the feed's VTIMEZONE in. Exchange writes the zone's
     * current rule from 1601 on, so it describes this year and the next, not the years of old
     * events (US rules changed in 2007: a 2005 series would match no zone).
     */
    private fun ruleYears(): Set<Int> = LocalDate.now(ZoneOffset.UTC).year.let { setOf(it, it + 1) }

    private fun decide(tzid: String, vtimezone: List<String>?): ZoneId? {
        val name = DISPLAY_NAME.matchEntire(tzid)
        val prefixOffset = name?.let {
            if (it.groupValues[1].isEmpty()) {
                ZoneOffset.UTC
            } else {
                val sign = if (it.groupValues[1] == "-") -1 else 1
                ZoneOffset.ofHoursMinutes(sign * it.groupValues[2].toInt(), sign * it.groupValues[3].toInt())
            }
        }
        val feedZone = vtimezone?.let { readVtimezone(it) }
        if (vtimezone != null && feedZone == null) return null
        val years = ruleYears()
        val cities = name?.let { cityZones(it.groupValues[4]) }.orEmpty()

        windowsZone(tzid)?.let { zone ->
            val agrees = when {
                feedZone != null -> matches(zone, feedZone, years)
                prefixOffset != null -> zone.rules.getStandardOffset(Instant.now()) == prefixOffset
                else -> true
            }
            if (agrees) return zone
        }
        if (feedZone != null) return matchFeedZone(feedZone, years, cities)
        if (prefixOffset == null) return null
        return cities.firstOrNull { it.rules.getStandardOffset(Instant.now()) == prefixOffset }
    }

    private val matchCache = object : LinkedHashMap<Pair<FeedZone, Set<Int>>, ZoneId?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<FeedZone, Set<Int>>, ZoneId?>) = size > 64
    }

    /**
     * Returns, in order, a matching city zone, Etc/GMT∓N for a whole-hour fixed offset, a matching
     * zone the Windows table names (Europe/Berlin before Africa/Ceuta), then any matching region
     * zone in sorted order.
     */
    private fun matchFeedZone(feedZone: FeedZone, years: Set<Int>, cities: List<ZoneId>): ZoneId? {
        cities.firstOrNull { matches(it, feedZone, years) }?.let { return it }
        val key = feedZone to years
        synchronized(matchCache) {
            if (matchCache.containsKey(key)) return matchCache[key]
        }
        val found = fixedEtcZone(feedZone)
            ?: windowsZones().firstOrNull { matches(it, feedZone, years) }
            ?: regionZones.firstOrNull { matches(it, feedZone, years) }
        synchronized(matchCache) { matchCache[key] = found }
        return found
    }

    private fun fixedEtcZone(feedZone: FeedZone): ZoneId? {
        if (feedZone !is FeedZone.Fixed) return null
        val seconds = feedZone.offset.totalSeconds
        if (seconds % 3600 != 0) return null
        val hours = seconds / 3600
        if (hours == 0) return ZoneId.of("UTC")
        // Etc/GMT signs are inverted: Etc/GMT-8 is UTC+08:00.
        return runCatching { ZoneId.of("Etc/GMT${if (hours > 0) "-" else "+"}${kotlin.math.abs(hours)}") }.getOrNull()
    }

    private fun matches(zone: ZoneId, feedZone: FeedZone, years: Set<Int>): Boolean {
        val rules = zone.rules
        return years.all { year ->
            when (feedZone) {
                is FeedZone.Fixed -> {
                    val start = LocalDate.of(year, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val end = LocalDate.of(year + 1, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
                    val next = rules.nextTransition(start)
                    rules.getOffset(start) == feedZone.offset && (next == null || !next.instant.isBefore(end))
                }
                is FeedZone.Yearly -> listOf(feedZone.standard, feedZone.daylight).all { observance ->
                    val at = observance.transitionIn(year) ?: return@all false
                    rules.getOffset(at.minusSeconds(1)) == observance.from && rules.getOffset(at) == observance.to
                }
            }
        }
    }

    // ---- name tables ----

    /** Windows display name (normalized) to its Windows ID and the table's zone. */
    private val windowsDisplayNames: Map<String, Pair<String, String>> by lazy {
        val table = mutableMapOf<String, Pair<String, String>>()
        try {
            TzidRewrite::class.java.classLoader
                ?.getResourceAsStream("net/fortuna/ical4j/transform/compliance/msTimezones")
                ?.bufferedReader(Charsets.UTF_8)
                ?.useLines { lines ->
                    for (line in lines) {
                        // "Windows ID;display name=zone"; a display name holds ':' and spaces,
                        // so it is split by hand rather than read as a properties file.
                        val semicolon = line.indexOf(';')
                        val equals = line.lastIndexOf('=')
                        if (semicolon <= 0 || equals <= semicolon) continue
                        table.putIfAbsent(
                            normalize(line.substring(semicolon + 1, equals)),
                            line.substring(0, semicolon).trim() to line.substring(equals + 1).trim()
                        )
                    }
                }
        } catch (_: Exception) { /* unreadable: no display names */ }
        table
    }

    /** Ignores case, spaces, and `&` against `and` (Exchange sends "(US & Canada)"). */
    private fun normalize(name: String) = name.lowercase(Locale.ROOT).replace("&", "and").filterNot { it.isWhitespace() }

    private fun windowsZone(tzid: String): ZoneId? {
        val (windowsId, tableZone) = windowsDisplayNames[normalize(tzid)] ?: return null
        return ICalDateTime.resolveTimezoneOrNull(windowsId)
            ?: runCatching { ZoneId.of(tableZone) }.getOrNull()
    }

    /** The zones the Windows IDs resolve to now (with the app's resolver, CLDR's choices). */
    private fun windowsZones(): List<ZoneId> = windowsDisplayNames.values
        .mapNotNull { (windowsId, _) -> ICalDateTime.resolveTimezoneOrNull(windowsId) }
        .distinct()

    /** Region zones, sorted, without alias IDs, for rule matching and city names. */
    private val regionZones: List<ZoneId> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { id -> '/' in id && ALIAS_PREFIXES.none { id.startsWith(it) } }
            .sorted()
            .map { ZoneId.of(it) }
    }

    private val zonesByCity: Map<String, List<ZoneId>> by lazy {
        regionZones.groupBy { it.id.substringAfterLast('/').lowercase(Locale.ROOT) }
    }

    /** The zones named by the comma-separated cities in [names], in their order. */
    private fun cityZones(names: String): List<ZoneId> = names.split(",")
        .map { it.trim().replace(' ', '_').lowercase(Locale.ROOT) }
        .filter { it.isNotEmpty() }
        .flatMap { zonesByCity[it].orEmpty() }

    // ---- reading a VTIMEZONE ----

    private sealed interface FeedZone {
        data class Fixed(val offset: ZoneOffset) : FeedZone
        data class Yearly(val standard: Observance, val daylight: Observance) : FeedZone
    }

    private data class Observance(
        val from: ZoneOffset,
        val to: ZoneOffset,
        val month: Int,
        val week: Int,
        val day: DayOfWeek,
        val time: LocalTime,
    ) {
        /**
         * The change instant in [year]: the [week]th (or last, -1) [day] of [month], at [time]
         * in [from].
         */
        fun transitionIn(year: Int): Instant? {
            val first = LocalDate.of(year, month, 1)
            val date = if (week == -1) {
                first.with(TemporalAdjusters.lastInMonth(day))
            } else {
                first.with(TemporalAdjusters.firstInMonth(day)).plusWeeks((week - 1).toLong())
            }
            if (date.monthValue != month) return null
            return LocalDateTime.of(date, time).toInstant(from)
        }
    }

    private class RawObservance(val kind: String, val props: Map<String, String>) {
        val from = props["TZOFFSETFROM"]?.let(::offset)
        val to = props["TZOFFSETTO"]?.let(::offset)
    }

    /** Reads the two shapes Exchange writes; returns null for anything else. */
    private fun readVtimezone(body: List<String>): FeedZone? {
        val observances = mutableListOf<RawObservance>()
        var kind: String? = null
        var props = mutableMapOf<String, String>()
        for (content in body) {
            val upper = content.uppercase(Locale.ROOT)
            when {
                upper == "BEGIN:STANDARD" || upper == "BEGIN:DAYLIGHT" -> {
                    kind = upper.substringAfter(':')
                    props = mutableMapOf()
                }
                upper == "END:STANDARD" || upper == "END:DAYLIGHT" -> {
                    if (kind != null) observances += RawObservance(kind, props)
                    kind = null
                }
                kind != null -> {
                    val line = split(content) ?: continue
                    props[line.name.uppercase(Locale.ROOT)] = line.value.trim()
                }
            }
        }
        if (observances.isEmpty() || kind != null) return null
        if (observances.any { "RDATE" in it.props || it.from == null || it.to == null }) return null

        val offsets = observances.flatMap { listOf(it.from!!, it.to!!) }
        if (offsets.distinct().size == 1) return FeedZone.Fixed(offsets.first())

        val standard = observances.singleOrNull { it.kind == "STANDARD" } ?: return null
        val daylight = observances.singleOrNull { it.kind == "DAYLIGHT" } ?: return null
        return FeedZone.Yearly(observance(standard) ?: return null, observance(daylight) ?: return null)
    }

    private fun observance(raw: RawObservance): Observance? {
        val rule = raw.props["RRULE"] ?: return null
        val parts = rule.split(";").associate { part ->
            part.substringBefore('=').uppercase(Locale.ROOT) to part.substringAfter('=', "")
        }
        if (parts["FREQ"]?.uppercase(Locale.ROOT) != "YEARLY") return null
        if ((parts["INTERVAL"] ?: "1") != "1" || "COUNT" in parts || "UNTIL" in parts) return null
        val month = parts["BYMONTH"]?.toIntOrNull()?.takeIf { it in 1..12 } ?: return null
        val byDay = BYDAY.matchEntire(parts["BYDAY"]?.uppercase(Locale.ROOT) ?: return null) ?: return null
        val week = byDay.groupValues[1].ifEmpty { "1" }.removePrefix("+").toIntOrNull() ?: return null
        if (week != -1 && week !in 1..4) return null
        val start = raw.props["DTSTART"]?.takeIf { it.length >= 15 && it[8] == 'T' } ?: return null
        val time = LocalTime.of(start.substring(9, 11).toInt(), start.substring(11, 13).toInt(), start.substring(13, 15).toInt())
        return Observance(raw.from!!, raw.to!!, month, week, DAYS.getValue(byDay.groupValues[2]), time)
    }

    private fun offset(value: String): ZoneOffset? {
        val match = OFFSET.matchEntire(value.trim()) ?: return null
        val sign = if (match.groupValues[1] == "-") -1 else 1
        val (h, m, s) = listOf(match.groupValues[2], match.groupValues[3], match.groupValues[4].ifEmpty { "0" })
            .map { it.toInt() }
        return runCatching { ZoneOffset.ofHoursMinutesSeconds(sign * h, sign * m, sign * s) }.getOrNull()
    }
}
