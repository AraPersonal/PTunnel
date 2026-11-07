package com.example.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.graphics.BlurMaskFilter
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.VpnConnectionState
import com.example.ui.theme.AccentActive
import com.example.ui.theme.TrackDarkBg
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MutedSageText
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin

// Subtle Liquid Glass specular tint (used at low alpha inside wide radial gradients, never as hard lines)
private val LiquidGlassSpecular = Color(0xFFF2FBF5)

// Smooth, deliberate iOS AirDrop easing curves
private val LiquidStretchGatherEasing = CubicBezierEasing(0.26f, 0.04f, 0.14f, 1.0f)
private val LiquidGlassDomeExpandEasing = CubicBezierEasing(0.16f, 0.82f, 0.20f, 1.0f)

/**
 * Shared reactive state driving:
 * 1. Subtle Android 15+ Overscroll-style vertical elastic stretch (`containerWarp` & `airDropElementWarp`)
 *    that keeps full horizontal width (`scaleX = 1f`) so content never bunches into the center.
 * 2. Seamless, line-free 3D Liquid Glass optical dome shockwave (`AirDropConnectionWaveOverlay`).
 */
@Stable
class AirDropChoreographyState {
    // 0f -> 1f while CONNECTING (drives subtle Android 15 vertical overscroll stretch)
    var gatherTension by mutableFloatStateOf(0f)
        internal set

    // +1f (subtle vertical stretch) -> -0.18f (soft rebound) -> 0f (elastic spring settle)
    var elasticWarpFactor by mutableFloatStateOf(0f)
        internal set

    // 0f -> 1f when CONNECTED triggers (drives the expanding Liquid Glass optical dome)
    var shockwaveProgress by mutableFloatStateOf(0f)
        internal set

    // 0f -> 1f -> 0f master visibility alpha so zero residual glow remains after completion
    var overlayAlpha by mutableFloatStateOf(0f)
        internal set

    // Unified soft blur applied once on the dashboard container (avoids per-row rectangular box seams)
    var containerBlurDp by mutableFloatStateOf(0f)
        internal set

    // Subtle elastic morph values for the top status header ("DISCONNECTED" -> "CONNECTING..." -> "CONNECTED")
    var headerScaleX by mutableFloatStateOf(1f)
        internal set
    var headerScaleY by mutableFloatStateOf(1f)
        internal set
    var headerOffsetY by mutableFloatStateOf(0f)
        internal set
    var headerBlurDp by mutableFloatStateOf(0f)
        internal set

    val isActive: Boolean
        get() = overlayAlpha > 0.003f ||
            gatherTension > 0.003f ||
            shockwaveProgress > 0.003f ||
            abs(elasticWarpFactor) > 0.003f
}

