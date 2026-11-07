package com.example.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.model.LocationMode
import com.example.model.VpnConnectionState
import com.example.ui.components.AirDropConnectionWaveOverlay
import com.example.ui.components.ConnectionDetailsSheet
import com.example.ui.components.LiquidGlassBox
import com.example.ui.components.LiquidSlider
import com.example.ui.components.LiveTrafficPanel
import com.example.ui.components.LocationBox
import com.example.ui.components.LocationBoxPanel
import com.example.ui.components.SubscriptionHeaderStrip
import com.example.ui.components.TelegramLivingBackground
import com.example.ui.components.LiveDebugSheet
import com.example.ui.components.UpdatePromptDialog
import com.example.ui.components.UserProfileSheet
import com.example.ui.theme.HairlineStrong
import com.example.ui.theme.LoginSurface
import com.example.ui.theme.MintAccent
import com.example.ui.theme.TextPrimary
import com.example.ui.components.airDropContainerWarp
import com.example.ui.components.airDropContentBlur
import com.example.ui.components.airDropElementWarp
import com.example.ui.components.rememberAirDropChoreographyState
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.DeepForestBgIdle
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.LightMintGlow
import com.example.viewmodel.VpnViewModel

@Composable
fun VpnScreen(
    modifier: Modifier = Modifier,
    vpnViewModel: VpnViewModel = viewModel()
) {
    val uiState by vpnViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isLiveDebugSheetOpen by remember { mutableStateOf(false) }

    // Check for a new build every time the app is entered (cold start and each foreground resume).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vpnViewModel.checkForUpdatesOnEntry()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (uiState.updatePromptVisible) {
        UpdatePromptDialog(
            updateState = uiState.updateState,
            installedVersionName = uiState.installedVersionName,
            onDownload = { vpnViewModel.downloadUpdate() },
            onInstall = { vpnViewModel.installUpdate() },
            onDismiss = { vpnViewModel.dismissUpdatePrompt() }
        )
    }

    // Smooth Liquid Glass transition between Login Screen and Main VPN Client
    AnimatedContent(
        targetState = uiState.isAuthenticated,
        transitionSpec = {
            (fadeIn(tween(420, easing = FastOutSlowInEasing)) +
                scaleIn(initialScale = 0.95f, animationSpec = tween(420, easing = FastOutSlowInEasing))) togetherWith
                (fadeOut(tween(280)) + scaleOut(targetScale = 1.03f, animationSpec = tween(280)))
        },
        label = "auth_to_vpn_screen_transition"
    ) { authenticated ->
        if (!authenticated) {
            AuthScreen(
                isLoading = uiState.isAuthLoading,
                errorMessage = uiState.authErrorMessage,
                onLoginSubmit = { username, password ->
                    vpnViewModel.login(username, password)
                },
                modifier = modifier
            )
        } else {
            MainVpnDashboard(
                uiState = uiState,
                vpnViewModel = vpnViewModel,
                modifier = modifier
            )
        }
    }

    // Read-Only User Profile Sheet (Name & Email locked from login; no Active Node Telemetry)
    if (uiState.isProfileSheetOpen) {
        UserProfileSheet(
            userProfile = uiState.userProfile,
            installedVersionName = uiState.installedVersionName,
            updateState = uiState.updateState,
            onCheckForUpdates = { vpnViewModel.checkForUpdates() },
            onDownloadUpdate = { vpnViewModel.downloadUpdate() },
            onInstallUpdate = { vpnViewModel.installUpdate() },
            onOpenDownloadPage = { vpnViewModel.openDownloadPage() },
            onLogout = { vpnViewModel.logout() },
            onDismiss = { vpnViewModel.setProfileSheetOpen(false) }
        )
    }

    // Floating Diagnostics Pill accessible in ALL states (Login screen & Dashboard)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopEnd
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, end = 16.dp)
                .clip(CircleShape)
                .background(LoginSurface.copy(alpha = 0.88f))
                .border(1.dp, HairlineStrong, CircleShape)
                .clickable { isLiveDebugSheetOpen = true }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MintAccent)
                )
                Text(
                    text = "Diagnostics",
                    color = TextPrimary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // Live Diagnostics & Core Ladder Bottom Sheet
    LiveDebugSheet(
        visible = isLiveDebugSheetOpen,
        onDismissRequest = { isLiveDebugSheetOpen = false }
    )
}

