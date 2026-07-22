package com.locallink.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Extended OmniPin colors (accessible via LocalOmniPinColors) ─────────

data class OmniPinColors(
    val userBubble: Color = OmniUserBubble,
    val aiBubble: Color = OmniAiBubble,
    val border: Color = OmniBorder,
    val borderLight: Color = OmniBorderSoft,
    val borderFocus: Color = OmniBorderFocus,
    val textTertiary: Color = OmniTextFaint,
    val toolSuccess: Color = OmniSuccess,
    val toolSuccessBg: Color = OmniSuccessDim,
    val statusConnected: Color = OmniSuccess,
    val statusDisconnected: Color = OmniError,
    val statusConnecting: Color = OmniWarning,
    val surfaceElevated: Color = OmniSurface2,
    val surfaceDim: Color = OmniSurface3,
    val accent: Color = OmniAccent,
    val accentLight: Color = OmniAccentContainer,
)

val LocalOmniPinColors = staticCompositionLocalOf { OmniPinColors() }

// ─── Amber Glass (dark) Material color scheme ────────────────────────────

private val GraphiteScheme = darkColorScheme(
    primary = OmniAccent,
    onPrimary = OmniTextOnAccent,
    primaryContainer = OmniAccentContainer,
    onPrimaryContainer = OmniAccentBright,
    secondary = OmniAccentBright,
    onSecondary = OmniTextOnAccent,
    secondaryContainer = OmniSurface3,
    onSecondaryContainer = OmniText,
    background = OmniBg,
    onBackground = OmniText,
    surface = OmniSurface,
    onSurface = OmniText,
    surfaceVariant = OmniSurface2,
    onSurfaceVariant = OmniTextDim,
    surfaceContainer = OmniSurface2,
    surfaceContainerHigh = OmniSurface3,
    outline = OmniBorder,
    outlineVariant = OmniBorderSoft,
    error = OmniError,
    onError = OmniTextOnAccent,
    errorContainer = OmniErrorDim,
    onErrorContainer = OmniError,
    scrim = OmniScrim,
)

// ─── Typography (refined; tight tracking on headings) ────────────────────

private val OmniPinTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.5.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

// ─── Theme (always dark Graphite) ────────────────────────────────────────

@Composable
fun LocalLinkProTheme(
    darkTheme: Boolean = true,        // OmniPin is dark-themed
    dynamicColor: Boolean = false,    // keep our identity, ignore Material You
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalOmniPinColors provides OmniPinColors()) {
        MaterialTheme(
            colorScheme = GraphiteScheme,
            typography = OmniPinTypography,
            content = content,
        )
    }
}
