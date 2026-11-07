package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import com.example.ui.icons.PtunnelIcons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentActive
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_CHART_CEILING_BYTES = 64f * 1024f
private const val CHART_SCALE_HOLD_MS = 20_000L

/**
 * Single point in a metric series.
 */
data class SeriesPoint(
    val label: String,
    val value: Float
)

/**
 * Named series of points.
 */
data class MetricSeries(
    val name: String,
    val data: List<SeriesPoint>,
    val color: Color? = null
)

/**
 * Chart visualization mode.
 */
enum class ChartView {
    CURVE,
    BARS
}

/**
 * Period selector option kept for API compatibility.
 */
data class PeriodOption(
    val value: String,
    val label: String
)

/**
 * Live VPN Traffic Sample stored per tick while the VPN is connected.
 */
data class VpnTrafficSample(
    val label: String,
    val downloadBytesPerSec: Long,
    val uploadBytesPerSec: Long,
    val totalSessionBytes: Long
)

/**
 * Default period options kept for API compatibility.
 */
val DefaultVpnTrafficPeriods = listOf(
    PeriodOption(value = "live_60s", label = "Live 60s")
)

/**
 * Formats throughput (Bytes/sec) compactly for VPN traffic analytics (KB/s, MB/s, GB/s).
 */
fun formatTrafficRateCompact(bytesPerSec: Float): String {
    val safe = bytesPerSec.coerceAtLeast(0f)
    val mbps = safe / (1024f * 1024f)
    val kbps = safe / 1024f
    return when {
        mbps >= 10f -> String.format(Locale.US, "%.1f MB/s", mbps)
        mbps >= 1f -> String.format(Locale.US, "%.2f MB/s", mbps)
        kbps >= 10f -> String.format(Locale.US, "%.0f KB/s", kbps)
        kbps >= 1f -> String.format(Locale.US, "%.1f KB/s", kbps)
        else -> String.format(Locale.US, "%.0f B/s", safe)
    }
}

/**
 * Formats total byte volume compactly (MB / GB).
 */
fun formatTrafficVolumeCompact(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val gb = safe.toDouble() / (1024.0 * 1024.0 * 1024.0)
    val mb = safe.toDouble() / (1024.0 * 1024.0)
    return when {
        gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
        mb >= 1.0 -> String.format(Locale.US, "%.1f MB", mb)
        else -> String.format(Locale.US, "%.0f KB", safe / 1024.0)
    }
}

/**
 * Borderless, edge-faded, full-width VPN Traffic chart component in Shield VPN's Emerald-Mint palette:
 * - No "LIVE TRAFFIC" title text
 * - No "DL / UL" colored dot legend
 * - No "Live 60s" period selector or dropdown menu
 * - No border and no boxed card container; softly fades around all edges into the screen background
 * - Chart line spans edge-to-edge from the left side to the right side of the phone screen
 */
