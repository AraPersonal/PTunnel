package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MutedSageText

private val UploadCurveColor = Color(0xFF52B788)
private const val CHART_POINT_COUNT = 14
private val IdleSample = VpnTrafficSample("", 0L, 0L, 0L)

/**
 * Live throughput number sitting on the traffic curve at the left edge. The curve has no card,
 * frame or backdrop of its own: it dissolves into the screen background underneath the number.
 */
@Composable
fun LiveTrafficPanel(
    samples: List<VpnTrafficSample>,
    liveThroughputBytesPerSec: Long,
    showSpeed: Boolean = true,
    modifier: Modifier = Modifier
) {
    // Always feed the chart a full window so the curve is on screen the instant the tunnel
    // comes up instead of appearing only after the first telemetry ticks land.
    val chartSeries = remember(samples) {
        val padding = (CHART_POINT_COUNT - samples.size).coerceAtLeast(0)
        val points = List(padding) { IdleSample } + samples
        listOf(
            MetricSeries(
                name = "DL",
                data = points.map { SeriesPoint(it.label, it.downloadBytesPerSec.toFloat()) }
            ),
            MetricSeries(
                name = "UL",
                data = points.map { SeriesPoint(it.label, it.uploadBytesPerSec.toFloat()) }
            )
        )
    }

    val speedText = remember(liveThroughputBytesPerSec) {
        if (liveThroughputBytesPerSec <= 0L) "0 KB/s"
        else formatTrafficRateCompact(liveThroughputBytesPerSec.toFloat())
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .testTag("live_traffic_panel"),
        contentAlignment = Alignment.BottomCenter
    ) {
        MetricChart(
            series = chartSeries,
            colors = listOf(LightMintGlow, UploadCurveColor),
            view = ChartView.CURVE,
            selectedIdx = null,
            onSelectIdx = {},
            valueFormatter = ::formatTrafficRateCompact,
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    // Dissolve the top of the curve so the speed number stays legible without
                    // needing any panel behind it. Never fully transparent: the line must stay
                    // visible the moment the tunnel comes up.
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.Black.copy(alpha = 0.14f),
                            0.20f to Color.Black.copy(alpha = 0.42f),
                            0.40f to Color.Black.copy(alpha = 0.88f),
                            0.56f to Color.Black,
                            1.00f to Color.Black
                        ),
                        blendMode = BlendMode.DstIn
                    )
                }
        )

        if (showSpeed) {
            Text(
                text = speedText,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.6).sp,
                    color = LightMintGlow,
                    fontFeatureSettings = "tnum"
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 26.dp)
                    .testTag("live_speed_headline")
            )
        } else {
            Text(
                text = "LIVE TRAFFIC",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 9.sp,
                    letterSpacing = 2.1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MutedSageText
                ),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 28.dp, top = 6.dp)
            )
        }
    }
}
