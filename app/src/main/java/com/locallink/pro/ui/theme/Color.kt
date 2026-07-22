package com.locallink.pro.ui.theme

import androidx.compose.ui.graphics.Color

// ─────────────────────────────────────────────────────────────────────────
// OmniPin — "Graphite + Indigo" design system
// Neutral cool-charcoal surfaces let a single indigo accent pop. Off-white
// text, restrained semantics. Clean and high-contrast (ChatGPT/Claude-like) —
// solid surfaces, not muddy translucency.
// ─────────────────────────────────────────────────────────────────────────

// ─── Base surfaces — layered neutral cool charcoal (each step ~+4% L) ─────
val OmniBg          = Color(0xFF0F1115) // app base (near-black slate)
val OmniSurface     = Color(0xFF171A21) // cards / sheets
val OmniSurface2    = Color(0xFF1F232C) // elevated card / input field
val OmniSurface3    = Color(0xFF2A2F3A) // hover / pressed / chips
val OmniScrim       = Color(0xCC0A0C10)

// ─── "Glass" fills — now SOLID-ish neutral surfaces (kept names for reuse) ─
val GlassFill       = Color(0xFF1A1E26) // default panel (opaque, clean)
val GlassFillStrong = Color(0xFF20242E) // input pill / stronger panel
val GlassFillFaint  = Color(0xFF15181F) // subtle chip / code block
val GlassHighlight  = Color(0x0FFFFFFF) // very faint top-edge light
val GlassBorder     = Color(0xFF2A2F3A) // hairline
val GlassBorderSoft = Color(0xFF20242E) // softer hairline

// ─── Ambient background stops — neutral, near-flat (barely-there depth) ────
val GlowAmber       = Color(0xFF6D7CFF) // (reused name) faint indigo lift
val GlowBronze      = Color(0xFF1A1E2E) // cool deep corner
val GlowEdge        = Color(0xFF0B0D11) // edges fade to base

// ─── Borders / hairlines ────────────────────────────────────────────────
val OmniBorder      = GlassBorder
val OmniBorderSoft  = GlassBorderSoft
val OmniBorderFocus = Color(0xFF3A4150)

// ─── Text — cool off-white ─────────────────────────────────────────────────
val OmniText        = Color(0xFFECEEF3) // primary (off-white)
val OmniTextDim     = Color(0xFFAEB4C2) // secondary
val OmniTextFaint   = Color(0xFF8A90A0) // tertiary / placeholders
val OmniTextOnAccent = Color(0xFFFFFFFF) // white text on indigo

// ─── Accent — indigo (single hero) + container ─────────────────────────────
val OmniAccent      = Color(0xFF6D7CFF) // indigo
val OmniAccentBright = Color(0xFF93A0FF) // lighter indigo (active/glow)
val OmniAccentDim   = Color(0xFF5260E0) // pressed indigo
val OmniAccentContainer = Color(0xFF20243B) // indigo-tinted dark surface

// ─── Chat bubbles ──────────────────────────────────────────────────────────
val OmniUserBubble  = Color(0xFF2A2F52) // indigo-tinted (user)
val OmniAiBubble    = Color(0xFF1A1E26) // neutral surface (assistant)

// ─── Semantic ──────────────────────────────────────────────────────────────
val OmniSuccess     = Color(0xFF3DD68C)
val OmniSuccessDim  = Color(0xFF12321F)
val OmniWarning     = Color(0xFFF5B14B)
val OmniError       = Color(0xFFFF6B6B)
val OmniErrorDim    = Color(0xFF3A1A1F)

// ─── Voice / live ──────────────────────────────────────────────────────────
val OmniVoice       = Color(0xFF6D7CFF) // indigo (on-brand) for listening
val OmniVoicePulse  = Color(0x336D7CFF)

// ─── Back-compat aliases (older screens reference these names) ─────────────
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
