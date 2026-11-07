package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.UserProfile
import com.example.model.VpnServer
import com.example.ui.icons.PtunnelIcons
import com.example.ui.theme.AccentActive
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MutedSageText
import java.util.Locale

/**
 * Clean, organized Telemetry + Timer + Quota Numbers section placed directly under the Power Slider.
 */
@Composable
fun SliderTelemetrySection(
    sessionSeconds: Long,
    isConnected: Boolean,
    userProfile: UserProfile,
    hasActiveServers: Boolean,
    liveThroughputBytesPerSec: Long,
    showConnectionTimer: Boolean = true,
    showLiveSpeed: Boolean = true,
    modifier: Modifier = Modifier
) {
    val timerFormatted = remember(sessionSeconds) { formatDurationHms(sessionSeconds) }
    val usedFormatted = remember(userProfile.usedBytes) { formatDataSize(userProfile.usedBytes) }
    val remainingFormatted = remember(userProfile.usedBytes, userProfile.totalBytes) {
        formatDataSize((userProfile.totalBytes - userProfile.usedBytes).coerceAtLeast(0L))
    }

    val quotaNumbersText = remember(userProfile.usedBytes, userProfile.totalBytes, hasActiveServers) {
        if (userProfile.totalBytes > 0L) {
            val percentInt = (userProfile.usageFraction * 100f).toInt().coerceIn(0, 100)
            "$remainingFormatted left · $percentInt% used"
        } else if (hasActiveServers || userProfile.usedBytes > 0L) {
            usedFormatted
        } else {
            "0.00 MB"
        }
    }

    val timerColor by animateColorAsState(
        targetValue = if (isConnected) LightMintGlow else MutedSageText.copy(alpha = 0.7f),
        animationSpec = tween(300),
        label = "timer_text_color"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .testTag("slider_telemetry_section"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (showConnectionTimer) {
            // Connection Duration Timer (HH:MM:SS)
            Text(
                text = timerFormatted,
                maxLines = 1,
                softWrap = false,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.2.sp,
                    color = timerColor
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("connection_duration_timer")
            )

            Spacer(modifier = Modifier.height(6.dp))
        }

        // Clean single-line Data Usage & Live Speed numbers directly below the timer
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = quotaNumbersText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.6.sp,
                    color = LightMintGlow.copy(alpha = 0.9f)
                ),
                modifier = Modifier.testTag("package_quota_value_text")
            )

            if (showLiveSpeed && isConnected && liveThroughputBytesPerSec > 0L) {
                Text(
                    text = "   •   ${formatSpeed(liveThroughputBytesPerSec)}",
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.5.sp,
                        color = LightMintGlow
                    ),
                    modifier = Modifier.testTag("live_speed_text")
                )
            }
        }
    }
}

/**
 * Liquid Glass Package Quota Bar positioned directly above the Server List,
 * accompanied right beside the bar by two custom SVG buttons:
 * 1) Refresh SVG (`ic_svg_refresh_vpn`) to refresh VPN servers
 * 2) Clock SVG (`ic_svg_clock_ping`) to refresh & update server pings
 * Strictly constrained height so it never wraps or pushes the ServerList down.
 */