@Composable
private fun MainVpnDashboard(
    uiState: com.example.viewmodel.VpnUiState,
    vpnViewModel: VpnViewModel,
    modifier: Modifier = Modifier
) {
    val isConnected = uiState.connectionState == VpnConnectionState.CONNECTED
    val context = LocalContext.current
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnViewModel.triggerConnect()
        } else {
            vpnViewModel.onVpnPermissionDenied()
        }
    }
    val requestConnect: () -> Unit = {
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent != null) {
            vpnPermissionLauncher.launch(permissionIntent)
        } else {
            vpnViewModel.triggerConnect()
        }
    }
    val airDropChoreography = rememberAirDropChoreographyState(
        connectionState = uiState.connectionState,
        enabled = uiState.settings.airdropWaveAnimationEnabled,
        connectingServerKey = if (uiState.connectionState == VpnConnectionState.CONNECTING) {
            uiState.selectedServerId
        } else {
            null
        }
    )

    var rootTopLeftInWindow by remember { mutableStateOf(Offset.Zero) }
    var rootHeightPx by remember { mutableStateOf(0f) }
    var sliderCenterInRoot by remember { mutableStateOf(Offset.Unspecified) }
    var locationBoxRectInRoot by remember { mutableStateOf(Rect.Zero) }

    var showConnectionDetails by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val pullThresholdPx = with(LocalDensity.current) { 110.dp.toPx() }
    val sliderZonePx = with(LocalDensity.current) { 142.dp.toPx() }

    LaunchedEffect(isConnected) {
        if (!isConnected) showConnectionDetails = false
    }

    // Base #051F20 background: completely static & lifeless when VPN is OFF,
    // and awakened with Telegram-style continuously moving light orbs when VPN is ON (CONNECTED).
    // The idle tone is also pulled toward neutral gray, so connecting visibly saturates the screen.
    val backgroundColor by animateColorAsState(
        targetValue = if (isConnected) DeepForestBg else DeepForestBgIdle,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "background_saturation"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .onGloballyPositioned { coords ->
                rootTopLeftInWindow = coords.positionInRoot()
                rootHeightPx = coords.size.height.toFloat()
            }
            .testTag("vpn_root_screen"),
        contentAlignment = Alignment.TopCenter
    ) {
        TelegramLivingBackground(
            isConnected = isConnected
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .pointerInput(isConnected, pullThresholdPx, sliderZonePx) {
                    // Hidden gesture: pulling the connected screen upward reveals session details.
                    if (!isConnected) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        if (down.position.y > size.height - sliderZonePx) return@awaitEachGesture
                        var pulledUp = 0f
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dy = change.positionChange().y
                            if (dy < 0f) pulledUp += -dy
                            if (pulledUp >= pullThresholdPx) {
                                showConnectionDetails = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                break
                            }
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Top Bar: PTUNNEL wordmark (left), profile (right)
            val avatarInitial = remember(uiState.userProfile.displayName) {
                uiState.userProfile.displayName.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "P"
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 12.dp)
                    .airDropElementWarp(
                        choreography = airDropChoreography,
                        distanceFactor = 0.14f,
                        stretchIntensity = 0.55f
                    )
                    .airDropContentBlur(
                        choreography = airDropChoreography,
                        blurScale = 1f
                    )
                    .testTag("top_bar_row"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.header_title),
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp,
                            letterSpacing = 1.2.sp,
                            color = LightMintGlow
                        ),
                        modifier = Modifier.testTag("header_shield_title")
                    )
                    Text(
                        text = ".",
                        color = LightMintGlow,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    LightMintGlow.copy(alpha = 0.18f),
                                    MidDarkElements.copy(alpha = 0.55f)
                                )
                            )
                        )
                        .border(1.dp, LightMintGlow.copy(alpha = 0.28f), CircleShape)
                        .clickable { vpnViewModel.setProfileSheetOpen(true) }
                        .testTag("profile_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarInitial,
                        color = LightMintGlow,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Subscription quota lives at the top of the home screen, frameless.
            SubscriptionHeaderStrip(
                userProfile = uiState.userProfile,
                isConnected = isConnected,
                modifier = Modifier
                    .airDropElementWarp(
                        choreography = airDropChoreography,
                        distanceFactor = 0.18f,
                        stretchIntensity = 0.6f
                    )
                    .airDropContentBlur(
                        choreography = airDropChoreography,
                        blurScale = 1f
                    )
            )

            Spacer(modifier = Modifier.weight(1f))

            // Bottom cluster: location box, live chart, then the connect control.
            // The box sits in the chart's place while disconnected and rides up with it
            // once the curve appears.
            LocationBox(
                mode = uiState.locationMode,
                locations = uiState.locations,
                selectedLocationId = uiState.selectedLocationId,
                isLoading = uiState.isLocationsLoading,
                isConnected = isConnected,
                expanded = uiState.isLocationSheetOpen,
                onToggle = {
                    vpnViewModel.setLocationSheetOpen(!uiState.isLocationSheetOpen)
                },
                onBoundsInRoot = { rectInRoot ->
                    locationBoxRectInRoot = rectInRoot.translate(-rootTopLeftInWindow)
                },
                modifier = Modifier.airDropContentBlur(
                    choreography = airDropChoreography,
                    blurScale = 1f
                )
            )

            AnimatedVisibility(
                visible = isConnected,
                enter = expandVertically(
                    expandFrom = Alignment.Bottom,
                    animationSpec = tween(
                        durationMillis = 460,
                        easing = FastOutSlowInEasing
                    )
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing)
                ),
                exit = shrinkVertically(
                    shrinkTowards = Alignment.Bottom,
                    animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing)
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 220)
                )
            ) {
                LiveTrafficPanel(
                    samples = uiState.trafficSamples,
                    liveThroughputBytesPerSec = uiState.liveThroughputBytesPerSec,
                    showSpeed = uiState.settings.showLiveSpeed,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(if (isConnected) 30.dp else 26.dp))

            // 4. Connect control anchored at the bottom of the screen
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                LiquidSlider(
                    connectionState = uiState.connectionState,
                    onConnectTriggered = requestConnect,
                    onDisconnectTriggered = { vpnViewModel.triggerDisconnect() },
                    airDropChoreography = airDropChoreography,
                    modifier = Modifier
                        .onGloballyPositioned { sliderCoords ->
                            val posInRoot = sliderCoords.positionInRoot() - rootTopLeftInWindow
                            val centerX = posInRoot.x + sliderCoords.size.width * 0.5f
                            val centerY = posInRoot.y + sliderCoords.size.height * 0.5f
                            sliderCenterInRoot = Offset(centerX, centerY)
                        }
                )

                Spacer(modifier = Modifier.height(62.dp))
            }
        }

        if (showConnectionDetails && isConnected) {
            ConnectionDetailsSheet(
                userProfile = uiState.userProfile,
                location = uiState.selectedLocation,
                locationMode = uiState.locationMode,
                sessionSeconds = uiState.sessionSeconds,
                sessionTotalBytes = uiState.sessionTotalBytes,
                downloadBytesPerSec = uiState.liveDownloadBytesPerSec,
                uploadBytesPerSec = uiState.liveUploadBytesPerSec,
                onDismiss = { showConnectionDetails = false }
            )
        }

        // Floating toast banner for server sync / ping refresh feedback (above the bottom connect control)
        AnimatedVisibility(
            visible = !uiState.statusBannerMessage.isNullOrBlank(),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 148.dp)
        ) {
            LiquidGlassBox(
                modifier = Modifier
                    .padding(horizontal = 28.dp, vertical = 4.dp)
                    .airDropContentBlur(
                        choreography = airDropChoreography,
                        blurScale = 1f
                    ),
                cornerRadius = 18.dp,
                innerShadowBlurDp = 10.dp,
                innerShadowSpreadDp = 4.dp
            ) {
                Text(
                    text = uiState.statusBannerMessage.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = LightMintGlow,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }

        // Full-screen iPhone AirDrop Connection Wave & Creamy Optical Lens Overlay centered around the Slider bar
        AirDropConnectionWaveOverlay(
            choreography = airDropChoreography,
            focalCenterOverride = sliderCenterInRoot
        )

        // The location box grows open in place: downward first, upward only as far as mid-screen,
        // scrolling inside the box when the list is still taller than that.
        LocationBoxPanel(
            open = uiState.isLocationSheetOpen,
            anchorRectInRoot = locationBoxRectInRoot,
            rootHeightPx = rootHeightPx,
            mode = uiState.locationMode,
            locations = uiState.locations,
            selectedLocationId = uiState.selectedLocationId,
            isLoading = uiState.isLocationsLoading,
            isConnected = isConnected,
            onUseAuto = {
                vpnViewModel.setLocationMode(LocationMode.AUTO)
                vpnViewModel.setLocationSheetOpen(false)
            },
            onSelect = { vpnViewModel.selectLocation(it) },
            onRefresh = { vpnViewModel.refreshLocations() },
            onDismiss = { vpnViewModel.setLocationSheetOpen(false) }
        )
    }
}
