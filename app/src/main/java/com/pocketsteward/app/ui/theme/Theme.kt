package com.pocketsteward.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * Whether file lists may decode real previews. Provided once at the root from
 * the "Show thumbnails" setting so every file row can read it without each
 * screen threading a parameter through.
 */
val LocalThumbnailsEnabled = staticCompositionLocalOf { true }

@Composable
fun PocketStewardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    wallpaperColors: Boolean = false,
    thumbnailsEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The fixed Pocket Steward palette is the default identity. Following the
    // wallpaper is an explicit opt-in from Settings, and only exists on
    // Android 12+.
    val colorScheme = when {
        wallpaperColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkStewardColors
        else -> LightStewardColors
    }

    CompositionLocalProvider(LocalThumbnailsEnabled provides thumbnailsEnabled) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = PocketStewardTypography,
            shapes = PocketStewardShapes,
            content = content,
        )
    }
}
