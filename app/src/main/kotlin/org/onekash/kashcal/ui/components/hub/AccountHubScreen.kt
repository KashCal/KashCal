package org.onekash.kashcal.ui.components.hub

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AppShortcut
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.onekash.kashcal.R
import org.onekash.kashcal.data.preferences.KashCalDataStore
import org.onekash.kashcal.ui.appicon.AppIconUtility
import org.onekash.kashcal.ui.components.formatBadgeCount
import org.onekash.kashcal.ui.components.pickers.AccentColorSheet
import org.onekash.kashcal.ui.components.pickers.WidgetAccentColorSheet
import org.onekash.kashcal.ui.screens.settings.AppIconSheet
import org.onekash.kashcal.ui.screens.settings.SettingsInfoButton
import org.onekash.kashcal.ui.screens.settings.SettingsRowInfo
import org.onekash.kashcal.ui.screens.settings.ThemeSheet
import org.onekash.kashcal.ui.screens.settings.WidgetThemeSheet
import org.onekash.kashcal.ui.shared.EventColorPalette
import org.onekash.kashcal.ui.theme.ColorSource
import org.onekash.kashcal.ui.theme.ThemeMode
import org.onekash.kashcal.ui.viewmodels.AppearanceViewModel
import org.onekash.kashcal.util.ExternalLinks
import org.onekash.kashcal.widget.WidgetColorSource
import org.onekash.kashcal.widget.WidgetThemeSource

/**
 * Shows the full-screen account hub opened from the top bar's avatar.
 *
 * It has its own untitled top bar with a back arrow, and a [BackHandler] so system back
 * dismisses it through the same [onBack] as the arrow. A hero avatar edits the user's initials
 * inline. Below it: Accounts & settings, the personalization rows, the calendar destinations,
 * a Privacy & security section, and About.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountHubScreen(
    pendingInvitesCount: Int,
    userInitials: String,
    onInitialsChange: (String) -> Unit,
    onInvitesClick: () -> Unit,
    onJumpToDateClick: () -> Unit,
    onShareAvailabilityClick: () -> Unit,
    onTagsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onAboutClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // App lock lives here, not in Settings, because the host activity owns the BiometricPrompt.
    // The defaults leave the row inert until the host wires it.
    appLockEnabled: Boolean = false,
    onToggleAppLock: (Boolean) -> Unit = {},
    // App permissions opens as a full-screen destination above the hub, so this is a plain
    // navigation row; the host owns the destination and its permission launchers.
    onAppPermissionsClick: () -> Unit = {},
    // Recent changes opens a sheet over the hub, like Invites.
    onRecentChangesClick: () -> Unit = {},
    // A slot so tests can stub it: the real section gets an AppearanceViewModel through
    // hiltViewModel(), which a plain Compose test has no graph for.
    makeItYours: @Composable () -> Unit = { MakeItYoursSection() },
    // For confirmations that fire while the hub is up, such as the app-lock toggle's. The hub
    // is an opaque overlay above the caller's Scaffold, which would hide the caller's host.
    snackbarHost: @Composable () -> Unit = {},
) {
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            HubHero(
                initials = userInitials,
                onInitialsChange = onInitialsChange,
            )

            // Accounts & settings is a centered pill under the avatar so it reads as an action
            // on "you", not a stray row above the sections. Outlined: a solid primary button
            // dominated the hub. Its accent label matches the section headers below.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                OutlinedButton(onClick = onSettingsClick) {
                    Icon(
                        Icons.Default.ManageAccounts,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.hub_accounts_and_settings),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            // Personalization sits next to the identity avatar.
            HubSectionHeader(stringResource(R.string.hub_section_make_it_yours))
            makeItYours()

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            HubSectionHeader(stringResource(R.string.hub_section_own_your_calendar))
            HubDrawerItem(
                label = stringResource(R.string.menu_invites),
                icon = Icons.Default.MailOutline,
                onClick = onInvitesClick,
                badge = { formatBadgeCount(pendingInvitesCount)?.let { Badge { Text(it) } } },
            )
            // No count badge: rows stay for days, so a count would sit unchanged.
            HubDrawerItem(
                label = stringResource(R.string.recent_changes_title),
                icon = Icons.Default.History,
                onClick = onRecentChangesClick,
            )
            HubDrawerItem(
                label = stringResource(R.string.jump_to_date),
                icon = Icons.Default.CalendarMonth,
                onClick = onJumpToDateClick,
            )
            HubDrawerItem(
                label = stringResource(R.string.share_availability_rail_label),
                icon = Icons.Default.Share,
                onClick = onShareAvailabilityClick,
            )
            // Tag management opens on top of the hub, like Settings, without swapping the
            // calendar view, so it has no `selected` state and the hub stays mounted beneath.
            HubDrawerItem(
                label = stringResource(R.string.tags_row_label),
                icon = Icons.Default.LocalOffer,
                onClick = onTagsClick,
            )

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            HubSectionHeader(stringResource(R.string.menu_privacy_security))
            HubAppLockRow(
                checked = appLockEnabled,
                onCheckedChange = onToggleAppLock,
            )
            HubDrawerItem(
                label = stringResource(R.string.hub_app_permissions),
                icon = Icons.Default.Security,
                onClick = onAppPermissionsClick,
            )
            PrivacyDataOwnershipRow()

            HorizontalDivider(modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))

            HubDrawerItem(
                label = stringResource(R.string.menu_about),
                icon = Icons.Default.Info,
                onClick = onAboutClick,
            )
        }
    }
}

/** Draws the small round color chip that trails an accent row's value. */
@Composable
private fun ColorSwatch(argb: Int) {
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(Color(argb)),
    )
}

