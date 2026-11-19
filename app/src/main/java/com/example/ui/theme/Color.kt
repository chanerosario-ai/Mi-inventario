package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Brand & Surface Palette — Industrial Hospitality Precision
val NavyPrimary = Color(0xFF102A43)
val NavyPrimaryLight = Color(0xFF243B53)
val NavyContainer = Color(0xFFD9E2EC)
val AmberAccent = Color(0xFFD97706)
val AmberContainer = Color(0xFFFEF3C7)
val TealSecondary = Color(0xFF0F766E)
val TealContainer = Color(0xFFCCFBF1)

val SurfaceLight = Color(0xFFF8FAFC)
val SurfaceCardLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFE2E8F0)
val OnSurfaceLight = Color(0xFF0F172A)
val OnSurfaceMutedLight = Color(0xFF475569)

val SurfaceDark = Color(0xFF0B1320)
val SurfaceCardDark = Color(0xFF132033)
val SurfaceVariantDark = Color(0xFF1E293B)
val OnSurfaceDark = Color(0xFFF1F5F9)
val OnSurfaceMutedDark = Color(0xFF94A3B8)

val StatusSuccess = Color(0xFF059669)
val StatusWarning = Color(0xFFD97706)
val StatusError = Color(0xFFDC2626)

// Distinct colors for the 4 inventory areas
val AreaGeneralColor = Color(0xFF1D4ED8)
val AreaDespensaColor = Color(0xFF047857)
val AreaLicoresColor = Color(0xFF9F1239)
val AreaBarColor = Color(0xFFB45309)

fun areaAccentColor(areaId: Long): Color = when (areaId) {
    1L -> AreaGeneralColor
    2L -> AreaDespensaColor
    3L -> AreaLicoresColor
    4L -> AreaBarColor
    else -> NavyPrimary
}
