package org.onekash.kashcal.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.util.UiMessage
import org.onekash.kashcal.ui.util.asString

// iCloud brand color
private val iCloudBlue = Color(0xFF5AC8FA)

/**
 * Visual transformation for Apple app-specific passwords.
 * Formats input as: xxxx-xxxx-xxxx-xxxx
 */
private class AppSpecificPasswordTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val trimmed = text.text.replace("-", "")
        val formatted = buildString {
            trimmed.forEachIndexed { index, char ->
                if (index > 0 && index % 4 == 0) append("-")
                append(char)
            }
        }
        return TransformedText(
            AnnotatedString(formatted),
            AppSpecificPasswordOffsetMapping(trimmed.length)
        )
    }
}

/**
 * Offset mapping for app-specific password format (xxxx-xxxx-xxxx-xxxx).
 * Handles cursor position when dashes are inserted.
 */
private class AppSpecificPasswordOffsetMapping(private val originalLength: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int {
        // Each group of 4 chars adds a dash before it (except first group)
        val dashes = if (offset > 0) (offset - 1) / 4 else 0
        return offset + dashes
    }

    override fun transformedToOriginal(offset: Int): Int {
        // Remove dash positions from offset
        var originalOffset = 0
        var transformedOffset = 0
        while (transformedOffset < offset && originalOffset < originalLength) {
            transformedOffset++
            if ((transformedOffset % 5) != 0) { // Not a dash position
                originalOffset++
            }
        }
        return originalOffset.coerceAtMost(originalLength)
    }
}

/**
 * iCloud sign-in bottom sheet.
 * Used by both OnboardingBanner and AccountSettingsScreen.
 *
 * Features:
 * - Apple ID input
 * - App-specific password with xxxx-xxxx-xxxx-xxxx formatting
 * - Expandable help section
 * - Loading state during connection
 * - Error display
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ICloudSignInSheet(
    appleId: String,
    password: String,
    showHelp: Boolean,
    error: UiMessage?,
    isConnecting: Boolean,
    onAppleIdChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onToggleHelp: () -> Unit,
    onSignIn: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    var passwordVisible by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = {},
        sheetGesturesEnabled = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Cloud icon
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = iCloudBlue,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = stringResource(R.string.signin_icloud_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Apple ID field
            OutlinedTextField(
                value = appleId,
                onValueChange = onAppleIdChange,
                label = { Text(stringResource(R.string.label_apple_id)) },
                placeholder = { Text(stringResource(R.string.placeholder_email)) },
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(
                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // App-Specific Password field with formatting
            OutlinedTextField(
                value = password,
                onValueChange = { newValue ->
                    // Strip dashes and limit to 16 characters
                    val cleaned = newValue.replace("-", "").take(16)
                    onPasswordChange(cleaned)
                },
                label = { Text(stringResource(R.string.label_app_password)) },
                placeholder = { Text(stringResource(R.string.placeholder_password)) },
                supportingText = { Text(stringResource(R.string.label_password_hint)) },
                singleLine = true,
                enabled = !isConnecting,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (passwordVisible) {
                    AppSpecificPasswordTransformation()
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = !isConnecting
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) stringResource(R.string.action_hide_password) else stringResource(R.string.action_show_password)
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        if (appleId.isNotBlank() && password.length == 16) {
                            onSignIn()
                        }
                    }
                )
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Help toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isConnecting) { onToggleHelp() }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    stringResource(R.string.signin_icloud_help_question),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Expandable help content
            AnimatedVisibility(
                visible = showHelp,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.help_app_password_description),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.help_app_password_steps_header),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(stringResource(R.string.help_app_password_step_1), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.help_app_password_step_2), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.help_app_password_step_3), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.help_app_password_step_4), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.help_app_password_step_5), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Error message
            if (error != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        error.asString(),
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Sign In button
            Button(
                onClick = onSignIn,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                enabled = appleId.isNotBlank() && password.length == 16 && !isConnecting
            ) {
                if (isConnecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.status_connecting), style = MaterialTheme.typography.titleMedium)
                } else {
                    Text(stringResource(R.string.action_sign_in), style = MaterialTheme.typography.titleMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Cancel button
            TextButton(
                onClick = onDismiss,
                enabled = !isConnecting
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}
