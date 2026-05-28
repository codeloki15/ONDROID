package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Color

// ─── OmniPin Clean Light Palette ─────────────────────────────────────

// Primary - Dark charcoal (used for primary actions, user bubbles)
val OmniPrimary = Color(0xFF2D2D2D)
val OmniPrimaryLight = Color(0xFF4A4A4A)
val OmniPrimaryContainer = Color(0xFFF0F0F0)

// Surfaces - White-dominant, ultra-clean
val OmniBackground = Color(0xFFFFFFFF)
val OmniSurface = Color(0xFFFFFFFF)
val OmniSurfaceVariant = Color(0xFFF7F7F8)
val OmniSurfaceElevated = Color(0xFFFAFAFB)
val OmniSurfaceDim = Color(0xFFF2F2F3)

// Borders
val OmniBorder = Color(0xFFE5E5E7)
val OmniBorderLight = Color(0xFFEEEEF0)
val OmniBorderFocus = Color(0xFFD0D0D5)

// Text
val OmniTextPrimary = Color(0xFF1A1A1A)
val OmniTextSecondary = Color(0xFF6B6B6B)
val OmniTextTertiary = Color(0xFF999999)
val OmniTextOnDark = Color(0xFFFFFFFF)
val OmniTextOnDarkSecondary = Color(0xFFE0E0E0)

// Chat bubbles
val OmniUserBubble = Color(0xFF2D2D2D)

// Status indicators
val OmniStatusConnected = Color(0xFF22C55E)
val OmniStatusDisconnected = Color(0xFFEF4444)
val OmniStatusConnecting = Color(0xFFF59E0B)
val OmniStatusError = Color(0xFFEF4444)

// Tool/Action badges
val OmniToolSuccess = Color(0xFF22C55E)
val OmniToolSuccessBg = Color(0xFFDCFCE7)

// Accent
val OmniAccent = Color(0xFF3B82F6)
val OmniAccentLight = Color(0xFFDBEAFE)

// Voice
val VoiceActive = Color(0xFFFF6B6B)
val VoicePulse = Color(0x40FF6B6B)

// ─── Legacy colors (kept for dark theme + specialized screens) ───────

// Primary - Deep Blue (Bluetooth indicator)
val BluetoothBlue = Color(0xFF2A5CAA)
val BluetoothBlueLight = Color(0xFF5B8BD5)
val BluetoothBlueDark = Color(0xFF1A3D72)

// Secondary - Green (SSH/WebSocket indicator)
val SshGreen = Color(0xFF2E7D32)
val SshGreenLight = Color(0xFF60AD5E)
val SshGreenDark = Color(0xFF005005)

// Dark theme surfaces
val SurfaceDark = Color(0xFF0F1419)
val SurfaceMediumDark = Color(0xFF1A1F2E)
val SurfaceCardDark = Color(0xFF222836)

// Dark theme text
val TextPrimary = Color(0xFFE8EAED)
val TextSecondary = Color(0xFF9AA0A6)
val TextOnDark = Color(0xFFFFFFFF)

// Status (legacy aliases)
val StatusConnected = OmniStatusConnected
val StatusDisconnected = OmniStatusDisconnected
val StatusConnecting = OmniStatusConnecting
val StatusError = OmniStatusError

// Terminal
val TerminalGreen = Color(0xFF4EC9B0)
val TerminalBg = Color(0xFF1E1E1E)

// Git status
val GitAdded = Color(0xFF73C991)
val GitModified = Color(0xFFE2C08D)
val GitDeleted = Color(0xFFF14C4C)
val GitUntracked = Color(0xFF858585)

// File types
val FileFolder = Color(0xFFDCB67A)
val FileCode = Color(0xFF519ABA)
val FileImage = Color(0xFFA074C4)
val FileDocument = Color(0xFF6997D5)