@Composable
fun PackageQuotaBar(
    userProfile: UserProfile,
    onRefreshServers: (() -> Unit)? = null,
    onRefreshPings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = userProfile.usageFraction,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "quota_progress_anim"
    )

    var refreshRotationTarget by remember { mutableFloatStateOf(0f) }
    val animatedRefreshRotation by animateFloatAsState(
        targetValue = refreshRotationTarget,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "refresh_svg_rotation"
    )

    var clockRotationTarget by remember { mutableFloatStateOf(0f) }
    val animatedClockRotation by animateFloatAsState(
        targetValue = clockRotationTarget,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "clock_svg_rotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .testTag("package_quota_container"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Left side: Single-line label + sleek Liquid Glass progress bar
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.quota_header_label),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.5.sp,
                    letterSpacing = 1.3.sp,
                    color = MutedSageText
                )
            )

            Spacer(modifier = Modifier.height(7.dp))

            LiquidGlassBox(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .testTag("package_quota_progress_bar"),
                cornerRadius = 4.dp,
                innerShadowBlurDp = 8.dp,
                innerShadowSpreadDp = 3.dp,
                contentAlignment = Alignment.CenterStart
            ) {
                if (animatedProgress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        MidDarkElements,
                                        AccentActive,
                                        LightMintGlow
                                    )
                                )
                            )
                    )
                }
            }
        }

        // Optional utility buttons; hidden on the simplified Home.
        if (onRefreshServers != null) {
            LiquidGlassBox(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        refreshRotationTarget += 360f
                        onRefreshServers.invoke()
                    }
                    .testTag("refresh_vpn_servers_svg_button"),
                cornerRadius = 14.dp,
                innerShadowBlurDp = 10.dp,
                innerShadowSpreadDp = 4.dp,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_svg_refresh_vpn),
                    contentDescription = stringResource(R.string.cd_refresh_vpn_servers),
                    tint = LightMintGlow,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer {
                            rotationZ = animatedRefreshRotation
                        }
                )
            }
        }

        if (onRefreshPings != null) {
            LiquidGlassBox(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        clockRotationTarget += 360f
                        onRefreshPings.invoke()
                    }
                    .testTag("refresh_server_pings_clock_svg_button"),
                cornerRadius = 14.dp,
                innerShadowBlurDp = 10.dp,
                innerShadowSpreadDp = 4.dp,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_svg_clock_ping),
                    contentDescription = stringResource(R.string.cd_refresh_pings_clock),
                    tint = LightMintGlow,
                    modifier = Modifier
                        .size(19.dp)
                        .graphicsLayer {
                            rotationZ = animatedClockRotation
                        }
                )
            }
        }
    }
}

@Composable
fun ServerList(
    servers: List<VpnServer>,
    selectedServerId: String?,
    onSelectServer: (String) -> Unit,
    showServerPing: Boolean = true,
    compactServerList: Boolean = false,
    airDropChoreography: AirDropChoreographyState? = null,
    modifier: Modifier = Modifier
) {
    if (servers.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp)
                .testTag("empty_server_list_state"),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.empty_servers_title),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = LightMintGlow.copy(alpha = 0.85f),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.8.sp
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.empty_servers_subtitle),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MutedSageText.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_lazy_column")
    ) {
        itemsIndexed(
            items = servers,
            key = { _, it -> it.id }
        ) { index, server ->
            val isSelected = server.id == selectedServerId
            val rowDistanceFactor = (0.48f + index * 0.08f).coerceAtMost(0.92f)
            val warpModifier = if (airDropChoreography != null) {
                Modifier.airDropElementWarp(
                    choreography = airDropChoreography,
                    distanceFactor = rowDistanceFactor,
                    stretchIntensity = 1.05f
                )
            } else {
                Modifier
            }
            ServerRowItem(
                server = server,
                isSelected = isSelected,
                showServerPing = showServerPing,
                compactServerList = compactServerList,
                onClick = { onSelectServer(server.id) },
                modifier = warpModifier
            )
        }
    }
}

@Composable
private fun ServerRowItem(
    server: VpnServer,
    isSelected: Boolean,
    showServerPing: Boolean,
    compactServerList: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) LightMintGlow else MutedSageText,
        animationSpec = tween(durationMillis = 220),
        label = "protocol_icon_tint"
    )

    val nameColor by animateColorAsState(
        targetValue = if (isSelected) LightMintGlow else MutedSageText,
        animationSpec = tween(durationMillis = 250),
        label = "server_name_color"
    )

    val pingColor by animateColorAsState(
        targetValue = if (isSelected) LightMintGlow.copy(alpha = 0.9f) else MutedSageText.copy(alpha = 0.65f),
        animationSpec = tween(durationMillis = 250),
        label = "server_ping_color"
    )

    val onePxDp = with(LocalDensity.current) { 1f.toDp() }
    val rowHeight = if (compactServerList) 52.dp else 60.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("server_item_${server.id}")
            .drawBehind {
                if (isSelected) {
                    drawRect(color = LightMintGlow.copy(alpha = 0.035f))
                    drawRect(
                        color = LightMintGlow.copy(alpha = 0.62f),
                        size = Size(2.dp.toPx(), size.height)
                    )
                }
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(
                            if (isSelected) {
                                LightMintGlow.copy(alpha = 0.12f)
                            } else {
                                MidDarkElements.copy(alpha = 0.35f)
                            }
                        )
                        .border(
                            1.dp,
                            if (isSelected) {
                                LightMintGlow.copy(alpha = 0.26f)
                            } else {
                                LightMintGlow.copy(alpha = 0.08f)
                            },
                            RoundedCornerShape(11.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = protocolIcon(server),
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            color = nameColor,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                        )
                    )
                    if (!compactServerList) {
                        Text(
                            text = protocolHint(server),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isSelected) {
                                    MutedSageText.copy(alpha = 0.75f)
                                } else {
                                    MutedSageText.copy(alpha = 0.55f)
                                },
                                fontSize = 9.5.sp,
                                letterSpacing = 0.2.sp
                            )
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (showServerPing) {
                    val pingLabel = if (server.pingMs > 0) {
                        stringResource(R.string.ping_format, server.pingMs)
                    } else {
                        "-- ms"
                    }
                    Text(
                        text = pingLabel,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = pingColor,
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                        )
                    )
                    Spacer(modifier = Modifier.width(7.dp))
                    LatencyBars(pingMs = server.pingMs, isSelected = isSelected)
                }

                AnimatedVisibility(
                    visible = isSelected,
                    enter = fadeIn(tween(200)) + scaleIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        )
                    ),
                    exit = fadeOut(tween(150)) + scaleOut(tween(150))
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(modifier = Modifier.width(14.dp))
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = stringResource(R.string.cd_selected_server),
                            tint = LightMintGlow,
                            modifier = Modifier
                                .size(18.dp)
                                .testTag("selected_checkmark_${server.id}")
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = onePxDp,
            color = LightMintGlow.copy(alpha = 0.1f)
        )
    }
}

