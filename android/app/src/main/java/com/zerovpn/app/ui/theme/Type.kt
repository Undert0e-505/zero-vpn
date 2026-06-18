package com.zerovpn.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

val ZeroVpnTypography = Typography(
    // Section titles: 12sp, weight 600, uppercase, letter-spacing 0.5sp, TextDim
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
        color = TextDim,
    ),
    // Body text: 15sp, weight 400
    bodyLarge = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal,
        color = TextPrimary,
    ),
    // Body medium: 15sp, weight 500
    bodyMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = TextPrimary,
    ),
    // Metadata: 13sp, TextDim
    bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        color = TextDim,
    ),
    // Exit/tunnel names: 18sp, weight 600
    titleLarge = TextStyle(
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        color = TextPrimary,
    ),
    // Button text: 14sp, weight 500
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        color = TextPrimary,
    ),
)

// Custom text styles for specific use cases
val SectionTitleStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.5.sp,
    color = TextDim,
)

val ExitNameStyle = TextStyle(
    fontSize = 18.sp,
    fontWeight = FontWeight.SemiBold,
    color = TextPrimary,
)

val ButtonTextStyle = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Medium,
    color = TextPrimary,
)

val ButtonActiveTextStyle = TextStyle(
    fontSize = 14.sp,
    fontWeight = FontWeight.Bold,
    color = Accent,
)

val MetadataStyle = TextStyle(
    fontSize = 12.sp,
    fontWeight = FontWeight.Normal,
    color = TextDim,
)

val MetadataMediumStyle = TextStyle(
    fontSize = 13.sp,
    fontWeight = FontWeight.Normal,
    color = TextDim,
)