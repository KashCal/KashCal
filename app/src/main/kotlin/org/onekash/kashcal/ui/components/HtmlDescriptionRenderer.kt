package org.onekash.kashcal.ui.components

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Converts an HTML description to an [AnnotatedString] for display.
 *
 * [AnnotatedString.fromHtml] (Compose `ui-text`) wraps
 * `HtmlCompat.fromHtml(text, FROM_HTML_MODE_COMPACT, null, tagHandler)`; the `null`
 * `ImageGetter` means an `<img>` tag never triggers a network fetch.
 *
 * A tapped link reaches [onNavigate] only if [shouldOpenExternally] allows its scheme (http,
 * https, tel, mailto); any other, such as `javascript:`, `data:`, `file:` or a deep link, is
 * silently ignored, as in [LinkifiedText].
 *
 * [htmlText] must contain HTML (decide with `looksLikeHtml`): the parser drops stray `<`
 * characters from plain text.
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
