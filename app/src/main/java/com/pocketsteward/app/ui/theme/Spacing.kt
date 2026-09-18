package com.pocketsteward.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * One spacing scale, used everywhere.
 *
 * Before this existed, padding was chosen per composable — 16.dp here, 12.dp
 * there, 4.dp between two labels that should have been one block — and the
 * result read as a list of unrelated rectangles rather than a screen. A scale
 * is not a style preference; it is what makes grouping legible without
 * drawing a single line.
 *
 * Steps are 4dp-based, which is Material's own grid. There are deliberately
 * only five: a sixth would immediately be used to avoid choosing between the
 * existing ones.
 */
object Spacing {
    /** Between two things that are one thing: a label and its value. */
    val hairline = 4.dp

    /** Between rows in a list, and inside a dense card. */
    val tight = 8.dp

    /** The default. Card padding, gaps between controls in a row. */
    val base = 12.dp

    /** Screen gutters, and the gap above an action row. */
    val screen = 16.dp

    /** Between sections that are genuinely separate subjects. */
    val section = 24.dp
}
