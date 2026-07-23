package com.locallink.pro.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ─── Extended OmniPro colors (accessible via LocalOmniPinColors) ─────────

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

// ─── Porcelain Material color scheme (light) ─────────────────────────────

private val AuroraScheme = androidx.compose.material3.lightColorScheme(
    primary = OmniAccent,
    onPrimary = OmniTextOnAccent,
    primaryContainer = OmniAccentContainer,
    onPrimaryContainer = OmniAccentBright,
    secondary = AuroraPink,
    onSecondary = OmniTextOnAccent,
    secondaryContainer = OmniSurface3,
    onSecondaryContainer = OmniText,
    tertiary = AuroraRose,
    onTertiary = OmniTextOnAccent,
    background = OmniBg,
    onBackground = OmniText,
    surface = OmniBg,
    onSurface = OmniText,
    surfaceVariant = OmniSurface2,
    onSurfaceVariant = OmniTextDim,
    surfaceContainer = OmniSurface,
    surfaceContainerLow = Color(0xFFFBFAF9),
    surfaceContainerHigh = OmniSurface2,
    surfaceContainerHighest = OmniSurface3,
    outline = OmniBorder,
    outlineVariant = OmniBorderSoft,
    error = OmniError,
    onError = OmniTextOnAccent,
    errorContainer = OmniErrorDim,
    onErrorContainer = OmniError,
    scrim = OmniScrim,
)

// ─── Theme (Porcelain — always light) ────────────────────────────────────

@Composable
fun LocalLinkProTheme(
    darkTheme: Boolean = false,       // Porcelain is light-themed
    dynamicColor: Boolean = false,    // keep our identity, ignore Material You
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalOmniPinColors provides OmniPinColors()) {
        MaterialTheme(
            colorScheme = AuroraScheme,
            typography = OmniTypography,
            content = content,
        )
    }
}
