package com.example.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import com.example.ui.icons.PtunnelIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.VpnConnectionState
import com.example.ui.theme.AccentActive
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// App palette intermediate mint-sage tone for the in-slider "CONNECTING" state
private val ConnectingPaletteMintSage = Color(0xFFB4D8C1)

/**
 * Liquid Glass Slider implementing:
 * - Clean #0B2B26 track (280.dp x 74.dp, 37.dp radius) containing the split-reveal status text:
 *     - Before connecting: "DISCONNECTED" in front of the thumb in the app's color palette (#8EB69B).
 *     - While dragging / connecting: as the thumb moves left-to-right, the text behind the thumb
 *       reveals "CONNECTING" while the text still in front of the thumb remains "DISCONNECTED".
 *     - When connected: "CONNECTED" rests behind (to the left of) the power button in the app's
 *       color palette (#DAF1DE / #8EB69B).
 * - AirDrop circular meniscus aura forms directly around this slider bar during connection,
 *   while keeping the slider, thumb, and inner text clearly readable (minimal blur).
 * - Authentic Liquid Glass thumb (62.dp circle), concentric with the track's end cap so the
 *   glass ring stays 6.dp thick on every side:
 *     - Drop shadow: 0px 6px 24px rgba(0, 0, 0, 0.2)
 *     - Inset shadow: inset 0 0 15px -6px #ffffff
 *     - Transparent tint: rgba(255, 255, 255, 0)
 *     - Subtle fractal-noise refraction distortion and 4px frost blur effect.
 * - Velocity-aware fluid drag physics, tap squash (scaleX 1.1, scaleY 0.9) + bouncy spring return,
 *   95% snap threshold, and infinite breathing #DAF1DE glow when CONNECTED.
 */