@Composable
fun ProgressMetricCard(
    title: String = "",
    totalDisplay: String,
    deltaDisplay: String? = null,
    deltaLabel: String = "live throughput",
    trendOverride: Float? = null,
    series: List<MetricSeries>,
    periods: List<PeriodOption> = DefaultVpnTrafficPeriods,
    defaultPeriod: String? = null,
    onPeriodChange: ((String) -> Unit)? = null,
    defaultView: ChartView = ChartView.CURVE,
    valueFormatter: (Float) -> String = ::formatTrafficRateCompact,
    modifier: Modifier = Modifier
) {
    val primaryData = series.firstOrNull()?.data.orEmpty()

    var activeView by remember { mutableStateOf(defaultView) }
    var selectedIdx by remember { mutableStateOf<Int?>(null) }

    val resolvedColors = remember(series.size) {
        val palette = listOf(
            LightMintGlow,                // Primary series: #DAF1DE (Bright Mint)
            Color(0xFF52B788),            // Secondary series: #52B788 (Vibrant Emerald Sage)
            MutedSageText,                // Tertiary series: #8EB69B
            AccentActive                  // Quaternary series: #235347
        )
        series.mapIndexed { idx, s -> s.color ?: palette[idx % palette.size] }
    }

    // Derived statistics from primary series
    val stats = remember(primaryData, trendOverride) {
        if (primaryData.isEmpty()) {
            MetricDerivedStats(peak = 0f, low = 0f, avg = 0f, trend = trendOverride ?: 0f)
        } else {
            var peak = Float.NEGATIVE_INFINITY
            var low = Float.POSITIVE_INFINITY
            var sum = 0f
            for (p in primaryData) {
                if (p.value > peak) peak = p.value
                if (p.value < low) low = p.value
                sum += p.value
            }
            val avg = sum / primaryData.size
            val computedTrend = if (trendOverride != null) {
                trendOverride
            } else {
                val mid = primaryData.size / 2
                if (mid == 0) {
                    0f
                } else {
                    val firstAvg = primaryData.subList(0, mid).map { it.value }.average().toFloat()
                    val lastAvg = primaryData.subList(mid, primaryData.size).map { it.value }.average().toFloat()
                    if (firstAvg == 0f) 0f else ((lastAvg - firstAvg) / abs(firstAvg)) * 100f
                }
            }
            MetricDerivedStats(
                peak = if (peak.isFinite()) peak else 0f,
                low = if (low.isFinite()) low else 0f,
                avg = avg,
                trend = computedTrend
            )
        }
    }

    val safeSelectedIdx = selectedIdx?.takeIf { it in primaryData.indices }
    val selectedPoint = safeSelectedIdx?.let { primaryData[it] }

    val displayedTotal = if (selectedPoint != null) {
        valueFormatter(selectedPoint.value)
    } else {
        totalDisplay
    }

    val trendDirection = when {
        stats.trend > 0.5f -> 1
        stats.trend < -0.5f -> -1
        else -> 0
    }
    val trendSign = if (stats.trend > 0f) "+" else ""
    val trendFormatted = String.format(Locale.US, "%s%.1f%%", trendSign, stats.trend)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .testTag("vpn_traffic_metric_card")
    ) {
        // =========================================================================
        // LAYER 1: Subtle Feathered Dot-Grid Backdrop (Strictly confined to the
        // middle chart band with zero radial halo above or below the chart)
        // =========================================================================
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .testTag("metric_card_backdrop_canvas")
        ) {
            val w = size.width
            val h = size.height

            // 8dp x 8dp Dot-Grid strictly inside the vertical chart region (30%..76% height)
            // so zero glow or dots ever appear above the chart under the ON/OFF slider bar
            val spacingPx = 8.dp.toPx().coerceAtLeast(4f)
            val dotRadiusPx = 0.80.dp.toPx()
            val topBandY = h * 0.30f
            val bottomBandY = h * 0.76f
            val bandHeight = (bottomBandY - topBandY).coerceAtLeast(1f)

            var x = spacingPx * 0.5f
            while (x < w) {
                val normX = (x / w.coerceAtLeast(1f)).coerceIn(0f, 1f)
                val edgeFadeX = (min(normX, 1f - normX) / 0.22f).coerceIn(0f, 1f)
                if (edgeFadeX > 0.02f) {
                    var y = topBandY
                    while (y < bottomBandY) {
                        val normY = ((y - topBandY) / bandHeight).coerceIn(0f, 1f)
                        val edgeFadeY = (min(normY, 1f - normY) / 0.35f).coerceIn(0f, 1f)
                        val dotAlpha = 0.08f * edgeFadeX * edgeFadeY
                        if (dotAlpha > 0.01f) {
                            drawCircle(
                                color = LightMintGlow.copy(alpha = dotAlpha),
                                radius = dotRadiusPx,
                                center = Offset(x, y)
                            )
                        }
                        y += spacingPx
                    }
                }
                x += spacingPx
            }
        }

        // =========================================================================
        // LAYER 3 & LAYER 4: Crisp Header + Full-Bleed Edge-to-Edge Chart + Footer
        // =========================================================================
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            // Top Row: Headline Total + Trend Badge on the left, ViewToggle on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Headline Metric Total (updates cleanly every 500ms without crossfade flicker)
                    Text(
                        text = displayedTotal,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp,
                            color = LightMintGlow,
                            fontFeatureSettings = "tnum"
                        ),
                        modifier = Modifier.testTag("metric_card_total_value")
                    )

                    // Trend Icon Badge + Trend %
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(AccentActive.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            val trendIcon = when (trendDirection) {
                                1 -> PtunnelIcons.ArrowUpward
                                -1 -> PtunnelIcons.ArrowDownward
                                else -> Icons.AutoMirrored.Filled.ArrowForward
                            }
                            Icon(
                                imageVector = trendIcon,
                                contentDescription = null,
                                tint = LightMintGlow,
                                modifier = Modifier.size(11.dp)
                            )
                        }

                        Text(
                            text = trendFormatted,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.testTag("metric_card_trend_text")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Full-Width Chart spanning from the left edge to the right edge of the phone screen
            MetricChart(
                series = series,
                colors = resolvedColors,
                view = ChartView.CURVE,
                selectedIdx = safeSelectedIdx,
                onSelectIdx = { idx -> selectedIdx = idx },
                valueFormatter = valueFormatter,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(78.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Bottom Footer: Live Delta / Selected Point on left, Peak · Low · Avg on right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (selectedPoint != null && safeSelectedIdx != null) {
                        val secondaryVal = series.getOrNull(1)?.data?.getOrNull(safeSelectedIdx)?.value
                        Text(
                            text = selectedPoint.label,
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        Text(
                            text = if (secondaryVal != null) {
                                "↓ ${valueFormatter(selectedPoint.value)} · ↑ ${valueFormatter(secondaryVal)}"
                            } else {
                                valueFormatter(selectedPoint.value)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.5.sp,
                                color = MutedSageText
                            )
                        )
                    } else if (!deltaDisplay.isNullOrBlank()) {
                        Text(
                            text = deltaDisplay,
                            maxLines = 1,
                            softWrap = false,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = LightMintGlow,
                                fontFamily = FontFamily.Monospace
                            ),
                            modifier = Modifier.testTag("metric_card_delta_value")
                        )
                        Text(
                            text = deltaLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MutedSageText.copy(alpha = 0.85f)
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                val peakStr = valueFormatter(stats.peak)
                val lowStr = valueFormatter(stats.low)
                val avgStr = valueFormatter(stats.avg)
                Text(
                    text = "peak $peakStr · low $lowStr · avg $avgStr",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.5.sp,
                        color = MutedSageText.copy(alpha = 0.88f),
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.testTag("metric_card_footer_stats")
                )
            }
        }
    }
}

private data class MetricDerivedStats(
    val peak: Float,
    val low: Float,
    val avg: Float,
    val trend: Float
)

/**
 * Holds synchronous conveyor-belt snapshots and hysteresis-stabilized Y bounds so `MetricChart`
 * glides at 60/120fps across every 500ms sample update with zero boundary pops or vertical wobble.
 */
private class SmoothChartAnimationState(
    initialValues: List<List<Float>>,
    initialMin: Float,
    initialMax: Float
) {
    var lastTargetValues: List<List<Float>> = initialValues
    var startValues: List<List<Float>> = initialValues
    var targetValues: List<List<Float>> = initialValues

    var stabilizedMin: Float = initialMin
    var stabilizedMax: Float = max(initialMax, MIN_CHART_CEILING_BYTES)
    var currentMin: Float = initialMin
    var currentMax: Float = max(initialMax, MIN_CHART_CEILING_BYTES)
    var lastCeilingTouchMs: Long = System.currentTimeMillis()

    var generation: Int = 0
}

/**
 * Full-bleed edge-to-edge `MetricChart` where the curve and bars span all the way
 * from the left edge (`x = 0`) to the right edge (`x = width`) of the phone screen,
 * using a causal Midpoint Quadratic B-Spline for 100% $C^1$-continuous 60fps wave motion.
 */
@Composable
fun MetricChart(
    series: List<MetricSeries>,
    colors: List<Color>,
    view: ChartView,
    selectedIdx: Int?,
    onSelectIdx: (Int?) -> Unit,
    valueFormatter: (Float) -> String,
    modifier: Modifier = Modifier
) {
    val primaryData = series.firstOrNull()?.data.orEmpty()
    val pointCount = primaryData.size
    if (pointCount == 0) {
        Box(modifier = modifier)
        return
    }

    val (rawMinVal, rawMaxVal) = remember(series) {
        var minV = Float.POSITIVE_INFINITY
        var maxV = Float.NEGATIVE_INFINITY
        for (s in series) {
            for (p in s.data) {
                if (p.value < minV) minV = p.value
                if (p.value > maxV) maxV = p.value
            }
        }
        if (!minV.isFinite()) minV = 0f
        if (!maxV.isFinite()) maxV = 1f
        // Anchor baseline at 0f (or below if negative) and add 15% headroom at the top
        // so normal wave motion never triggers vertical rescaling jitter.
        val floorMin = min(0f, minV)
        val ceilingMax = if (maxV <= floorMin) floorMin + 1f else maxV * 1.15f
        floorMin to ceilingMax
    }

    val incomingValues: List<List<Float>> = remember(series) {
        series.map { s -> s.data.map { it.value } }
    }

    val animState = remember {
        SmoothChartAnimationState(
            initialValues = incomingValues,
            initialMin = rawMinVal,
            initialMax = rawMaxVal
        )
    }

    var progress by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }

    // Synchronously transition to the next conveyor-belt step when new 500ms data arrives.
    // Using `animState.targetValues` as the new `startValues` preserves the exact discrete
    // sample chain so `p = 1f` of tick T and `p = 0f` of tick T+1 match bit-for-bit.
    if (incomingValues != animState.lastTargetValues) {
        animState.startValues = animState.targetValues
        animState.targetValues = incomingValues
        animState.lastTargetValues = incomingValues

        animState.stabilizedMin = 0f
        val now = System.currentTimeMillis()
        val neededCeiling = max(rawMaxVal, MIN_CHART_CEILING_BYTES)
        if (neededCeiling >= animState.stabilizedMax * 0.75f) {
            // The line is still using most of the canvas: keep the scale anchored.
            animState.lastCeilingTouchMs = now
            if (neededCeiling > animState.stabilizedMax) {
                animState.stabilizedMax = neededCeiling
            }
        } else if (now - animState.lastCeilingTouchMs >= CHART_SCALE_HOLD_MS) {
            // Traffic has stayed quiet long enough; shrink the ceiling once and ease into it.
            animState.stabilizedMax = neededCeiling
            animState.lastCeilingTouchMs = now
        }

        animState.generation++
        progress = 0f
    }

    androidx.compose.runtime.LaunchedEffect(animState.generation) {
        if (progress >= 1f) return@LaunchedEffect
        val durationNanos = 490_000_000L // 490ms linear conveyor glide matching the 500ms telemetry tick
        var startNanos = -1L
        var lastFrameNanos = -1L
        while (progress < 1f) {
            androidx.compose.runtime.withFrameNanos { frameNanos ->
                if (startNanos < 0L) {
                    startNanos = frameNanos
                }
                if (lastFrameNanos < 0L) {
                    lastFrameNanos = frameNanos
                }
                val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastFrameNanos = frameNanos
                val elapsed = (frameNanos - startNanos).coerceAtLeast(0L)
                progress = (elapsed.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
                // Expand quickly when the line needs headroom, but drift downward slowly after a
                // quiet period so the graph never rescales on every small dip.
                val expanding = animState.stabilizedMax >= animState.currentMax
                val tauSeconds = if (expanding) 0.25f else 1.35f
                val easing = 1f - exp(-dt / tauSeconds)
                animState.currentMin += (0f - animState.currentMin) * easing
                animState.currentMax += (animState.stabilizedMax - animState.currentMax) * easing
            }
        }
    }

    val p = progress.coerceIn(0f, 1f)
    val animMinVal = animState.currentMin
    val animMaxVal = max(animMinVal + 1f, animState.currentMax)

    // Compute smoothly interpolated grid values for interactive selection & bars
    val interpolatedGridValues: List<List<Float>> = animState.targetValues.mapIndexed { sIdx, targetList ->
        val startList = animState.startValues.getOrNull(sIdx)
        if (startList == null || startList.size != targetList.size) {
            targetList
        } else {
            targetList.mapIndexed { i, targetV ->
                val startV = startList[i]
                startV + (targetV - startV) * p
            }
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "metric_chart_live_pulse")
    val livePulseScale by infiniteTransition.animateFloat(
        initialValue = 0.90f,
        targetValue = 1.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_endpoint_pulse_scale"
    )
    val livePulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.48f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "live_endpoint_pulse_alpha"
    )

    BoxWithConstraints(
        modifier = modifier
            .testTag("metric_chart_canvas_container")
            .pointerInput(pointCount) {
                detectTapGestures { tapOffset ->
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    val fraction = (tapOffset.x / w).coerceIn(0f, 1f)
                    val tappedIdx = (fraction * (pointCount - 1).coerceAtLeast(0))
                        .roundToInt()
                        .coerceIn(0, pointCount - 1)
                    if (selectedIdx == tappedIdx) {
                        onSelectIdx(null)
                    } else {
                        onSelectIdx(tappedIdx)
                    }
                }
            }
            .pointerInput(pointCount) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val fraction = (change.position.x / w).coerceIn(0f, 1f)
                        val draggedIdx = (fraction * (pointCount - 1).coerceAtLeast(0))
                            .roundToInt()
                            .coerceIn(0, pointCount - 1)
                        onSelectIdx(draggedIdx)
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val containerWidthPx = with(density) { maxWidth.toPx() }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Feather the left and right screen edges of the chart seamlessly into the background
                    drawRect(
                        brush = Brush.horizontalGradient(
                            0.0f to Color.Transparent,
                            0.05f to Color.Black,
                            0.95f to Color.Black,
                            1.0f to Color.Transparent
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val padTop = 8.dp.toPx()
            val padBottom = 4.dp.toPx()
            val innerW = w.coerceAtLeast(1f)
            val innerH = (h - padTop - padBottom).coerceAtLeast(1f)
            val range = (animMaxVal - animMinVal).coerceAtLeast(1f)

            // Subtle horizontal reference guide lines at 33% and 66% height
            val guideDash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()), 0f)
            for (frac in listOf(0.32f, 0.68f)) {
                val gy = padTop + innerH * frac
                drawLine(
                    color = LightMintGlow.copy(alpha = 0.06f),
                    start = Offset(0f, gy),
                    end = Offset(innerW, gy),
                    strokeWidth = 0.8.dp.toPx(),
                    pathEffect = guideDash
                )
            }

            // Build 15-point control polygon for the Causal Midpoint Quadratic B-Spline.
            // With `visibleSpan = max(1, count - 2)` and `P[j].x = (j - 0.5f - p) * stepX`,
            // the midpoints `M[k] = 0.5 * (P[k] + P[k+1])` sit at `M[k].x = (k - p) * stepX`.
            // At p=0, M[0] is at x=0 and M[count-2] is at x=innerW.
            // At p=1, M[1] is at x=0 and M[count-1] is at x=innerW.
            // This guarantees 100% C1 continuity and zero boundary pop when p wraps 1f -> 0f!
            val splineControlPointsPx: List<List<Offset>> = animState.targetValues.mapIndexed { sIdx, targetList ->
                val startList = animState.startValues.getOrNull(sIdx)
                val count = targetList.size
                if (count <= 2 || startList == null || startList.size != count) {
                    val fallbackStep = if (count <= 1) innerW else innerW / (count - 1).toFloat()
                    targetList.mapIndexed { i, v ->
                        val x = if (count <= 1) innerW * 0.5f else i.toFloat() * fallbackStep
                        val norm = ((v - animMinVal) / range).coerceIn(0f, 1f)
                        val y = padTop + (1f - norm) * innerH
                        Offset(x, y)
                    }
                } else {
                    val visibleSpan = (count - 2).toFloat()
                    val splineStepX = innerW / visibleSpan
                    val chainSize = count + 1
                    List(chainSize) { j ->
                        val valueAtJ = when (j) {
                            0 -> startList.first()
                            count -> targetList.last()
                            else -> {
                                val sVal = startList[j]
                                val tVal = targetList[j - 1]
                                if (abs(tVal - sVal) < 0.5f) tVal else sVal + (tVal - sVal) * p
                            }
                        }
                        val x = (j.toFloat() - 0.5f - p) * splineStepX
                        val norm = ((valueAtJ - animMinVal) / range).coerceIn(0f, 1f)
                        val y = padTop + (1f - norm) * innerH
                        Offset(x, y)
                    }
                }
            }

            val gridStepX = if (pointCount <= 1) innerW else innerW / (pointCount - 1).toFloat()
            val gridPointsPx: List<List<Offset>> = interpolatedGridValues.map { vals ->
                val count = vals.size
                vals.mapIndexed { i, v ->
                    val x = if (count <= 1) innerW * 0.5f else i.toFloat() * gridStepX
                    val norm = ((v - animMinVal) / range).coerceIn(0f, 1f)
                    val y = padTop + (1f - norm) * innerH
                    Offset(x, y)
                }
            }

            val primaryControlPts = splineControlPointsPx.firstOrNull().orEmpty()
            val primaryGridPts = gridPointsPx.firstOrNull().orEmpty()
            val primaryColor = colors.firstOrNull() ?: LightMintGlow

            if (view == ChartView.CURVE) {
                // 1. Soft vertical gradient area fills under each series (secondary first, primary on top)
                for (sIdx in splineControlPointsPx.indices.reversed()) {
                    val ctrlPts = splineControlPointsPx[sIdx]
                    if (ctrlPts.size < 2) continue
                    val seriesColor = colors.getOrElse(sIdx) { primaryColor }
                    val (linePath, firstMid, lastMid) = buildMidpointQuadraticSplinePath(ctrlPts)
                    val areaPath = Path().apply {
                        addPath(linePath)
                        lineTo(lastMid.x, h - padBottom)
                        lineTo(firstMid.x, h - padBottom)
                        close()
                    }
                    val topAlpha = if (sIdx == 0) 0.26f else 0.12f
                    val midAlpha = if (sIdx == 0) 0.09f else 0.03f
                    drawPath(
                        path = areaPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                seriesColor.copy(alpha = topAlpha),
                                seriesColor.copy(alpha = midAlpha),
                                Color.Transparent
                            ),
                            startY = padTop,
                            endY = h - padBottom
                        )
                    )
                }

                // 2. Draw all series smooth curves with soft neon-glass outer glow + crisp core stroke
                for (sIdx in splineControlPointsPx.indices.reversed()) {
                    val ctrlPts = splineControlPointsPx[sIdx]
                    if (ctrlPts.size < 2) continue
                    val color = colors.getOrElse(sIdx) { primaryColor }
                    val strokeW = if (sIdx == 0) 2.2.dp.toPx() else 1.5.dp.toPx()
                    val glowW = if (sIdx == 0) 5.5.dp.toPx() else 3.8.dp.toPx()
                    val strokeAlpha = if (sIdx == 0) 0.96f else 0.78f
                    val glowAlpha = if (sIdx == 0) 0.16f else 0.10f
                    val (curvePath, _, _) = buildMidpointQuadraticSplinePath(ctrlPts)

                    // Soft outer glow stroke
                    drawPath(
                        path = curvePath,
                        color = color.copy(alpha = glowAlpha),
                        style = Stroke(
                            width = glowW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    // Crisp core stroke
                    drawPath(
                        path = curvePath,
                        color = color.copy(alpha = strokeAlpha),
                        style = Stroke(
                            width = strokeW,
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                }

                // 3. Interactive selected point indicator OR analytical curve-riding live endpoints
                if (selectedIdx != null && selectedIdx in primaryGridPts.indices) {
                    val selX = primaryGridPts[selectedIdx].x.coerceIn(
                        8.dp.toPx(),
                        (innerW - 8.dp.toPx()).coerceAtLeast(8.dp.toPx())
                    )
                    drawLine(
                        color = LightMintGlow.copy(alpha = 0.30f),
                        start = Offset(selX, padTop),
                        end = Offset(selX, h - padBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                    splineControlPointsPx.forEachIndexed { sIdx, ctrlPts ->
                        val exactY = evaluateMidpointQuadraticSplineY(ctrlPts, selX)
                            ?: gridPointsPx.getOrNull(sIdx)?.getOrNull(selectedIdx)?.y
                            ?: return@forEachIndexed
                        val pt = Offset(selX, exactY)
                        val c = colors.getOrElse(sIdx) { primaryColor }
                        drawCircle(
                            color = c.copy(alpha = 0.28f),
                            radius = 6.dp.toPx(),
                            center = pt
                        )
                        drawCircle(
                            color = c,
                            radius = 3.2.dp.toPx(),
                            center = pt
                        )
                        drawCircle(
                            color = DeepForestBg,
                            radius = 1.4.dp.toPx(),
                            center = pt
                        )
                    }
                } else if (primaryControlPts.isNotEmpty()) {
                    // Place live riding dots near the right edge, evaluated analytically on the exact spline
                    val dotX = (innerW - 10.dp.toPx()).coerceAtLeast(0f)
                    for (sIdx in splineControlPointsPx.indices.reversed()) {
                        val ctrlPts = splineControlPointsPx[sIdx]
                        val dotY = evaluateMidpointQuadraticSplineY(ctrlPts, dotX) ?: continue
                        val dotCenter = Offset(dotX, dotY)
                        val c = colors.getOrElse(sIdx) { primaryColor }
                        if (sIdx == 0) {
                            drawCircle(
                                color = c.copy(alpha = livePulseAlpha),
                                radius = 5.dp.toPx() * livePulseScale,
                                center = dotCenter
                            )
                            drawCircle(
                                color = c,
                                radius = 3.dp.toPx(),
                                center = dotCenter
                            )
                            drawCircle(
                                color = DeepForestBg,
                                radius = 1.1.dp.toPx(),
                                center = dotCenter
                            )
                        } else {
                            drawCircle(
                                color = c.copy(alpha = 0.24f),
                                radius = 4.dp.toPx(),
                                center = dotCenter
                            )
                            drawCircle(
                                color = c,
                                radius = 2.2.dp.toPx(),
                                center = dotCenter
                            )
                        }
                    }
                }
            } else {
                // =========================================================================
                // BARS VIEW (`ChartView.BARS`) spanning full screen width with smooth 60fps heights
                // =========================================================================
                val slotWidth = innerW / pointCount.coerceAtLeast(1)
                val groupWidth = slotWidth * 0.68f
                val seriesCount = series.size.coerceAtLeast(1)
                val barGap = if (seriesCount > 1) 1.5.dp.toPx() else 0f
                val singleBarWidth = max(
                    2.dp.toPx(),
                    (groupWidth - barGap * (seriesCount - 1)) / seriesCount
                )

                if (selectedIdx != null && selectedIdx in 0 until pointCount) {
                    val centerX = slotWidth * selectedIdx + slotWidth * 0.5f
                    drawLine(
                        color = LightMintGlow.copy(alpha = 0.25f),
                        start = Offset(centerX, padTop),
                        end = Offset(centerX, h - padBottom),
                        strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    )
                }

                for (i in 0 until pointCount) {
                    val groupStartX = slotWidth * i + (slotWidth - groupWidth) * 0.5f
                    val isSelected = selectedIdx == i
                    val dimmed = selectedIdx != null && !isSelected

                    interpolatedGridValues.forEachIndexed { sIdx, vals ->
                        val interpVal = vals.getOrNull(i) ?: return@forEachIndexed
                        val norm = ((interpVal - animMinVal) / range).coerceIn(0f, 1f)
                        val barH = max(3.dp.toPx(), norm * innerH)
                        val barX = groupStartX + sIdx * (singleBarWidth + barGap)
                        val barY = padTop + innerH - barH
                        val c = colors.getOrElse(sIdx) { primaryColor }
                        val alphaMult = if (dimmed) 0.35f else 1f

                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    c.copy(alpha = 0.95f * alphaMult),
                                    c.copy(alpha = 0.38f * alphaMult)
                                ),
                                startY = barY,
                                endY = barY + barH
                            ),
                            topLeft = Offset(barX, barY),
                            size = Size(singleBarWidth, barH),
                            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                        )
                    }
                }
            }
        }

        // Floating Tooltip when a data point is selected
        if (selectedIdx != null && selectedIdx in primaryData.indices) {
            val ptData = primaryData[selectedIdx]
            val secondaryPtData = series.getOrNull(1)?.data?.getOrNull(selectedIdx)
            val fraction = if (pointCount <= 1) 0.5f else selectedIdx.toFloat() / (pointCount - 1).toFloat()
            val tooltipWidthPx = with(density) { 136.dp.toPx() }
            val edgeMarginPx = with(density) { 16.dp.toPx() }
            val rawLeftPx = fraction * containerWidthPx - tooltipWidthPx * 0.5f
            val clampedLeftPx = rawLeftPx.coerceIn(
                edgeMarginPx,
                (containerWidthPx - tooltipWidthPx - edgeMarginPx).coerceAtLeast(edgeMarginPx)
            )

            Box(
                modifier = Modifier
                    .offset { IntOffset(clampedLeftPx.roundToInt(), 0) }
                    .widthIn(min = 96.dp, max = 168.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DeepForestBg.copy(alpha = 0.92f))
                    .padding(horizontal = 7.dp, vertical = 2.5.dp)
                    .testTag("metric_chart_tooltip"),
                contentAlignment = Alignment.Center
            ) {
                val tooltipText = if (secondaryPtData != null) {
                    "${ptData.label} · ↓${valueFormatter(ptData.value)} ↑${valueFormatter(secondaryPtData.value)}"
                } else {
                    "${ptData.label} · ${valueFormatter(ptData.value)}"
                }
                Text(
                    text = tooltipText,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = LightMintGlow,
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
    }
}

private data class MidpointSplineResult(
    val path: Path,
    val firstMidpoint: Offset,
    val lastMidpoint: Offset
)

/**
 * Builds a $C^1$-continuous Midpoint Quadratic B-Spline through the midpoints of [ctrlPts].
 * Unlike Catmull-Rom, this spline never overshoots and depends strictly locally on
 * `(P_{j-1}, P_j, P_{j+1})`, guaranteeing zero boundary tangent pops as points stream in.
 */
private fun buildMidpointQuadraticSplinePath(
    ctrlPts: List<Offset>
): MidpointSplineResult {
    val path = Path()
    if (ctrlPts.isEmpty()) {
        return MidpointSplineResult(path, Offset.Zero, Offset.Zero)
    }
    if (ctrlPts.size == 1) {
        path.moveTo(ctrlPts[0].x, ctrlPts[0].y)
        return MidpointSplineResult(path, ctrlPts[0], ctrlPts[0])
    }
    if (ctrlPts.size == 2) {
        path.moveTo(ctrlPts[0].x, ctrlPts[0].y)
        path.lineTo(ctrlPts[1].x, ctrlPts[1].y)
        return MidpointSplineResult(path, ctrlPts[0], ctrlPts[1])
    }

    val firstMid = Offset(
        x = (ctrlPts[0].x + ctrlPts[1].x) * 0.5f,
        y = (ctrlPts[0].y + ctrlPts[1].y) * 0.5f
    )
    path.moveTo(firstMid.x, firstMid.y)

    var lastMid = firstMid
    for (j in 1 until ctrlPts.size - 1) {
        val pJ = ctrlPts[j]
        val pNext = ctrlPts[j + 1]
        val mid = Offset(
            x = (pJ.x + pNext.x) * 0.5f,
            y = (pJ.y + pNext.y) * 0.5f
        )
        path.quadraticTo(pJ.x, pJ.y, mid.x, mid.y)
        lastMid = mid
    }
    return MidpointSplineResult(path, firstMid, lastMid)
}

/**
 * Analytically evaluates the exact Y coordinate on the Midpoint Quadratic B-Spline at [queryX].
 * Because uniformly spaced control points satisfy `P_j.x = 0.5 * (M_{j-1}.x + M_j.x)`,
 * `x(t)` is strictly linear in `t`, so `y(t)` is exact in closed form.
 */
private fun evaluateMidpointQuadraticSplineY(
    ctrlPts: List<Offset>,
    queryX: Float
): Float? {
    if (ctrlPts.isEmpty()) return null
    if (ctrlPts.size == 1) return ctrlPts[0].y
    if (ctrlPts.size == 2) {
        val dx = (ctrlPts[1].x - ctrlPts[0].x)
        val t = if (abs(dx) < 1e-4f) 0f else ((queryX - ctrlPts[0].x) / dx).coerceIn(0f, 1f)
        return ctrlPts[0].y + (ctrlPts[1].y - ctrlPts[0].y) * t
    }

    val midCount = ctrlPts.size - 1
    for (j in 1 until midCount) {
        val mPrevX = (ctrlPts[j - 1].x + ctrlPts[j].x) * 0.5f
        val mPrevY = (ctrlPts[j - 1].y + ctrlPts[j].y) * 0.5f
        val mCurrX = (ctrlPts[j].x + ctrlPts[j + 1].x) * 0.5f
        val mCurrY = (ctrlPts[j].y + ctrlPts[j + 1].y) * 0.5f

        if (queryX <= mCurrX || j == midCount - 1) {
            val spanX = (mCurrX - mPrevX).coerceAtLeast(1e-4f)
            val t = ((queryX - mPrevX) / spanX).coerceIn(0f, 1f)
            val oneMinusT = 1f - t
            val pY = ctrlPts[j].y
            return oneMinusT * oneMinusT * mPrevY + 2f * oneMinusT * t * pY + t * t * mCurrY
        }
    }
    return ctrlPts.last().y
}

/**
 * Port of `ViewToggle` switching between Smooth Curve and Bar Chart views.
 */
@Composable
fun ViewToggle(
    view: ChartView,
    onChange: (ChartView) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(7.dp))
            .background(DeepForestBg.copy(alpha = 0.65f))
            .padding(2.dp)
            .testTag("metric_view_toggle"),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 1. Curve View Button
        val curveSelected = view == ChartView.CURVE
        val curveBg by animateColorAsState(
            targetValue = if (curveSelected) AccentActive.copy(alpha = 0.85f) else Color.Transparent,
            animationSpec = tween(180),
            label = "curve_toggle_bg"
        )
        val curveTint by animateColorAsState(
            targetValue = if (curveSelected) LightMintGlow else MutedSageText.copy(alpha = 0.75f),
            animationSpec = tween(180),
            label = "curve_toggle_tint"
        )

        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(curveBg)
                .clickable { onChange(ChartView.CURVE) }
                .testTag("metric_view_toggle_curve"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(11.dp)) {
                val w = size.width
                val h = size.height
                val p = Path().apply {
                    moveTo(w * 0.08f, h * 0.78f)
                    cubicTo(
                        w * 0.28f, h * 0.30f,
                        w * 0.48f, h * 0.30f,
                        w * 0.62f, h * 0.56f
                    )
                    cubicTo(
                        w * 0.76f, h * 0.80f,
                        w * 0.86f, h * 0.40f,
                        w * 0.94f, h * 0.22f
                    )
                }
                drawPath(
                    path = p,
                    color = curveTint,
                    style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        // 2. Bars View Button
        val barsSelected = view == ChartView.BARS
        val barsBg by animateColorAsState(
            targetValue = if (barsSelected) AccentActive.copy(alpha = 0.85f) else Color.Transparent,
            animationSpec = tween(180),
            label = "bars_toggle_bg"
        )
        val barsTint by animateColorAsState(
            targetValue = if (barsSelected) LightMintGlow else MutedSageText.copy(alpha = 0.75f),
            animationSpec = tween(180),
            label = "bars_toggle_tint"
        )

        Box(
            modifier = Modifier
                .size(width = 22.dp, height = 18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(barsBg)
                .clickable { onChange(ChartView.BARS) }
                .testTag("metric_view_toggle_bars"),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(11.dp)) {
                val w = size.width
                val h = size.height
                val barW = w * 0.20f
                val corner = CornerRadius(1.dp.toPx(), 1.dp.toPx())
                drawRoundRect(
                    color = barsTint,
                    topLeft = Offset(w * 0.10f, h * 0.52f),
                    size = Size(barW, h * 0.40f),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = barsTint,
                    topLeft = Offset(w * 0.40f, h * 0.18f),
                    size = Size(barW, h * 0.74f),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = barsTint,
                    topLeft = Offset(w * 0.70f, h * 0.36f),
                    size = Size(barW, h * 0.56f),
                    cornerRadius = corner
                )
            }
        }
    }
}
