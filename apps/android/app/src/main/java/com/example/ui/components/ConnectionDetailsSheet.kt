package com.example.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LocationMode
import com.example.model.UserProfile
import com.example.model.VpnLocation
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MintAccent
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg

/**
 * Details of the live session, revealed only by pulling the home screen upward.
 * There is intentionally no handle or affordance on the home screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionDetailsSheet(
    userProfile: UserProfile,
    location: VpnLocation?,
    locationMode: LocationMode,
    sessionSeconds: Long,
    sessionTotalBytes: Long,
    downloadBytesPerSec: Long,
    uploadBytesPerSec: Long,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pulse = rememberInfiniteTransition(label = "details_live_pulse")
    val liveAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "details_live_dot_alpha"
    )

    val country = location?.country?.ifBlank { null } ?: "Germany"
    val flag = location?.flag?.ifBlank { null } ?: "🇩🇪"
    val domain = location?.domain?.ifBlank { null } ?: "ptu.beauty"
    val host = location?.host?.ifBlank { null } ?: "86.54.42.124"
    val modeLabel = if (locationMode == LocationMode.AUTO) "Auto · fastest route" else "Manual location"
    val daysLabel = if (userProfile.expiryDaysRemaining > 0) {
        "${userProfile.expiryDaysRemaining} days left"
    } else {
        "No expiry"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = TrackDarkBg,
        contentColor = LightMintGlow,
        scrimColor = DeepForestBg.copy(alpha = 0.82f),
        dragHandle = null
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 24.dp, bottom = 32.dp)
                    .testTag("connection_details_sheet")
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CONNECTION DETAILS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                letterSpacing = 2.2.sp,
                                color = MutedSageText
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MintAccent.copy(alpha = liveAlpha))
                            )
                            Text(
                                text = "Protected · 3 parallel links",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = LightMintGlow
                                )
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Close",
                            color = MutedSageText,
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Server identity
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MidDarkElements.copy(alpha = 0.42f))
                        .border(1.dp, LightMintGlow.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(LightMintGlow.copy(alpha = 0.09f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = flag, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = country,
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow
                            )
                        )
                        Text(
                            text = "$host:443 · $modeLabel",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 10.5.sp,
                                color = MutedSageText
                            )
                        )
                        Text(
                            text = "API $domain",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 9.5.sp,
                                color = MutedSageText.copy(alpha = 0.65f)
                            )
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "3",
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = MintAccent
                            )
                        )
                        Text(
                            text = "LINKS",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 8.5.sp,
                                letterSpacing = 1.4.sp,
                                color = MutedSageText
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("ACTIVE TUNNELS")

                Spacer(modifier = Modifier.height(8.dp))

                TunnelRow("VLESS · Reality", "TCP 443 · XTLS Vision · SNI www.microsoft.com", "Primary")
                TunnelRow("VMess · WebSocket", "TCP 2083 · TLS · WebSocket · SNI ptu.beauty", "Balanced")
                TunnelRow("Trojan · TLS", "TCP 2053 · TLS · SNI ptu.beauty", "Failover")

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Connections are balanced by least-ping; if one path degrades, traffic moves to another automatically.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 10.5.sp,
                        color = MutedSageText.copy(alpha = 0.8f)
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("THIS SESSION")

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailStat(
                        label = "DURATION",
                        value = formatDurationHms(sessionSeconds),
                        modifier = Modifier.weight(1f)
                    )
                    DetailStat(
                        label = "SESSION DATA",
                        value = formatTrafficVolumeCompact(sessionTotalBytes),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DetailStat(
                        label = "DOWNLOAD",
                        value = formatTrafficRateCompact(downloadBytesPerSec.toFloat()),
                        modifier = Modifier.weight(1f)
                    )
                    DetailStat(
                        label = "UPLOAD",
                        value = formatTrafficRateCompact(uploadBytesPerSec.toFloat()),
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("SUBSCRIPTION")

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MidDarkElements.copy(alpha = 0.32f))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = userProfile.planTitle.ifBlank { "PTunnel Data" },
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow
                            )
                        )
                        Text(
                            text = daysLabel,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MintAccent
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(7.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${formatDataSize(userProfile.usedBytes)} used",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.sp,
                                color = MutedSageText
                            )
                        )
                        Text(
                            text = "${formatDataSize(userProfile.totalBytes)} total",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 11.sp,
                                color = MutedSageText
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                SectionLabel("TRANSPORT & ROUTING")

                Spacer(modifier = Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailChip("MTU 1420")
                    DetailChip("DoH 1.1.1.1")
                    DetailChip("QUIC blocked")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailChip("UDP 443 blocked")
                    DetailChip("Private IPs blocked")
                    DetailChip("No mux")
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = "Pull down to close",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        letterSpacing = 1.2.sp,
                        color = MutedSageText.copy(alpha = 0.55f)
                    )
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            letterSpacing = 2.0.sp,
            color = MutedSageText.copy(alpha = 0.9f)
        )
    )
}

@Composable
private fun TunnelRow(title: String, endpoint: String, role: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LightMintGlow.copy(alpha = 0.035f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MintAccent)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = LightMintGlow
                )
            )
            Text(
                text = endpoint,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 9.5.sp,
                    color = MutedSageText
                )
            )
        }
        Text(
            text = role,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.5.sp,
                letterSpacing = 0.8.sp,
                color = MutedSageText.copy(alpha = 0.8f)
            )
        )
    }
}

@Composable
private fun DetailStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MidDarkElements.copy(alpha = 0.32f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.5.sp,
                letterSpacing = 1.4.sp,
                color = MutedSageText
            )
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = value,
            maxLines = 1,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = LightMintGlow
            )
        )
    }
}

@Composable
private fun DetailChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(
            fontSize = 9.sp,
            color = MutedSageText
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MidDarkElements.copy(alpha = 0.38f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    )
}
