package com.locallink.pro.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.locallink.pro.R

/** Epilogue — geometric grotesque from the reference design sheet. */
val Epilogue = FontFamily(
    Font(R.font.epilogue_regular, FontWeight.Normal),
    Font(R.font.epilogue_medium, FontWeight.Medium),
    Font(R.font.epilogue_semibold, FontWeight.SemiBold),
    Font(R.font.epilogue_bold, FontWeight.Bold),
    Font(R.font.epilogue_extrabold, FontWeight.ExtraBold),
)

/**
 * Type scale. Display sizes carry the big editorial headlines ("Create,
 * explore, be inspired"); body stays airy for chat readability.
 */
val OmniTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.ExtraBold,
        fontSize = 38.sp, lineHeight = 44.sp, letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-1.0).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Bold,
        fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Bold,
        fontSize = 21.sp, lineHeight = 27.sp, letterSpacing = (-0.4).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp, lineHeight = 25.sp, letterSpacing = (-0.3).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.SemiBold,
        fontSize = 14.5.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Normal,
        fontSize = 15.5.sp, lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.5.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Normal,
        fontSize = 12.5.sp, lineHeight = 17.5.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Epilogue, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp,
    ),
)