private fun protocolIcon(server: VpnServer): ImageVector {
    val id = server.id.lowercase()
    val scheme = server.protocolScheme.lowercase()
    val name = server.name.lowercase()
    return when {
        id.contains("reality") || scheme == "vless" || name.contains("reality") -> PtunnelIcons.Reality
        id.contains("vmess") || scheme == "vmess" || name.contains("vmess") -> PtunnelIcons.VMess
        id.contains("trojan") || scheme == "trojan" || name.contains("trojan") -> PtunnelIcons.Trojan
        id.contains("shadow") || scheme == "ss" || scheme == "shadowsocks" || name.contains("shadow") -> PtunnelIcons.Shadowsocks
        else -> PtunnelIcons.Globe
    }
}

private fun protocolHint(server: VpnServer): String {
    val id = server.id.lowercase()
    val scheme = server.protocolScheme.lowercase()
    val name = server.name.lowercase()
    return when {
        id.contains("reality") || scheme == "vless" || name.contains("reality") -> "Strict networks · TLS 1.3"
        id.contains("vmess") || scheme == "vmess" || name.contains("vmess") -> "WebSocket TLS fallback"
        id.contains("trojan") || scheme == "trojan" || name.contains("trojan") -> "TLS fallback"
        id.contains("shadow") || scheme == "ss" || scheme == "shadowsocks" || name.contains("shadow") -> "Lightweight · last resort"
        else -> "Imported route"
    }
}

@Composable
private fun LatencyBars(pingMs: Int, isSelected: Boolean) {
    val activeCount = when {
        pingMs <= 0 -> 0
        pingMs <= 100 -> 3
        pingMs <= 150 -> 2
        else -> 1
    }
    Row(
        modifier = Modifier.height(12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        repeat(3) { index ->
            val active = index < activeCount
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((4 + index * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (active) {
                            LightMintGlow.copy(alpha = if (isSelected) 0.9f else 0.55f)
                        } else {
                            LightMintGlow.copy(alpha = 0.12f)
                        }
                    )
            )
        }
    }
}

internal fun formatDurationHms(totalSeconds: Long): String {
    val safeSec = totalSeconds.coerceAtLeast(0L)
    val hours = safeSec / 3600L
    val minutes = (safeSec % 3600L) / 60L
    val seconds = safeSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

internal fun formatDataSize(bytes: Long): String {
    val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) {
        String.format(Locale.US, "%.2f GB", gb)
    } else {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        String.format(Locale.US, "%.2f MB", mb)
    }
}

internal fun formatSpeed(bytesPerSec: Long): String {
    val mbps = bytesPerSec.toDouble() / (1024.0 * 1024.0)
    return if (mbps >= 1.0) {
        String.format(Locale.US, "%.2f MB/s", mbps)
    } else {
        val kbps = bytesPerSec.toDouble() / 1024.0
        String.format(Locale.US, "%.1f KB/s", kbps)
    }
}
