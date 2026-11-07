package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.icons.PtunnelIcons
import com.example.ui.theme.AccentActive
import com.example.ui.theme.DangerRed
import com.example.ui.theme.DeepForestBg
import com.example.ui.theme.Hairline
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.LoginSurface
import com.example.ui.theme.LoginSurfaceFocused
import com.example.ui.theme.MintAccent
import com.example.ui.theme.MintAccentDeep
import com.example.ui.theme.MintGlowLight
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

@Composable
fun AuthScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onLoginSubmit: (username: String, password: String) -> Unit,
    modifier: Modifier = Modifier
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()

    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val contentAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 650, delayMillis = 60),
        label = "loginAlpha"
    )
    val contentOffset by animateDpAsState(
        targetValue = if (appeared) 0.dp else 22.dp,
        animationSpec = tween(durationMillis = 700, delayMillis = 60),
        label = "loginOffset"
    )

    val submit: () -> Unit = {
        if (!isLoading) {
            focusManager.clearFocus()
            onLoginSubmit(username.trim(), password)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepForestBg)
            .drawWithCache {
                val topGlow = Brush.radialGradient(
                    colors = listOf(MintAccent.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.12f, size.height * 0.20f),
                    radius = size.width * 0.95f
                )
                val cornerGlow = Brush.radialGradient(
                    colors = listOf(AccentActive.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(size.width * 0.98f, size.height * 0.04f),
                    radius = size.width * 0.80f
                )
                onDrawBehind {
                    drawRect(brush = topGlow)
                    drawRect(brush = cornerGlow)
                }
            }
            .testTag("auth_root_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .padding(horizontal = 24.dp)
                .graphicsLayer {
                    alpha = contentAlpha
                    translationY = contentOffset.toPx()
                }
        ) {
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandMark()
                Spacer(Modifier.width(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "PTUNNEL",
                        color = TextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                        fontFamily = InterFontFamily
                    )
                    Text(
                        text = ".",
                        color = MintAccent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Lock,
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(10.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "TLS 1.3",
                        color = TextTertiary,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.2.sp,
                        fontFamily = InterFontFamily
                    )
                }
            }

            Spacer(Modifier.height(48.dp))

            PrivateAccessEyebrow()

            Spacer(Modifier.height(14.dp))

            Text(
                text = buildAnnotatedString {
                    append("Welcome to unrestricted Network")
                    withStyle(SpanStyle(color = MintAccent)) { append(".") }
                },
                color = TextPrimary,
                fontSize = 40.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1.6).sp,
                fontFamily = InterFontFamily
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Your private network, one tap away. Sign in with the account issued by the PTunnel team.",
                color = TextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 22.sp,
                fontFamily = InterFontFamily,
                modifier = Modifier.widthIn(max = 320.dp)
            )

            Spacer(Modifier.height(32.dp))

            PtField(
                label = "USERNAME",
                value = username,
                onValueChange = { username = it },
                placeholder = "your username",
                leadingIcon = Icons.Outlined.Person,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                testTag = "auth_username_input"
            )

            Spacer(Modifier.height(16.dp))

            PtField(
                label = "PASSWORD",
                value = password,
                onValueChange = { password = it },
                placeholder = "••••••••",
                leadingIcon = Icons.Outlined.Lock,
                enabled = !isLoading,
                isPassword = true,
                passwordVisible = passwordVisible,
                onTogglePassword = { passwordVisible = !passwordVisible },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                focusRequester = passwordFocus,
                testTag = "auth_password_input"
            )

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Accounts are created by support",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontFamily = InterFontFamily
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MintAccent.copy(alpha = 0.7f),
                    modifier = Modifier.size(10.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Encrypted",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontFamily = InterFontFamily
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { liveRegion = LiveRegionMode.Polite },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(5.dp)
                            .background(DangerRed, CircleShape)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = errorMessage.orEmpty(),
                        color = DangerRed,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        fontFamily = InterFontFamily
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            LoginPrimaryButton(
                text = "Sign in",
                loading = isLoading,
                enabled = !isLoading,
                onClick = submit
            )

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = TextTertiary,
                    modifier = Modifier.size(11.dp)
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = "XRAY · REALITY · END-TO-END ENCRYPTED",
                    color = TextTertiary,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.4.sp,
                    fontFamily = InterFontFamily
                )
            }

            Spacer(Modifier.height(44.dp))

            RoutesBlock()

            Spacer(Modifier.height(22.dp))

            Text(
                text = "BUILT FOR RESTRICTED NETWORKS",
                color = TextTertiary,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.3.sp,
                fontFamily = InterFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(26.dp))
        }
    }
}