@Composable
fun LiquidSlider(
    connectionState: VpnConnectionState,
    onConnectTriggered: () -> Unit,
    onDisconnectTriggered: () -> Unit,
    airDropChoreography: AirDropChoreographyState? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val trackWidth = 280.dp
    val trackHeight = 74.dp
    val trackCornerRadius = trackHeight / 2
    val trackPadding = 6.dp
    // The thumb is concentric with the track's end cap, so the visible glass ring keeps an even
    // thickness all the way around: outer cap radius − inset = thumb radius.
    val thumbSize = (trackCornerRadius - trackPadding) * 2

    val trackPaddingPx = with(density) { trackPadding.toPx() }
    val thumbSizePx = with(density) { thumbSize.toPx() }
    val maxDragPx = with(density) { (trackWidth - thumbSize - (trackPadding * 2)).toPx() }
    val tapSlopPx = 10f

    val currentState by rememberUpdatedState(connectionState)
    val currentOnConnect by rememberUpdatedState(onConnectTriggered)
    val currentOnDisconnect by rememberUpdatedState(onDisconnectTriggered)

    // Smooth physics Animatables for thumb position, liquid wall-collision squash/stretch,
    // optical wall-contact shockwave flash, and internal icon recoil.
    val thumbOffsetX = remember { Animatable(0f) }
    val thumbScaleX = remember { Animatable(1f) }
    val thumbScaleY = remember { Animatable(1f) }
    val wallImpactFlash = remember { Animatable(0f) }
    val iconWallOffsetX = remember { Animatable(0f) }
    val maxIconBouncePx = with(density) { 5.dp.toPx() }

    // -1 = colliding with left inner wall (0f), +1 = colliding with right inner wall (maxDragPx), 0 = mid-track
    var wallImpactSide by remember { mutableIntStateOf(-1) }
    var isUserInteracting by remember { mutableStateOf(false) }

    suspend fun playWallCollisionAnimation(side: Int, intensity: Float) = coroutineScope {
        val clampedIntensity = intensity.coerceIn(0.45f, 1.0f)
        wallImpactSide = side

        // Run the optical wall-contact flash in parallel with the whole squash + rebound sequence
        // so it NEVER blocks Phase 1 from transitioning immediately into Phase 2 rebound.
        launch {
            wallImpactFlash.snapTo((clampedIntensity * 0.92f).coerceIn(0.38f, 1f))
            wallImpactFlash.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)
            )
        }

        // Phase 1: Instantaneous, zero-pause liquid droplet compression against the inner wall (40ms).
        // Because TransformOrigin is anchored at the wall contact point (0f for left wall, 1f for right wall),
        // the outer edge remains 100% flush with the wall and never crosses under the bar border.
        val squashScaleX = (1f - 0.20f * clampedIntensity).coerceIn(0.77f, 0.92f)
        val bulgeScaleY = (1f + 0.14f * clampedIntensity).coerceIn(1.05f, 1.15f)
        val iconShiftPx = side * maxIconBouncePx * clampedIntensity

        coroutineScope {
            launch {
                thumbScaleX.animateTo(
                    targetValue = squashScaleX,
                    animationSpec = tween(durationMillis = 40, easing = FastOutSlowInEasing)
                )
            }
            launch {
                thumbScaleY.animateTo(
                    targetValue = bulgeScaleY,
                    animationSpec = tween(durationMillis = 40, easing = FastOutSlowInEasing)
                )
            }
            launch {
                iconWallOffsetX.animateTo(
                    targetValue = iconShiftPx,
                    animationSpec = tween(durationMillis = 36, easing = FastOutSlowInEasing)
                )
            }
        }

        // Phase 2: Immediate elastic liquid rebound back to spherical equilibrium (1f, 1f) with zero pause.
        // Anchored at the wall so any >1f horizontal rebound stretches inward into the open track.
        val liquidWallReboundSpring = spring<Float>(
            dampingRatio = 0.40f,
            stiffness = 620f
        )
        coroutineScope {
            launch {
                thumbScaleX.animateTo(
                    targetValue = 1f,
                    animationSpec = liquidWallReboundSpring
                )
            }
            launch {
                thumbScaleY.animateTo(
                    targetValue = 1f,
                    animationSpec = liquidWallReboundSpring
                )
            }
            launch {
                iconWallOffsetX.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = 0.44f,
                        stiffness = 560f
                    )
                )
            }
        }
    }

    // Enforce hard track bounds [0f, maxDragPx] on thumbOffsetX at all times so the power thumb
    // can NEVER overshoot into negative coordinates or past the right cap and go under the bar.
    LaunchedEffect(maxDragPx) {
        val safeMax = maxDragPx.coerceAtLeast(0f)
        thumbOffsetX.updateBounds(lowerBound = 0f, upperBound = safeMax)
    }

    // Synchronize slider thumb position when connectionState updates externally
    LaunchedEffect(connectionState, maxDragPx) {
        val safeMax = maxDragPx.coerceAtLeast(0f)
        thumbOffsetX.updateBounds(lowerBound = 0f, upperBound = safeMax)
        if (!isUserInteracting) {
            val targetX = when (connectionState) {
                VpnConnectionState.CONNECTED,
                VpnConnectionState.CONNECTING -> safeMax
                VpnConnectionState.DISCONNECTED -> 0f
            }
            val distanceToTarget = abs(thumbOffsetX.value - targetX)
            if (distanceToTarget > 4f) {
                val travelRatio = (distanceToTarget / safeMax.coerceAtLeast(1f)).coerceIn(0.12f, 1f)
                thumbOffsetX.animateTo(
                    targetValue = targetX,
                    animationSpec = tween(
                        durationMillis = (travelRatio * 260f).roundToInt().coerceIn(160, 260),
                        easing = FastOutSlowInEasing
                    )
                )
                playWallCollisionAnimation(
                    side = if (targetX == 0f) -1 else 1,
                    intensity = (0.55f + travelRatio * 0.40f).coerceIn(0.55f, 0.95f)
                )
            } else {
                thumbOffsetX.snapTo(targetX)
                wallImpactSide = if (targetX == 0f) -1 else 1
            }
        }
    }

    // Infinite breathing glow animation when CONNECTED (#DAF1DE)
    val infiniteTransition = rememberInfiniteTransition(label = "breathing_glow_transition")
    val breathingRadiusMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_glow_radius"
    )
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathing_glow_alpha"
    )
    val connectSpin by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing)
        ),
        label = "connect_spin"
    )

    val isConnected = connectionState == VpnConnectionState.CONNECTED
    val isConnecting = connectionState == VpnConnectionState.CONNECTING
    val clampedThumbOffsetX = thumbOffsetX.value.coerceIn(0f, maxDragPx.coerceAtLeast(1f))
    val dragFraction = (clampedThumbOffsetX / maxDragPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

    // Exact horizontal center of the thumb in the 280.dp track coordinate space
    val thumbCenterX = trackPaddingPx + (thumbSizePx * 0.5f) + clampedThumbOffsetX

    val powerIconTint by animateColorAsState(
        targetValue = when {
            isConnected -> LightMintGlow
            isConnecting -> LightMintGlow.copy(alpha = 0.9f)
            dragFraction > 0.85f -> LightMintGlow
            else -> MutedSageText.copy(alpha = 0.9f)
        },
        animationSpec = tween(180),
        label = "power_icon_tint"
    )

    // Left-of-thumb (behind thumb) text color in the app's green palette
    val leftRevealedTextColor by animateColorAsState(
        targetValue = when (connectionState) {
            VpnConnectionState.CONNECTED -> LightMintGlow
            VpnConnectionState.CONNECTING -> ConnectingPaletteMintSage
            VpnConnectionState.DISCONNECTED -> ConnectingPaletteMintSage
        },
        animationSpec = tween(220),
        label = "left_revealed_text_color"
    )

    val disconnectedLabel = stringResource(R.string.status_disconnected)
    val connectingLabel = stringResource(R.string.status_connecting)
    val connectedLabel = stringResource(R.string.status_connected)

    // Text shown behind (to the left of) the thumb:
    // - While dragging or CONNECTING -> "CONNECTING"
    // - Once CONNECTED -> "CONNECTED"
    val behindThumbLabel = if (isConnected) connectedLabel else connectingLabel

    val gather = airDropChoreography?.gatherTension?.coerceIn(0f, 1f) ?: 0f
    val textScaleX = airDropChoreography?.headerScaleX ?: 1f
    val textScaleY = airDropChoreography?.headerScaleY ?: 1f
    val textOffsetY = airDropChoreography?.headerOffsetY ?: 0f
    // Subtle, reduced blur on the inner text only so the user can clearly read it during AirDrop blur
    // without ever blurring the outer slider pill bounds
    val innerTextBlurDp = ((airDropChoreography?.headerBlurDp ?: 0f) * 0.12f).coerceIn(0f, 0.85f)

    // Pre-allocated native Paints for hardware/software blur shadows
    val dropShadowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val insetWhiteShadowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
    }
    val breathingTrackGlowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }

    // Slider Track: 74.dp height, 280.dp width, 37.dp corner radius, solid #0B2B26 background
    Box(
        modifier = modifier
            .width(trackWidth)
            .height(trackHeight)
            .testTag("vpn_liquid_slider_track")
            .drawBehind {
                // Smooth continuous breathing glow on the track only when CONNECTED (no stepped rings)
                val activeGlowAlpha = if (isConnected) breathingAlpha else 0f
                if (activeGlowAlpha > 0.01f) {
                    val blurRadiusPx = (22.dp.toPx() * breathingRadiusMultiplier).coerceAtLeast(1f)
                    drawIntoCanvas { canvas ->
                        breathingTrackGlowPaint.color = LightMintGlow
                            .copy(alpha = (activeGlowAlpha * 0.42f).coerceIn(0f, 1f))
                            .toArgb()
                        breathingTrackGlowPaint.maskFilter = BlurMaskFilter(
                            blurRadiusPx,
                            BlurMaskFilter.Blur.NORMAL
                        )
                        val spread = 4.dp.toPx() * breathingRadiusMultiplier
                        canvas.nativeCanvas.drawRoundRect(
                            -spread,
                            -spread,
                            size.width + spread,
                            size.height + spread,
                            trackCornerRadius.toPx() + spread,
                            trackCornerRadius.toPx() + spread,
                            breathingTrackGlowPaint
                        )
                    }
                }
            }
            .clip(RoundedCornerShape(trackCornerRadius))
            .background(TrackDarkBg)
            .drawWithContent {
                val cornerPx = trackCornerRadius.toPx()
                drawContent()
                // Subtle dark inner depth rim on the #0B2B26 track
                drawRoundRect(
                    color = MidDarkElements.copy(alpha = if (isConnected) 0.55f else 0.32f),
                    cornerRadius = CornerRadius(cornerPx, cornerPx),
                    style = Stroke(width = 1.dp.toPx())
                )
                // Liquid Glass specular energy rim flowing along the pill contour during AirDrop gather
                if (gather > 0.01f) {
                    val rimAlpha = (sin(gather * PI * 0.5).toFloat() * 0.72f).coerceIn(0f, 1f)
                    val sweepCenterX = size.width * (0.18f + 0.68f * gather)
                    drawRoundRect(
                        brush = Brush.radialGradient(
                            0.0f to LightMintGlow.copy(alpha = rimAlpha),
                            0.48f to AccentActive.copy(alpha = rimAlpha * 0.65f),
                            1.0f to Color.Transparent,
                            center = Offset(sweepCenterX, size.height * 0.5f),
                            radius = size.width * 0.58f
                        ),
                        cornerRadius = CornerRadius(cornerPx, cornerPx),
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        // =========================================================================
        // IN-SLIDER SPLIT-REVEAL STATUS TEXT LAYER (BEHIND THE LIQUID GLASS THUMB)
        // Strictly partitions the track at `thumbCenterX`:
        // - Left of thumb (`0 .. thumbCenterX - 6.dp`): "CONNECTING" / "CONNECTED"
        // - Right of thumb (`thumbCenterX + 6.dp .. width`): "DISCONNECTED"
        // Applied across ALL states (including dragging back from CONNECTED -> DISCONNECTED)
        // so the two words NEVER overlap or mix.
        // =========================================================================

        // 1. RIGHT OF THUMB (Ahead of the thumb): "DISCONNECTED"
        if (dragFraction < 0.96f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val totalW = size.width.coerceAtLeast(1f)
                        val fadeStartPx = (thumbCenterX + 6.dp.toPx()).coerceIn(0f, totalW)
                        val fadeEndPx = (thumbCenterX + 22.dp.toPx()).coerceIn(fadeStartPx + 1f, totalW)
                        val s0 = (fadeStartPx / totalW).coerceIn(0f, 0.99f)
                        val s1 = (fadeEndPx / totalW).coerceIn(s0 + 0.005f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Transparent,
                                s0 to Color.Transparent,
                                s1 to Color.Black,
                                1.0f to Color.Black
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = disconnectedLabel,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MutedSageText,
                        fontSize = 12.sp,
                        letterSpacing = 2.2.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(x = 8.dp)
                        .then(
                            if (dragFraction <= 0.5f) {
                                Modifier.testTag("vpn_status_text")
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }

        // 2. LEFT OF THUMB (Behind the thumb): "CONNECTING" while dragging/connecting -> "CONNECTED" when connected
        if (dragFraction > 0.04f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        val totalW = size.width.coerceAtLeast(1f)
                        val fadeStartPx = (thumbCenterX - 22.dp.toPx()).coerceIn(0f, totalW)
                        val fadeEndPx = (thumbCenterX - 6.dp.toPx()).coerceIn(fadeStartPx + 1f, totalW)
                        val s0 = (fadeStartPx / totalW).coerceIn(0f, 0.99f)
                        val s1 = (fadeEndPx / totalW).coerceIn(s0 + 0.005f, 1f)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                0.0f to Color.Black,
                                s0 to Color.Black,
                                s1 to Color.Transparent,
                                1.0f to Color.Transparent
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = behindThumbLabel,
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = leftRevealedTextColor,
                        fontSize = 12.sp,
                        letterSpacing = (2.2f + gather * 0.7f).sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(x = (-8).dp)
                        .graphicsLayer {
                            scaleX = textScaleX
                            scaleY = textScaleY
                            translationY = textOffsetY * 0.45f
                        }
                        .then(
                            if (innerTextBlurDp > 0.18f) {
                                Modifier.blur(
                                    radius = innerTextBlurDp.dp,
                                    edgeTreatment = BlurredEdgeTreatment.Unbounded
                                )
                            } else {
                                Modifier
                            }
                        )
                        .then(
                            if (dragFraction > 0.5f) {
                                Modifier.testTag("vpn_status_text")
                            } else {
                                Modifier
                            }
                        )
                )
            }
        }

        // =========================================================================
        // THE SLIDER THUMB: 62.dp circle, concentric with the track's end cap
        // =========================================================================
        Box(
            modifier = Modifier
                .offset {
                    val liveClampedX = thumbOffsetX.value.coerceIn(0f, maxDragPx.coerceAtLeast(1f))
                    IntOffset((trackPaddingPx + liveClampedX).roundToInt(), 0)
                }
                .size(thumbSize)
                .testTag("vpn_liquid_slider_thumb")
                .graphicsLayer {
                    val liveFraction = (thumbOffsetX.value / maxDragPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
                    // Pin TransformOrigin directly to the colliding wall (0f = left wall, 1f = right wall)
                    // so that horizontal compression (scaleX < 1f) and elastic rebound (scaleX > 1f)
                    // keep the wall-facing edge 100% flush with the inner wall and NEVER cross under the bar!
                    val pivotX = when {
                        wallImpactSide < 0 || liveFraction <= 0.12f -> 0f
                        wallImpactSide > 0 || liveFraction >= 0.88f -> 1f
                        else -> 0.5f
                    }
                    transformOrigin = TransformOrigin(pivotX, 0.5f)
                    scaleX = thumbScaleX.value.coerceIn(0.75f, 1.14f)
                    scaleY = thumbScaleY.value.coerceIn(0.84f, 1.16f)
                }
                .drawBehind {
                    val radius = size.minDimension / 2f

                    // CSS box-shadow: 0px 6px 24px rgba(0, 0, 0, 0.2)
                    val dropOffsetY = 5.dp.toPx()
                    val dropBlurPx = 18.dp.toPx()
                    drawIntoCanvas { canvas ->
                        dropShadowPaint.color = Color.Black.copy(alpha = 0.20f).toArgb()
                        dropShadowPaint.maskFilter = BlurMaskFilter(
                            dropBlurPx,
                            BlurMaskFilter.Blur.NORMAL
                        )
                        canvas.nativeCanvas.drawCircle(
                            center.x,
                            center.y + dropOffsetY,
                            radius * 0.88f,
                            dropShadowPaint
                        )
                    }

                    if (isConnecting) {
                        rotate(connectSpin) {
                            drawArc(
                                color = LightMintGlow.copy(alpha = 0.78f),
                                startAngle = -90f,
                                sweepAngle = 230f,
                                useCenter = false,
                                topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                                size = Size(
                                    size.width + 4.dp.toPx(),
                                    size.height + 4.dp.toPx()
                                ),
                                style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }
                }
                .clip(CircleShape)
                .drawWithContent {
                    val radius = size.minDimension / 2f
                    val circlePath = Path().apply {
                        addOval(Rect(0f, 0f, size.width, size.height))
                    }

                    clipPath(circlePath) {
                        // A. Uniform, centered Liquid Glass optical backdrop lens
                        val innerRimGlow = if (isConnected) breathingAlpha * 0.45f else if (isConnecting) 0.20f else 0.10f
                        drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to TrackDarkBg.copy(alpha = 0.35f),
                                0.65f to MidDarkElements.copy(alpha = 0.18f),
                                0.88f to AccentActive.copy(alpha = innerRimGlow),
                                1.0f to LightMintGlow.copy(alpha = innerRimGlow * 0.85f),
                                center = center,
                                radius = radius.coerceAtLeast(1f)
                            ),
                            radius = radius,
                            center = center
                        )

                        // A2. Optical Liquid Wall-Contact Shockwave & Meniscus Glow upon hitting left/right inner wall
                        val impactFlash = wallImpactFlash.value.coerceIn(0f, 1f)
                        if (impactFlash > 0.01f && wallImpactSide != 0) {
                            val contactX = if (wallImpactSide < 0) 0f else size.width
                            val contactPoint = Offset(contactX, center.y)
                            val shockwaveRadius = (size.width * (0.48f + 0.42f * (1f - impactFlash))).coerceAtLeast(1f)
                            drawCircle(
                                brush = Brush.radialGradient(
                                    0.0f to Color.White.copy(alpha = 0.56f * impactFlash),
                                    0.32f to LightMintGlow.copy(alpha = 0.44f * impactFlash),
                                    0.68f to AccentActive.copy(alpha = 0.22f * impactFlash),
                                    1.0f to Color.Transparent,
                                    center = contactPoint,
                                    radius = shockwaveRadius
                                ),
                                radius = shockwaveRadius,
                                center = contactPoint
                            )
                        }

                        // Draw the centered Power Icon
                        this@drawWithContent.drawContent()

                        // B. Exact CSS ::before -> box-shadow: inset 0 0 15px -6px #ffffff;
                        //    background-color: rgba(255, 255, 255, 0);
                        val insetBlurPx = 15.dp.toPx()
                        val insetSpreadPx = 6.dp.toPx()
                        drawIntoCanvas { canvas ->
                            insetWhiteShadowPaint.color = Color.White.copy(alpha = 0.88f).toArgb()
                            insetWhiteShadowPaint.strokeWidth = (insetBlurPx - insetSpreadPx).coerceAtLeast(4f)
                            insetWhiteShadowPaint.maskFilter = BlurMaskFilter(
                                insetBlurPx * 0.65f,
                                BlurMaskFilter.Blur.NORMAL
                            )
                            canvas.nativeCanvas.drawCircle(
                                center.x,
                                center.y,
                                radius + (insetSpreadPx * 0.45f),
                                insetWhiteShadowPaint
                            )
                        }

                        // Multi-stop radial inner glow matching `inset 0 0 15px -6px #ffffff`
                        drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to Color.White.copy(alpha = 0.0f),
                                0.68f to Color.White.copy(alpha = 0.0f),
                                0.84f to Color.White.copy(alpha = 0.12f),
                                0.94f to Color.White.copy(alpha = 0.38f),
                                1.0f to Color.White.copy(alpha = 0.78f),
                                center = center,
                                radius = radius.coerceAtLeast(1f)
                            ),
                            radius = radius,
                            center = center
                        )

                        // Ultra-fine refractive edge highlight (intensifies at wall contact point during collision)
                        val leftRimAlpha = if (wallImpactSide < 0) (0.65f + 0.35f * impactFlash).coerceAtMost(1f) else 0.55f
                        val rightRimAlpha = if (wallImpactSide > 0) (0.65f + 0.35f * impactFlash).coerceAtMost(1f) else 0.45f
                        drawCircle(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = leftRimAlpha),
                                    LightMintGlow.copy(alpha = (0.24f + 0.45f * impactFlash).coerceAtMost(0.9f)),
                                    Color.White.copy(alpha = rightRimAlpha)
                                ),
                                start = Offset(0f, center.y - radius * 0.4f),
                                end = Offset(size.width, center.y + radius * 0.4f)
                            ),
                            radius = radius - 0.5.dp.toPx(),
                            center = center,
                            style = Stroke(width = (1f + 1.1f * impactFlash).dp.toPx())
                        )
                    }
                }
                .pointerInput(maxDragPx) {
                    val safeMaxDrag = maxDragPx.coerceAtLeast(1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()

                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPosition(down.uptimeMillis, down.position)

                        var totalDistance = 0f
                        var currentThumbX = thumbOffsetX.value.coerceIn(0f, safeMaxDrag)
                        var hasTriggeredInGesture = false
                        val initialGestureState = currentState
                        isUserInteracting = true

                        // Subtle tactile liquid press response
                        scope.launch {
                            wallImpactSide = when {
                                currentThumbX <= 2f -> -1
                                currentThumbX >= safeMaxDrag - 2f -> 1
                                else -> 0
                            }
                            thumbScaleX.animateTo(
                                targetValue = 1.03f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                            thumbScaleY.animateTo(
                                targetValue = 0.97f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMedium
                                )
                            )
                        }

                        var pointerId = down.id
                        while (true) {
                            val event = awaitPointerEvent()
                            val dragChange = event.changes.firstOrNull { it.id == pointerId }
                                ?: event.changes.firstOrNull()
                                ?: break

                            pointerId = dragChange.id
                            velocityTracker.addPosition(dragChange.uptimeMillis, dragChange.position)

                            if (!dragChange.pressed) {
                                dragChange.consume()
                                break
                            }

                            val delta = dragChange.positionChange()
                            if (delta != Offset.Zero) {
                                totalDistance += delta.getDistance()
                                dragChange.consume()

                                if (!hasTriggeredInGesture) {
                                    val unclampedNextX = currentThumbX + delta.x
                                    currentThumbX = unclampedNextX.coerceIn(0f, safeMaxDrag)
                                    val liveRatio = currentThumbX / safeMaxDrag

                                    // 1. Dragging from DISCONNECTED into the right inner wall:
                                    //    Lock to right wall, trigger connect, and play right-wall collision animation!
                                    if (initialGestureState == VpnConnectionState.DISCONNECTED && liveRatio >= 0.96f) {
                                        hasTriggeredInGesture = true
                                        currentThumbX = safeMaxDrag
                                        val hitIntensity = (0.78f + (abs(delta.x) / 36f)).coerceIn(0.80f, 1.0f)
                                        scope.launch {
                                            try {
                                                thumbOffsetX.snapTo(safeMaxDrag)
                                                playWallCollisionAnimation(side = 1, intensity = hitIntensity)
                                            } finally {
                                                isUserInteracting = false
                                            }
                                        }
                                        currentOnConnect()
                                    } else if (initialGestureState != VpnConnectionState.DISCONNECTED && liveRatio <= 0.04f) {
                                        // 2. Dragging back from CONNECTED into the left inner wall:
                                        //    Lock to left wall, trigger disconnect, and play left-wall collision animation!
                                        hasTriggeredInGesture = true
                                        currentThumbX = 0f
                                        val hitIntensity = (0.78f + (abs(delta.x) / 36f)).coerceIn(0.80f, 1.0f)
                                        scope.launch {
                                            try {
                                                thumbOffsetX.snapTo(0f)
                                                playWallCollisionAnimation(side = -1, intensity = hitIntensity)
                                            } finally {
                                                isUserInteracting = false
                                            }
                                        }
                                        currentOnDisconnect()
                                    } else if (unclampedNextX < 0f && currentThumbX == 0f) {
                                        // Pushing directly against the left inner wall while at x = 0f
                                        wallImpactSide = -1
                                        val wallPush = (abs(delta.x) / 28f).coerceIn(0.04f, 0.16f)
                                        scope.launch {
                                            thumbOffsetX.snapTo(0f)
                                            thumbScaleX.snapTo(1f - wallPush)
                                            thumbScaleY.snapTo(1f + wallPush * 0.78f)
                                            wallImpactFlash.snapTo((wallPush * 4.5f).coerceIn(0.18f, 0.75f))
                                        }
                                    } else if (unclampedNextX > safeMaxDrag && currentThumbX == safeMaxDrag) {
                                        // Pushing directly against the right inner wall while at x = safeMaxDrag
                                        wallImpactSide = 1
                                        val wallPush = (abs(delta.x) / 28f).coerceIn(0.04f, 0.16f)
                                        scope.launch {
                                            thumbOffsetX.snapTo(safeMaxDrag)
                                            thumbScaleX.snapTo(1f - wallPush)
                                            thumbScaleY.snapTo(1f + wallPush * 0.78f)
                                            wallImpactFlash.snapTo((wallPush * 4.5f).coerceIn(0.18f, 0.75f))
                                        }
                                    } else {
                                        // Mid-track fluid drag stretch
                                        wallImpactSide = 0
                                        val stretch = (abs(delta.x) / 38f).coerceIn(0f, 0.055f)
                                        val snapTarget = currentThumbX
                                        scope.launch {
                                            thumbOffsetX.snapTo(snapTarget)
                                            thumbScaleX.snapTo(1f + stretch)
                                            thumbScaleY.snapTo(1f - stretch * 0.85f)
                                        }
                                    }
                                }
                            }
                        }

                        val releaseVelocityX = velocityTracker.calculateVelocity().x.coerceIn(-1800f, 1800f)

                        if (hasTriggeredInGesture) {
                            // Wall collision animation was already launched when the thumb hit the wall during drag;
                            // its finally block will clear isUserInteracting once the rebound finishes.
                        } else if (totalDistance < tapSlopPx) {
                            // A tap on the power thumb performs the same action as a completed drag.
                            when (initialGestureState) {
                                VpnConnectionState.DISCONNECTED -> {
                                    scope.launch {
                                        try {
                                            if (abs(safeMaxDrag - thumbOffsetX.value) > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = safeMaxDrag,
                                                    animationSpec = tween(
                                                        durationMillis = 210,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(safeMaxDrag)
                                            }
                                            playWallCollisionAnimation(side = 1, intensity = 0.92f)
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                    currentOnConnect()
                                }
                                VpnConnectionState.CONNECTED -> {
                                    scope.launch {
                                        try {
                                            if (thumbOffsetX.value > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = 190,
                                                        easing = FastOutSlowInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(0f)
                                            }
                                            playWallCollisionAnimation(side = -1, intensity = 0.92f)
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                    currentOnDisconnect()
                                }
                                VpnConnectionState.CONNECTING -> {
                                    isUserInteracting = false
                                }
                            }
                        } else {
                            // 2. Drag & Release Mechanics using LIVE currentThumbX
                            val liveOffsetX = currentThumbX.coerceIn(0f, safeMaxDrag)
                            val currentRatio = liveOffsetX / safeMaxDrag
                            if (initialGestureState == VpnConnectionState.DISCONNECTED) {
                                // Snap to right wall if dragged to >= 82% (or flung rightward past 60%)
                                val reachedThreshold = currentRatio >= 0.82f ||
                                    (currentRatio >= 0.60f && releaseVelocityX > 700f)

                                if (reachedThreshold) {
                                    val remainingRatio = (1f - currentRatio).coerceIn(0f, 1f)
                                    val travelMs = (remainingRatio * 130f).roundToInt().coerceIn(35, 120)
                                    scope.launch {
                                        try {
                                            if (abs(safeMaxDrag - liveOffsetX) > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = safeMaxDrag,
                                                    animationSpec = tween(
                                                        durationMillis = travelMs,
                                                        easing = FastOutLinearInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(safeMaxDrag)
                                            }
                                            playWallCollisionAnimation(
                                                side = 1,
                                                intensity = (0.82f + remainingRatio * 0.18f).coerceIn(0.82f, 1.0f)
                                            )
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                    currentOnConnect()
                                } else {
                                    // Released before threshold -> travel briskly back to left wall (0f)
                                    // and play liquid wall-collision animation when hitting the left wall!
                                    val travelRatio = currentRatio.coerceIn(0f, 1f)
                                    val travelMs = (travelRatio * 140f).roundToInt().coerceIn(40, 130)
                                    val impactStrength = (0.58f + travelRatio * 0.42f).coerceIn(0.58f, 1.0f)
                                    scope.launch {
                                        try {
                                            if (liveOffsetX > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = travelMs,
                                                        easing = FastOutLinearInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(0f)
                                            }
                                            playWallCollisionAnimation(
                                                side = -1,
                                                intensity = impactStrength
                                            )
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                }
                            } else {
                                // 3. Disconnect or snap back when starting from CONNECTED
                                val shouldDisconnect = currentRatio <= 0.88f &&
                                    (abs(safeMaxDrag - liveOffsetX) > 12f || releaseVelocityX < -500f)

                                if (shouldDisconnect) {
                                    val travelRatio = currentRatio.coerceIn(0f, 1f)
                                    val travelMs = (travelRatio * 140f).roundToInt().coerceIn(40, 130)
                                    val impactStrength = (0.68f + travelRatio * 0.32f).coerceIn(0.68f, 1.0f)
                                    scope.launch {
                                        try {
                                            if (liveOffsetX > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = 0f,
                                                    animationSpec = tween(
                                                        durationMillis = travelMs,
                                                        easing = FastOutLinearInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(0f)
                                            }
                                            playWallCollisionAnimation(
                                                side = -1,
                                                intensity = impactStrength
                                            )
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                    currentOnDisconnect()
                                } else {
                                    val remainingRatio = (1f - currentRatio).coerceIn(0f, 1f)
                                    val travelMs = (remainingRatio * 125f).roundToInt().coerceIn(35, 115)
                                    scope.launch {
                                        try {
                                            if (abs(safeMaxDrag - liveOffsetX) > 2f) {
                                                thumbOffsetX.animateTo(
                                                    targetValue = safeMaxDrag,
                                                    animationSpec = tween(
                                                        durationMillis = travelMs,
                                                        easing = FastOutLinearInEasing
                                                    )
                                                )
                                            } else {
                                                thumbOffsetX.snapTo(safeMaxDrag)
                                            }
                                            playWallCollisionAnimation(
                                                side = 1,
                                                intensity = (0.62f + remainingRatio * 0.35f).coerceIn(0.62f, 0.92f)
                                            )
                                        } finally {
                                            isUserInteracting = false
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = PtunnelIcons.Power,
                contentDescription = stringResource(R.string.cd_power_slider),
                tint = powerIconTint,
                modifier = Modifier
                    .offset { IntOffset(iconWallOffsetX.value.roundToInt(), 0) }
                    .size(26.dp)
            )
        }
    }
}