@Composable
fun rememberAirDropChoreographyState(
    connectionState: VpnConnectionState,
    enabled: Boolean,
    connectingServerKey: String? = null
): AirDropChoreographyState {
    val state = remember { AirDropChoreographyState() }
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current

    val gatherAnim = remember { Animatable(0f) }
    val elasticWarpAnim = remember { Animatable(0f) }
    val shockwaveAnim = remember { Animatable(0f) }
    val alphaAnim = remember { Animatable(0f) }
    val containerBlurAnim = remember { Animatable(0f) }

    val hScaleX = remember { Animatable(1f) }
    val hScaleY = remember { Animatable(1f) }
    val hOffsetY = remember { Animatable(0f) }
    val hBlur = remember { Animatable(0f) }

    var previousState by remember { mutableStateOf(connectionState) }

    LaunchedEffect(
        gatherAnim.value,
        elasticWarpAnim.value,
        shockwaveAnim.value,
        alphaAnim.value,
        containerBlurAnim.value,
        hScaleX.value,
        hScaleY.value,
        hOffsetY.value,
        hBlur.value
    ) {
        state.gatherTension = gatherAnim.value
        state.elasticWarpFactor = elasticWarpAnim.value
        state.shockwaveProgress = shockwaveAnim.value
        state.overlayAlpha = alphaAnim.value
        state.containerBlurDp = containerBlurAnim.value
        state.headerScaleX = hScaleX.value
        state.headerScaleY = hScaleY.value
        state.headerOffsetY = hOffsetY.value
        state.headerBlurDp = hBlur.value
    }

    LaunchedEffect(connectionState, enabled, connectingServerKey) {
        if (!enabled) {
            gatherAnim.snapTo(0f)
            elasticWarpAnim.snapTo(0f)
            shockwaveAnim.snapTo(0f)
            alphaAnim.snapTo(0f)
            containerBlurAnim.snapTo(0f)
            hScaleX.snapTo(1f)
            hScaleY.snapTo(1f)
            hOffsetY.snapTo(0f)
            hBlur.snapTo(0f)
            previousState = connectionState
            return@LaunchedEffect
        }

        when (connectionState) {
            VpnConnectionState.CONNECTING -> {
                previousState = VpnConnectionState.CONNECTING
                shockwaveAnim.snapTo(0f)
                gatherAnim.snapTo(0f)
                elasticWarpAnim.snapTo(0f)
                containerBlurAnim.snapTo(0f)
                alphaAnim.snapTo(1f)

                // Precision-synchronized rising haptic ramp across the full 3.2s connection ceremony
                launch {
                    val pulseDelays = longArrayOf(0L, 420L, 380L, 340L, 320L, 300L, 280L, 260L)
                    val durations = longArrayOf(12L, 14L, 16L, 18L, 20L, 22L, 24L, 26L)
                    val amplitudes = intArrayOf(45, 70, 105, 145, 180, 215, 240, 255)
                    for (i in pulseDelays.indices) {
                        if (pulseDelays[i] > 0L) delay(pulseDelays[i])
                        vibrateOneShot(context, durations[i], amplitudes[i])
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                coroutineScope {
                    // 3,200ms smooth Android 15-style subtle vertical overscroll stretch
                    launch {
                        gatherAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 3200,
                                easing = LiquidStretchGatherEasing
                            )
                        )
                    }
                    launch {
                        elasticWarpAnim.animateTo(
                            targetValue = 1f,
                            animationSpec = tween(
                                durationMillis = 3200,
                                easing = LiquidStretchGatherEasing
                            )
                        )
                    }
                    // Smooth progressive blur before the AirDrop wave release
                    launch {
                        delay(1100L)
                        containerBlurAnim.animateTo(
                            targetValue = 5.5f,
                            animationSpec = tween(
                                durationMillis = 1600,
                                easing = FastOutSlowInEasing
                            )
                        )
                    }
                    // Subtle vertical stretch on the status header without extreme distortion
                    launch {
                        hScaleX.animateTo(
                            targetValue = 0.96f,
                            animationSpec = tween(durationMillis = 3200, easing = LiquidStretchGatherEasing)
                        )
                    }
                    launch {
                        hScaleY.animateTo(
                            targetValue = 1.10f,
                            animationSpec = tween(durationMillis = 3200, easing = LiquidStretchGatherEasing)
                        )
                    }
                    launch {
                        hOffsetY.animateTo(
                            targetValue = -5f,
                            animationSpec = tween(durationMillis = 3200, easing = LiquidStretchGatherEasing)
                        )
                    }
                    launch {
                        delay(1200L)
                        hBlur.animateTo(
                            targetValue = 7.5f,
                            animationSpec = tween(durationMillis = 1500, easing = FastOutSlowInEasing)
                        )
                    }
                }
            }

            VpnConnectionState.CONNECTED -> {
                val shouldPlayReleaseWave = previousState == VpnConnectionState.CONNECTING ||
                    previousState == VpnConnectionState.DISCONNECTED ||
                    gatherAnim.value > 0.005f ||
                    alphaAnim.value > 0.005f
                previousState = VpnConnectionState.CONNECTED

                if (shouldPlayReleaseWave) {
                    alphaAnim.snapTo(1f)
                    if (gatherAnim.value < 0.85f) {
                        gatherAnim.snapTo(1f)
                        elasticWarpAnim.snapTo(1f)
                    }

                    // Synchronized multi-stage AirDrop Wave Release haptic choreography:
                    // 1) Immediate deep release pulse at 0ms
                    // 2) Elastic spring rebound pulse at +95ms
                    // 3) Secondary wave ripple pulse at +240ms
                    // 4) Soft final settle tick at +450ms
                    launch {
                        vibrateOneShot(context, durationMs = 36L, amplitude = 255)
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        delay(95L)
                        vibrateOneShot(context, durationMs = 20L, amplitude = 150)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        delay(145L)
                        vibrateOneShot(context, durationMs = 15L, amplitude = 90)
                        delay(210L)
                        vibrateOneShot(context, durationMs = 12L, amplitude = 50)
                    }

                    // Gentle pop on the status text before spring settle
                    hScaleX.snapTo(1.06f)
                    hScaleY.snapTo(0.95f)
                    hBlur.snapTo(6f)

                    val headerElasticSpring = spring<Float>(
                        dampingRatio = 0.54f,
                        stiffness = 160f
                    )
                    // Android 15-style overscroll spring return (smooth, refined bounce without wild oscillation)
                    val android15StretchSpring = spring<Float>(
                        dampingRatio = 0.68f,
                        stiffness = 180f
                    )

                    coroutineScope {
                        // 2,600ms silky 3D Liquid Glass optical dome expansion across the screen
                        launch {
                            shockwaveAnim.snapTo(0f)
                            shockwaveAnim.animateTo(
                                targetValue = 1f,
                                animationSpec = tween(
                                    durationMillis = 2600,
                                    easing = LiquidGlassDomeExpandEasing
                                )
                            )
                        }
                        // Release gather tension as the Liquid Glass dome expands
                        // so no residual Stage 1 halo lingers under the slider bar above the chart
                        launch {
                            gatherAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(
                                    durationMillis = 260,
                                    easing = FastOutSlowInEasing
                                )
                            )
                        }
                        // Smooth Android 15 overscroll elastic release back to 0f
                        launch {
                            elasticWarpAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = android15StretchSpring,
                                initialVelocity = -1.2f
                            )
                        }
                        // Smoothly unblur the content as the Liquid Glass dome washes over it
                        launch {
                            containerBlurAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 520, easing = LinearOutSlowInEasing)
                            )
                        }
                        // Elastic rebound for the "CONNECTED" status header
                        launch { hScaleX.animateTo(1f, headerElasticSpring) }
                        launch { hScaleY.animateTo(1f, headerElasticSpring) }
                        launch { hOffsetY.animateTo(0f, headerElasticSpring) }
                        launch {
                            hBlur.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 700, easing = LinearOutSlowInEasing)
                            )
                        }
                        // Fade out the Liquid Glass dome cleanly at the end
                        launch {
                            delay(1650L)
                            alphaAnim.animateTo(
                                targetValue = 0f,
                                animationSpec = tween(durationMillis = 900, easing = LinearOutSlowInEasing)
                            )
                        }
                    }
                }
            }

            VpnConnectionState.DISCONNECTED -> {
                val wasActive = previousState != VpnConnectionState.DISCONNECTED
                previousState = VpnConnectionState.DISCONNECTED
                if (wasActive) {
                    vibrateOneShot(context, durationMs = 16L, amplitude = 110)
                }
                val resetSpring = spring<Float>(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
                coroutineScope {
                    launch { gatherAnim.animateTo(0f, tween(260)) }
                    launch { elasticWarpAnim.animateTo(0f, resetSpring) }
                    launch { shockwaveAnim.snapTo(0f) }
                    launch { alphaAnim.animateTo(0f, tween(240)) }
                    launch { containerBlurAnim.animateTo(0f, tween(200)) }
                    launch { hScaleX.animateTo(1f, resetSpring) }
                    launch { hScaleY.animateTo(1f, resetSpring) }
                    launch { hOffsetY.animateTo(0f, resetSpring) }
                    launch { hBlur.animateTo(0f, tween(200)) }
                }
            }
        }
    }

    return state
}

