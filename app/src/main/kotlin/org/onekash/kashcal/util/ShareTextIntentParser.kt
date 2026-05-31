package org.onekash.kashcal.util

import android.content.Intent

/**
 * Parsed shape of an `Intent.ACTION_SEND` `text/plain` share targeted at KashCal.
 *
 * `Short` flows into the Quick Add dialog seeded with the cleaned single-line text.
 * `Long` flows into the full event form with the original (multi-line) text in
 * the description field.
 */
sealed class ShareTextResult {
    data class Short(
        val text: String,
        val location: String?,
        val referenceMs: kotlin.Long
    ) : ShareTextResult()

    data class Long(
        val title: String,
        val description: String,
        val location: String?,
        val referenceMs: kotlin.Long
    ) : ShareTextResult()
}

/**
 * Extracts a `ShareTextResult` from an Android share intent, or null if the
 * intent isn't a plain-text share. Callers route the result to the right
 * [PendingAction]: `Short` → Quick Add seed; `Long` → full event form.
 *
 * `EXTRA_TEXT` is preferred over `EXTRA_SUBJECT`. Both are read as `CharSequence`
 * since some senders (Gmail) ship Spannable strings.
 */
object ShareTextIntentParser {

    fun parse(intent: Intent?, nowMs: Long): ShareTextResult? {
        if (intent == null) return null
        if (intent.action != Intent.ACTION_SEND) return null
        if (intent.type != "text/plain") return null

        val raw = readExtras(intent) ?: return null
        if (raw.isBlank()) return null

        val normalized = SharedTextNormalizer.normalize(raw)
        return when (normalized) {
            is NormalizedShareText.Short -> ShareTextResult.Short(
                text = normalized.text,
                location = normalized.location,
                referenceMs = nowMs
            )
            is NormalizedShareText.Long -> ShareTextResult.Long(
                title = normalized.title,
                description = normalized.description,
                location = normalized.location,
                referenceMs = nowMs
            )
        }
    }

    // Senders may put Spannable into EXTRA_TEXT; getStringExtra would return null.
    // Wrap reads so a malicious sender's Bundle can't crash the share path.
    // Falls back to EXTRA_SUBJECT when EXTRA_TEXT is missing OR blank — some
    // senders set EXTRA_TEXT="" for header-only shares and the subject
    // shouldn't be lost to a whitespace string.
    private fun readExtras(intent: Intent): String? = try {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        text?.takeIf { it.isNotBlank() } ?: subject
    } catch (e: RuntimeException) {
        null
    }
}
