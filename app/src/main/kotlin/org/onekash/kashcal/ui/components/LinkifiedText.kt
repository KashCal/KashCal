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
 * Text composable with automatic URL detection and linking.
 *
 * Features:
 * - Detects web URLs, meeting links, phone numbers, and email addresses
 * - Makes detected links clickable (opens in default app)
 * - Wraps content in SelectionContainer for copy support
 * - Applies primary color with underline to links
 * - Provides accessibility descriptions for screen readers
 *
 * @param text The text to render with linkified URLs
 * @param modifier Modifier for the text composable
 * @param style Text style (defaults to bodyMedium)
 * @param maxLines Maximum lines before truncation
 * @param overflow Text overflow behavior
 * @param onLinkClick Optional callback when a link is clicked (for analytics)
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

    // cleanHtmlEntities must NOT run in the HTML branch — fromHtml does its
    // own decoding, and pre-decoding would let literals like "&lt;b&gt;"
    // become parseable tags.
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

    // If no URLs, render simple text
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

    // Build annotated string with embedded LinkAnnotation.Url for each detected URL.
    // LinkAnnotation routes clicks via the embedded listener — no offset lookup needed.
    val annotatedString = remember(cleanedText, detectedUrls, linkStyles) {
        buildAnnotatedString {
            var lastIndex = 0

            detectedUrls.forEach { detected ->
                if (detected.startIndex > lastIndex) {
                    append(cleanedText.substring(lastIndex, detected.startIndex))
                }

                // Supplying linkInteractionListener overrides Compose's default
                // URL-open behavior, so we must call uriHandler ourselves to keep
                // the shouldOpenExternally safety gate.
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
 * Classify a URL string into a [DetectedUrl] shape so HTML-derived links
 * can reuse the plain-text accessibility-description builder.
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
        // System will show "No app found" toast
    }
}

/**
 * Resolve the screen-reader-friendly contentDescription for a set of detected
 * URLs using the shared i18n strings. Returns null if the list is empty.
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
