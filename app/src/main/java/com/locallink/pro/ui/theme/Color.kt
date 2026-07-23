package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// OmniPro — "Porcelain" design system (light, Apple-like pastel)
// Warm off-white surfaces, ink text, pastel lavender/mint accents, black
// pill CTAs, violet→pink identity gradient kept for the orb/mic.
// Reference: mobile_ui_3.webp. Token NAMES preserved from Aurora Ink.
// ─────────────────────────────────────────────────────────────────────────

// ─── Base surfaces — porcelain ────────────────────────────────────────────
val OmniBg          = Color(0xFFF7F5F3) // app base (warm off-white)
val OmniSurface     = Color(0xFFFFFFFF) // cards / sheets
val OmniSurface2    = Color(0xFFF1EEF1) // input fields / elevated tint
val OmniSurface3    = Color(0xFFE9E5E9) // pressed / chips / tracks
val OmniScrim       = Color(0x66201A22)

// ─── Fills (legacy "glass" names kept for reuse) ──────────────────────────
val GlassFill       = Color(0xFFFFFFFF) // default panel
val GlassFillStrong = Color(0xFFF3F0F4) // input pill
val GlassFillFaint  = Color(0xFFF3F1EE) // subtle chip / code block
val GlassHighlight  = Color(0x0AFFFFFF)
val GlassBorder     = Color(0xFFE5E1E4) // hairline outline
val GlassBorderSoft = Color(0xFFEFECEF)

// ─── Brand accents (pastelized violet family + support pastels) ───────────
val AuroraViolet    = Color(0xFFA24BDB) // hero violet (softened for light bg)
val AuroraVioletHi  = Color(0xFF8B36C9) // stronger violet for text/icons on light
val AuroraVioletLo  = Color(0xFF7C2DB8)
val AuroraPink      = Color(0xFFE47FBE) // gradient end pink
val AuroraRose      = Color(0xFFC26E73) // dusty rose (kept)
val AuroraPeach     = Color(0xFFF4C9A6) // pastel peach bloom
val PastelLavender  = Color(0xFFEBDDFB) // user bubble / feature card fill
val PastelMint      = Color(0xFFE1F2DA) // secondary feature card fill
val InkPill         = Color(0xFF141216) // black CTA pill

// ─── Signature gradients ──────────────────────────────────────────────────
/** Identity gradient — orb, mic, active accents. */
val AuroraBrush = Brush.linearGradient(listOf(AuroraViolet, AuroraPink))
/** User bubble fill — soft lavender wash with dark text on top. */
val BubbleBrush = Brush.linearGradient(listOf(Color(0xFFEDE0FC), Color(0xFFE3D2F9)))

// ─── Ambient wash stops ───────────────────────────────────────────────────
val GlowAmber       = Color(0xFFD9BEF5) // (legacy name) lavender bloom
val GlowBronze      = Color(0xFFF4C9A6) // peach bloom
val GlowEdge        = Color(0xFFF7F5F3)

// ─── Borders / hairlines ──────────────────────────────────────────────────
val OmniBorder      = GlassBorder
val OmniBorderSoft  = GlassBorderSoft
val OmniBorderFocus = Color(0xFFC9BED2)

// ─── Text — ink on porcelain ──────────────────────────────────────────────
val OmniText        = Color(0xFF1B171D) // primary ink
val OmniTextDim     = Color(0xFF5D5762) // secondary
val OmniTextFaint   = Color(0xFF938D98) // tertiary / placeholders
val OmniTextOnAccent = Color(0xFFFFFFFF)
val OmniTextOnBubble = Color(0xFF35204D) // dark plum on the lavender user bubble

// ─── Accent aliases ───────────────────────────────────────────────────────
val OmniAccent      = AuroraViolet
val OmniAccentBright = AuroraVioletHi
val OmniAccentDim   = AuroraVioletLo
val OmniAccentContainer = Color(0xFFF0E4FB) // lavender-tinted light surface

// ─── Chat bubbles ─────────────────────────────────────────────────────────
val OmniUserBubble  = PastelLavender
val OmniAiBubble    = Color(0xFFFFFFFF) // white card (assistant)

// ─── Semantic ─────────────────────────────────────────────────────────────
val OmniSuccess     = Color(0xFF2FA36B)
val OmniSuccessDim  = Color(0xFFDDF2E6)
val OmniWarning     = Color(0xFFC98A2B)
val OmniError       = Color(0xFFD8494E)
val OmniErrorDim    = Color(0xFFFBE3E4)

// ─── Voice / live ─────────────────────────────────────────────────────────
val OmniVoice       = AuroraPink
val OmniVoicePulse  = Color(0x33E47FBE)

// ─── Back-compat aliases ──────────────────────────────────────────────────
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
