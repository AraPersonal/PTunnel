package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.UserProfile
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MintAccent
import com.example.ui.theme.MutedSageText

/**
 * Top-of-home subscription strip: remaining days, quota bar, used and total volume.
 * Deliberately frameless so the home screen reads as one continuous surface.
 */
@Composable
fun SubscriptionHeaderStrip(
    userProfile: UserProfile,
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val targetProgress = if (userProfile.totalBytes > 0L) userProfile.usageFraction else 0f
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "subscription_header_progress"
    )

    val planTitle = remember(userProfile.planTitle) {
        userProfile.planTitle.ifBlank { "PTunnel Data" }
    }
    val daysLabel = remember(userProfile.expiryDaysRemaining) {
        if (userProfile.expiryDaysRemaining > 0) {
            "${userProfile.expiryDaysRemaining} days left"
        } else {
            "No expiry"
        }
    }
    val usedText = remember(userProfile.usedBytes, userProfile.totalBytes) {
        if (userProfile.totalBytes > 0L) {
            "${formatDataSize(userProfile.usedBytes)} of ${formatDataSize(userProfile.totalBytes)} used"
        } else {
            "${formatDataSize(userProfile.usedBytes)} used"
        }
    }
    val remainingText = remember(userProfile.remainingBytes) {
        "${formatDataSize(userProfile.remainingBytes)} left"
    }
    val isActive = userProfile.status == "active"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .testTag("subscription_header_strip")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 3.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) MintAccent else MutedSageText)
                )
                Column {
                    Text(
                        text = "DATA PACKAGE",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 8.5.sp,
                            letterSpacing = 1.8.sp,
                            color = MutedSageText
                        )
                    )
                    Text(
                        text = planTitle,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = LightMintGlow
                        )
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = daysLabel,
                    maxLines = 1,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isConnected) MintAccent else LightMintGlow.copy(alpha = 0.9f)
                    ),
                    modifier = Modifier.testTag("subscription_days_left")
                )
                Text(
                    text = if (isConnected) "Tunnel active" else "Subscription",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 8.5.sp,
                        letterSpacing = 0.6.sp,
                        color = MutedSageText.copy(alpha = 0.8f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(11.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MidDarkElements.copy(alpha = 0.55f))
                .testTag("subscription_quota_bar")
        ) {
            if (animatedProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress.coerceIn(0.015f, 1f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MidDarkElements,
                                    MintAccent.copy(alpha = 0.75f),
                                    LightMintGlow
                                )
                            )
                        )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = usedText,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = LightMintGlow.copy(alpha = 0.82f)
                ),
                modifier = Modifier.testTag("subscription_used_total")
            )
            Text(
                text = remainingText,
                maxLines = 1,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    color = MutedSageText
                )
            )
        }
    }
}