@Composable
private fun BrandMark() {
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .size(29.dp)
            .drawWithCache {
                onDrawBehind {
                    drawRoundRect(
                        color = MintAccent.copy(alpha = 0.10f),
                        topLeft = Offset(-4.dp.toPx(), -4.dp.toPx()),
                        size = Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()),
                        cornerRadius = CornerRadius(13.dp.toPx())
                    )
                }
            }
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(MintAccent.copy(alpha = 0.22f), MintAccent.copy(alpha = 0.06f))
                )
            )
            .border(1.dp, MintAccent.copy(alpha = 0.30f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = PtunnelIcons.Shield,
            contentDescription = null,
            tint = MintAccent,
            modifier = Modifier.size(16.dp)
        )
    }
}

@Composable
private fun PrivateAccessEyebrow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(MintAccent.copy(alpha = 0.16f), CircleShape)
            )
            Box(
                Modifier
                    .size(5.dp)
                    .background(MintAccent, CircleShape)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "PRIVATE ACCESS",
            color = MintAccent,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.9.sp,
            fontFamily = InterFontFamily
        )
    }
}

@Composable
private fun PtField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    passwordVisible: Boolean = false,
    onTogglePassword: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
    testTag: String
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(15.dp)
    val borderColor by animateColorAsState(
        targetValue = if (focused) MintAccent.copy(alpha = 0.55f) else Hairline,
        animationSpec = tween(durationMillis = 220),
        label = "fieldBorder"
    )
    val backgroundColor by animateColorAsState(
        targetValue = if (focused) LoginSurfaceFocused else LoginSurface.copy(alpha = 0.62f),
        animationSpec = tween(durationMillis = 220),
        label = "fieldBackground"
    )
    val glow by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "fieldGlow"
    )

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = TextTertiary,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.7.sp,
            fontFamily = InterFontFamily
        )
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .drawWithCache {
                    onDrawBehind {
                        if (glow > 0f) {
                            drawRoundRect(
                                color = MintAccent.copy(alpha = 0.10f * glow),
                                topLeft = Offset(-2.dp.toPx(), -2.dp.toPx()),
                                size = Size(size.width + 4.dp.toPx(), size.height + 4.dp.toPx()),
                                cornerRadius = CornerRadius(18.dp.toPx())
                            )
                        }
                    }
                }
                .clip(shape)
                .background(backgroundColor)
                .border(1.dp, borderColor, shape)
                .padding(horizontal = 15.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) MintAccent else TextTertiary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextPrimary.copy(alpha = if (enabled) 1f else 0.5f),
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontFamily = InterFontFamily,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.sp
                    ),
                    cursorBrush = SolidColor(MintAccent),
                    visualTransformation = if (isPassword && !passwordVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            }
                        )
                        .onFocusChanged { focused = it.isFocused }
                        .testTag(testTag)
                        .semantics { contentDescription = label },
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = placeholder,
                                    color = TextTertiary,
                                    fontSize = 15.sp,
                                    fontFamily = InterFontFamily
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                if (isPassword && onTogglePassword != null) {
                    IconButton(
                        onClick = onTogglePassword,
                        enabled = enabled,
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                PtunnelIcons.VisibilityOff
                            } else {
                                PtunnelIcons.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginPrimaryButton(
    text: String,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.975f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f),
        label = "buttonScale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = MintAccent.copy(alpha = 0.35f),
                spotColor = MintAccent.copy(alpha = 0.35f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    colors = listOf(MintGlowLight, MintAccent, MintAccentDeep)
                )
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled && !loading,
                onClick = onClick
            )
            .semantics { role = Role.Button }
            .testTag("auth_submit_button"),
        contentAlignment = Alignment.Center
    ) {
        if (loading) {
            CircularProgressIndicator(
                color = DeepForestBg,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = text,
                    color = DeepForestBg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.2).sp,
                    fontFamily = InterFontFamily
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(DeepForestBg.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center
                ) {
                    ArrowRight(tint = DeepForestBg)
                }
            }
        }
    }
}

@Composable
private fun ArrowRight(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(14.dp)) {
        val stroke = 1.9.dp.toPx()
        val startX = size.width * 0.16f
        val endX = size.width * 0.84f
        val midY = size.height * 0.5f
        drawLine(
            color = tint,
            start = Offset(startX, midY),
            end = Offset(endX, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.22f),
            end = Offset(endX, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(size.width * 0.58f, size.height * 0.78f),
            end = Offset(endX, midY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
}

@Composable
private fun RoutesBlock() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Hairline)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "AVAILABLE ROUTES",
            color = TextTertiary,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp,
            fontFamily = InterFontFamily
        )
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RouteName("Reality")
            RouteDot()
            RouteName("VMess")
            RouteDot()
            RouteName("Trojan")
            RouteDot()
            RouteName("Shadowsocks")
        }
    }
}

@Composable
private fun RouteName(name: String) {
    Text(
        text = name,
        color = TextSecondary,
        fontSize = 10.sp,
        fontFamily = InterFontFamily
    )
}

@Composable
private fun RouteDot() {
    Spacer(Modifier.width(7.dp))
    Box(
        Modifier
            .size(2.dp)
            .background(TextTertiary.copy(alpha = 0.7f), CircleShape)
    )
    Spacer(Modifier.width(7.dp))
}
