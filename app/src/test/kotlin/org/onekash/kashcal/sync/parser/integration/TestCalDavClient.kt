package org.onekash.kashcal.sync.parser.integration

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Simple CalDAV client for integration testing.
 * Not for production use - just for testing the parser with real iCloud data.
 */
class TestCalDavClient(
    private val username: String,
    private val password: String
) {
    companion object {
        private const val ICLOUD_CALDAV_URL = "https://caldav.icloud.com"
        private val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()
    }

    private val httpClient: OkHttpClient by lazy {
        val credential = Credentials.basic(username, password)
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", credential)
                    .header("User-Agent", "KashCal-Test/1.0")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Discover the user's principal URL.
     */
    fun discoverPrincipal(): Result<String> {
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
                <d:prop>
                    <d:current-user-principal/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(ICLOUD_CALDAV_URL)
            .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            println("PROPFIND Response Code: ${response.code}")
            println("PROPFIND Response (first 2000 chars): ${body.take(2000)}")

            if (!response.isSuccessful && response.code != 207) {
                return Result.failure(Exception("PROPFIND failed: ${response.code} - $body"))
            }

            // Extract principal URL from XML response - try various namespace formats
            val patterns = listOf(
                """<d:current-user-principal>\s*<d:href>([^<]+)</d:href>""",
                """<D:current-user-principal>\s*<D:href>([^<]+)</D:href>""",
                """<current-user-principal>\s*<href>([^<]+)</href>""",
                """current-user-principal.*?<.*?href[^>]*>([^<]+)</""",
                """<[^:]*:?current-user-principal[^>]*>\s*<[^:]*:?href[^>]*>([^<]+)</"""
            )

            var principalPath: String? = null
            for (pattern in patterns) {
                val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val matchResult = regex.find(body)
                if (matchResult != null) {
                    principalPath = matchResult.groupValues[1]
                    println("Matched principal with pattern: $pattern")
                    println("Principal path: $principalPath")
                    break
                }
            }

            if (principalPath == null) {
                return Result.failure(Exception("Principal URL not found in response"))
            }

            val principalUrl = if (principalPath.startsWith("http")) {
                principalPath
            } else {
                "$ICLOUD_CALDAV_URL$principalPath"
            }

            Result.success(principalUrl)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Discover calendar home URLs from principal.
     * Returns all hrefs in calendar-home-set (RFC 4791 allows multiple).
     */
    fun discoverCalendarHome(principalUrl: String): Result<List<String>> {
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <c:calendar-home-set/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(principalUrl)
            .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "0")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            println("Calendar Home PROPFIND Response Code: ${response.code}")
            println("Calendar Home Response (first 2000 chars): ${body.take(2000)}")

            if (!response.isSuccessful && response.code != 207) {
                return Result.failure(Exception("PROPFIND failed: ${response.code}"))
            }

            // Extract all calendar-home-set URLs
            val homeSetRegex = Regex(
                """calendar-home-set.*?</[^>]*calendar-home-set>""",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )
            val homeSetBlock = homeSetRegex.find(body)?.value ?: ""

            val hrefRegex = Regex(
                """<[^>]*href[^>]*>([^<]+)</[^>]*href>""",
                RegexOption.IGNORE_CASE
            )
            val homePaths = hrefRegex.findAll(homeSetBlock).map { it.groupValues[1].trim() }
                .filter { it.isNotEmpty() }.toList()

            if (homePaths.isEmpty()) {
                return Result.failure(Exception("Calendar home not found"))
            }

            val homeUrls = homePaths.map { homePath ->
                if (homePath.startsWith("http")) {
                    homePath
                } else {
                    val host = principalUrl.substringBefore("/", principalUrl).let {
                        if (it.contains("://")) it.substringBefore("/").plus("/").plus(principalUrl.substringAfter("://").substringBefore("/"))
                        else ICLOUD_CALDAV_URL
                    }
                    "$host$homePath".replace("//", "/").replace(":/", "://")
                }
            }

            println("Calendar home URLs: $homeUrls")
            Result.success(homeUrls)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Get list of calendars from calendar home.
     */
    fun getCalendars(calendarHomeUrl: String): Result<List<CalendarInfo>> {
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://calendarserver.org/ns/" xmlns:ic="http://apple.com/ns/ical/">
                <d:prop>
                    <d:displayname/>
                    <d:resourcetype/>
                    <ic:calendar-color/>
                    <cs:getctag/>
                </d:prop>
            </d:propfind>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarHomeUrl)
            .method("PROPFIND", propfindBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            println("Calendars PROPFIND Response Code: ${response.code}")
            println("Calendars Response (first 3000 chars): ${body.take(3000)}")

            if (!response.isSuccessful && response.code != 207) {
                return Result.failure(Exception("PROPFIND failed: ${response.code}"))
            }

            // Parse response elements - iCloud uses non-prefixed namespaces
            val calendars = mutableListOf<CalendarInfo>()
            // Match both <d:response> and <response xmlns="...">
            val responseRegex = Regex("""<(?:d:)?response[^>]*>(.*?)</(?:d:)?response>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

            for (match in responseRegex.findAll(body)) {
                val responseXml = match.groupValues[1]

                // Check if it's a calendar (has calendar resourcetype)
                // iCloud uses: <calendar xmlns="urn:ietf:params:xml:ns:caldav"/>
                val isCalendar = responseXml.contains("<calendar", ignoreCase = true) &&
                    responseXml.contains("caldav", ignoreCase = true)

                if (isCalendar) {
                    // Skip if it's tasks/reminders
                    if (responseXml.contains("tasks", ignoreCase = true) ||
                        responseXml.contains("reminder", ignoreCase = true)) {
                        continue
                    }

                    // Match both <d:href> and <href>
                    val hrefMatch = Regex("""<(?:d:)?href[^>]*>([^<]+)</(?:d:)?href>""", RegexOption.IGNORE_CASE).find(responseXml)
                    val nameMatch = Regex("""<(?:d:)?displayname[^>]*>([^<]*)</(?:d:)?displayname>""", RegexOption.IGNORE_CASE).find(responseXml)

                    val href = hrefMatch?.groupValues?.get(1)
                    if (href != null) {
                        val url = if (href.startsWith("http")) href else {
                            val baseHost = if (calendarHomeUrl.contains("://")) {
                                calendarHomeUrl.substringBefore("://") + "://" + calendarHomeUrl.substringAfter("://").substringBefore("/")
                            } else {
                                calendarHomeUrl.substringBefore("/")
                            }
                            "$baseHost$href"
                        }
                        calendars.add(CalendarInfo(
                            url = url,
                            displayName = nameMatch?.groupValues?.get(1) ?: "Unnamed"
                        ))
                    }
                }
            }

            Result.success(calendars)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch events from a calendar using REPORT.
     */
    fun fetchEvents(calendarUrl: String, daysBack: Int = 30, daysForward: Int = 90): Result<List<String>> {
        val now = System.currentTimeMillis()
        val startDate = formatICalDate(now - daysBack.toLong() * 24 * 60 * 60 * 1000)
        val endDate = formatICalDate(now + daysForward.toLong() * 24 * 60 * 60 * 1000)

        val reportBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
                <d:prop>
                    <d:getetag/>
                    <c:calendar-data/>
                </d:prop>
                <c:filter>
                    <c:comp-filter name="VCALENDAR">
                        <c:comp-filter name="VEVENT">
                            <c:time-range start="$startDate" end="$endDate"/>
                        </c:comp-filter>
                    </c:comp-filter>
                </c:filter>
            </c:calendar-query>
        """.trimIndent()

        val request = Request.Builder()
            .url(calendarUrl)
            .method("REPORT", reportBody.toRequestBody(XML_MEDIA_TYPE))
            .header("Depth", "1")
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: ""

            println("REPORT Response Code: ${response.code}")
            println("REPORT Response (first 2000 chars): ${body.take(2000)}")

            if (!response.isSuccessful && response.code != 207) {
                return Result.failure(Exception("REPORT failed: ${response.code} - ${body.take(500)}"))
            }

            // Extract calendar-data (iCal) from each response
            // iCloud uses: <calendar-data xmlns="..."><![CDATA[BEGIN:VCALENDAR...]]></calendar-data>
            val icalDataList = mutableListOf<String>()

            // Try multiple patterns for calendar-data extraction
            val calDataPatterns = listOf(
                // iCloud format with CDATA
                """<(?:c:|cal:)?calendar-data[^>]*><!\[CDATA\[(.*?)\]\]></(?:c:|cal:)?calendar-data>""",
                // Standard format without CDATA
                """<(?:c:|cal:)?calendar-data[^>]*>(.*?)</(?:c:|cal:)?calendar-data>""",
                // No namespace prefix (iCloud)
                """<calendar-data[^>]*><!\[CDATA\[(.*?)\]\]></calendar-data>""",
                """<calendar-data[^>]*>(.*?)</calendar-data>"""
            )

            for (pattern in calDataPatterns) {
                val calDataRegex = Regex(pattern, setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                for (match in calDataRegex.findAll(body)) {
                    val icalData = match.groupValues[1]
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&amp;", "&")
                        .trim()
                    if (icalData.contains("BEGIN:VCALENDAR") && !icalDataList.contains(icalData)) {
                        icalDataList.add(icalData)
                    }
                }
                // If we found data with this pattern, don't try others
                if (icalDataList.isNotEmpty()) break
            }

            Result.success(icalDataList)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatICalDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = millis
        return String.format(
            "%04d%02d%02dT000000Z",
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        )
    }

    data class CalendarInfo(
        val url: String,
        val displayName: String
    )
}
