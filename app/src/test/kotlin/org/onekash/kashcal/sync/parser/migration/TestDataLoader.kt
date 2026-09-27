package org.onekash.kashcal.sync.parser.migration

/** Loads ICS test resources for parser tests. */
object TestDataLoader {

    /**
     * Returns the contents of the test resource at [path].
     *
     * @param path relative to the resources root, e.g. "ical/basic/simple_event.ics"
     * @throws IllegalArgumentException if the resource isn't found
     */
    fun loadTestResource(path: String): String {
        return TestDataLoader::class.java.classLoader?.getResourceAsStream(path)
            ?.bufferedReader()?.readText()
            ?: throw IllegalArgumentException("Resource not found: $path")
    }

    /**
     * Returns filename to contents for the known files in [directory]; an unknown directory
     * gives an empty map.
     *
     * @param directory relative to resources/ical/, e.g. "basic"
     */
    fun loadAllFromDirectory(directory: String): Map<String, String> {
        val basePath = "ical/$directory"
        return getKnownFilesInDirectory(directory)
            .associateWith { filename -> loadTestResource("$basePath/$filename") }
    }

    /**
     * Returns the hard-coded file list for [directory], since the classloader can't list
     * resources. A file added to a directory must be added here too.
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
