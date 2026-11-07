package com.example.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Small local icon set replacing material-icons-extended (an ~87MB dependency).
 */
object PtunnelIcons {

    val Shield: ImageVector = ImageVector.Builder(
        name = "Shield",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 1.5f)
            lineTo(3f, 5f)
            lineTo(3f, 11f)
            curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
            curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f)
            lineTo(21f, 5f)
            close()
        }
    }.build()

    val Visibility: ImageVector = ImageVector.Builder(
        name = "Visibility",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(2f, 12f)
            curveTo(5f, 6.5f, 8.5f, 4.5f, 12f, 4.5f)
            curveTo(15.5f, 4.5f, 19f, 6.5f, 22f, 12f)
            curveTo(19f, 17.5f, 15.5f, 19.5f, 12f, 19.5f)
            curveTo(8.5f, 19.5f, 5f, 17.5f, 2f, 12f)
            close()
        }
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 9f)
            curveTo(13.66f, 9f, 15f, 10.34f, 15f, 12f)
            curveTo(15f, 13.66f, 13.66f, 15f, 12f, 15f)
            curveTo(10.34f, 15f, 9f, 13.66f, 9f, 12f)
            curveTo(9f, 10.34f, 10.34f, 9f, 12f, 9f)
            close()
        }
    }.build()

    val VisibilityOff: ImageVector = ImageVector.Builder(
        name = "VisibilityOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(2f, 12f)
            curveTo(5f, 6.5f, 8.5f, 4.5f, 12f, 4.5f)
            curveTo(15.5f, 4.5f, 19f, 6.5f, 22f, 12f)
            curveTo(19f, 17.5f, 15.5f, 19.5f, 12f, 19.5f)
            curveTo(8.5f, 19.5f, 5f, 17.5f, 2f, 12f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(3f, 3f)
            lineTo(21f, 21f)
        }
    }.build()

    val Power: ImageVector = ImageVector.Builder(
        name = "Power",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(12f, 4f)
            curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
            curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
            curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
            curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
            close()
        }
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f) {
            moveTo(12f, 2f)
            lineTo(12f, 11f)
        }
    }.build()

    val ArrowUpward: ImageVector = ImageVector.Builder(
        name = "ArrowUpward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 4f)
            lineTo(18f, 11f)
            lineTo(14f, 11f)
            lineTo(14f, 20f)
            lineTo(10f, 20f)
            lineTo(10f, 11f)
            lineTo(6f, 11f)
            close()
        }
    }.build()

    val ArrowDownward: ImageVector = ImageVector.Builder(
        name = "ArrowDownward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 20f)
            lineTo(6f, 13f)
            lineTo(10f, 13f)
            lineTo(10f, 4f)
            lineTo(14f, 4f)
            lineTo(14f, 13f)
            lineTo(18f, 13f)
            close()
        }
    }.build()

    val ChevronUp: ImageVector = ImageVector.Builder(
        name = "ChevronUp",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(5.5f, 14.5f)
            lineTo(12f, 8f)
            lineTo(18.5f, 14.5f)
        }
    }.build()

    val Settings: ImageVector = ImageVector.Builder(
        name = "Settings",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(4f, 7f); lineTo(20f, 7f)
            moveTo(4f, 12f); lineTo(20f, 12f)
            moveTo(4f, 17f); lineTo(20f, 17f)
            moveTo(9f, 5f); lineTo(9f, 9f)
            moveTo(15f, 10f); lineTo(15f, 14f)
            moveTo(7.5f, 15f); lineTo(7.5f, 19f)
        }
    }.build()

    val User: ImageVector = ImageVector.Builder(
        name = "User",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.6f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(15.3f, 8f)
            curveTo(15.3f, 9.82f, 13.82f, 11.3f, 12f, 11.3f)
            curveTo(10.18f, 11.3f, 8.7f, 9.82f, 8.7f, 8f)
            curveTo(8.7f, 6.18f, 10.18f, 4.7f, 12f, 4.7f)
            curveTo(13.82f, 4.7f, 15.3f, 6.18f, 15.3f, 8f)
            close()
            moveTo(4.8f, 20f)
            curveTo(5.5f, 16.8f, 8.3f, 14.7f, 12f, 14.7f)
            curveTo(15.7f, 14.7f, 18.5f, 16.8f, 19.2f, 20f)
        }
    }.build()

    val Reality: ImageVector = ImageVector.Builder(
        name = "Reality",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(4f, 12f)
            curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
            curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
            moveTo(7f, 12f)
            curveTo(7f, 9.24f, 9.24f, 7f, 12f, 7f)
            curveTo(14.76f, 7f, 17f, 9.24f, 17f, 12f)
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(13.4f, 12f)
            curveTo(13.4f, 12.77f, 12.77f, 13.4f, 12f, 13.4f)
            curveTo(11.23f, 13.4f, 10.6f, 12.77f, 10.6f, 12f)
            curveTo(10.6f, 11.23f, 11.23f, 10.6f, 12f, 10.6f)
            curveTo(12.77f, 10.6f, 13.4f, 11.23f, 13.4f, 12f)
            close()
        }
    }.build()

    val VMess: ImageVector = ImageVector.Builder(
        name = "VMess",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 3f)
            lineTo(20f, 7.5f)
            lineTo(20f, 16.5f)
            lineTo(12f, 21f)
            lineTo(4f, 16.5f)
            lineTo(4f, 7.5f)
            close()
            moveTo(4.3f, 7.6f)
            lineTo(12f, 11.8f)
            lineTo(19.7f, 7.6f)
            moveTo(12f, 11.8f)
            lineTo(12f, 21f)
        }
    }.build()

    val Trojan: ImageVector = ImageVector.Builder(
        name = "Trojan",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(12f, 2.8f)
            lineTo(19.5f, 6f)
            lineTo(19.5f, 11.2f)
            curveTo(19.5f, 15.9f, 16.4f, 19.4f, 12f, 21.1f)
            curveTo(7.6f, 19.4f, 4.5f, 15.9f, 4.5f, 11.2f)
            lineTo(4.5f, 6f)
            close()
            moveTo(14f, 11f)
            curveTo(14f, 12.1f, 13.1f, 13f, 12f, 13f)
            curveTo(10.9f, 13f, 10f, 12.1f, 10f, 11f)
            curveTo(10f, 9.9f, 10.9f, 9f, 12f, 9f)
            curveTo(13.1f, 9f, 14f, 9.9f, 14f, 11f)
            close()
            moveTo(12f, 13f)
            lineTo(12f, 16f)
        }
    }.build()

    val Shadowsocks: ImageVector = ImageVector.Builder(
        name = "Shadowsocks",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(7.5f, 10f)
            lineTo(7.5f, 7.6f)
            curveTo(7.5f, 5.6f, 9.5f, 4f, 12f, 4f)
            curveTo(14.5f, 4f, 16.5f, 5.6f, 16.5f, 7.6f)
            lineTo(16.5f, 10f)
            moveTo(6f, 10f)
            lineTo(18f, 10f)
            lineTo(18f, 19f)
            lineTo(6f, 19f)
            close()
            moveTo(13.2f, 14.4f)
            curveTo(13.2f, 15.06f, 12.66f, 15.6f, 12f, 15.6f)
            curveTo(11.34f, 15.6f, 10.8f, 15.06f, 10.8f, 14.4f)
            curveTo(10.8f, 13.74f, 11.34f, 13.2f, 12f, 13.2f)
            curveTo(12.66f, 13.2f, 13.2f, 13.74f, 13.2f, 14.4f)
            close()
            moveTo(12f, 15.6f)
            lineTo(12f, 17.6f)
        }
    }.build()

    val Globe: ImageVector = ImageVector.Builder(
        name = "Globe",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(20f, 12f)
            curveTo(20f, 16.42f, 16.42f, 20f, 12f, 20f)
            curveTo(7.58f, 20f, 4f, 16.42f, 4f, 12f)
            curveTo(4f, 7.58f, 7.58f, 4f, 12f, 4f)
            curveTo(16.42f, 4f, 20f, 7.58f, 20f, 12f)
            close()
            moveTo(4f, 12f)
            lineTo(20f, 12f)
            moveTo(12f, 4f)
            curveTo(14.2f, 6.2f, 15.4f, 9f, 15.4f, 12f)
            curveTo(15.4f, 15f, 14.2f, 17.8f, 12f, 20f)
            curveTo(9.8f, 17.8f, 8.6f, 15f, 8.6f, 12f)
            curveTo(8.6f, 9f, 9.8f, 6.2f, 12f, 4f)
            close()
        }
    }.build()
}
