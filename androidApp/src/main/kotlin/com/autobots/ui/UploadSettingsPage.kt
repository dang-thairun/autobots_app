package com.autobots.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.autobots.camera.upload.RunxAuthClient
import com.autobots.camera.upload.UploadAuthUiState
import com.autobots.camera.upload.UploadConfig
import com.autobots.camera.upload.UploadSession
import com.autobots.camera.upload.UploadSettings

private val Accent = Color(0xFF80CBC4)
private val Muted = Color(0xFF90A4AE)
private val Bad = Color(0xFFEF9A9A)
private val Good = Color(0xFFA5D6A7)

/**
 * Where the operator points the app at a backend and says who they are (B3d/B3e — see
 * docs/PHASES.md).
 *
 * Three groups, in the order they have to happen: **endpoints** (from `.env` or a QR, rarely
 * touched), **sign in** (every launch — the token is never stored), then **which event**
 * today's photos belong to. The last two used to be a bearer token and a 24-character MongoID
 * typed by hand on a phone; both now come from the backend itself.
 */
@Composable
fun UploadSettingsPage(
    config: UploadConfig,
    account: UploadSession.SignedIn?,
    auth: UploadAuthUiState,
    remembered: UploadSettings.Credentials?,
    onSave: (UploadConfig) -> Unit,
    onSignIn: (username: String, password: String, remember: Boolean) -> Unit,
    onSignOut: () -> Unit,
    onLoadEvents: () -> Unit,
    onSetScope: (RunxAuthClient.EventScope) -> Unit,
    onSetSearch: (String) -> Unit,
    onSelectEvent: (RunxAuthClient.EventSummary) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraPermission = rememberCameraPermissionState()
    var draft by remember(config) { mutableStateOf(config) }
    var scanning by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }

    var username by remember(remembered) { mutableStateOf(remembered?.username.orEmpty()) }
    var password by remember(remembered) { mutableStateOf(remembered?.password.orEmpty()) }
    var rememberMe by remember(remembered) { mutableStateOf(remembered != null) }

    if (cameraPermission.granted && pendingScan) {
        scanning = true
        pendingScan = false
    }

    // Signed in with an empty picker means the operator arrived here from a paused queue or a
    // fresh launch; fetching without being asked saves a tap on every single one of those.
    LaunchedEffect(account) {
        if (account != null && !auth.loaded && !auth.loadingEvents) onLoadEvents()
    }

    val problem = draft.validate()
    val dirty = draft != config

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "←  Upload settings",
            color = Color.White,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .clickable(onClick = onBack)
                .padding(bottom = 4.dp),
        )

        SectionLabel("Backend")
        ConfigField("GraphQL URL", draft.graphqlUrl, KeyboardType.Uri) {
            draft = draft.copy(graphqlUrl = it)
        }
        ConfigField("Complete URL", draft.completeUrl, KeyboardType.Uri) {
            draft = draft.copy(completeUrl = it)
        }
        ConfigField("Platform", draft.platform) { draft = draft.copy(platform = it) }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    scanError = null
                    when {
                        scanning -> scanning = false
                        cameraPermission.granted -> scanning = true
                        else -> {
                            pendingScan = true
                            cameraPermission.request()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = when {
                        scanning -> "Close scanner"
                        cameraPermission.granted -> "Scan QR"
                        else -> "Allow camera"
                    },
                    maxLines = 1,
                )
            }
            Button(
                onClick = { onSave(draft) },
                enabled = dirty,
                modifier = Modifier.weight(1f),
                colors = disabledLook(),
            ) {
                Text(text = if (dirty) "Save" else "Saved", maxLines = 1)
            }
        }

        if (scanning && cameraPermission.granted) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                QrScanPreview(
                    onQr = { payload ->
                        val parsed = UploadConfig.parseQr(draft, payload)
                        if (parsed == null) {
                            // A QR from somewhere else must not wipe a working config.
                            scanError = "That QR is not an upload configuration"
                        } else {
                            draft = parsed.config
                            scanError = null
                        }
                        scanning = false
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // --- sign in ---------------------------------------------------------------------

        SectionLabel("Sign in")
        if (account == null) {
            ConfigField("Username", username) { username = it }
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = fieldColors(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { rememberMe = !rememberMe },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = rememberMe,
                    onCheckedChange = { rememberMe = it },
                    colors = CheckboxDefaults.colors(checkedColor = Accent),
                )
                Text(
                    text = "Remember username and password on this device",
                    color = Muted,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Button(
                onClick = { onSignIn(username.trim(), password, rememberMe) },
                enabled = !auth.busy && username.isNotBlank() && password.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = disabledLook(),
            ) {
                Text(if (auth.busy) "Signing in…" else "Sign in")
            }
            Text(
                text = "The token is kept in memory only, so signing in is needed once per app start.",
                color = Color(0xFF607D8B),
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Text(
                text = "Signed in as ${account.username}" +
                    (account.role?.let { " · $it" } ?: ""),
                color = Good,
                style = MaterialTheme.typography.labelMedium,
            )
            // Not the token itself. It is an admin credential and this screen is often held up
            // in front of other people; enough to confirm the right one is loaded, no more.
            Text(
                text = "Token in use: ${UploadConfig.tokenPreview(account.token)}",
                color = Color(0xFF78909C),
                style = MaterialTheme.typography.labelSmall,
            )
            TextButton(onClick = onSignOut) {
                Text("Sign out", color = Bad)
            }
        }

        // --- event ------------------------------------------------------------------------

        SectionLabel("Event")
        if (account == null) {
            Text(
                text = "Sign in to choose an event." +
                    if (config.eventId.isNotBlank()) {
                        "\nCurrently set to ${config.eventTitle.ifBlank { config.eventId }}"
                    } else {
                        ""
                    },
                color = Muted,
                style = MaterialTheme.typography.labelSmall,
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RunxAuthClient.EventScope.entries.forEach { scope ->
                    Button(
                        onClick = { onSetScope(scope) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (auth.scope == scope) Accent else Color.White.copy(alpha = 0.14f),
                            contentColor = if (auth.scope == scope) Color.Black else Color.White,
                        ),
                    ) {
                        Text(scope.label, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }

            // The list is the interface: it loads itself after sign-in and every event the
            // account can see is in it. Nothing here should require typing.
            EventPicker(
                events = auth.events,
                selectedId = config.eventId,
                selectedTitle = config.eventTitle,
                loading = auth.loadingEvents,
                loaded = auth.loaded,
                onSelect = onSelectEvent,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onLoadEvents, enabled = !auth.loadingEvents) {
                    Text(
                        text = if (auth.loadingEvents) "Loading…" else "Refresh list",
                        color = Accent,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (auth.loaded && auth.events.isNotEmpty()) {
                    Text(
                        text = "${auth.events.size} events",
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            // Search appears only when the list genuinely cannot hold everything — otherwise
            // it is a box that asks the operator to do work the dropdown already did.
            if (auth.truncated) {
                Text(
                    text = "More events than one list can hold — narrow it down by title.",
                    color = Color(0xFFFFCC80),
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = auth.search,
                        onValueChange = onSetSearch,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("Filter by title") },
                        colors = fieldColors(),
                    )
                    Button(onClick = onLoadEvents, enabled = !auth.loadingEvents) {
                        Text("Find", maxLines = 1)
                    }
                }
            }
        }

        // --- status -----------------------------------------------------------------------

        val message = scanError ?: auth.error ?: problem
        Text(
            text = message ?: "Ready — uploads will use this backend from B3e onward.",
            color = when {
                scanError != null || auth.error != null -> Bad
                problem != null -> Muted
                else -> Good
            },
            style = MaterialTheme.typography.labelSmall,
        )

        Text(
            text = "A provisioning QR holds JSON. Fields it leaves out keep their current value:\n${UploadConfig.QR_EXAMPLE}",
            color = Color(0xFF607D8B),
            style = MaterialTheme.typography.labelSmall,
        )

        TextButton(onClick = onClear, enabled = config != UploadConfig()) {
            Text("Clear configuration", color = Bad)
        }
    }
}

/**
 * The picker itself.
 *
 * An empty list after a completed fetch says so out loud rather than showing an empty menu —
 * that state usually means the scope filter is the wrong one for this account, and a silent
 * empty dropdown reads as a broken app.
 */
@Composable
private fun EventPicker(
    events: List<RunxAuthClient.EventSummary>,
    selectedId: String,
    selectedTitle: String,
    loading: Boolean,
    loaded: Boolean,
    onSelect: (RunxAuthClient.EventSummary) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { expanded = !expanded },
            enabled = events.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            colors = disabledLook(),
        ) {
            Text(
                text = when {
                    loading -> "Loading events…"
                    events.isEmpty() && loaded -> "No events for this account"
                    events.isEmpty() -> "No events loaded"
                    selectedId.isBlank() -> "Select an event (${events.size})"
                    else -> selectedTitle.ifBlank { selectedId }
                },
                maxLines = 1,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // The app is dark; the menu's default surface is not, and white-on-white is what
            // that gives. Painting the container is cheaper than theming all of Material here.
            modifier = Modifier
                .heightIn(max = 320.dp)
                .background(Color(0xFF1E272C)),
        ) {
            events.forEach { event ->
                DropdownMenuItem(
                    onClick = {
                        onSelect(event)
                        expanded = false
                    },
                    text = {
                        Column {
                            Text(
                                text = event.title,
                                color = if (event.id == selectedId) Accent else Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(
                                text = listOfNotNull(
                                    event.day,
                                    event.photoCount?.let { "$it photos" },
                                    if (event.approved == false) "not approved" else null,
                                ).joinToString(" · "),
                                color = Muted,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    },
                )
            }
        }
    }

    if (selectedId.isNotBlank()) {
        Text(
            text = "Event ID: $selectedId",
            color = Color(0xFF78909C),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = Accent,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun disabledLook() = ButtonDefaults.buttonColors(
    disabledContainerColor = Color.White.copy(alpha = 0.14f),
    disabledContentColor = Color.White.copy(alpha = 0.55f),
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = Color.White,
    focusedBorderColor = Accent,
    unfocusedBorderColor = Color.White.copy(alpha = 0.35f),
    focusedLabelColor = Accent,
    unfocusedLabelColor = Muted,
)

@Composable
private fun ConfigField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = fieldColors(),
    )
}
