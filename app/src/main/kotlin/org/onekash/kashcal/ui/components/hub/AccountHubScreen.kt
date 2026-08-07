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
 * Full-screen "account hub" that replaces the former overflow bottom sheet.
 *
 * Structured like the Insights destination: its own top bar with a back arrow
 * (no title) and a [BackHandler] so the system back gesture/button dismiss it
 * through the same [onBack] path as the arrow. A hero avatar at the top edits
 * the user's initials inline; below are the same destinations the overflow menu
 * offered, with a Privacy & Security link at the bottom of the list.
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
    // App lock lives here (not Settings) because its host activity owns the
    // BiometricPrompt. Defaults keep the section inert until the host wires it.
    appLockEnabled: Boolean = false,
    onToggleAppLock: (Boolean) -> Unit = {},
    // App permissions opens as a full-screen destination above the hub (like
    // Manage tags), so it's a plain navigation row here; the host owns the
    // destination and its permission launchers.
    onAppPermissionsClick: () -> Unit = {},
    // Personalization rows are hoisted as a slot so they can be stubbed in tests:
    // the real section pulls an AppearanceViewModel via hiltViewModel(), which a
    // plain Compose test has no graph to satisfy.
    makeItYours: @Composable () -> Unit = { MakeItYoursSection() },
    // Snackbar host for confirmations that fire while the hub is up (e.g. the
    // app-lock toggle). The hub is an opaque overlay above the caller's Scaffold,
    // so its snackbar host would be hidden; the caller passes one mounted here.
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

            // Accounts + app configuration presented as a centered pill tied to
            // the identity block above, so it reads as an action on "you" rather
            // than a stray list row floating above the sections. An outlined button
            // gives a clear tap affordance (the border) without the heavy solid
            // fill of a primary button, which dominated the hub. Its accent-colored
            // label matches the "Make it yours" header below it — the pill and the
            // section headers share one accent tone, so they read as a coherent
            // identity block. A leading glyph aids scannability.
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

            // Personalization (theme, accent, app icon) sits next to the
            // identity avatar.
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
            // Tag management. Opens on top of the hub (like Settings) rather than
            // swapping the calendar view, so it carries no `selected` state and the
            // hub stays mounted beneath it.
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
            // Link to the data-ownership policy, closing the privacy section.
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

@Composable
private fun HubDrawerItem(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    selected: Boolean = false,
    badge: @Composable (() -> Unit)? = null,
) {
    NavigationDrawerItem(
        label = { Text(label) },
        icon = { Icon(icon, contentDescription = null) },
        badge = badge,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

/**
 * App-lock toggle rendered as a hub-native row so its icon lands at the same
 * 28dp inset as every [HubDrawerItem] and it keeps the hub's row height (rather
 * than the denser settings-row inset and padding). A trailing ⓘ explains the
 * setting; the switch opts out of the 48dp minimum so the row doesn't stand
 * taller than its neighbours, and the whole row toggles the lock.
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
        // Opt out of the 48dp minimum so the switch keeps the row at hub-row
        // height instead of standing taller.
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
 * Personalization rows (theme, accent color, app icon) driven by
 * [AppearanceViewModel], plus the widget-appearance rows (widget design, widget
 * accent) which are independent of the app face. Each row opens the same
 * reusable sheet the settings screen used, so there's no duplicated picker
 * logic; the sheets render on top of the hub and dismiss back to it.
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
        badge = { Text(stringResource(themeMode.labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(accentSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (colorSource == ColorSource.SEED) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(accentSeed)),
                    )
                }
            }
        },
    )
    HubDrawerItem(
        label = stringResource(R.string.settings_app_icon),
        icon = Icons.Default.AppShortcut,
        onClick = { showAppIconSheet = true },
        badge = { Text(stringResource(currentAppIcon.labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
    )

    // Widgets get their own light/dark face and color source, independent of the
    // app face above; the rows stay flat under "Make it yours" rather than under
    // a nested sub-header.
    HubDrawerItem(
        label = stringResource(R.string.hub_widget_theme),
        icon = Icons.Default.Widgets,
        onClick = { showWidgetThemeSheet = true },
        badge = { Text(stringResource(widgetThemeSource.labelRes), color = MaterialTheme.colorScheme.onSurfaceVariant) },
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
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(widgetAccentSubtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (widgetColorSource == WidgetColorSource.SEED) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color(widgetAccentSeed)),
                    )
                }
            }
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
            // Open fully expanded so the icon options + support link + note are all
            // visible at once, not half-height requiring a drag-up.
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            currentPreset = currentAppIcon,
            onPresetSelect = { preset ->
                // Skip the no-op: re-toggling the active alias needlessly refreshes
                // the launcher (and can briefly restart the app).
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
 * Hero header: a large avatar that swaps into an inline 2-letter editor when
 * tapped. State transitions live in [InitialsEditorState] so they're unit
 * tested off-device.
 */
@Composable
private fun HubHero(
    initials: String,
    onInitialsChange: (String) -> Unit,
) {
    // Saveable so an in-progress edit survives rotation (the hub's showHub flag
    // does too). Not keyed on `initials`: instead adopt external changes via
    // syncCurrent, which no-ops mid-edit so a sync/backup re-emit can't wipe the
    // user's draft.
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
            // The user tapped specifically to type their initials, so focus the
            // field (which raises the soft keyboard) as soon as edit mode begins.
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
                // No clip here: the pencil badge sits at the bottom-end corner and a
                // circular clip on this wrapper would cut it off. The avatar clips
                // its own circular background internally.
                modifier = Modifier
                    .clickable(role = Role.Button, onClick = editor::start)
                    // A real name (not just an onClick action label) so TalkBack
                    // announces the hero — critical in the empty state, where the
                    // avatar is a glyph with no text of its own.
                    .semantics(mergeDescendants = true) { contentDescription = editLabel },
            ) {
                AccountAvatar(initials = initials, size = 76.dp, fontSize = 30.sp)
                // Small pencil badge marks the avatar as an editable field in both
                // the empty and set states (the hint text below only shows when empty).
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
            // Only prompt when there's nothing set yet; once initials exist the
            // pencil badge alone signals the avatar is editable.
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

/** External link to the data-ownership policy, with an open-in-new affordance. */
@Composable
private fun PrivacyDataOwnershipRow() {
    val context = LocalContext.current
    val label = stringResource(R.string.hub_privacy_data_ownership)
    val opensInBrowser = stringResource(R.string.cd_opens_in_browser)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button) { ExternalLinks.openUrl(context, ExternalLinks.PRIVACY) }
            // The OpenInNew glyph is the only "leaves the app" cue and is decorative
            // to TalkBack, so fold "Opens in browser" into the row's merged label.
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
