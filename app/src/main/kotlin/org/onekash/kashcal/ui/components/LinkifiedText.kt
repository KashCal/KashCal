package org.onekash.kashcal.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import org.onekash.kashcal.R
import org.onekash.kashcal.util.text.DetectedUrl
import org.onekash.kashcal.util.text.UrlType
import org.onekash.kashcal.util.text.cleanHtmlEntities
import org.onekash.kashcal.util.text.extractUrls
import org.onekash.kashcal.util.text.getUrlDisplayText
import org.onekash.kashcal.util.text.isMeetingUrl
import org.onekash.kashcal.util.text.looksLikeHtml
import org.onekash.kashcal.util.text.shouldOpenExternally

/**
 * Shows selectable text with its links tappable, underlined in the primary color and listed
 * in the content description for screen readers.
 *
 * HTML text (per `looksLikeHtml`) goes through [buildHtmlDescriptionAnnotatedString]. Plain
 * text is entity-decoded and scanned for web, meeting, phone and email links, up to 50. A tap
 * opens a link in its default app only when [shouldOpenExternally] allows its scheme.
 *
 * @param onLinkClick called on a link tap; in plain text before the scheme check, in HTML only
 *   for links that pass it. No caller sets it.
 */
@Composable
fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onLinkClick: ((DetectedUrl) -> Unit)? = null
) {
    val uriHandler = LocalUriHandler.current
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)
    val currentUriHandler by rememberUpdatedState(uriHandler)

    val linkColor = MaterialTheme.colorScheme.primary
    val defaultTextColor = MaterialTheme.colorScheme.onSurface
    val textColor = remember(style.color, defaultTextColor) {
        style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
            ?: defaultTextColor
    }
    val linkStyles = remember(linkColor) {
        TextLinkStyles(
            style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
        )
    }

    // cleanHtmlEntities must not run in the HTML branch: fromHtml decodes entities itself, and
    // decoding first would turn a literal "&lt;b&gt;" into a parseable tag.
    val isHtml = remember(text) { looksLikeHtml(text) }
    if (isHtml) {
        val annotatedHtml = remember(text, linkStyles) {
            buildHtmlDescriptionAnnotatedString(
                htmlText = text,
                linkStyles = linkStyles,
                onNavigate = { url ->
                    currentOnLinkClick?.invoke(urlToDetected(url))
                    currentUriHandler.openUriSafely(url)
                }
            )
        }
        val htmlUrls = remember(annotatedHtml) {
            annotatedHtml.getLinkAnnotations(0, annotatedHtml.length)
                .mapNotNull { range ->
                    (range.item as? LinkAnnotation.Url)?.url?.let(::urlToDetected)
                }
        }
        val htmlAccessibility = buildAccessibilityDescription(htmlUrls)
        SelectionContainer {
            Text(
                text = annotatedHtml,
                modifier = modifier.then(
                    if (htmlAccessibility != null) {
                        Modifier.semantics { contentDescription = htmlAccessibility }
                    } else Modifier
                ),
                style = style.copy(color = textColor),
                maxLines = maxLines,
                overflow = overflow
            )
        }
        return
    }

    // Plain-text branch: decode entities, then linkify detected URLs.
    val cleanedText = remember(text) { cleanHtmlEntities(text) }
    val detectedUrls = remember(cleanedText) { extractUrls(cleanedText, limit = 50) }

    if (detectedUrls.isEmpty()) {
        SelectionContainer {
            Text(
                text = cleanedText,
                modifier = modifier,
                style = style,
                maxLines = maxLines,
                overflow = overflow
            )
        }
        return
    }

    // Each link carries its own click listener, so no offset lookup is needed.
    val annotatedString = remember(cleanedText, detectedUrls, linkStyles) {
        buildAnnotatedString {
            var lastIndex = 0

            detectedUrls.forEach { detected ->
                if (detected.startIndex > lastIndex) {
                    append(cleanedText.substring(lastIndex, detected.startIndex))
                }

                // A linkInteractionListener replaces Compose's default URL opening, so the
                // listener opens the link itself, behind the shouldOpenExternally gate.
                val link = LinkAnnotation.Url(
                    url = detected.url,
                    styles = linkStyles,
                    linkInteractionListener = {
                        currentOnLinkClick?.invoke(detected)
                        if (shouldOpenExternally(detected.url)) {
                            currentUriHandler.openUriSafely(detected.url)
                        }
                    }
                )
                pushLink(link)
                append(cleanedText.substring(detected.startIndex, detected.endIndex))
                pop()

                lastIndex = detected.endIndex
            }

            if (lastIndex < cleanedText.length) {
                append(cleanedText.substring(lastIndex))
            }
        }
    }

    val accessibilityDescription = buildAccessibilityDescription(detectedUrls)

    SelectionContainer {
        Text(
            text = annotatedString,
            modifier = modifier.then(
                if (accessibilityDescription != null) {
                    Modifier.semantics { contentDescription = accessibilityDescription }
                } else Modifier
            ),
            style = style.copy(color = textColor),
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

/**
 * Classifies a URL into a [DetectedUrl] so HTML links can reuse [buildAccessibilityDescription].
 */
private fun urlToDetected(url: String): DetectedUrl {
    val type = when {
        url.startsWith("tel:", ignoreCase = true) -> UrlType.PHONE
        url.startsWith("mailto:", ignoreCase = true) -> UrlType.EMAIL
        isMeetingUrl(url) -> UrlType.MEETING
        else -> UrlType.WEB
    }
    val displayText = when (type) {
        UrlType.PHONE -> "Phone number"
        UrlType.EMAIL -> url.removePrefix("mailto:").removePrefix("MAILTO:")
        UrlType.WEB, UrlType.MEETING -> getUrlDisplayText(url)
    }
    return DetectedUrl(
        url = url,
        startIndex = 0,
        endIndex = 0,
        type = type,
        displayText = displayText
    )
}

private fun UriHandler.openUriSafely(url: String) {
    try {
        openUri(url)
    } catch (_: Exception) {
        // No app handles the URI; the tap does nothing.
    }
}

/**
 * Returns the content description naming each of [urls] for screen readers, or null if there
 * are none.
 */
@Composable
private fun buildAccessibilityDescription(urls: List<DetectedUrl>): String? {
    if (urls.isEmpty()) return null
    val labels = urls.map { url ->
        when (url.type) {
            UrlType.MEETING -> stringResource(R.string.cd_meeting_link, url.displayText)
            UrlType.PHONE -> stringResource(R.string.cd_phone_number)
            UrlType.EMAIL -> stringResource(R.string.cd_email_link, url.displayText)
            UrlType.WEB -> stringResource(R.string.cd_web_link, url.displayText)
        }
    }
    val joined = labels.joinToString(", ")
    return if (labels.size == 1) stringResource(R.string.cd_text_with_link, joined)
    else stringResource(R.string.cd_text_with_links, labels.size, joined)
}