@Composable
private fun HubDrawerItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    badgeText: String? = null,
    badgeTrailing: (@Composable () -> Unit)? = null,
    badge: @Composable (() -> Unit)? = null,
) {
    // A row takes a current-value string (badgeText) or a custom badge slot (a count), never
    // both: passing both would silently drop the badge.
    require(badgeText == null || badge == null) {
        "HubDrawerItem takes badgeText or badge, not both"
    }
    // badgeTrailing is a fixed-size element (a color swatch) pinned after the value string.
    require(badgeTrailing == null || badgeText != null) {
        "HubDrawerItem badgeTrailing requires badgeText"
    }
    NavigationDrawerItem(
        // The value string renders inside the label row so the label keeps its width and the
        // value truncates in the leftover. NavigationDrawerItem's badge slot isn't weighted,
        // so a long value there would squish the weighted label ("Theme" in locales with a
        // long mode name).
        label = {
            if (badgeText != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, maxLines = 1)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = badgeText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    badgeTrailing?.let {
                        Spacer(Modifier.width(8.dp))
                        it()
                    }
                }
            } else {
                Text(label)
            }
        },
        icon = { Icon(icon, contentDescription = null) },
        badge = if (badgeText != null) null else badge,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * Shows the app-lock toggle as a hub row, so its icon sits at the 28dp inset of every
 * [HubDrawerItem] and it keeps the hub's row height instead of the denser settings-row inset.
 * A trailing ⓘ explains the setting, and the whole row toggles the lock.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HubAppLockRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val label = stringResource(R.string.app_lock_label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onCheckedChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SettingsInfoButton(
            SettingsRowInfo(
                title = label,
                text = stringResource(R.string.settings_app_lock_info),
            ),
        )
        Spacer(Modifier.width(4.dp))
        // Opting out of the 48dp minimum keeps the switch from making the row taller than its
        // neighbours.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun HubSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Shows the personalization rows (theme, accent color, app icon) and the widget rows (widget
 * design, widget accent), which are independent of the app face. [AppearanceViewModel] drives
 * every row except the app icon, which [AppIconUtility] reads and sets. Each row opens its
 * picker sheet over the hub, and the sheet dismisses back to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MakeItYoursSection() {
    val vm: AppearanceViewModel = hiltViewModel()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val colorSource by vm.colorSource.collectAsStateWithLifecycle(initialValue = ColorSource.DYNAMIC)
    val accentSeed by vm.accentSeed.collectAsStateWithLifecycle(initialValue = KashCalDataStore.ACCENT_SEED_DEFAULT)
    val widgetThemeSource by vm.widgetThemeSource.collectAsStateWithLifecycle(initialValue = WidgetThemeSource.FOLLOW_APP)
    val widgetColorSource by vm.widgetColorSource.collectAsStateWithLifecycle(initialValue = WidgetColorSource.FOLLOW_APP)
    val widgetAccentSeed by vm.widgetAccentSeed.collectAsStateWithLifecycle(initialValue = KashCalDataStore.ACCENT_SEED_DEFAULT)

    val context = LocalContext.current
    val appIconUtility = remember(context) { AppIconUtility(context) }
    var currentAppIcon by remember { mutableStateOf(appIconUtility.currentPreset()) }

    var showThemeSheet by rememberSaveable { mutableStateOf(false) }
    var showAccentSheet by rememberSaveable { mutableStateOf(false) }
    var showAppIconSheet by rememberSaveable { mutableStateOf(false) }
    var showWidgetThemeSheet by rememberSaveable { mutableStateOf(false) }
    var showWidgetAccentSheet by rememberSaveable { mutableStateOf(false) }

    HubDrawerItem(
        label = stringResource(R.string.settings_theme),
        icon = Icons.Default.BrightnessMedium,
        onClick = { showThemeSheet = true },
        badgeText = stringResource(themeMode.labelRes),
    )
    val accentSubtitle = when {
        colorSource != ColorSource.SEED -> stringResource(R.string.settings_accent_color_dynamic)
        // Brand teal isn't a CSS3 palette entry, so it would otherwise read as "Custom".
        accentSeed == KashCalDataStore.ACCENT_SEED_DEFAULT -> stringResource(R.string.settings_accent_color_brand)
        else -> stringResource(EventColorPalette.stringResIdForColor(accentSeed))
    }
    HubDrawerItem(
        label = stringResource(R.string.settings_accent_color),
        icon = Icons.Default.Palette,
        onClick = { showAccentSheet = true },
        badgeText = accentSubtitle,
        badgeTrailing = if (colorSource == ColorSource.SEED) {
            { ColorSwatch(accentSeed) }
        } else {
            null
        },
    )
    HubDrawerItem(
        label = stringResource(R.string.settings_app_icon),
        icon = Icons.Default.AppShortcut,
        onClick = { showAppIconSheet = true },
        badgeText = stringResource(currentAppIcon.labelRes),
    )

    // Widgets have their own light/dark face and color source; their rows stay flat under
    // "Make it yours", with no nested sub-header.
    HubDrawerItem(
        label = stringResource(R.string.hub_widget_theme),
        icon = Icons.Default.Widgets,
        onClick = { showWidgetThemeSheet = true },
        badgeText = stringResource(widgetThemeSource.labelRes),
    )
    val widgetAccentSubtitle = when {
        widgetColorSource == WidgetColorSource.FOLLOW_APP -> stringResource(R.string.settings_widget_color_follow_app)
        widgetColorSource == WidgetColorSource.DYNAMIC -> stringResource(R.string.settings_accent_color_dynamic)
        // Brand teal isn't a CSS3 palette entry, so it would otherwise read as "Custom".
        widgetAccentSeed == KashCalDataStore.ACCENT_SEED_DEFAULT -> stringResource(R.string.settings_accent_color_brand)
        else -> stringResource(EventColorPalette.stringResIdForColor(widgetAccentSeed))
    }
    HubDrawerItem(
        label = stringResource(R.string.hub_widget_accent),
        icon = Icons.Default.Palette,
        onClick = { showWidgetAccentSheet = true },
        badgeText = widgetAccentSubtitle,
        badgeTrailing = if (widgetColorSource == WidgetColorSource.SEED) {
            { ColorSwatch(widgetAccentSeed) }
        } else {
            null
        },
    )

    if (showThemeSheet) {
        ThemeSheet(
            sheetState = rememberModalBottomSheetState(),
            currentMode = themeMode,
            onModeSelect = { vm.setThemeMode(it) },
            onDismiss = { showThemeSheet = false },
        )
    }
    if (showAccentSheet) {
        AccentColorSheet(
            selectedArgb = accentSeed,
            useDynamic = colorSource == ColorSource.DYNAMIC,
            onColorSelected = { vm.setAccentSeed(it); showAccentSheet = false },
            onUseDynamic = { vm.setColorSource(ColorSource.DYNAMIC); showAccentSheet = false },
            onDismiss = { showAccentSheet = false },
        )
    }
    if (showWidgetThemeSheet) {
        WidgetThemeSheet(
            sheetState = rememberModalBottomSheetState(),
            currentSource = widgetThemeSource,
            onSourceSelect = { vm.setWidgetThemeSource(it) },
            onDismiss = { showWidgetThemeSheet = false },
        )
    }
    if (showWidgetAccentSheet) {
        WidgetAccentColorSheet(
            source = widgetColorSource,
            selectedArgb = widgetAccentSeed,
            onFollowApp = { vm.setWidgetColorSource(WidgetColorSource.FOLLOW_APP); showWidgetAccentSheet = false },
            onUseDynamic = { vm.setWidgetColorSource(WidgetColorSource.DYNAMIC); showWidgetAccentSheet = false },
            onColorSelected = { vm.setWidgetAccentSeed(it); showWidgetAccentSheet = false },
            onDismiss = { showWidgetAccentSheet = false },
        )
    }
    if (showAppIconSheet) {
        AppIconSheet(
            // Fully expanded so the icon options, support link and note show without a drag.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            currentPreset = currentAppIcon,
            onPresetSelect = { preset ->
                // Re-toggling the active alias refreshes the launcher for nothing and can
                // briefly restart the app.
                if (preset != currentAppIcon) {
                    appIconUtility.setAppIcon(preset)
                    currentAppIcon = preset
                }
            },
            onSupportClick = { ExternalLinks.openUrl(context, ExternalLinks.DONATE) },
            onDismiss = { showAppIconSheet = false },
        )
    }
}

