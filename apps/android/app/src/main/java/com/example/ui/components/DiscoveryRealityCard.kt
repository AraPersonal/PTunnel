package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.HairlineStrong
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.LoginSurface
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MintAccent
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg

data class DiscoveryRealityState(
    val status: String = "idle", // "scanning", "connected", "idle"
    val operatorDisplayName: String = "همراه اول",
    val operatorTag: String = "mci",
    val simDetails: String = "MCC 432 / MNC 11",
    val networkType: String = "cellular",
    val region: String = "teh",
    val path: String = "xray/reality · port 443",
    val latencyMs: Long = 0L,
    val throughputKBps: Double = 0.0,
    val connectedMinutes: Double = 0.0,
    val deaths: Int = 0,
    val confidence: String = "high", // "high", "medium", "low"
    val isNewOperator: Boolean = false
)

/**
 * Reality Card strictly following docs/design/0001 §9:
 * Only "scanning" status (no marketing/emotional prose) + observed facts card + manual "اسکن مجدد" button.
 */
@Composable
fun DiscoveryRealityCard(
    state: DiscoveryRealityState,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pulse = rememberInfiniteTransition(label = "reality_pulse")
    val scanningAlpha by pulse.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scanning_pulse_alpha"
    )

    LiquidGlassBox(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .testTag("discovery_reality_card"),
        cornerRadius = 18.dp,
        innerShadowBlurDp = 8.dp,
        innerShadowSpreadDp = 3.dp
    ) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Status Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (state.status == "scanning") MintAccent.copy(alpha = scanningAlpha)
                                    else if (state.status == "connected") MintAccent
                                    else MutedSageText
                                )
                        )
                        Text(
                            text = if (state.status == "scanning") "scanning" else if (state.status == "connected") "connected" else "آماده",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = if (state.status == "scanning") MintAccent else LightMintGlow
                            ),
                            modifier = Modifier.testTag("reality_status_label")
                        )
                    }

                    // Manual Rescan Button ("اسکن مجدد")
                    OutlinedButton(
                        onClick = onRescan,
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("rescan_button"),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MintAccent.copy(alpha = 0.45f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = LoginSurface.copy(alpha = 0.5f),
                            contentColor = LightMintGlow
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "اسکن مجدد",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LightMintGlow
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(HairlineStrong.copy(alpha = 0.5f))
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Observed Reality Facts
                val confidenceLabel = if (state.isNewOperator) {
                    "(تازه‌کشف‌شده · confidence: ${state.confidence})"
                } else {
                    "(confidence: ${state.confidence})"
                }

                RealityRow(
                    label = "اپراتور:",
                    value = "${state.operatorDisplayName} $confidenceLabel"
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "سیم:",
                    value = state.simDetails
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "شبکه:",
                    value = "${state.networkType} · region: ${state.region}"
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "مسیر:",
                    value = state.path
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "تأخیر:",
                    value = if (state.latencyMs > 0) "${state.latencyMs} ms" else "—"
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "throughput:",
                    value = if (state.throughputKBps > 0) "${state.throughputKBps.toInt()} KB/s (چانکی)" else "—"
                )
                Spacer(modifier = Modifier.height(6.dp))
                RealityRow(
                    label = "پایداری:",
                    value = if (state.connectedMinutes > 0) "${"%.1f".format(state.connectedMinutes)} min · ${state.deaths} مرگ" else "—"
                )
            }
        }
    }
}

@Composable
private fun RealityRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                color = MutedSageText,
                fontWeight = FontWeight.Medium
            )
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                color = LightMintGlow,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}
