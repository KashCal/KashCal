package org.onekash.kashcal.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.onekash.kashcal.R
import org.onekash.kashcal.ui.screens.settings.CalDavConnectionState

/**
 * Bottom sheet for CalDAV account sign-in.
 *
 * Supports generic CalDAV servers (Nextcloud, Baikal, FastMail, Radicale, etc.)
 *
 * Simplified flow (v21.5.0):
 * 1. NotConnected: Show server/username/password fields -> Connect button
 * 2. Discovering: Show fields disabled + spinner (auto-adds all calendars)
 * 3. Success: Sheet closes, success sheet shown (handled by SettingsActivity)
 *
 * Features:
 * - Server URL with auto-https
 * - Username and password fields
 * - "Trust insecure connection" toggle for self-signed certificates and local HTTP servers
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalDavSignInSheet(
    state: CalDavConnectionState,
    onServerUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTrustInsecureChange: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onDismiss: () -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Icon
            Icon(
                imageVector = Icons.Default.CalendarMonth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Title
            Text(
                text = "Add CalDAV Account",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (state) {
                is CalDavConnectionState.NotConnected -> {
                    NotConnectedContent(
                        state = state,
                        passwordVisible = passwordVisible,
                        onPasswordVisibilityChange = { passwordVisible = it },
                        onServerUrlChange = onServerUrlChange,
                        onDisplayNameChange = onDisplayNameChange,
                        onUsernameChange = onUsernameChange,
                        onPasswordChange = onPasswordChange,
                        onTrustInsecureChange = onTrustInsecureChange,
                        onConnect = onDiscover,
                        onDismiss = onDismiss,
                        focusManager = focusManager
                    )
                }

                is CalDavConnectionState.Discovering -> {
                    ConnectingContent(state = state)
                }
            }
        }
    }
}

@Composable
private fun NotConnectedContent(
    state: CalDavConnectionState.NotConnected,
    passwordVisible: Boolean,
    onPasswordVisibilityChange: (Boolean) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onDisplayNameChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTrustInsecureChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    focusManager: androidx.compose.ui.focus.FocusManager
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Server URL field
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = onServerUrlChange,
            label = { Text("Server URL") },
            placeholder = { Text("nextcloud.example.com") },
            supportingText = { Text(stringResource(R.string.hint_https_default)) },
            singleLine = true,
            isError = state.errorField == CalDavConnectionState.ErrorField.SERVER,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Username field
        OutlinedTextField(
            value = state.username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            placeholder = { Text("user@example.com") },
            singleLine = true,
            isError = state.errorField == CalDavConnectionState.ErrorField.CREDENTIALS,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Password field
        OutlinedTextField(
            value = state.password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            isError = state.errorField == CalDavConnectionState.ErrorField.PASSWORD ||
                    state.errorField == CalDavConnectionState.ErrorField.CREDENTIALS,
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { onPasswordVisibilityChange(!passwordVisible) }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = if (passwordVisible) "Hide password" else "Show password"
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
                    if (state.serverUrl.isNotBlank() &&
                        state.username.isNotBlank() &&
                        state.password.isNotBlank()
                    ) {
                        onConnect()
                    }
                }
            ),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Display Name field (auto-populated from server URL + username, user can override)
        val displayNameHasError = state.errorField == CalDavConnectionState.ErrorField.DISPLAY_NAME
        OutlinedTextField(
            value = state.displayName,
            onValueChange = onDisplayNameChange,
            label = { Text("Display Name") },
            supportingText = if (displayNameHasError && state.error != null) {
                { Text(state.error, color = MaterialTheme.colorScheme.error) }
            } else {
                { Text("Name shown in settings") }
            },
            singleLine = true,
            isError = displayNameHasError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { focusManager.clearFocus() }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    if (displayNameHasError && state.error != null) {
                        error(state.error)
                    }
                }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Trust insecure connection checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onTrustInsecureChange(!state.trustInsecure) }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = state.trustInsecure,
                onCheckedChange = onTrustInsecureChange
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                Icons.Default.Security,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (state.trustInsecure) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    "Trust insecure connection",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    stringResource(R.string.caldav_trust_insecure_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Error message
        if (state.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    state.error,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Connect button
        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            enabled = state.serverUrl.isNotBlank() &&
                    state.username.isNotBlank() &&
                    state.password.isNotBlank()
        ) {
            Text("Connect", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Cancel button
        TextButton(onClick = onDismiss) {
            Text("Cancel")
        }
    }
}

@Composable
private fun ConnectingContent(state: CalDavConnectionState.Discovering) {
    // Show fields disabled during connection
    OutlinedTextField(
        value = state.serverUrl,
        onValueChange = {},
        label = { Text("Server URL") },
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = state.username,
        onValueChange = {},
        label = { Text("Username") },
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = "********",
        onValueChange = {},
        label = { Text("Password") },
        enabled = false,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(24.dp))

    Button(
        onClick = {},
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
        enabled = false
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Connecting...", style = MaterialTheme.typography.titleMedium)
    }
}