/**
 * Triggers a hardware-calibrated haptic pulse using Android's [Vibrator] / [VibratorManager].
 */
private fun vibrateOneShot(
    context: Context,
    durationMs: Long,
    amplitude: Int
) {
    runCatching {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val safeAmp = amplitude.coerceIn(1, 255)
                vibrator.vibrate(
                    VibrationEffect.createOneShot(durationMs.coerceAtLeast(8L), safeAmp)
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(durationMs.coerceAtLeast(8L))
            }
        }
    }
}

/**
 * Android 15+ Overscroll Stretch (`EdgeEffect` style) applied to the main dashboard Column:
 * - Keeps `scaleX = 1.0f` at all times so content NEVER bunches or shrinks into the middle of the screen.
 * - Applies a subtle, refined vertical overscroll stretch (`scaleY` up to `1.042f`, i.e., ~4.2% vertical
 *   elastic stretch anchored at the top edge `TransformOrigin(0.5f, 0f)`).
 */
@Composable
fun Modifier.airDropContainerWarp(
    choreography: AirDropChoreographyState
): Modifier {
    if (!choreography.isActive) return this

    val warp = choreography.elasticWarpFactor

    // Subtle Android 15 overscroll vertical stretch (~4.2% max) with ZERO horizontal pinch (scaleX = 1f)
    val containerScaleY = (1f + warp * 0.042f).coerceIn(0.985f, 1.055f)

    return this.graphicsLayer {
        transformOrigin = TransformOrigin(0.5f, 0f)
        scaleX = 1f
        scaleY = containerScaleY
        translationY = -5.dp.toPx() * warp
    }
}

/**
 * Applies the unified AirDrop optical blur (`containerBlurDp`) to a dashboard section.
 * - Surrounding sections use `blurScale = 1f` (full 5.5dp creamy AirDrop blur).
 * - The LiquidSlider (track, power thumb, and in-slider status text) uses a much lower
 *   `blurScale` (`0.16f`, ~0.88dp max) so the user can clearly read the text inside the slider
 *   while the rest of the screen blurs around it.
 */
