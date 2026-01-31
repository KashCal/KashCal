package org.onekash.kashcal.sync.quirks

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.provider.icloud.ICloudQuirks
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.system.measureNanoTime

/**
 * Comparison test: Regex-based parsing vs XmlPullParser.
 *
 * XmlPullParser is Android's recommended XML parser:
 * - Streaming/pull-based (low memory)
 * - Handles namespaces properly
 * - Automatic entity decoding
 * - Available in unit tests via org.xmlpull.v1
 *
 * Reference: https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
 *
 * Run with: ./gradlew :app:testDebugUnitTest --tests "*XmlParserComparisonTest*"
 */
class XmlParserComparisonTest {

    private lateinit var regexParser: ICloudQuirks
    private lateinit var defaultQuirks: DefaultQuirks
    private lateinit var pullParser: XmlPullParserImpl

    @Before
    fun setup() {
        regexParser = ICloudQuirks()
        defaultQuirks = DefaultQuirks("https://example.com")
        pullParser = XmlPullParserImpl()
    }

    // ==================== Principal URL Extraction ====================

    @Test
    fun `compare principal URL extraction - iCloud fixture`() {
        val xml = loadResource("caldav/icloud/01_current_user_principal.xml")

        val regexResult = regexParser.extractPrincipalUrl(xml)
        val pullResult = pullParser.extractPrincipalUrl(xml)

        println("=== Principal URL Extraction (iCloud) ===")
        println("Regex result:      $regexResult")
        println("XmlPullParser:     $pullResult")

        assertEquals("Both parsers should return same principal URL", pullResult, regexResult)
    }

    @Test
    fun `compare principal URL extraction - Nextcloud fixture`() {
        val xml = loadResource("caldav/nextcloud/01_current_user_principal.xml")

        val regexResult = defaultQuirks.extractPrincipalUrl(xml)
        val pullResult = pullParser.extractPrincipalUrl(xml)

        println("=== Principal URL Extraction (Nextcloud) ===")
        println("Regex result:      $regexResult")
        println("XmlPullParser:     $pullResult")

        assertEquals("Both parsers should return same principal URL", pullResult, regexResult)
    }

    // ==================== Calendar Home URL Extraction ====================

    @Test
    fun `compare calendar home URL extraction - iCloud fixture`() {
        val xml = loadResource("caldav/icloud/02_calendar_home_set.xml")

        val regexResult = regexParser.extractCalendarHomeUrl(xml)
        val pullResult = pullParser.extractCalendarHomeUrl(xml)

        println("=== Calendar Home URL Extraction (iCloud) ===")
        println("Regex result:      $regexResult")
        println("XmlPullParser:     $pullResult")

        assertEquals("Both parsers should return same calendar home URL", pullResult, regexResult)
    }

    // ==================== Calendar List Extraction ====================

    @Test
    fun `compare calendar list extraction - iCloud fixture`() {
        val xml = loadResource("caldav/icloud/03_calendar_list.xml")
        val baseHost = "https://p180-caldav.icloud.com:443"

        val regexCalendars = regexParser.extractCalendars(xml, baseHost)
        val pullCalendars = pullParser.extractCalendars(xml)

        println("=== Calendar List Extraction (iCloud) ===")
        println("Regex found ${regexCalendars.size} calendars:")
        regexCalendars.forEach {
            println("  - ${it.displayName}")
            println("    href: ${it.href}")
            println("    color: ${it.color}")
            println("    ctag: ${it.ctag?.take(30)}...")
        }
        println("\nXmlPullParser found ${pullCalendars.size} calendars:")
        pullCalendars.forEach {
            println("  - ${it.displayName}")
            println("    href: ${it.href}")
            println("    color: ${it.color}")
            println("    ctag: ${it.ctag?.take(30)}...")
        }

        assertEquals("Same number of calendars", regexCalendars.size, pullCalendars.size)

        // Compare each calendar
        regexCalendars.forEachIndexed { i, regex ->
            val pull = pullCalendars[i]
            assertEquals("href match", regex.href, pull.href)
            assertEquals("displayName match", regex.displayName, pull.displayName)
            assertEquals("color match", regex.color, pull.color)
            assertEquals("ctag match", regex.ctag, pull.ctag)
        }
    }

