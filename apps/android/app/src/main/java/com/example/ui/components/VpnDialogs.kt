package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.UserProfile
import com.example.ui.theme.AccentActive
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.Hairline
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MintAccent
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg
import com.example.update.UpdateState
import java.util.Locale

/**
 * Read-Only User Profile Sheet.
 * Profile Name and Email Address are locked at login/registration and cannot be edited here.
 * Does NOT show Active Node Telemetry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSheet(
    userProfile: UserProfile,
    installedVersionName: String,
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenDownloadPage: () -> Unit,
    onLogout: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val lockedFieldColors = OutlinedTextFieldDefaults.colors(
        disabledBorderColor = MutedSageText.copy(alpha = 0.28f),
        disabledTextColor = LightMintGlow.copy(alpha = 0.9f),
        disabledLabelColor = MutedSageText,
        disabledTrailingIconColor = MutedSageText.copy(alpha = 0.55f),
        disabledContainerColor = DeepForestBg.copy(alpha = 0.35f)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackDarkBg,
        contentColor = LightMintGlow,
        scrimColor = DeepForestBg.copy(alpha = 0.78f)
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .testTag("profile_bottom_sheet")
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiquidGlassBox(
                        modifier = Modifier.size(44.dp),
                        cornerRadius = 22.dp
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = LightMintGlow
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.cd_profile),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow
                            )
                        )
                        if (userProfile.uid.isNotBlank()) {
                            Text(
                                text = "UID: ${userProfile.uid}",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 11.sp,
                                    color = MutedSageText
                                )
                            )
                        }
                    }
                }

                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.testTag("logout_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ExitToApp,
                        contentDescription = null,
                        tint = MutedSageText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.action_logout),
                        color = MutedSageText,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Read-Only Profile Name (registered at login, cannot be modified)
            OutlinedTextField(
                value = userProfile.displayName,
                onValueChange = {},
                enabled = false,
                readOnly = true,
                label = { Text(stringResource(R.string.auth_name_label)) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = lockedFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_name_readonly")
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Read-Only Email Address (registered at login, cannot be modified)
            OutlinedTextField(
                value = userProfile.email,
                onValueChange = {},
                enabled = false,
                readOnly = true,
                label = { Text(stringResource(R.string.auth_name_label).let { stringResource(R.string.auth_email_label) }) },
                trailingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = lockedFieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("profile_email_readonly")
            )

            Spacer(modifier = Modifier.height(24.dp))

            HorizontalDivider(thickness = 0.5.dp, color = Hairline)

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "APP UPDATE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MutedSageText,
                        letterSpacing = 1.8.sp
                    )
                )
                if (installedVersionName.isNotBlank()) {
                    Text(
                        text = "v$installedVersionName",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 11.sp,
                            color = MutedSageText.copy(alpha = 0.75f)
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            UpdateStatusRow(
                updateState = updateState,
                onCheckForUpdates = onCheckForUpdates,
                onDownloadUpdate = onDownloadUpdate,
                onInstallUpdate = onInstallUpdate
            )

            TextButton(
                onClick = onOpenDownloadPage,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .padding(top = 2.dp)
                    .testTag("open_download_page")
            ) {
                Text(
                    text = "Open direct download page",
                    color = MintAccent.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }
        }
    }
}

@Composable
private fun UpdateStatusRow(
    updateState: UpdateState,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit
) {
    val title: String
    val subtitle: String
    val actionLabel: String?
    val action: (() -> Unit)?

    when (updateState) {
        is UpdateState.Idle -> {
            title = "Check for updates"
            subtitle = "Fetch the latest signed build from the server."
            actionLabel = "Check"
            action = onCheckForUpdates
        }
        is UpdateState.Checking -> {
            title = "Checking for updates…"
            subtitle = "Contacting the release server."
            actionLabel = null
            action = null
        }
        is UpdateState.UpToDate -> {
            title = "You're up to date"
            subtitle = "v${updateState.versionName} is the latest build."
            actionLabel = "Check again"
            action = onCheckForUpdates
        }
        is UpdateState.Available -> {
            title = "Update v${updateState.info.versionName} available"
            subtitle = updateState.info.notes.ifBlank { "A newer signed build is ready to download." }
            actionLabel = "Download"
            action = onDownloadUpdate
        }
        is UpdateState.Downloading -> {
            title = "Downloading v${updateState.info.versionName}"
            subtitle = "${updateState.progress}% · keep the app open"
            actionLabel = null
            action = null
        }
        is UpdateState.ReadyToInstall -> {
            title = "Ready to install v${updateState.info.versionName}"
            subtitle = "Tap install and confirm in Android."
            actionLabel = "Install"
            action = onInstallUpdate
        }
        is UpdateState.Failed -> {
            title = "Update failed"
            subtitle = updateState.message
            actionLabel = "Retry"
            action = onCheckForUpdates
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = LightMintGlow,
                    fontWeight = FontWeight.Medium
                )
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    color = if (updateState is UpdateState.Failed) {
                        MutedSageText.copy(alpha = 0.9f)
                    } else {
                        MutedSageText.copy(alpha = 0.75f)
                    }
                )
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        when {
            updateState is UpdateState.Checking -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MintAccent
            )
            actionLabel != null && action != null -> Button(
                onClick = action,
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, LightMintGlow.copy(alpha = 0.16f)),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentActive,
                    contentColor = LightMintGlow
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (updateState is UpdateState.Downloading) {
        Spacer(modifier = Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { updateState.progress / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MintAccent,
            trackColor = MidDarkElements
        )
    }
}

/**
 * Update prompt raised on every app entry when the server advertises a newer build.
 * It never downloads by itself: the user explicitly accepts the download, then the install.
 */
