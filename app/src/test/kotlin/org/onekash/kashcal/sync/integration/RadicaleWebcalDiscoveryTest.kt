package org.onekash.kashcal.sync.integration

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.onekash.kashcal.sync.integration.multiserver.CalDavServerConfig
import org.onekash.kashcal.sync.integration.multiserver.CalDavTestServerLoader
import org.onekash.kashcal.sync.integration.multiserver.ServerCredentials
import org.onekash.kashcal.sync.parser.CalDavXmlParser
import java.util.concurrent.TimeUnit

/**
 * Integration test for webcal (CS:subscribed) discovery on Radicale.
 *
 * Tests the full discovery flow with multiple subscribed collections:
 * 1. Parse subscribed collections from PROPFIND with <cs:source/>
 * 2. Extract source URLs, display names, colors
 * 3. Handle edge cases: empty source, webcal:// scheme, duplicate URLs
 * 4. Verify regular calendars are unaffected
 *
 * Prerequisites:
 * - Radicale running at localhost:5232 with testuser1
 * - Multiple VSUBSCRIBED collections created (see WebcalDiscoveryProbeTest)
 *
 * Run: ./gradlew testDebugUnitTest -Pintegration --tests "*RadicaleWebcalDiscoveryTest*"
 */
class RadicaleWebcalDiscoveryTest {

    private val config = CalDavServerConfig.RADICALE
    private lateinit var creds: ServerCredentials
    private lateinit var okhttp: OkHttpClient
    private val xmlParser = CalDavXmlParser()

