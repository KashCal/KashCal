package org.onekash.kashcal.ui.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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
            androidx.compose.material3.Text(
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

    // Cache colors to prevent unnecessary recomposition
    val linkColor = MaterialTheme.colorScheme.primary
    val defaultTextColor = MaterialTheme.colorScheme.onSurface
    val textColor = remember(style.color, defaultTextColor) {
        style.color.takeIf { it != androidx.compose.ui.graphics.Color.Unspecified }
            ?: defaultTextColor
    }

    // Build annotated string with link spans
    val annotatedString = remember(cleanedText, detectedUrls, linkColor, textColor) {
        buildAnnotatedString {
            var lastIndex = 0

            detectedUrls.forEach { detected ->
                // Add text before this URL
                if (detected.startIndex > lastIndex) {
                    append(cleanedText.substring(lastIndex, detected.startIndex))
                }

                // Add the URL with link styling and annotation
                val linkStart = length
                pushStyle(
                    SpanStyle(
                        color = linkColor,
                        textDecoration = TextDecoration.Underline
                    )
                )
                // Display the original text (not the normalized URL)
                append(cleanedText.substring(detected.startIndex, detected.endIndex))
                pop()

                // Add URL annotation for click handling
                addStringAnnotation(
                    tag = "URL",
                    annotation = detected.url,
                    start = linkStart,
                    end = length
                )

                lastIndex = detected.endIndex
            }

            // Add remaining text after last URL
            if (lastIndex < cleanedText.length) {
                append(cleanedText.substring(lastIndex))
            }
        }
    }

    // Build accessibility description
    val accessibilityDescription = remember(detectedUrls) {
        if (detectedUrls.isEmpty()) null
        else "Text with ${detectedUrls.size} link${if (detectedUrls.size > 1) "s" else ""}: " +
             detectedUrls.joinToString(", ") { getAccessibilityLabel(it) }
    }

    SelectionContainer {
        ClickableText(
            text = annotatedString,
            modifier = modifier.then(
                if (accessibilityDescription != null) {
                    Modifier.semantics { contentDescription = accessibilityDescription }
                } else Modifier
            ),
            style = style.copy(color = textColor),
            maxLines = maxLines,
            overflow = overflow,
            onClick = { offset ->
                annotatedString.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        val url = annotation.item
                        // Find the corresponding DetectedUrl for callback
                        val detected = detectedUrls.find { it.url == url }
                        detected?.let { onLinkClick?.invoke(it) }

                        // Open URL if safe
                        if (shouldOpenExternally(url)) {
                            try {
                                uriHandler.openUri(url)
                            } catch (e: Exception) {
                                // System will show "No app found" toast
                            }
                        }
                    }
            }
        )
    }
}

/**
 * Get accessibility label for a detected URL.
 */
private fun getAccessibilityLabel(url: DetectedUrl): String {
    return when (url.type) {
        UrlType.MEETING -> "Meeting link: ${url.displayText}"
        UrlType.PHONE -> "Phone number"
        UrlType.EMAIL -> "Email: ${url.displayText}"
        UrlType.WEB -> "Link: ${url.displayText}"
    }
}