@Composable
fun Modifier.airDropContentBlur(
    choreography: AirDropChoreographyState,
    blurScale: Float = 1f
): Modifier {
    if (!choreography.isActive) return this
    val effectiveBlurDp = (choreography.containerBlurDp * blurScale).coerceAtLeast(0f)
    return if (effectiveBlurDp > 0.18f) {
        this.blur(
            radius = effectiveBlurDp.dp,
            edgeTreatment = BlurredEdgeTreatment.Unbounded
        )
    } else {
        this
    }
}

/**
 * Subtle Android 15+ Overscroll per-item vertical elasticity:
 * - `scaleX` stays at `1.0f` so rows never shrink horizontally toward the center.
 * - Each item gently stretches vertically by only ~2.5% to 3.5% (`scaleY = 1.028f`) and shifts by a few
 *   millimeters (`-6.dp` max) like Android 15's native list overscroll stretch, then softly ripples as the
 *   AirDrop wave passes over it.
 */
@Composable
fun Modifier.airDropElementWarp(
    choreography: AirDropChoreographyState,
    distanceFactor: Float,
    stretchIntensity: Float = 1f
): Modifier {
    if (!choreography.isActive) return this

    val warp = choreography.elasticWarpFactor
    val gather = choreography.gatherTension.coerceIn(0f, 1f)
    val shock = choreography.shockwaveProgress.coerceIn(0f, 1f)

    val delayedGather = ((gather - distanceFactor * 0.14f) / (1f - distanceFactor * 0.14f).coerceAtLeast(0.2f))
        .coerceIn(0f, 1f)
    val effectiveWarp = if (shock > 0.005f) warp else delayedGather

    // Localized wave-crest ripple as the expanding Liquid Glass dome passes through `distanceFactor`
    val waveDelta = shock - (distanceFactor * 0.74f)
    val waveCrest = if (shock > 0.005f) {
        (exp(-(waveDelta * waveDelta) / 0.020f) * (1f - shock * 0.72f)).coerceIn(0f, 1f)
    } else {
        0f
    }

    // Subtle Android 15-style vertical elasticity (~2.8% vertical stretch, zero horizontal pinch)
    val itemScaleY = (1f + (0.028f * effectiveWarp * stretchIntensity) + (0.016f * waveCrest))
        .coerceIn(0.985f, 1.048f)

    return this.graphicsLayer {
        transformOrigin = TransformOrigin(0.5f, 0f)
        scaleX = 1f
        scaleY = itemScaleY
        val pullUpPx = -6.dp.toPx() * effectiveWarp * stretchIntensity * distanceFactor
        val wavePushPx = 4.dp.toPx() * waveCrest
        translationY = pullUpPx + wavePushPx
    }
}

/**
 * Telegram-inspired Living Animated Background (`بگراند زنده مشابه تلگرام`).
 *
 * - When [isConnected] is `false` (`DISCONNECTED` or `CONNECTING`):
 *   Remains 100% static and still (fades out cleanly so only the flat `#051F20` background is shown).
 * - When [isConnected] is `true` (`CONNECTED`):
 *   Smoothly awakens and continuously moves! Four soft, organic emerald & mint light lobes
 *   glide and swirl along continuous orbital curves in the background just like Telegram's
 *   animated gradient wallpaper.
 */