    companion object {
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        private val PROPFIND_WITH_SOURCE = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"
                        xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <ic:calendar-color/>
                    <cs:getctag/>
                    <d:current-user-privilege-set/>
                    <c:supported-calendar-component-set/>
                    <cs:source/>
                </d:prop>
            </d:propfind>
        """.trimIndent()
    }

    @Before
    fun setup() {
        val loaded = CalDavTestServerLoader.loadCredentials(config)
        assumeTrue("No Radicale credentials", loaded != null)
        creds = loaded!!
        assumeTrue(
            "Radicale not reachable",
            CalDavTestServerLoader.isServerReachable(creds.serverUrl)
        )

        okhttp = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Core test: parse subscribed collections and extract source URLs from live Radicale.
     */
    @Test
    fun `discovers subscribed collections with source URLs from Radicale`() = runBlocking {
        val (client, _) = CalDavTestServerLoader.createClient(config)!!

        // Step 1: Discover calendar home
        val principal = client.discoverPrincipal(creds.davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        // Step 2: Send PROPFIND with <cs:source/> via raw OkHttp
        val xml = sendPropfind(home!!)
        assert(xml != null) { "PROPFIND failed" }

        // Step 3: Parse with existing parser — only regular calendars
        val quirks = config.quirksFactory(creds.serverUrl)
        val baseHost = extractBaseHost(home)
        val regularCalendars = quirks.extractCalendars(xml!!, baseHost)

        println("\n=== Regular calendars (existing parser): ${regularCalendars.size} ===")
        regularCalendars.forEach { cal ->
            println("  - ${cal.displayName} | ${cal.href}")
        }

        // Step 4: Parse subscribed collections from raw XML
        val subscribed = parseSubscribedCollections(xml)

        println("\n=== Subscribed collections: ${subscribed.size} ===")
        subscribed.forEach { sub ->
            println("  - ${sub.displayName} | source=${sub.sourceUrl} | color=${sub.color}")
        }

        // Verify: regular calendar not in subscribed list
        val regularHrefs = regularCalendars.map { it.href }.toSet()
        val subscribedHrefs = subscribed.map { it.href }.toSet()
        assert(regularHrefs.intersect(subscribedHrefs).isEmpty()) {
            "Regular calendars should not appear in subscribed list"
        }

        // Verify: at least one subscribed collection found
        assert(subscribed.isNotEmpty()) {
            "Expected at least one CS:subscribed collection on Radicale test server"
        }

        // Verify: at least one has a valid source URL
        val withSource = subscribed.filter { it.sourceUrl != null }
        assert(withSource.isNotEmpty()) {
            "Expected at least one subscribed collection with a source URL"
        }

        println("\n  RESULT: ${regularCalendars.size} regular, ${subscribed.size} subscribed (${withSource.size} with source URL)")
    }

    /**
     * Simulates the full KashCal discovery-to-subscription flow:
     * 1. Discover calendars (regular + subscribed)
     * 2. Filter subscribed with valid source URLs
     * 3. Normalize URLs (webcal:// → https://)
     * 4. Deduplicate by normalized URL
     * 5. Simulate IcsSubscriptionRepository.addSubscription() calls
     */
    @Test
    fun `full discovery-to-subscription flow with dedup and normalization`() = runBlocking {
        val (client, _) = CalDavTestServerLoader.createClient(config)!!

        val principal = client.discoverPrincipal(creds.davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val xml = sendPropfind(home!!)
        assert(xml != null) { "PROPFIND failed" }

        // Parse all subscribed collections
        val allSubscribed = parseSubscribedCollections(xml!!)

        println("\n=== All subscribed collections: ${allSubscribed.size} ===")
        allSubscribed.forEach { sub ->
            println("  - ${sub.displayName} | source=${sub.sourceUrl} | color=${sub.color}")
        }

        // Step 1: Filter out collections with no source URL (edge case: empty href)
        val withSource = allSubscribed.filter { !it.sourceUrl.isNullOrBlank() }

        println("\n=== After filtering empty source: ${withSource.size} ===")
        withSource.forEach { sub ->
            println("  - ${sub.displayName} | source=${sub.sourceUrl}")
        }

        // Step 2: Normalize URLs (webcal:// → https://)
        val normalized = withSource.map { sub ->
            sub.copy(sourceUrl = normalizeUrl(sub.sourceUrl!!))
        }

        println("\n=== After URL normalization: ===")
        normalized.forEach { sub ->
            println("  - ${sub.displayName} | normalized=${sub.sourceUrl}")
        }

        // Verify webcal:// was normalized
        val webcalOriginal = withSource.find { it.sourceUrl!!.startsWith("webcal://") }
        if (webcalOriginal != null) {
            val webcalNormalized = normalized.find { it.href == webcalOriginal.href }
            assert(webcalNormalized!!.sourceUrl!!.startsWith("https://")) {
                "webcal:// should be normalized to https://"
            }
            println("\n  webcal:// normalization: ${webcalOriginal.sourceUrl} → ${webcalNormalized.sourceUrl}")
        }

        // Step 3: Deduplicate by normalized URL (first wins)
        val seen = mutableSetOf<String>()
        val deduped = normalized.filter { sub ->
            seen.add(sub.sourceUrl!!)
        }

        println("\n=== After deduplication: ${deduped.size} (from ${normalized.size}) ===")
        deduped.forEach { sub ->
            println("  - ${sub.displayName} | ${sub.sourceUrl}")
        }

        val duplicateCount = normalized.size - deduped.size
        println("\n  Duplicates removed: $duplicateCount")

        // Step 4: Simulate the addSubscription() calls
        println("\n=== Simulated IcsSubscriptionRepository.addSubscription() calls ===")
        val existingUrls = mutableSetOf<String>() // Simulates IcsSubscriptionsDao.urlExists()
        var created = 0
        var skippedDuplicate = 0

        for (sub in deduped) {
            val url = sub.sourceUrl!!
            if (existingUrls.contains(url)) {
                println("  SKIP (already exists): ${sub.displayName} → $url")
                skippedDuplicate++
            } else {
                existingUrls.add(url)
                val color = parseColor(sub.color)
                println("  CREATE: ${sub.displayName} → $url (color=$color)")
                created++
            }
        }

        println("\n  RESULT: $created subscriptions created, $skippedDuplicate skipped (duplicate URL)")
        println("  Total subscribed on server: ${allSubscribed.size}")
        println("  Filtered (no source): ${allSubscribed.size - withSource.size}")
        println("  Deduped: $duplicateCount")

        // Assertions
        assert(created > 0) { "Expected at least one subscription to be created" }
        assert(allSubscribed.size > deduped.size) {
            "Expected some collections to be filtered or deduped (empty source or duplicate URL)"
        }
    }

    /**
     * Verifies that adding <cs:source/> doesn't change the regular calendar list.
     * Same as WebcalDiscoveryProbeTest but focused on Radicale with multiple subscribed collections.
     */
    @Test
    fun `subscribed collections do not appear in regular calendar list`() = runBlocking {
        val (client, _) = CalDavTestServerLoader.createClient(config)!!

        val principal = client.discoverPrincipal(creds.davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        // Get baseline via CalDavClient (current production code)
        val baselineResult = client.listCalendars(home!!)
        assert(baselineResult.isSuccess()) { "listCalendars failed: $baselineResult" }
        val baseline = baselineResult.getOrNull()!!

        // Get modified response with <cs:source/>
        val xml = sendPropfind(home)
        assert(xml != null) { "PROPFIND failed" }

        val quirks = config.quirksFactory(creds.serverUrl)
        val baseHost = extractBaseHost(home)
        val modified = quirks.extractCalendars(xml!!, baseHost)

        println("\n=== Baseline: ${baseline.size} calendars ===")
        baseline.forEach { println("  - ${it.displayName}") }

        println("\n=== Modified (with cs:source): ${modified.size} calendars ===")
        modified.forEach { println("  - ${it.displayName}") }

        // Calendar count must match
        assert(baseline.size == modified.size) {
            "Calendar count changed: baseline=${baseline.size}, modified=${modified.size}"
        }

        // No subscribed collection names in regular list
        val subscribedNames = parseSubscribedCollections(xml).map { it.displayName }
        val regularNames = modified.map { it.displayName }
        val overlap = subscribedNames.intersect(regularNames.toSet())
        assert(overlap.isEmpty()) {
            "Subscribed collections leaked into regular calendar list: $overlap"
        }

        println("\n  RESULT: ${baseline.size} regular calendars (unchanged), ${subscribedNames.size} subscribed collections correctly excluded")
    }

    /**
     * Verifies a second discovery pass (refresh) handles already-existing subscriptions.
     */
    @Test
    fun `refresh discovery skips already-existing subscriptions`() = runBlocking {
        val (client, _) = CalDavTestServerLoader.createClient(config)!!

        val principal = client.discoverPrincipal(creds.davEndpoint).getOrNull()
        assumeTrue("Could not discover principal", principal != null)

        val home = client.discoverCalendarHome(principal!!).getOrNull()?.firstOrNull()
        assumeTrue("Could not discover calendar home", home != null)

        val xml = sendPropfind(home!!)
        assert(xml != null) { "PROPFIND failed" }

        val subscribed = parseSubscribedCollections(xml!!)
            .filter { !it.sourceUrl.isNullOrBlank() }
            .map { it.copy(sourceUrl = normalizeUrl(it.sourceUrl!!)) }

        // Deduplicate
        val seen = mutableSetOf<String>()
        val unique = subscribed.filter { seen.add(it.sourceUrl!!) }

        // First pass: create all
        val existingUrls = mutableSetOf<String>()
        var firstPassCreated = 0
        for (sub in unique) {
            if (!existingUrls.contains(sub.sourceUrl!!)) {
                existingUrls.add(sub.sourceUrl!!)
                firstPassCreated++
            }
        }

        // Second pass (refresh): all should be skipped
        var secondPassCreated = 0
        var secondPassSkipped = 0
        for (sub in unique) {
            if (existingUrls.contains(sub.sourceUrl!!)) {
                secondPassSkipped++
            } else {
                existingUrls.add(sub.sourceUrl!!)
                secondPassCreated++
            }
        }

        println("\n=== Refresh simulation ===")
        println("  First pass: $firstPassCreated created")
        println("  Second pass: $secondPassCreated created, $secondPassSkipped skipped")

        assert(firstPassCreated > 0) { "First pass should create subscriptions" }
        assert(secondPassCreated == 0) { "Second pass should create nothing (all exist)" }
        assert(secondPassSkipped == firstPassCreated) { "Second pass should skip all" }

        println("\n  RESULT: Refresh correctly skips all ${secondPassSkipped} existing subscriptions")
    }

    // ========== Helpers ==========

    private fun sendPropfind(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", PROPFIND_WITH_SOURCE.toRequestBody(XML_MEDIA_TYPE))
                .header("Depth", "1")
                .header("Authorization", OkHttpCredentials.basic(creds.username, creds.password))
                .build()

            val response = okhttp.newCall(request).execute()
            if (response.code in 200..299 || response.code == 207) {
                response.body?.string()
            } else {
                println("  PROPFIND HTTP ${response.code}")
                null
            }
        } catch (e: Exception) {
            println("  PROPFIND exception: ${e.message}")
            null
        }
    }

    /**
     * Parse CS:subscribed collections from PROPFIND XML.
     * This is the logic that would go into CalDavXmlParser.extractSubscribedCalendars().
     */
    private fun parseSubscribedCollections(xml: String): List<SubscribedCollection> {
        val collections = mutableListOf<SubscribedCollection>()

        val responsePattern = Regex(
            "<(?:[a-zA-Z]+:)?response>(.+?)</(?:[a-zA-Z]+:)?response>",
            RegexOption.DOT_MATCHES_ALL
        )

        for (match in responsePattern.findAll(xml)) {
            val block = match.groupValues[1]

            // Check for CS:subscribed in resourcetype
            val hasSubscribed = Regex(
                "<(?:[a-zA-Z]+:)?subscribed\\s*/?>",
                RegexOption.DOT_MATCHES_ALL
            ).containsMatchIn(block)

            if (!hasSubscribed) continue

            // Check it's NOT also a <calendar> (treat as subscribed if both present, per spec)
            // Actually per spec: if both present, treat as subscribed

            // Extract href
            val href = Regex(
                "<(?:[a-zA-Z]+:)?href>([^<]+)</(?:[a-zA-Z]+:)?href>"
            ).find(block)?.groupValues?.get(1)?.trim() ?: continue

            // Extract displayname
            val displayName = Regex(
                "<(?:[a-zA-Z]+:)?displayname>([^<]+)</(?:[a-zA-Z]+:)?displayname>"
            ).find(block)?.groupValues?.get(1)?.trim() ?: "Unnamed"

            // Extract calendar-color
            val color = Regex(
                "<(?:[a-zA-Z]+:)?calendar-color>([^<]+)</(?:[a-zA-Z]+:)?calendar-color>"
            ).find(block)?.groupValues?.get(1)?.trim()

            // Extract CS:source href
            val sourceUrl = Regex(
                "<(?:[a-zA-Z]+:)?source>\\s*<(?:[a-zA-Z]+:)?href>([^<]+)</(?:[a-zA-Z]+:)?href>",
                RegexOption.DOT_MATCHES_ALL
            ).find(block)?.groupValues?.get(1)?.trim()

            collections.add(
                SubscribedCollection(
                    href = href,
                    displayName = displayName,
                    color = color,
                    sourceUrl = sourceUrl
                )
            )
        }

        return collections
    }

    private fun normalizeUrl(url: String): String {
        return url.trim()
            .replace("webcal://", "https://")
            .replace("webcals://", "https://")
    }

    private fun parseColor(colorStr: String?): Int {
        if (colorStr.isNullOrBlank()) return -0x7f7f80 // default gray
        return try {
            val hex = colorStr.trimStart('#')
            when (hex.length) {
                6 -> (0xFF000000 or hex.toLong(16)).toInt()
                8 -> hex.toLong(16).toInt()
                else -> -0x7f7f80
            }
        } catch (_: Exception) {
            -0x7f7f80
        }
    }

    private fun extractBaseHost(url: String): String {
        val parsed = java.net.URL(url)
        val port = if (parsed.port != -1 && parsed.port != parsed.defaultPort) ":${parsed.port}" else ""
        return "${parsed.protocol}://${parsed.host}$port"
    }

    data class SubscribedCollection(
        val href: String,
        val displayName: String,
        val color: String?,
        val sourceUrl: String?
    )
}
