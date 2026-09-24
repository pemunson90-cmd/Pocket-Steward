package com.pocketsteward.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier

/**
 * A determinate progress bar that glides between updates instead of jumping.
 * Work reports progress in batches (every N files), so a raw bar moves in
 * visible steps; easing each step makes long runs read as steady progress.
 * The number shown next to the bar stays exact.
 */
@Composable
fun SmoothProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val target = if (fraction.isNaN()) 0f else fraction.coerceIn(0f, 1f)
    val shown by animateFloatAsState(targetValue = target, animationSpec = tween(400), label = "progress")
    LinearProgressIndicator(progress = { shown }, modifier = modifier)
}