@Composable
fun UpdatePromptDialog(
    updateState: UpdateState,
    installedVersionName: String,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = when (updateState) {
        is UpdateState.Available -> updateState.info
        is UpdateState.Downloading -> updateState.info
        is UpdateState.ReadyToInstall -> updateState.info
        else -> null
    }
    val downloading = updateState as? UpdateState.Downloading
    val ready = updateState is UpdateState.ReadyToInstall
    val failure = (updateState as? UpdateState.Failed)?.message

    if (info == null && failure == null) return

    val title = when {
        ready -> "Update ready to install"
        downloading != null -> "Downloading update"
        failure != null -> "Update interrupted"
        else -> "Update available"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = TrackDarkBg,
        titleContentColor = LightMintGlow,
        textContentColor = MutedSageText,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.testTag("update_prompt_dialog"),
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = LightMintGlow
                    )
                )
                if (info != null) {
                    Text(
                        text = "v$installedVersionName  →  v${info.versionName}",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 12.sp,
                            color = MutedSageText.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        },
        text = {
            Column {
                when {
                    downloading != null -> {
                        Text(
                            text = "Keep the app open until the download finishes.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                color = MutedSageText
                            )
                        )
                        Text(
                            text = "${downloading.progress}%",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow
                            ),
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { downloading.progress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = MintAccent,
                            trackColor = MidDarkElements
                        )
                    }
                    ready -> Text(
                        text = "The new build is downloaded and verified. Install it to finish updating.",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = MutedSageText
                        )
                    )
                    failure != null -> Text(
                        text = failure,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.sp,
                            color = Color(0xFFFFB4AB)
                        )
                    )
                    else -> Column {
                        if (!info?.notes.isNullOrBlank()) {
                            Text(
                                text = info.notes,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 13.sp,
                                    color = MutedSageText
                                )
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Text(
                            text = buildString {
                                val size = info?.sizeBytes ?: 0L
                                if (size > 0L) {
                                    append(String.format(Locale.US, "%.1f MB", size / 1_048_576f))
                                    append("  •  ")
                                }
                                append("Installs over the current app; account and settings stay intact.")
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                color = MutedSageText.copy(alpha = 0.8f)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            val (label, action) = when {
                downloading != null -> "Hide" to onDismiss
                ready -> "Install now" to onInstall
                failure != null -> "Try again" to onDownload
                else -> "Update now" to onDownload
            }
            TextButton(
                onClick = action,
                modifier = Modifier.testTag("update_prompt_primary")
            ) {
                Text(text = label, color = LightMintGlow)
            }
        },
        dismissButton = {
            if (downloading == null) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("update_prompt_later")
                ) {
                    Text(text = "Later", color = MutedSageText)
                }
            }
        }
    )
}
