package org.onekash.kashcal.util

sealed class NormalizedShareText {
    data class Short(val text: String, val location: String?) : NormalizedShareText()
    data class Long(val title: String, val description: String, val location: String?) : NormalizedShareText()
}

object SharedTextNormalizer {

    private const val SHORT_LIMIT = 500
    private const val TITLE_CAP = 80

    // Scheme-required URL regex. android.util.Patterns.WEB_URL would match
    // "notes.txt" and "example.com", which are not links the user intends
    // as a meeting URL.
    private val URL_REGEX = Regex("""https?://\S+""")

    // Punctuation that natural-language framings put right after a URL but
    // that the URL grammar would not include. Kept conservative — `/` and
    // `=` and `?` are valid URL chars and never trimmed.
    private val URL_TRAILING_TRIM = ".,;:!?)\"]}>'"

    fun normalize(input: String): NormalizedShareText {
        val rawUrl = URL_REGEX.find(input)?.value
        val firstUrl = rawUrl?.let { trimTrailingPunctuation(it) }
        // The matched span we need to strip from the body — including any
        // trailing punctuation we just trimmed off the URL — so the title
        // doesn't grow stray characters where the URL used to be.
        val urlSpan = rawUrl
        val joined = input.replace(Regex("""\r?\n"""), " ")

        return if (joined.length <= SHORT_LIMIT) {
            val withoutUrl = if (urlSpan != null) joined.replaceFirst(urlSpan, "") else joined
            val cleaned = withoutUrl.replace(Regex("""\s+"""), " ").trim()
            NormalizedShareText.Short(text = cleaned, location = firstUrl)
        } else {
            val firstLine = input.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            val titleSource = if (urlSpan != null) firstLine.replaceFirst(urlSpan, "") else firstLine
            val title = titleSource.replace(Regex("""\s+"""), " ").trim().take(TITLE_CAP)
            NormalizedShareText.Long(
                title = title,
                description = input,
                location = firstUrl
            )
        }
    }

    private fun trimTrailingPunctuation(url: String): String =
        url.trimEnd { it in URL_TRAILING_TRIM }
}
