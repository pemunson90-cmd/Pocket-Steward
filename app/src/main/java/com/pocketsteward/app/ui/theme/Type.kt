package com.pocketsteward.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketsteward.app.R

/**
 * Inter, bundled (SIL Open Font License, see assets/licenses/Inter-OFL.txt).
 * Bundled rather than downloaded: the app has no INTERNET permission and must
 * look the same in airplane mode.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val base = Typography()

private fun TextStyle.inter(weight: FontWeight? = null) =
    copy(fontFamily = Inter, fontWeight = weight ?: fontWeight)

/**
 * The full Material 3 type scale in Inter. The previous scale set only three
 * of the fifteen styles, so everything else rendered in the system default.
 * Metrics are Material's; headings are a step heavier for a clearer hierarchy.
 */
val PocketStewardTypography = Typography(
    displayLarge = base.displayLarge.inter(),
    displayMedium = base.displayMedium.inter(),
    displaySmall = base.displaySmall.inter(),
    headlineLarge = base.headlineLarge.inter(FontWeight.SemiBold),
    headlineMedium = base.headlineMedium.inter(FontWeight.SemiBold),
    headlineSmall = base.headlineSmall.inter(FontWeight.SemiBold),
    titleLarge = base.titleLarge.inter(FontWeight.SemiBold),
    titleMedium = base.titleMedium.inter(FontWeight.SemiBold),
    titleSmall = base.titleSmall.inter(FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.inter(),
    bodyMedium = base.bodyMedium.inter(),
    bodySmall = base.bodySmall.inter(),
    labelLarge = base.labelLarge.inter(FontWeight.Medium),
    labelMedium = base.labelMedium.inter(FontWeight.Medium),
    labelSmall = base.labelSmall.inter(FontWeight.Medium),
)

/** Slightly softer corners than the Material defaults, applied everywhere cards and sheets appear. */
val PocketStewardShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