@Composable
fun TelegramLivingBackground(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val activeAlpha by animateFloatAsState(
        targetValue = if (isConnected) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (isConnected) 950 else 650,
            easing = FastOutSlowInEasing
        ),
        label = "telegram_living_bg_alpha"
    )

    if (activeAlpha <= 0.004f) return

    val infiniteTransition = rememberInfiniteTransition(label = "telegram_living_bg_orbit")
    val twoPi = (2.0 * PI).toFloat()

    // Four long, calm, incommensurable 0..2π orbital phases.
    // Every sin/cos expression below uses ONLY exact integer multiples (±1) of these phases
    // so f(2π) == f(0) and f'(2π) == f'(0) identically — guaranteeing 100% seamless,
    // continuous motion with zero jump or loop reset ever.
    val phase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 19000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_orbit_phase_1"
    )

    val phase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 25000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_orbit_phase_2"
    )

    val phase3 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 31000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_orbit_phase_3"
    )

    val phase4 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = twoPi,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 37000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_orbit_phase_4"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("telegram_living_background_canvas")
    ) {
        val w = size.width
        val h = size.height
        val maxDim = size.maxDimension

        // 1. Moving Light Orb 1 (Upper-Left <-> Upper-Right Lissajous Glide)
        val orb1Center = Offset(
            x = w * (0.50f + 0.32f * cos(phase1)),
            y = h * (0.24f + 0.14f * sin(phase2))
        )
        val orb1Radius = maxDim * (0.52f + 0.05f * sin(phase1))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to AccentActive.copy(alpha = 0.42f * activeAlpha),
                0.42f to MidDarkElements.copy(alpha = 0.28f * activeAlpha),
                0.75f to TrackDarkBg.copy(alpha = 0.14f * activeAlpha),
                1.0f to Color.Transparent,
                center = orb1Center,
                radius = orb1Radius
            ),
            radius = orb1Radius,
            center = orb1Center
        )

        // 2. Moving Light Orb 2 (Middle-Right <-> Lower-Left Diagonal Swirl with soft Mint glow)
        val orb2Center = Offset(
            x = w * (0.52f + 0.34f * sin(phase2 + 0.8f)),
            y = h * (0.56f + 0.20f * cos(phase3 + 0.5f))
        )
        val orb2Radius = maxDim * (0.48f + 0.05f * cos(phase2))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to MutedSageText.copy(alpha = 0.22f * activeAlpha),
                0.36f to AccentActive.copy(alpha = 0.32f * activeAlpha),
                0.72f to MidDarkElements.copy(alpha = 0.16f * activeAlpha),
                1.0f to Color.Transparent,
                center = orb2Center,
                radius = orb2Radius
            ),
            radius = orb2Radius,
            center = orb2Center
        )

        // 3. Moving Light Orb 3 (Lower-Right <-> Center-Left Counter-Orbit)
        val orb3Center = Offset(
            x = w * (0.48f - 0.33f * cos(phase3)),
            y = h * (0.76f + 0.15f * sin(phase1 - phase4))
        )
        val orb3Radius = maxDim * (0.55f + 0.06f * sin(phase3))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to LightMintGlow.copy(alpha = 0.15f * activeAlpha),
                0.35f to AccentActive.copy(alpha = 0.30f * activeAlpha),
                0.70f to TrackDarkBg.copy(alpha = 0.18f * activeAlpha),
                1.0f to Color.Transparent,
                center = orb3Center,
                radius = orb3Radius
            ),
            radius = orb3Radius,
            center = orb3Center
        )

        // 4. Moving Light Orb 4 (Gentle Top-to-Bottom Breathing Luminous Current)
        val orb4Center = Offset(
            x = w * (0.50f + 0.28f * sin(phase4 - 1.1f)),
            y = h * (0.42f + 0.24f * cos(phase4 + phase1))
        )
        val orb4Radius = maxDim * (0.45f + 0.04f * cos(phase4))
        drawCircle(
            brush = Brush.radialGradient(
                0.0f to LightMintGlow.copy(alpha = 0.13f * activeAlpha),
                0.45f to MutedSageText.copy(alpha = 0.16f * activeAlpha),
                0.80f to AccentActive.copy(alpha = 0.10f * activeAlpha),
                1.0f to Color.Transparent,
                center = orb4Center,
                radius = orb4Radius
            ),
            radius = orb4Radius,
            center = orb4Center
        )
    }
}

/**
 * Full-screen iPhone AirDrop "Liquid Glass" Optical Dome Overlay:
 * - Uses exact Euclidean Stadium Parallel-Offset geometry (`RoundRect(W + 2d, H + 2d, R + d)`)
 *   combined with `clipPath(sliderPillPath, ClipOp.Difference)` so the effect hugs the
 *   280.dp x 74.dp slider pill contour seamlessly without ever forming an awkward oval circle
 *   or painting inside the slider bar.
 * - Stage 1 (CONNECTING): Flush stadium-contoured Liquid Glass backlight + two inward-converging
 *   magnetic stadium ripples + power-thumb focal lens flare on the right cap.
 * - Stage 2 (CONNECTED): Seamless Stadium-to-Horizon 3D Liquid Glass wave expanding from the
 *   exact slider contour across the entire screen.
 */
