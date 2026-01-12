package org.onekash.kashcal.sync.parser

/**
 * Result of parsing iCal data.
 */
data class ParseResult(
    /** Successfully parsed events */
    val events: List<ParsedEvent>,

    /** Errors encountered during parsing */
    val errors: List<ParseError>
) {
    /**
     * Check if parsing was completely successful.
     */
    fun isSuccess(): Boolean = errors.isEmpty()

    /**
     * Check if any events were parsed (even if some errors occurred).
     */
    fun hasEvents(): Boolean = events.isNotEmpty()

    companion object {
        /**
         * Create a successful result with events.
         */
        fun success(events: List<ParsedEvent>): ParseResult {
            return ParseResult(events, emptyList())
        }

        /**
         * Create a failed result with error.
         */
        fun failure(error: String): ParseResult {
            return ParseResult(emptyList(), listOf(ParseError(error)))
        }

        /**
         * Create an empty result (no events, no errors).
         */
        fun empty(): ParseResult {
            return ParseResult(emptyList(), emptyList())
        }
    }
}

/**
 * Error encountered during iCal parsing.
 */
data class ParseError(
    /** Error message */
    val message: String,

    /** Line number where error occurred (if known) */
    val lineNumber: Int? = null,

    /** Property name where error occurred (if known) */
    val property: String? = null
) {
    override fun toString(): String {
        val location = listOfNotNull(
            lineNumber?.let { "line $it" },
            property?.let { "property $it" }
        ).joinToString(", ")

        return if (location.isNotEmpty()) {
            "$message ($location)"
        } else {
            message
        }
    }
}