/**
 * Shows the hero avatar, which swaps into an inline two-letter editor when tapped. The
 * transitions live in [InitialsEditorState] so they are unit tested off-device.
 */
@Composable
private fun HubHero(
    initials: String,
    onInitialsChange: (String) -> Unit,
) {
    // Saveable so an in-progress edit survives rotation, as the hub's showHub flag does. Not
    // keyed on `initials`: syncCurrent adopts external changes and is a no-op mid-edit, so a
    // sync or backup re-emit can't wipe the draft.
    val editor = rememberSaveable(saver = InitialsEditorState.Saver) {
        InitialsEditorState(current = initials)
    }
    LaunchedEffect(initials) { editor.syncCurrent(initials) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (editor.isEditing) {
            // The user tapped to type, so focus the field (raising the keyboard) on entering
            // edit mode.
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            OutlinedTextField(
                value = editor.draft,
                onValueChange = editor::onType,
                singleLine = true,
                label = { Text(stringResource(R.string.hub_initials_field_label)) },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { onInitialsChange(editor.save()) }),
                modifier = Modifier
                    .width(120.dp)
                    .focusRequester(focusRequester),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = editor::cancel) {
                    Text(stringResource(R.string.hub_initials_cancel))
                }
                OutlinedButton(onClick = { onInitialsChange(editor.save()) }) {
                    Text(stringResource(R.string.hub_initials_save))
                }
            }
        } else {
            val editLabel = stringResource(R.string.cd_edit_initials)
            Box(
                // No clip: a circular clip here would cut off the bottom-end pencil badge. The
                // avatar clips its own background.
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = editor::start)
                    // A real name, not only an action label, so TalkBack announces the hero;
                    // in the empty state the avatar is a glyph with no text of its own.
                    .semantics(mergeDescendants = true) { contentDescription = editLabel },
            ) {
                AccountAvatar(initials = initials, size = 76.dp, fontSize = 30.sp)
                // Marks the avatar editable in both states; the hint below shows only when
                // empty.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (normalizeInitials(initials).isEmpty()) {
                Text(
                    text = stringResource(R.string.hub_initials_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Shows the external link to the data-ownership policy, with an open-in-new icon. */
@Composable
private fun PrivacyDataOwnershipRow() {
    val context = LocalContext.current
    val label = stringResource(R.string.hub_privacy_data_ownership)
    val opensInBrowser = stringResource(R.string.cd_opens_in_browser)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { ExternalLinks.openUrl(context, ExternalLinks.PRIVACY) }
            // The OpenInNew glyph is the only "leaves the app" cue and TalkBack skips it, so the
            // merged label carries "Opens in browser".
            .semantics(mergeDescendants = true) { contentDescription = "$label, $opensInBrowser" }
            .padding(horizontal = 28.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}