@Composable
fun AirDropConnectionWaveOverlay(
    choreography: AirDropChoreographyState,
    focalCenterOverride: Offset = Offset.Unspecified,
    modifier: Modifier = Modifier
) {
    if (!choreography.isActive) return

    val glowFillPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val waveStrokePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("airdrop_connection_wave_canvas")
    ) {
        val masterAlpha = choreography.overlayAlpha.coerceIn(0f, 1f)
        val gather = choreography.gatherTension.coerceIn(0f, 1f)
        val shock = choreography.shockwaveProgress.coerceIn(0f, 1f)

        val focalX = if (focalCenterOverride != Offset.Unspecified && focalCenterOverride.x > 1f) {
            focalCenterOverride.x
        } else {
            size.width * 0.5f
        }
        val focalY = if (focalCenterOverride != Offset.Unspecified && focalCenterOverride.y > 1f) {
            focalCenterOverride.y
        } else {
            size.height * 0.185f
        }

        // Exact dimensions of the 280.dp x 74.dp LiquidSlider pill
        val pillHalfW = 140.dp.toPx()
        val pillHalfH = 37.dp.toPx()
        val sliderPillPath = Path().apply {
            addRoundRect(
                RoundRect(
                    left = focalX - pillHalfW,
                    top = focalY - pillHalfH,
                    right = focalX + pillHalfW,
                    bottom = focalY + pillHalfH,
                    cornerRadius = CornerRadius(pillHalfH, pillHalfH)
                )
            )
        }

        // Clip out the interior of the slider pill so the inside of the bar stays 100% untouched
        // (#0B2B26) while the outer Liquid Glass aura & waves emerge flush from its exact border.
        clipPath(sliderPillPath, clipOp = ClipOp.Difference) {
            // =========================================================================
            // STAGE 1: STADIUM-CONTOURED LIQUID GLASS MAGNETIC AURA & CONVERGING RIPPLES
            // =========================================================================
            if (gather > 0.005f) {
                val stage1Fade = (1f - shock * 5.0f).coerceIn(0f, 1f)
                val gatherIntensity = sin(gather * PI * 0.5).toFloat().coerceIn(0f, 1f) * masterAlpha * stage1Fade

                if (gatherIntensity > 0.005f) {
                    // 1. Soft atmospheric emerald-mint silk field centered strictly on the slider
                    val ambientCenter = Offset(
                        x = focalX,
                        y = focalY
                    )
                    val ambientRadius = size.minDimension * (0.58f - 0.10f * gather).coerceAtLeast(0.32f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to AccentActive.copy(alpha = 0.22f * gatherIntensity),
                            0.42f to MidDarkElements.copy(alpha = 0.14f * gatherIntensity),
                            0.74f to LightMintGlow.copy(alpha = 0.06f * gatherIntensity),
                            1.0f to Color.Transparent,
                            center = ambientCenter,
                            radius = ambientRadius
                        ),
                        radius = ambientRadius,
                        center = ambientCenter
                    )

                    // 2. Flush Stadium-Contoured Liquid Glass Backlight hugging the exact 280x74 pill border
                    drawIntoCanvas { canvas ->
                        // Outer deep emerald-teal cushion
                        val outerSpread = (6.dp.toPx() + 20.dp.toPx() * gather)
                        val outerBlur = (26.dp.toPx() + 14.dp.toPx() * gather).coerceAtLeast(1f)
                        glowFillPaint.color = AccentActive
                            .copy(alpha = (0.46f * gatherIntensity).coerceIn(0f, 1f))
                            .toArgb()
                        glowFillPaint.maskFilter = BlurMaskFilter(outerBlur, BlurMaskFilter.Blur.NORMAL)
                        val outerR = pillHalfH + outerSpread
                        canvas.nativeCanvas.drawRoundRect(
                            focalX - pillHalfW - outerSpread,
                            focalY - pillHalfH - outerSpread,
                            focalX + pillHalfW + outerSpread,
                            focalY + pillHalfH + outerSpread,
                            outerR,
                            outerR,
                            glowFillPaint
                        )

                        // Inner luminous mint-glass meniscus sitting flush against the slider edge
                        val innerSpread = (2.dp.toPx() + 9.dp.toPx() * gather)
                        val innerBlur = (12.dp.toPx() + 8.dp.toPx() * gather).coerceAtLeast(1f)
                        glowFillPaint.color = LightMintGlow
                            .copy(alpha = (0.34f * gatherIntensity).coerceIn(0f, 1f))
                            .toArgb()
                        glowFillPaint.maskFilter = BlurMaskFilter(innerBlur, BlurMaskFilter.Blur.NORMAL)
                        val innerR = pillHalfH + innerSpread
                        canvas.nativeCanvas.drawRoundRect(
                            focalX - pillHalfW - innerSpread,
                            focalY - pillHalfH - innerSpread,
                            focalX + pillHalfW + innerSpread,
                            focalY + pillHalfH + innerSpread,
                            innerR,
                            innerR,
                            glowFillPaint
                        )

                        // 3. Dual Inward-Converging Stadium Ripples (only while gathering in CONNECTING)
                        if (shock <= 0.004f) {
                            for (rippleIndex in 0..1) {
                                val phaseShift = rippleIndex * 0.42f
                                val rippleProgress = ((gather * 1.35f - phaseShift) % 1f).coerceIn(0f, 1f)
                                if (gather > phaseShift * 0.6f) {
                                    // Contracts inward from 76.dp offset down to 2.dp flush with the pill border
                                    val offsetDist = (76.dp.toPx() * (1f - rippleProgress)).coerceAtLeast(2.dp.toPx())
                                    val rippleEnvelope = sin(rippleProgress * PI).toFloat().coerceIn(0f, 1f)
                                    val rippleAlpha = (0.26f * rippleEnvelope * gatherIntensity).coerceIn(0f, 1f)
                                    if (rippleAlpha > 0.01f) {
                                        val strokeW = (14.dp.toPx() * (1f - 0.35f * rippleProgress)).coerceAtLeast(4.dp.toPx())
                                        val blurPx = (14.dp.toPx() * (1f - 0.25f * rippleProgress)).coerceAtLeast(4f)
                                        waveStrokePaint.color = (if (rippleIndex == 0) LightMintGlow else AccentActive)
                                            .copy(alpha = rippleAlpha)
                                            .toArgb()
                                        waveStrokePaint.strokeWidth = strokeW
                                        waveStrokePaint.maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                                        val rCorner = pillHalfH + offsetDist
                                        canvas.nativeCanvas.drawRoundRect(
                                            focalX - pillHalfW - offsetDist,
                                            focalY - pillHalfH - offsetDist,
                                            focalX + pillHalfW + offsetDist,
                                            focalY + pillHalfH + offsetDist,
                                            rCorner,
                                            rCorner,
                                            waveStrokePaint
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 4. Right-Cap Power Thumb Liquid Focal Bloom (charges at the right end of the slider)
                    val thumbFocalX = focalX + (pillHalfW - pillHalfH) * (0.35f + 0.65f * gather)
                    val thumbFocalCenter = Offset(thumbFocalX, focalY)
                    val thumbBloomRadius = 68.dp.toPx() * (0.65f + 0.45f * gather)
                    drawCircle(
                        brush = Brush.radialGradient(
                            0.0f to LightMintGlow.copy(alpha = 0.28f * gatherIntensity),
                            0.45f to AccentActive.copy(alpha = 0.20f * gatherIntensity),
                            1.0f to Color.Transparent,
                            center = thumbFocalCenter,
                            radius = thumbBloomRadius
                        ),
                        radius = thumbBloomRadius,
                        center = thumbFocalCenter
                    )
                }
            }

            // =========================================================================
            // STAGE 2: STADIUM-TO-HORIZON 3D LIQUID GLASS WAVE (CONNECTED)
            // Expands using true Euclidean Stadium Parallel-Offset geometry
            // `RoundRect(W + 2d, H + 2d, R + d)` so it starts as a 1:1 stadium contour
            // around the slider pill and naturally morphs into a giant 3D wave across the screen.
            // =========================================================================
            if (shock > 0.004f) {
                val maxDiagonal = hypot(size.width, size.height) * 1.02f
                val expandDist = maxDiagonal * shock
                val domeFade = ((1f - shock * 0.80f).coerceIn(0f, 1f)) * masterAlpha

                val waveHalfW = pillHalfW + expandDist
                val waveHalfH = pillHalfH + expandDist
                val waveCorner = pillHalfH + expandDist

                val baseBandWidth = (115.dp.toPx() * (1f - shock * 0.30f)).coerceAtLeast(42.dp.toPx())
                val midBandWidth = (64.dp.toPx() * (1f - shock * 0.28f)).coerceAtLeast(24.dp.toPx())
                val crestBandWidth = (26.dp.toPx() * (1f - shock * 0.25f)).coerceAtLeast(10.dp.toPx())

                drawIntoCanvas { canvas ->
                    // Layer A: Wide deep-emerald refractive wave body
                    waveStrokePaint.color = MidDarkElements
                        .copy(alpha = (0.34f * domeFade).coerceIn(0f, 1f))
                        .toArgb()
                    waveStrokePaint.strokeWidth = baseBandWidth
                    waveStrokePaint.maskFilter = BlurMaskFilter(
                        (baseBandWidth * 0.55f).coerceAtLeast(8f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        focalX - waveHalfW,
                        focalY - waveHalfH,
                        focalX + waveHalfW,
                        focalY + waveHalfH,
                        waveCorner,
                        waveCorner,
                        waveStrokePaint
                    )

                    // Layer B: Active emerald-teal optical lens core
                    waveStrokePaint.color = AccentActive
                        .copy(alpha = (0.38f * domeFade).coerceIn(0f, 1f))
                        .toArgb()
                    waveStrokePaint.strokeWidth = midBandWidth
                    waveStrokePaint.maskFilter = BlurMaskFilter(
                        (midBandWidth * 0.52f).coerceAtLeast(6f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        focalX - waveHalfW,
                        focalY - waveHalfH,
                        focalX + waveHalfW,
                        focalY + waveHalfH,
                        waveCorner,
                        waveCorner,
                        waveStrokePaint
                    )

                    // Layer C: Silky Liquid Glass mint specular crest
                    waveStrokePaint.color = LightMintGlow
                        .copy(alpha = (0.28f * domeFade).coerceIn(0f, 1f))
                        .toArgb()
                    waveStrokePaint.strokeWidth = crestBandWidth
                    waveStrokePaint.maskFilter = BlurMaskFilter(
                        (crestBandWidth * 0.60f).coerceAtLeast(4f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        focalX - waveHalfW,
                        focalY - waveHalfH,
                        focalX + waveHalfW,
                        focalY + waveHalfH,
                        waveCorner,
                        waveCorner,
                        waveStrokePaint
                    )

                    // Secondary trailing stadium wave for deep liquid resonance
                    val secondWave = ((shock - 0.13f) / 0.87f).coerceIn(0f, 1f)
                    if (secondWave > 0.01f) {
                        val secondDist = maxDiagonal * 0.74f * secondWave
                        val secondFade = ((1f - secondWave).coerceIn(0f, 1f)) * masterAlpha
                        val sHalfW = pillHalfW + secondDist
                        val sHalfH = pillHalfH + secondDist
                        val sCorner = pillHalfH + secondDist
                        val sBand = (58.dp.toPx() * (1f - secondWave * 0.35f)).coerceAtLeast(20.dp.toPx())

                        waveStrokePaint.color = AccentActive
                            .copy(alpha = (0.24f * secondFade).coerceIn(0f, 1f))
                            .toArgb()
                        waveStrokePaint.strokeWidth = sBand
                        waveStrokePaint.maskFilter = BlurMaskFilter(
                            (sBand * 0.58f).coerceAtLeast(6f),
                            BlurMaskFilter.Blur.NORMAL
                        )
                        canvas.nativeCanvas.drawRoundRect(
                            focalX - sHalfW,
                            focalY - sHalfH,
                            focalX + sHalfW,
                            focalY + sHalfH,
                            sCorner,
                            sCorner,
                            waveStrokePaint
                        )
                    }
                }
            }
        }
    }
}

/**
 * Status Header ("DISCONNECTED" -> "CONNECTING..." -> "CONNECTED") synchronized with
 * [AirDropChoreographyState].
 */
@Composable
fun AirDropMorphingStatusHeader(
    connectionState: VpnConnectionState,
    choreography: AirDropChoreographyState,
    modifier: Modifier = Modifier
) {
    val statusTextColor by animateColorAsState(
        targetValue = when (connectionState) {
            VpnConnectionState.CONNECTED -> LightMintGlow
            VpnConnectionState.CONNECTING -> LightMintGlow
            VpnConnectionState.DISCONNECTED -> MutedSageText
        },
        animationSpec = tween(380),
        label = "airdrop_status_text_color"
    )

    val statusLabel = when (connectionState) {
        VpnConnectionState.DISCONNECTED -> stringResource(R.string.status_disconnected)
        VpnConnectionState.CONNECTING -> stringResource(R.string.status_connecting)
        VpnConnectionState.CONNECTED -> stringResource(R.string.status_connected)
    }

    val blurDp = choreography.headerBlurDp.coerceAtLeast(0f)
    val gather = choreography.gatherTension.coerceIn(0f, 1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = statusLabel,
            maxLines = 1,
            softWrap = false,
            style = MaterialTheme.typography.labelLarge.copy(
                color = statusTextColor,
                letterSpacing = (4.2f + gather * 1.4f).sp,
                fontWeight = if (connectionState == VpnConnectionState.CONNECTED) {
                    FontWeight.SemiBold
                } else {
                    FontWeight.Medium
                }
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .graphicsLayer {
                    scaleX = choreography.headerScaleX
                    scaleY = choreography.headerScaleY
                    translationY = choreography.headerOffsetY
                }
                .then(
                    if (blurDp > 0.25f) {
                        Modifier.blur(
                            radius = blurDp.dp,
                            edgeTreatment = BlurredEdgeTreatment.Unbounded
                        )
                    } else {
                        Modifier
                    }
                )
                .drawBehind {
                    val glowAlpha = (gather * 0.28f).coerceIn(0f, 0.28f)
                    if (glowAlpha > 0.01f) {
                        val glowRadius = size.maxDimension * 0.85f
                        drawCircle(
                            brush = Brush.radialGradient(
                                0.0f to AccentActive.copy(alpha = glowAlpha),
                                0.55f to LightMintGlow.copy(alpha = glowAlpha * 0.5f),
                                1.0f to Color.Transparent,
                                center = Offset(size.width * 0.5f, size.height * 0.5f),
                                radius = glowRadius.coerceAtLeast(1f)
                            ),
                            radius = glowRadius,
                            center = Offset(size.width * 0.5f, size.height * 0.5f)
                        )
                    }
                }
                .testTag("vpn_status_text")
        )
    }
}
