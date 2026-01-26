package org.onekash.kashcal.sync.parser.migration

/**
 * Utility for loading ICS test resources in Phase 0 tests.
 */
object TestDataLoader {

    /**
     * Load an ICS test file from resources.
     *
     * @param path Path relative to resources root, e.g., "ical/basic/simple_event.ics"
     * @return File contents as string
     * @throws IllegalArgumentException if resource not found
     */
    fun loadTestResource(path: String): String {
        return TestDataLoader::class.java.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }

    /**
     * Load all ICS files from a directory.
     *
     * @param directory Directory relative to resources/ical/, e.g., "basic"
     * @return Map of filename to contents
     */
    fun loadAllFromDirectory(directory: String): Map<String, String> {
        val basePath = "ical/$directory"
        // List of known test files (since we can't list resources dynamically)
        return getKnownFilesInDirectory(directory)
            .associateWith { filename -> loadTestResource("$basePath/$filename") }
    }

    /**
     * Get known test files in a directory.
     * This is a workaround since Java classloader can't list resources.
     */
    private fun getKnownFilesInDirectory(directory: String): List<String> {
        return when (directory) {
            "basic" -> listOf(
                "simple_event.ics",
                "all_day_event.ics",
                "multi_day_all_day.ics"
            )
            "datetime" -> listOf(
                "utc_datetime.ics",
                "floating_datetime.ics",
                "tzid_america_chicago.ics",
                "tzid_europe_london.ics"
            )
            "recurring" -> listOf(
                "daily_simple.ics",
                "weekly_multiple_days.ics",
                "monthly_by_day.ics",
                "monthly_by_setpos.ics",
                "yearly_simple.ics",
                "quarterly.ics"
            )
            "exceptions" -> listOf(
                "recurrence_id_modified.ics",
                "recurrence_id_cancelled.ics",
                "exdate_single.ics",
                "exdate_multiple.ics",
                "with_multiple_exdates.ics",
                "with_rdate.ics"
            )
            "reminders" -> listOf(
                "valarm_15min.ics",
                "valarm_1hour.ics",
                "valarm_1day.ics",
                "valarm_multiple.ics"
            )
            "extra_properties" -> listOf(
                "x_apple_properties.ics",
                "full_icloud_event.ics"
            )
            "edge_cases" -> listOf(
                "cancelled_status.ics",
                "high_sequence.ics",
                "long_description_folded.ics",
                "vtimezone_with_rrule.ics"
            )
            else -> emptyList()
        }
    }
}
