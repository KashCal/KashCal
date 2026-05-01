package org.onekash.icaldav.model

/**
 * iTIP methods per RFC 5546.
 * Used in METHOD property at VCALENDAR level for scheduling.
 */
enum class ITipMethod(val value: String) {
    PUBLISH("PUBLISH"),           // Publish calendar data (no scheduling)
    REQUEST("REQUEST"),           // Request meeting attendance
    REPLY("REPLY"),               // Response to REQUEST
    ADD("ADD"),                   // Add instances to recurring event
    CANCEL("CANCEL"),             // Cancel event or remove attendee
    REFRESH("REFRESH"),           // Request latest event version
    COUNTER("COUNTER"),           // Propose alternative time
    DECLINECOUNTER("DECLINECOUNTER"); // Decline proposed alternative

    companion object {
        fun fromString(value: String): ITipMethod? =
            entries.find { it.value.equals(value, ignoreCase = true) }
    }
}

/**
 * SCHEDULE-AGENT parameter per RFC 6638.
 * Controls who handles scheduling message delivery.
 */
enum class ScheduleAgent(val value: String) {
    SERVER("SERVER"),   // Server handles scheduling (default)
    CLIENT("CLIENT"),   // Client handles scheduling
    NONE("NONE");       // No scheduling (store-only)

    companion object {
        fun fromString(value: String): ScheduleAgent =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: SERVER
    }
}

/**
 * SCHEDULE-STATUS delivery codes per RFC 6638 Section 3.2.9.
 * Reports the result of delivering a scheduling message.
 */
data class ScheduleStatus(
    val code: String,
    val description: String? = null
) {
    val category: StatusCategory get() = when {
        code.startsWith("1.") -> StatusCategory.PENDING
        code.startsWith("2.") -> StatusCategory.SUCCESS
        code.startsWith("3.") -> StatusCategory.PERMISSION_ERROR
        code.startsWith("5.") -> StatusCategory.DELIVERY_ERROR
        else -> StatusCategory.UNKNOWN
    }

    val isSuccess: Boolean get() = category == StatusCategory.SUCCESS
    val isPending: Boolean get() = category == StatusCategory.PENDING

    enum class StatusCategory {
        PENDING, SUCCESS, PERMISSION_ERROR, DELIVERY_ERROR, UNKNOWN
    }

    companion object {
        val PENDING = ScheduleStatus("1.0", "Request pending")
        val SUCCESS = ScheduleStatus("2.0", "Successfully delivered")
        val INVALID_USER = ScheduleStatus("3.7", "Invalid calendar user")
        val DELIVERY_FAILED = ScheduleStatus("5.1", "Could not deliver")

        fun fromString(value: String): ScheduleStatus {
            val parts = value.split(";", limit = 2)
            return ScheduleStatus(parts[0].trim(), parts.getOrNull(1)?.trim())
        }
    }
}

/**
 * SCHEDULE-FORCE-SEND parameter per RFC 6638.
 * Forces sending of scheduling message even when normally not required.
 */
enum class ScheduleForceSend(val value: String) {
    REQUEST("REQUEST"),
    REPLY("REPLY");

    companion object {
        fun fromString(value: String): ScheduleForceSend? =
            entries.find { it.value.equals(value, ignoreCase = true) }
    }
}

/**
 * REQUEST-STATUS codes per RFC 5546 Section 3.6.
 * Used in iTIP responses to indicate success/failure.
 */
enum class RequestStatus(val code: String, val description: String) {
    SUCCESS("2.0", "Success"),
    SUCCESS_FALLBACK("2.1", "Success, fallback taken on one or more property values"),
    SUCCESS_IGNORED("2.2", "Success, invalid property ignored"),
    INVALID_PROPERTY_NAME("3.0", "Invalid property name"),
    INVALID_PROPERTY_VALUE("3.1", "Invalid property value"),
    INVALID_PARAMETER("3.2", "Invalid property parameter"),
    INVALID_CALENDAR_USER("3.7", "Invalid calendar user"),
    NO_SCHEDULING_SUPPORT("3.8", "No scheduling support for user"),
    REQUEST_NOT_SUPPORTED("5.0", "Request not supported"),
    SERVICE_UNAVAILABLE("5.1", "Service unavailable"),
    INVALID_FREEBUSY_USER("5.3", "Invalid calendar user for free-busy");

    val isSuccess: Boolean get() = code.startsWith("2.")
    val isPending: Boolean get() = code.startsWith("1.")
    val isError: Boolean get() = code.startsWith("3.") || code.startsWith("5.")

    companion object {
        fun fromCode(code: String): RequestStatus? =
            entries.find { it.code == code }
    }
}

/**
 * Result of a scheduling operation.
 */
data class SchedulingResult(
    val success: Boolean,
    val recipientResults: List<RecipientResult>,
    val rawRequest: String? = null,   // For debugging
    val rawResponse: String? = null
) {
    data class RecipientResult(
        val recipient: String,
        val status: ScheduleStatus,
        val requestStatus: RequestStatus? = null,
        val calendarData: String? = null
    )
}

/**
 * Scheduling URLs discovered from principal.
 */
data class SchedulingUrls(
    val scheduleInboxUrl: String?,
    val scheduleOutboxUrl: String?
) {
    val supportsScheduling: Boolean get() =
        scheduleInboxUrl != null && scheduleOutboxUrl != null
}
