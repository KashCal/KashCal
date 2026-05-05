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
    // Clean HTML entities and detect URLs
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

    val uriHandler = LocalUriHandler.current
    // Hold the latest caller-supplied callback so the AnnotatedString's
    // LinkInteractionListeners don't pin a stale reference across recompositions.
    val currentOnLinkClick by rememberUpdatedState(onLinkClick)

    // Cache colors to prevent unnecessary recomposition
    val linkColor = MaterialTheme.colorScheme.primary
    val defaultTextColor = MaterialTheme.colorScheme.onSurface
    val textColor = remember(style.color, defaultTextColor) {
        style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
            ?: defaultTextColor
    }

    // Build annotated string with embedded LinkAnnotation.Url for each detected URL.
    // LinkAnnotation routes clicks via the embedded listener — no offset lookup needed.
    val annotatedString = remember(cleanedText, detectedUrls, linkColor) {
        val linkStyles = TextLinkStyles(
            style = SpanStyle(
                color = linkColor,
                textDecoration = TextDecoration.Underline
            )
        )
        buildAnnotatedString {
            var lastIndex = 0

            detectedUrls.forEach { detected ->
                // Add text before this URL
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
                            try {
                                uriHandler.openUri(detected.url)
                            } catch (_: Exception) {
                                // System will show "No app found" toast
                            }
                        }
                    }
                )
                pushLink(link)
                // Display the original text (not the normalized URL)
                append(cleanedText.substring(detected.startIndex, detected.endIndex))
                pop()

                lastIndex = detected.endIndex
            }

            // Add remaining text after last URL
            if (lastIndex < cleanedText.length) {
                append(cleanedText.substring(lastIndex))
            }
        }
    }

    // Build accessibility description — resolve i18n strings in Composable scope
    val accessibilityDescription = if (detectedUrls.isEmpty()) null else {
        val labels = detectedUrls.map { url ->
            when (url.type) {
                UrlType.MEETING -> stringResource(R.string.cd_meeting_link, url.displayText)
                UrlType.PHONE -> stringResource(R.string.cd_phone_number)
                UrlType.EMAIL -> stringResource(R.string.cd_email_link, url.displayText)
                UrlType.WEB -> stringResource(R.string.cd_web_link, url.displayText)
            }
        }
        val joined = labels.joinToString(", ")
        if (labels.size == 1) stringResource(R.string.cd_text_with_link, joined)
        else stringResource(R.string.cd_text_with_links, labels.size, joined)
    }

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
