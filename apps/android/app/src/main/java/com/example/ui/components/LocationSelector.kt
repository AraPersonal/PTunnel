package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.example.model.LocationMode
import com.example.model.VpnLocation
import com.example.ui.icons.PtunnelIcons
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.LightMintGlow
import com.example.ui.theme.MidDarkElements
import com.example.ui.theme.MutedSageText
import com.example.ui.theme.TrackDarkBg
import kotlin.math.roundToInt

private val LocationBoxShape = RoundedCornerShape(16.dp)
private val LocationPanelShape = RoundedCornerShape(18.dp)
private val LocationBoxSurface = Brush.verticalGradient(
    colors = listOf(Color(0xFF0F342C), TrackDarkBg)
)
private val LocationBoxBorder = LightMintGlow.copy(alpha = 0.14f)
private val LocationBoxHeight = 46.dp
private val LocationRowHeight = 50.dp

private data class LocationSummary(val descriptor: String, val flag: String)

private fun summarizeLocation(
    mode: LocationMode,
    locations: List<VpnLocation>,
    selectedLocationId: String?,
    isLoading: Boolean,
    isConnected: Boolean
): LocationSummary {
    val selected = locations.firstOrNull { it.id == selectedLocationId } ?: locations.firstOrNull()
    val country = selected?.country?.takeIf { it.isNotBlank() }
    val descriptor = when {
        isLoading -> "Checking…"
        country == null -> "Best server"
        isConnected -> "Connected to $country"
        mode == LocationMode.MANUAL -> country
        else -> "Best server"
    }
    return LocationSummary(descriptor, selected?.flag.orEmpty())
}

/**
 * Closed location box: "Locations • Best server" on the left, the country flag on the right.
 * Reports its bounds so [LocationBoxPanel] can grow out of exactly this rectangle.
 */
@Composable
fun LocationBox(
    mode: LocationMode,
    locations: List<VpnLocation>,
    selectedLocationId: String?,
    isLoading: Boolean,
    isConnected: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onBoundsInRoot: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val summary = remember(mode, locations, selectedLocationId, isLoading, isConnected) {
        summarizeLocation(mode, locations, selectedLocationId, isLoading, isConnected)
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(280, easing = FastOutSlowInEasing),
        label = "location_box_chevron"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp)
            .height(LocationBoxHeight)
            .clip(LocationBoxShape)
            .background(LocationBoxSurface)
            .border(1.dp, LocationBoxBorder, LocationBoxShape)
            .clickable(onClick = onToggle)
            .onGloballyPositioned { coords ->
                onBoundsInRoot(
                    Rect(
                        coords.positionInRoot(),
                        Size(coords.size.width.toFloat(), coords.size.height.toFloat())
                    )
                )
            }
            .padding(horizontal = 14.dp)
            .testTag("location_box"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LocationHeaderContent(
            descriptor = summary.descriptor,
            flag = summary.flag,
            highlighted = isConnected,
            chevronRotation = chevronRotation
        )
    }
}

/**
 * The same box grown open: it keeps its own rectangle and expands downward over the chart and
 * the connect control. When the list does not fit below, the box also rises — never past the
 * vertical middle of the screen — and anything still too tall scrolls inside the box.
 */