    @Test
    fun `compare calendar list extraction - Nextcloud fixture`() {
        val xml = loadResource("caldav/nextcloud/03_calendar_list.xml")
        val baseHost = "https://nextcloud.example.com"

        val regexCalendars = defaultQuirks.extractCalendars(xml, baseHost)
        val pullCalendars = pullParser.extractCalendars(xml)

        println("=== Calendar List Extraction (Nextcloud) ===")
        println("Regex found ${regexCalendars.size} calendars:")
        regexCalendars.forEach { println("  - ${it.displayName} (${it.href})") }
        println("XmlPullParser found ${pullCalendars.size} calendars:")
        pullCalendars.forEach { println("  - ${it.displayName} (${it.href})") }

        assertEquals("Same number of calendars", regexCalendars.size, pullCalendars.size)
    }

    // ==================== Sync Token Extraction ====================

    @Test
    fun `compare sync token extraction - Nextcloud fixture`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")

        val regexResult = defaultQuirks.extractSyncToken(xml)
        val pullResult = pullParser.extractSyncToken(xml)

        println("=== Sync Token Extraction ===")
        println("Regex result:      $regexResult")
        println("XmlPullParser:     $pullResult")

        assertEquals("Both parsers should return same sync token", pullResult, regexResult)
    }

    // ==================== Ctag Extraction ====================

    @Test
    fun `compare ctag extraction - iCloud fixture`() {
        val xml = loadResource("caldav/icloud/03_calendar_list.xml")

        val regexResult = regexParser.extractCtag(xml)
        val pullResult = pullParser.extractCtag(xml)

        println("=== Ctag Extraction (first ctag in document) ===")
        println("Regex result:      $regexResult")
        println("XmlPullParser:     $pullResult")

        assertEquals("Both parsers should return same ctag", pullResult, regexResult)
    }

    // ==================== Event Data with Etag Extraction ====================

    @Test
    fun `compare iCal data extraction - Nextcloud fixture`() {
        val xml = loadResource("caldav/nextcloud/04_event_report.xml")

        val regexEvents = defaultQuirks.extractICalData(xml)
        val pullEvents = pullParser.extractICalData(xml)

        println("=== iCal Data Extraction (Nextcloud) ===")
        println("Regex found ${regexEvents.size} events:")
        regexEvents.forEach {
            println("  - ${it.href}")
            println("    etag: ${it.etag}")
            println("    ical: ${it.icalData.take(50)}...")
        }
        println("\nXmlPullParser found ${pullEvents.size} events:")
        pullEvents.forEach {
            println("  - ${it.href}")
            println("    etag: ${it.etag}")
            println("    ical: ${it.icalData.take(50)}...")
        }

        assertEquals("Same number of events", regexEvents.size, pullEvents.size)

        // Compare etags (critical for sync)
        regexEvents.forEachIndexed { i, regex ->
            val pull = pullEvents[i]
            println("\nComparing event ${i + 1}:")
            println("  Regex etag: '${regex.etag}'")
            println("  Pull etag:  '${pull.etag}'")
            assertEquals("etag match for ${regex.href}", regex.etag, pull.etag)
        }
    }

    // ==================== Changed Items (href + etag pairs) ====================

    @Test
    fun `compare changed items extraction - Nextcloud sync collection`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")

        val regexItems = defaultQuirks.extractChangedItems(xml)
        val pullItems = pullParser.extractChangedItems(xml)

        println("=== Changed Items Extraction (sync-collection) ===")
        println("Regex found ${regexItems.size} changed items:")
        regexItems.forEach { println("  - ${it.first} (etag: ${it.second})") }
        println("\nXmlPullParser found ${pullItems.size} changed items:")
        pullItems.forEach { println("  - ${it.first} (etag: ${it.second})") }

        assertEquals("Same number of changed items", regexItems.size, pullItems.size)

        regexItems.forEachIndexed { i, regex ->
            val pull = pullItems[i]
            assertEquals("href match", regex.first, pull.first)
            assertEquals("etag match for ${regex.first}", regex.second, pull.second)
        }
    }

    // ==================== Deleted Hrefs ====================

    @Test
    fun `compare deleted hrefs extraction - Nextcloud sync collection`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")

        val regexDeleted = defaultQuirks.extractDeletedHrefs(xml)
        val pullDeleted = pullParser.extractDeletedHrefs(xml)

        println("=== Deleted Hrefs Extraction ===")
        println("Regex found ${regexDeleted.size} deleted:")
        regexDeleted.forEach { println("  - $it") }
        println("XmlPullParser found ${pullDeleted.size} deleted:")
        pullDeleted.forEach { println("  - $it") }

        assertEquals("Same number of deleted items", regexDeleted.size, pullDeleted.size)
        assertEquals("Same deleted hrefs", regexDeleted.toSet(), pullDeleted.toSet())
    }

    // ==================== XML Entity Decoding ====================

    @Test
    fun `compare XML entity decoding in etag`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/user/event.ics</href>
                    <propstat>
                        <prop>
                            <getetag>&quot;abc123&quot;</getetag>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val regexItems = defaultQuirks.extractChangedItems(xml)
        val pullItems = pullParser.extractChangedItems(xml)

        println("=== XML Entity Decoding in Etag ===")
        println("Input etag: &quot;abc123&quot;")
        println("Regex etag: ${regexItems.firstOrNull()?.second}")
        println("Pull etag:  ${pullItems.firstOrNull()?.second}")

        // Both should decode &quot; to "
        assertEquals("abc123", regexItems.firstOrNull()?.second)
        assertEquals("abc123", pullItems.firstOrNull()?.second)
    }

    @Test
    fun `compare XML entity decoding in displayname`() {
        val xml = """
            <multistatus xmlns="DAV:">
                <response>
                    <href>/calendars/user/work/</href>
                    <propstat>
                        <prop>
                            <displayname>Work &amp; Personal &lt;2024&gt;</displayname>
                            <resourcetype><collection/><calendar xmlns="urn:ietf:params:xml:ns:caldav"/></resourcetype>
                        </prop>
                        <status>HTTP/1.1 200 OK</status>
                    </propstat>
                </response>
            </multistatus>
        """.trimIndent()

        val regexCalendars = regexParser.extractCalendars(xml, "https://example.com")
        val pullCalendars = pullParser.extractCalendars(xml)

        println("=== XML Entity Decoding in DisplayName ===")
        println("Input: Work &amp; Personal &lt;2024&gt;")
        println("Regex displayName: ${regexCalendars.firstOrNull()?.displayName}")
        println("Pull displayName:  ${pullCalendars.firstOrNull()?.displayName}")

        // XmlPullParser auto-decodes, regex may not
        assertEquals("Work & Personal <2024>", pullCalendars.firstOrNull()?.displayName)

        // Document regex behavior
        val regexName = regexCalendars.firstOrNull()?.displayName
        if (regexName != "Work & Personal <2024>") {
            println("WARNING: Regex does NOT decode XML entities in displayName!")
            println("  Expected: Work & Personal <2024>")
            println("  Got:      $regexName")
        }
    }

    // ==================== Performance Comparison ====================

    @Test
    fun `benchmark parsing performance - calendar list`() {
        val xml = loadResource("caldav/icloud/03_calendar_list.xml")
        val baseHost = "https://p180-caldav.icloud.com:443"
        val iterations = 100

        // Warmup
        repeat(10) {
            regexParser.extractCalendars(xml, baseHost)
            pullParser.extractCalendars(xml)
        }

        // Measure regex
        val regexTimeNs = measureNanoTime {
            repeat(iterations) {
                regexParser.extractCalendars(xml, baseHost)
            }
        }

        // Measure XmlPullParser
        val pullTimeNs = measureNanoTime {
            repeat(iterations) {
                pullParser.extractCalendars(xml)
            }
        }

        val regexAvgMs = regexTimeNs / iterations / 1_000_000.0
        val pullAvgMs = pullTimeNs / iterations / 1_000_000.0

        println("=== Performance Benchmark (Calendar List, $iterations iterations) ===")
        println("XML size: ${xml.length} chars")
        println("Regex avg:         %.3f ms".format(regexAvgMs))
        println("XmlPullParser avg: %.3f ms".format(pullAvgMs))
        println("Ratio: %.2fx (%s)".format(
            if (regexAvgMs < pullAvgMs) pullAvgMs / regexAvgMs else regexAvgMs / pullAvgMs,
            if (regexAvgMs < pullAvgMs) "regex faster" else "XmlPullParser faster"
        ))
    }

    @Test
    fun `benchmark parsing performance - event report with etags`() {
        val xml = loadResource("caldav/nextcloud/04_event_report.xml")
        val iterations = 100

        // Warmup
        repeat(10) {
            defaultQuirks.extractICalData(xml)
            pullParser.extractICalData(xml)
        }

        // Measure regex
        val regexTimeNs = measureNanoTime {
            repeat(iterations) {
                defaultQuirks.extractICalData(xml)
            }
        }

        // Measure XmlPullParser
        val pullTimeNs = measureNanoTime {
            repeat(iterations) {
                pullParser.extractICalData(xml)
            }
        }

        val regexAvgMs = regexTimeNs / iterations / 1_000_000.0
        val pullAvgMs = pullTimeNs / iterations / 1_000_000.0

        println("=== Performance Benchmark (Event Report + Etags, $iterations iterations) ===")
        println("XML size: ${xml.length} chars")
        println("Regex avg:         %.3f ms".format(regexAvgMs))
        println("XmlPullParser avg: %.3f ms".format(pullAvgMs))
        println("Ratio: %.2fx (%s)".format(
            if (regexAvgMs < pullAvgMs) pullAvgMs / regexAvgMs else regexAvgMs / pullAvgMs,
            if (regexAvgMs < pullAvgMs) "regex faster" else "XmlPullParser faster"
        ))
    }

    @Test
    fun `benchmark parsing performance - sync collection`() {
        val xml = loadResource("caldav/nextcloud/05_sync_collection.xml")
        val iterations = 100

        // Warmup
        repeat(10) {
            defaultQuirks.extractChangedItems(xml)
            defaultQuirks.extractDeletedHrefs(xml)
            defaultQuirks.extractSyncToken(xml)
            pullParser.extractChangedItems(xml)
            pullParser.extractDeletedHrefs(xml)
            pullParser.extractSyncToken(xml)
        }

        // Measure regex (all three operations)
        val regexTimeNs = measureNanoTime {
            repeat(iterations) {
                defaultQuirks.extractChangedItems(xml)
                defaultQuirks.extractDeletedHrefs(xml)
                defaultQuirks.extractSyncToken(xml)
            }
        }

        // Measure XmlPullParser (single pass extracts all)
        val pullTimeNs = measureNanoTime {
            repeat(iterations) {
                pullParser.extractSyncCollectionData(xml)
            }
        }

        val regexAvgMs = regexTimeNs / iterations / 1_000_000.0
        val pullAvgMs = pullTimeNs / iterations / 1_000_000.0

        println("=== Performance Benchmark (Sync Collection - 3 extractions, $iterations iterations) ===")
        println("XML size: ${xml.length} chars")
        println("Regex avg (3 passes):      %.3f ms".format(regexAvgMs))
        println("XmlPullParser avg (1 pass): %.3f ms".format(pullAvgMs))
        println("Ratio: %.2fx (%s)".format(
            if (regexAvgMs < pullAvgMs) pullAvgMs / regexAvgMs else regexAvgMs / pullAvgMs,
            if (regexAvgMs < pullAvgMs) "regex faster" else "XmlPullParser faster"
        ))
    }

    // ==================== Helpers ====================

    private fun loadResource(path: String): String {
        return javaClass.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }

    /**
     * XmlPullParser-based CalDAV XML parser.
     *
     * Uses Android's recommended streaming XML parser.
     * Reference: https://developer.android.com/reference/org/xmlpull/v1/XmlPullParser
     *
     * Benefits over regex:
     * - Proper namespace handling
     * - Automatic XML entity decoding (&amp; → &)
     * - Single-pass extraction (more efficient for multiple fields)
     * - Validates XML structure
     */
    class XmlPullParserImpl {
        private val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = true
        }

        fun extractPrincipalUrl(xml: String): String? {
            val parser = createParser(xml)
            var inPrincipal = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "current-user-principal") {
                            inPrincipal = true
                        } else if (inPrincipal && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "current-user-principal") {
                            inPrincipal = false
                        }
                    }
                }
                parser.next()
            }
            return null
        }

        fun extractCalendarHomeUrl(xml: String): String? {
            val parser = createParser(xml)
            var inHomeSet = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = true
                        } else if (inHomeSet && parser.name == "href") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "calendar-home-set") {
                            inHomeSet = false
                        }
                    }
                }
                parser.next()
            }
            return null
        }

        fun extractSyncToken(xml: String): String? {
            val parser = createParser(xml)

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "sync-token") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                }
                parser.next()
            }
            return null
        }

        fun extractCtag(xml: String): String? {
            val parser = createParser(xml)

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "getctag") {
                            parser.next()
                            if (parser.eventType == XmlPullParser.TEXT) {
                                return parser.text.trim()
                            }
                        }
                    }
                }
                parser.next()
            }
            return null
        }

        data class ParsedCalendar(
            val href: String,
            val displayName: String,
            val color: String?,
            val ctag: String?,
            val isReadOnly: Boolean = false
        )

        fun extractCalendars(xml: String): List<ParsedCalendar> {
            val parser = createParser(xml)
            val calendars = mutableListOf<ParsedCalendar>()

            var inResponse = false
            var inPropstat = false
            var inResourceType = false
            var currentHref: String? = null
            var currentDisplayName: String? = null
            var currentColor: String? = null
            var currentCtag: String? = null
            var isCalendar = false
            var statusOk = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentDisplayName = null
                                currentColor = null
                                currentCtag = null
                                isCalendar = false
                                statusOk = false
                            }
                            "propstat" -> inPropstat = true
                            "resourcetype" -> inResourceType = true
                            "calendar" -> if (inResourceType) isCalendar = true
                            "href" -> if (inResponse && !inPropstat && currentHref == null) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentHref = parser.text.trim()
                                }
                            }
                            "displayname" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentDisplayName = parser.text.trim().takeIf { it.isNotBlank() }
                                }
                            }
                            "calendar-color" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentColor = parser.text.trim().takeIf { it.isNotBlank() }
                                }
                            }
                            "getctag" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentCtag = parser.text.trim().takeIf { it.isNotBlank() }
                                }
                            }
                            "status" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    statusOk = parser.text.contains("200")
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                if (isCalendar && currentHref != null && statusOk) {
                                    val href = currentHref!!
                                    val name = currentDisplayName ?: "Unnamed"

                                    // Skip inbox/outbox/notification/tasks
                                    val hrefLower = href.lowercase()
                                    val nameLower = name.lowercase()
                                    if (!hrefLower.contains("inbox") &&
                                        !hrefLower.contains("outbox") &&
                                        !hrefLower.contains("notification") &&
                                        !nameLower.contains("tasks") &&
                                        !nameLower.contains("reminders")) {
                                        calendars.add(ParsedCalendar(
                                            href = href,
                                            displayName = name,
                                            color = currentColor,
                                            ctag = currentCtag
                                        ))
                                    }
                                }
                                inResponse = false
                            }
                            "propstat" -> inPropstat = false
                            "resourcetype" -> inResourceType = false
                        }
                    }
                }
                parser.next()
            }

            return calendars
        }

        data class ParsedEventData(
            val href: String,
            val etag: String?,
            val icalData: String
        )

        fun extractICalData(xml: String): List<ParsedEventData> {
            val parser = createParser(xml)
            val events = mutableListOf<ParsedEventData>()

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var currentIcalData: String? = null

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                currentIcalData = null
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentHref = parser.text.trim()
                                }
                            }
                            "getetag" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentEtag = normalizeEtag(parser.text.trim())
                                }
                            }
                            "calendar-data" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT ||
                                    parser.eventType == XmlPullParser.CDSECT) {
                                    currentIcalData = parser.text.trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (currentHref != null && currentIcalData != null &&
                                currentIcalData!!.contains("BEGIN:VCALENDAR")) {
                                events.add(ParsedEventData(
                                    href = currentHref!!,
                                    etag = currentEtag,
                                    icalData = currentIcalData!!
                                ))
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            return events
        }

        fun extractChangedItems(xml: String): List<Pair<String, String?>> {
            val parser = createParser(xml)
            val items = mutableListOf<Pair<String, String?>>()

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentHref = parser.text.trim()
                                }
                            }
                            "getetag" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentEtag = normalizeEtag(parser.text.trim())
                                }
                            }
                            "status" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    if (parser.text.contains("404")) {
                                        isDeleted = true
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (!isDeleted && currentHref != null && currentHref!!.endsWith(".ics")) {
                                items.add(Pair(currentHref!!, currentEtag))
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            return items
        }

        fun extractDeletedHrefs(xml: String): List<String> {
            val parser = createParser(xml)
            val deleted = mutableListOf<String>()

            var inResponse = false
            var currentHref: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentHref = parser.text.trim()
                                }
                            }
                            "status" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    if (parser.text.contains("404")) {
                                        isDeleted = true
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (isDeleted && currentHref != null) {
                                deleted.add(currentHref!!)
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            return deleted
        }

        /**
         * Single-pass extraction of all sync-collection data.
         * More efficient than 3 separate regex passes.
         */
        data class SyncCollectionData(
            val syncToken: String?,
            val changedItems: List<Pair<String, String?>>,
            val deletedHrefs: List<String>
        )

        fun extractSyncCollectionData(xml: String): SyncCollectionData {
            val parser = createParser(xml)
            val changedItems = mutableListOf<Pair<String, String?>>()
            val deletedHrefs = mutableListOf<String>()
            var syncToken: String? = null

            var inResponse = false
            var currentHref: String? = null
            var currentEtag: String? = null
            var isDeleted = false

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "response" -> {
                                inResponse = true
                                currentHref = null
                                currentEtag = null
                                isDeleted = false
                            }
                            "href" -> if (inResponse && currentHref == null) {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentHref = parser.text.trim()
                                }
                            }
                            "getetag" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    currentEtag = normalizeEtag(parser.text.trim())
                                }
                            }
                            "status" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    if (parser.text.contains("404")) {
                                        isDeleted = true
                                    }
                                }
                            }
                            "sync-token" -> {
                                parser.next()
                                if (parser.eventType == XmlPullParser.TEXT) {
                                    syncToken = parser.text.trim()
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response") {
                            if (currentHref != null) {
                                if (isDeleted) {
                                    deletedHrefs.add(currentHref!!)
                                } else if (currentHref!!.endsWith(".ics")) {
                                    changedItems.add(Pair(currentHref!!, currentEtag))
                                }
                            }
                            inResponse = false
                        }
                    }
                }
                parser.next()
            }

            return SyncCollectionData(syncToken, changedItems, deletedHrefs)
        }

        private fun createParser(xml: String): XmlPullParser {
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            return parser
        }

        private fun normalizeEtag(etag: String): String {
            // Remove surrounding quotes and W/ prefix
            var result = etag
            if (result.startsWith("W/")) {
                result = result.substring(2)
            }
            if (result.startsWith("\"") && result.endsWith("\"")) {
                result = result.substring(1, result.length - 1)
            }
            return result
        }
    }
}
