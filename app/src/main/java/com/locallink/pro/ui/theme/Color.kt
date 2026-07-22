package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// OmniPro — "Aurora Ink" design system
// Near-black ink surfaces, one hero violet→pink gradient, dusty-rose support.
// Reference palette: #ac1ed6 · #c26e73 · #090607 · #221f20, font Epilogue.
// ─────────────────────────────────────────────────────────────────────────

// ─── Base surfaces — ink ──────────────────────────────────────────────────
val OmniBg          = Color(0xFF090607) // app base (near-black ink)
val OmniSurface     = Color(0xFF221F20) // cards / sheets / AI bubbles
val OmniSurface2    = Color(0xFF2A2627) // elevated card / input field
val OmniSurface3    = Color(0xFF353031) // hover / pressed / chips
val OmniScrim       = Color(0xCC090607)

// ─── Fills (legacy "glass" names kept for reuse) ──────────────────────────
val GlassFill       = Color(0xFF221F20) // default panel
val GlassFillStrong = Color(0xFF272324) // input pill / stronger panel
val GlassFillFaint  = Color(0xFF1A1718) // subtle chip / code block
val GlassHighlight  = Color(0x0AFFFFFF) // faint top-edge light
val GlassBorder     = Color(0xFF393435) // hairline outline
val GlassBorderSoft = Color(0xFF2B2728) // softer hairline

// ─── Aurora accents ───────────────────────────────────────────────────────
val AuroraViolet    = Color(0xFFAC1ED6) // hero violet
val AuroraVioletHi  = Color(0xFFC957E8) // lighter violet (active / glow)
val AuroraVioletLo  = Color(0xFF8A16AE) // pressed violet
val AuroraPink      = Color(0xFFE066B4) // gradient end pink
val AuroraRose      = Color(0xFFC26E73) // dusty rose (support)
val AuroraPeach     = Color(0xFFE8A28A) // warm highlight in aurora glow

// ─── Signature gradients ──────────────────────────────────────────────────
/** Hero brand gradient — buttons, user bubbles, orbs. */
val AuroraBrush = Brush.linearGradient(listOf(AuroraViolet, AuroraPink))
/** Softer diagonal variant for large fills (user bubble). */
val BubbleBrush = Brush.linearGradient(listOf(Color(0xFF9E22CC), Color(0xFFD960B0)))

// ─── Ambient glow stops (aurora header) ───────────────────────────────────
val GlowAmber       = Color(0xFFB44BD8) // (legacy name) violet bloom
val GlowBronze      = Color(0xFFC26E73) // rose bloom
val GlowEdge        = Color(0xFF090607) // edges fade to ink

// ─── Borders / hairlines ──────────────────────────────────────────────────
val OmniBorder      = GlassBorder
val OmniBorderSoft  = GlassBorderSoft
val OmniBorderFocus = Color(0xFF57474F)

// ─── Text — warm off-white ────────────────────────────────────────────────
val OmniText        = Color(0xFFF7F3F5) // primary
val OmniTextDim     = Color(0xFFB9B1B5) // secondary
val OmniTextFaint   = Color(0xFF878083) // tertiary / placeholders
val OmniTextOnAccent = Color(0xFFFFFFFF)

// ─── Accent aliases (violet is the single hero) ───────────────────────────
val OmniAccent      = AuroraViolet
val OmniAccentBright = AuroraVioletHi
val OmniAccentDim   = AuroraVioletLo
val OmniAccentContainer = Color(0xFF351A3F) // violet-tinted dark surface

// ─── Chat bubbles ─────────────────────────────────────────────────────────
val OmniUserBubble  = Color(0xFFAC1ED6) // painted with BubbleBrush; solid fallback
val OmniAiBubble    = Color(0xFF221F20) // charcoal (assistant)

// ─── Semantic ─────────────────────────────────────────────────────────────
val OmniSuccess     = Color(0xFF4CD990)
val OmniSuccessDim  = Color(0xFF14301F)
val OmniWarning     = Color(0xFFF0B45C)
val OmniError       = Color(0xFFF06A6A)
val OmniErrorDim    = Color(0xFF3A1A1F)

// ─── Voice / live ─────────────────────────────────────────────────────────
val OmniVoice       = AuroraPink
val OmniVoicePulse  = Color(0x33E066B4)

// ─── Back-compat aliases (older call sites reference these names) ─────────
val OmniBackground       = OmniBg
val OmniSurfaceVariant   = OmniSurface2
val OmniSurfaceElevated  = OmniSurface2
val OmniSurfaceDim       = OmniSurface3
val OmniBorderLight      = GlassBorderSoft
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