@Composable
fun LocationBoxPanel(
    open: Boolean,
    anchorRectInRoot: Rect,
    rootHeightPx: Float,
    mode: LocationMode,
    locations: List<VpnLocation>,
    selectedLocationId: String?,
    isLoading: Boolean,
    isConnected: Boolean,
    onUseAuto: () -> Unit,
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    if (anchorRectInRoot.width <= 0f || rootHeightPx <= 0f) return

    AnimatedVisibility(
        visible = open,
        enter = fadeIn(tween(140)),
        exit = fadeOut(tween(200)),
        modifier = Modifier.fillMaxSize()
    ) {
        val grow by transition.animateFloat(
            transitionSpec = { tween(durationMillis = 300, easing = FastOutSlowInEasing) },
            label = "location_panel_grow"
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
        val summary = remember(mode, locations, selectedLocationId, isLoading, isConnected) {
            summarizeLocation(mode, locations, selectedLocationId, isLoading, isConnected)
        }

        val density = LocalDensity.current
        val headerPx = with(density) { LocationBoxHeight.toPx() }
        val rowPx = with(density) { LocationRowHeight.toPx() }
        val dividerPx = with(density) { 1.dp.toPx() }
        val listPaddingPx = with(density) { 16.dp.toPx() }
        val bottomMarginPx = with(density) { 14.dp.toPx() }

        val rowCount = 1 + locations.size + if (locations.isEmpty()) 1 else 0
        val desiredHeight = headerPx + dividerPx + listPaddingPx + rowCount * rowPx
        val spaceBelow = (rootHeightPx - bottomMarginPx - anchorRectInRoot.top)
            .coerceAtLeast(headerPx)
        val riseBudget = (anchorRectInRoot.top - rootHeightPx * 0.5f).coerceAtLeast(0f)

        val targetHeight: Float
        val targetTop: Float
        if (desiredHeight <= spaceBelow) {
            targetHeight = desiredHeight
            targetTop = anchorRectInRoot.top
        } else {
            targetHeight = desiredHeight.coerceAtMost(spaceBelow + riseBudget)
            targetTop = anchorRectInRoot.top + spaceBelow - targetHeight
        }

        val animatedHeight = lerp(headerPx, targetHeight, grow)
        val animatedTop = lerp(anchorRectInRoot.top, targetTop, grow)

        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DeepForestBg.copy(alpha = 0.62f * grow))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            Column(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            anchorRectInRoot.left.roundToInt(),
                            animatedTop.roundToInt()
                        )
                    }
                    .width(with(density) { anchorRectInRoot.width.toDp() })
                    .height(with(density) { animatedHeight.toDp() })
                    .clip(LocationPanelShape)
                    .background(LocationBoxSurface)
                    .border(1.dp, LocationBoxBorder, LocationPanelShape)
                    .testTag("location_box_panel")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LocationBoxHeight)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LocationHeaderContent(
                        descriptor = summary.descriptor,
                        flag = summary.flag,
                        highlighted = isConnected,
                        chevronRotation = 180f * grow
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(LightMintGlow.copy(alpha = 0.09f))
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                ) {
                    LocationOptionRow(
                        title = "Automatic",
                        suffix = "Best Server",
                        flag = "",
                        showGlobe = true,
                        selected = mode == LocationMode.AUTO,
                        onClick = onUseAuto
                    )

                    locations.forEach { location ->
                        LocationOptionRow(
                            title = location.country.ifBlank { location.name },
                            suffix = null,
                            flag = location.flag,
                            showGlobe = false,
                            selected = mode == LocationMode.MANUAL && location.id == selectedLocationId,
                            onClick = { onSelect(location.id) }
                        )
                    }

                    if (locations.isEmpty()) {
                        LocationEmptyRow(isLoading = isLoading, onRefresh = onRefresh)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.LocationHeaderContent(
    descriptor: String,
    flag: String,
    highlighted: Boolean,
    chevronRotation: Float
) {
    Text(
        text = "Locations",
        maxLines = 1,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.sp,
            color = LightMintGlow.copy(alpha = 0.92f)
        )
    )
    Text(
        text = "  •  ",
        maxLines = 1,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 12.sp,
            color = MutedSageText.copy(alpha = 0.6f)
        )
    )
    AnimatedContent(
        targetState = descriptor,
        transitionSpec = {
            (fadeIn(tween(220)) + slideInVertically { it / 3 }) togetherWith
                (fadeOut(tween(160)) + slideOutVertically { -it / 3 })
        },
        label = "location_box_descriptor",
        modifier = Modifier.weight(1f)
    ) { text ->
        Text(
            text = text,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.2.sp,
                color = if (highlighted) LightMintGlow else MutedSageText
            ),
            modifier = Modifier.testTag("location_box_descriptor")
        )
    }
    if (flag.isNotEmpty()) {
        Text(
            text = flag,
            maxLines = 1,
            fontSize = 15.sp,
            modifier = Modifier.testTag("location_box_flag")
        )
        Spacer(modifier = Modifier.width(9.dp))
    }
    Icon(
        imageVector = PtunnelIcons.ChevronUp,
        contentDescription = null,
        tint = MutedSageText.copy(alpha = 0.85f),
        modifier = Modifier
            .size(14.dp)
            .rotate(chevronRotation)
    )
}

@Composable
private fun LocationOptionRow(
    title: String,
    suffix: String?,
    flag: String,
    showGlobe: Boolean,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocationRowHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(if (selected) LightMintGlow.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .then(
                    if (showGlobe) {
                        Modifier
                            .clip(CircleShape)
                            .background(MidDarkElements.copy(alpha = 0.6f))
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (showGlobe) {
                Icon(
                    imageVector = PtunnelIcons.Globe,
                    contentDescription = null,
                    tint = if (selected) LightMintGlow else MutedSageText,
                    modifier = Modifier.size(14.dp)
                )
            } else {
                Text(
                    text = flag.ifBlank { "🌐" },
                    maxLines = 1,
                    fontSize = 17.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(11.dp))

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selected) LightMintGlow else LightMintGlow.copy(alpha = 0.88f)
                ),
                modifier = Modifier.weight(1f, fill = false)
            )
            if (suffix != null) {
                Text(
                    text = "  •  $suffix",
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 11.sp,
                        color = MutedSageText.copy(alpha = 0.9f)
                    )
                )
            }
        }

        if (selected) {
            Spacer(modifier = Modifier.width(10.dp))
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = LightMintGlow,
                modifier = Modifier.size(17.dp)
            )
        }
    }
}

@Composable
private fun LocationEmptyRow(isLoading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(LocationRowHeight)
            .then(
                if (isLoading) {
                    Modifier
                } else {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onRefresh)
                }
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = if (isLoading) "Loading locations…" else "No locations yet — tap to retry",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontSize = 12.sp,
                color = MutedSageText
            )
        )
    }
}
