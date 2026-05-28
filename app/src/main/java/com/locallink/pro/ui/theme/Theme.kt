package com.locallink.pro.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Extended OmniPin Colors ────────────────────────────────────────

data class OmniPinColors(
    val userBubble: Color = OmniUserBubble,
    val border: Color = OmniBorder,
    val borderLight: Color = OmniBorderLight,
    val borderFocus: Color = OmniBorderFocus,
    val textTertiary: Color = OmniTextTertiary,
    val toolSuccess: Color = OmniToolSuccess,
    val toolSuccessBg: Color = OmniToolSuccessBg,
    val statusConnected: Color = OmniStatusConnected,
    val statusDisconnected: Color = OmniStatusDisconnected,
    val statusConnecting: Color = OmniStatusConnecting,
    val surfaceElevated: Color = OmniSurfaceElevated,
    val surfaceDim: Color = OmniSurfaceDim,
    val accent: Color = OmniAccent,
    val accentLight: Color = OmniAccentLight,
)

val LocalOmniPinColors = staticCompositionLocalOf { OmniPinColors() }

// ─── OmniPin Light Color Scheme ─────────────────────────────────────

private val OmniPinLightScheme = lightColorScheme(
    primary = OmniPrimary,
    onPrimary = OmniTextOnDark,
    primaryContainer = OmniPrimaryContainer,
    onPrimaryContainer = OmniTextPrimary,
    secondary = OmniAccent,
    onSecondary = OmniTextOnDark,
    secondaryContainer = OmniAccentLight,
    onSecondaryContainer = OmniAccent,
    background = OmniBackground,
    onBackground = OmniTextPrimary,
    surface = OmniSurface,
    onSurface = OmniTextPrimary,
    surfaceVariant = OmniSurfaceVariant,
    onSurfaceVariant = OmniTextSecondary,
    outline = OmniBorder,
    outlineVariant = OmniBorderLight,
    error = OmniStatusError,
    onError = OmniTextOnDark,
)

// ─── Dark Color Scheme (kept from original) ─────────────────────────

private val DarkColorScheme = darkColorScheme(
    primary = BluetoothBlue,
    onPrimary = TextOnDark,
    primaryContainer = BluetoothBlueDark,
    secondary = SshGreen,
    onSecondary = TextOnDark,
    secondaryContainer = SshGreenDark,
    background = SurfaceDark,
    surface = SurfaceMediumDark,
    surfaceVariant = SurfaceCardDark,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    onSurfaceVariant = TextSecondary,
    error = StatusError
)

// ─── Typography ─────────────────────────────────────────────────────

private val OmniPinTypography = Typography(
    titleLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

// ─── Theme Composable ───────────────────────────────────────────────

@Composable
fun LocalLinkProTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> OmniPinLightScheme
    }

    val omniColors = if (darkTheme) {
        OmniPinColors(
            userBubble = BluetoothBlue,
            border = SurfaceCardDark,
            borderLight = SurfaceMediumDark,
            borderFocus = TextSecondary,
            textTertiary = TextSecondary,
            surfaceElevated = SurfaceCardDark,
            surfaceDim = SurfaceMediumDark,
        )
    } else {
        OmniPinColors()
    }

    CompositionLocalProvider(LocalOmniPinColors provides omniColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OmniPinTypography,
            content = content
        )
    }
}
