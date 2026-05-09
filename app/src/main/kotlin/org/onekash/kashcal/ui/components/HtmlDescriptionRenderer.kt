package org.onekash.kashcal.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Convert an HTML description to an [AnnotatedString] for display.
 *
 * Delegates to [AnnotatedString.fromHtml] (Compose `ui-text`), which wraps
 * `HtmlCompat.fromHtml(text, FROM_HTML_MODE_COMPACT, null, tagHandler)` —
 * the `null` `ImageGetter` ensures `<img>` tags never trigger a network
 * fetch.
 *
 * Link taps are gated through [shouldOpenExternally] so that dangerous
 * schemes (`javascript:`, `data:`, `file:`, custom deep links) are silently
 * ignored and never reach [onNavigate]. This matches the safety contract of
 * the plain-text rendering path.
 *
 * Caller contract: `htmlText` must actually contain HTML (use `looksLikeHtml`
 * to decide). Plain text passed here would have stray `<` characters dropped
 * by the parser.
 */
fun buildHtmlDescriptionAnnotatedString(
    htmlText: String,
    linkStyles: TextLinkStyles?,
    onNavigate: (String) -> Unit
): AnnotatedString {
    val listener = LinkInteractionListener { link ->
        val url = (link as? LinkAnnotation.Url)?.url ?: return@LinkInteractionListener
        if (shouldOpenExternally(url)) {
            onNavigate(url)
        }
    }
    return AnnotatedString.fromHtml(
        htmlString = htmlText,
        linkStyles = linkStyles,
        linkInteractionListener = listener
    )
}
