package org.onekash.kashcal.util

import android.content.Intent

/**
 * Parsed `Intent.ACTION_SEND` `text/plain` share.
 *
 * [Short] seeds the Quick Add dialog with the cleaned single-line text. [Long] opens the full
 * event form with the original multi-line text in the description.
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
 * Extracts a [ShareTextResult] from a plain-text share intent, or null when the intent isn't
 * one, carries no text or has unreadable extras. [ShareIntentRouter] maps the result to a
 * [org.onekash.kashcal.ui.viewmodels.PendingAction].
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

    // Reads as CharSequence: some senders (Gmail) put a Spannable in EXTRA_TEXT, for which
    // getStringExtra returns null. Falls back to EXTRA_SUBJECT when EXTRA_TEXT is missing or
    // blank, since some senders set EXTRA_TEXT="" for header-only shares. The try keeps a
    // malicious sender's Bundle from crashing the share path.
    private fun readExtras(intent: Intent): String? = try {
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        text?.takeIf { it.isNotBlank() } ?: subject
    } catch (e: RuntimeException) {
        null
    }
}
