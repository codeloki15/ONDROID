package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// OmniPin — Modern Dark design system ("Graphite")
// A refined, premium AI-chat palette: near-black graphite surfaces, a single
// warm-violet accent, restrained semantic colors. Familiar (ChatGPT/Claude)
// but with its own cohesive identity.
// ─────────────────────────────────────────────────────────────────────────

// Surfaces — layered near-black graphite (each step ~+4% lightness)
val OmniBg          = Color(0xFF0B0B0F) // app background (deepest)
val OmniSurface     = Color(0xFF141419) // cards / sheets
val OmniSurface2    = Color(0xFF1C1C23) // elevated card / input field
val OmniSurface3    = Color(0xFF26262F) // hover / pressed / chips
val OmniScrim       = Color(0xCC000000)

// Borders / hairlines
val OmniBorder      = Color(0xFF2A2A33)
val OmniBorderSoft  = Color(0xFF1F1F26)
val OmniBorderFocus = Color(0xFF3A3A46)

// Text
val OmniText        = Color(0xFFF2F2F5) // primary
val OmniTextDim     = Color(0xFFA8A8B3) // secondary
val OmniTextFaint   = Color(0xFF6E6E7A) // tertiary / placeholders
val OmniTextOnAccent = Color(0xFF0B0B0F)

// Accent — warm violet (single hero accent + soft container)
val OmniAccent      = Color(0xFF7C6CFF)
val OmniAccentBright = Color(0xFF9A8CFF)
val OmniAccentDim   = Color(0xFF5B4FD6)
val OmniAccentContainer = Color(0xFF1E1A3A) // accent-tinted surface

// Chat bubbles
val OmniUserBubble  = Color(0xFF2A2540) // subtle violet-tinted graphite (user)
val OmniAiBubble    = Color(0xFF16161C) // near-surface (assistant)

// Semantic
val OmniSuccess     = Color(0xFF3DD68C)
val OmniSuccessDim  = Color(0xFF143324)
val OmniWarning     = Color(0xFFF5B14B)
val OmniError       = Color(0xFFFF6B6B)
val OmniErrorDim    = Color(0xFF3A1A1F)

// Voice / live
val OmniVoice       = Color(0xFFFF6B6B)
val OmniVoicePulse  = Color(0x33FF6B6B)

// ─── Back-compat aliases (older screens reference these names) ───────────
val OmniBackground       = OmniBg
val OmniSurfaceVariant   = OmniSurface2
val OmniSurfaceElevated  = OmniSurface2
val OmniSurfaceDim       = OmniSurface3
val OmniBorderLight      = OmniBorderSoft
val OmniPrimary          = OmniAccent
val OmniPrimaryLight     = OmniAccentBright
val OmniPrimaryContainer = OmniAccentContainer
val OmniTextPrimary      = OmniText
val OmniTextSecondary    = OmniTextDim
val OmniTextTertiary     = OmniTextFaint
val OmniTextOnDark       = OmniText
val OmniTextOnDarkSecondary = OmniTextDim
val OmniAccentLight      = OmniAccentContainer
val OmniStatusConnected    = OmniSuccess
val OmniStatusDisconnected = OmniError
val OmniStatusConnecting   = OmniWarning
val OmniStatusError        = OmniError
val OmniToolSuccess        = OmniSuccess
val OmniToolSuccessBg      = OmniSuccessDim
val VoiceActive            = OmniVoice
val VoicePulse             = OmniVoicePulse

// Legacy names still referenced by theme/specialized code
val TextPrimary   = OmniText
val TextSecondary = OmniTextDim
val TextOnDark    = OmniText
val SurfaceDark        = OmniBg
val SurfaceMediumDark  = OmniSurface
val SurfaceCardDark    = OmniSurface2
val StatusConnected    = OmniSuccess
val StatusDisconnected = OmniError
val StatusConnecting   = OmniWarning
val StatusError        = OmniError
