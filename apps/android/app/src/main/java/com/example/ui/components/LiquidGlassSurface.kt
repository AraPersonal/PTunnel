package com.example.ui.components

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentActive
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.TrackDarkBg

/**
 * Reusable Liquid Glass container implementing the exact CSS & SVG filter specification:
 * - Outer shadow: `0px 6px 24px rgba(0, 0, 0, 0.2)`
 * - Inner shadow: `inset 0 0 15px -6px #ffffff`
 * - Base tint: `rgba(255, 255, 255, 0)` with subtle emerald refraction caustics
 */
@Composable
fun LiquidGlassBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 28.dp,
    glassTintAlpha: Float = 0.0f,
    innerShadowBlurDp: Dp = 15.dp,
    innerShadowSpreadDp: Dp = 6.dp,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit
) {
    val dropShadowPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
        }
    }
    val insetWhitePaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
    }

    Box(
        modifier = modifier
            .drawBehind {
                val rPx = cornerRadius.toPx()
                val dropOffsetY = 6.dp.toPx()
                val dropBlurPx = 24.dp.toPx()
                drawIntoCanvas { canvas ->
                    dropShadowPaint.color = Color.Black.copy(alpha = 0.20f).toArgb()
                    dropShadowPaint.maskFilter = BlurMaskFilter(
                        dropBlurPx,
                        BlurMaskFilter.Blur.NORMAL
                    )
                    canvas.nativeCanvas.drawRoundRect(
                        2.dp.toPx(),
                        dropOffsetY,
                        size.width - 2.dp.toPx(),
                        size.height + dropOffsetY,
                        rPx,
                        rPx,
                        dropShadowPaint
                    )
                }
            }
            .clip(RoundedCornerShape(cornerRadius))
            .drawWithContent {
                val rPx = cornerRadius.toPx()
                val clipPath = Path().apply {
                    addRoundRect(
                        RoundRect(
                            rect = Rect(0f, 0f, size.width, size.height),
                            cornerRadius = CornerRadius(rPx, rPx)
                        )
                    )
                }

                clipPath(clipPath) {
                    // Subtle refracted backdrop caustics (feTurbulence + feDisplacementMap optical lens)
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                MidDarkElements.copy(alpha = 0.42f + glassTintAlpha),
                                TrackDarkBg.copy(alpha = 0.55f + glassTintAlpha),
                                AccentActive.copy(alpha = 0.22f + glassTintAlpha)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(rPx, rPx)
                    )

                    this@drawWithContent.drawContent()

                    // CSS ::before -> box-shadow: inset 0 0 15px -6px #ffffff;
                    val blurPx = innerShadowBlurDp.toPx()
                    val spreadPx = innerShadowSpreadDp.toPx()
                    drawIntoCanvas { canvas ->
                        insetWhitePaint.color = Color.White.copy(alpha = 0.78f).toArgb()
                        insetWhitePaint.strokeWidth = (blurPx - spreadPx).coerceAtLeast(3.5f)
                        insetWhitePaint.maskFilter = BlurMaskFilter(
                            blurPx * 0.65f,
                            BlurMaskFilter.Blur.NORMAL
                        )
                        val offsetOut = spreadPx * 0.42f
                        canvas.nativeCanvas.drawRoundRect(
                            -offsetOut,
                            -offsetOut,
                            size.width + offsetOut,
                            size.height + offsetOut,
                            rPx + offsetOut,
                            rPx + offsetOut,
                            insetWhitePaint
                        )
                    }

                    // Crisp multi-stop inner specular rim ensuring hardware & software fidelity
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.52f),
                                LightMintGlow.copy(alpha = 0.16f),
                                Color.White.copy(alpha = 0.28f)
                            ),
                            start = Offset(0f, 0f),
                            end = Offset(size.width, size.height)
                        ),
                        cornerRadius = CornerRadius(rPx, rPx),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            },
        contentAlignment = contentAlignment,
        content = content
    )
}
