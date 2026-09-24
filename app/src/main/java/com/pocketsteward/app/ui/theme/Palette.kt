package com.pocketsteward.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Complete Material 3 color schemes generated from the original Pocket Steward
 * green (#3E6B3E) with Google's material-color-utilities, Tonal Spot, 2021
 * spec, standard contrast.
 *
 * The previous scheme set only 8 of the roles Compose reads. Everything else,
 * including the tertiary container that marks destructive plan rows and every
 * surface-container level cards sit on, fell back to Material's default purple
 * baseline. Every role is now derived from the same seed.
 */

internal val LightStewardColors = lightColorScheme(
    primary = Color(0xFF3A693A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBBF0B5),
    onPrimaryContainer = Color(0xFF225025),
    inversePrimary = Color(0xFFA0D49B),
    secondary = Color(0xFF52634F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD5E8CF),
    onSecondaryContainer = Color(0xFF3B4B39),
    tertiary = Color(0xFF39656B),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFBCEBF1),
    onTertiaryContainer = Color(0xFF1F4D53),
    background = Color(0xFFF7FBF1),
    onBackground = Color(0xFF181D17),
    surface = Color(0xFFF7FBF1),
    onSurface = Color(0xFF181D17),
    surfaceVariant = Color(0xFFDEE5D9),
    onSurfaceVariant = Color(0xFF424940),
    surfaceTint = Color(0xFF3A693A),
    inverseSurface = Color(0xFF2D322C),
    inverseOnSurface = Color(0xFFEEF2E9),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF72796F),
    outlineVariant = Color(0xFFC2C9BD),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF7FBF1),
    surfaceContainer = Color(0xFFEBEFE6),
    surfaceContainerHigh = Color(0xFFE6E9E0),
    surfaceContainerHighest = Color(0xFFE0E4DB),
    surfaceContainerLow = Color(0xFFF1F5EC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFD8DBD2),
)

internal val DarkStewardColors = darkColorScheme(
    primary = Color(0xFFA0D49B),
    onPrimary = Color(0xFF073910),
    primaryContainer = Color(0xFF225025),
    onPrimaryContainer = Color(0xFFBBF0B5),
    inversePrimary = Color(0xFF3A693A),
    secondary = Color(0xFFB9CCB4),
    onSecondary = Color(0xFF253423),
    secondaryContainer = Color(0xFF3B4B39),
    onSecondaryContainer = Color(0xFFD5E8CF),
    tertiary = Color(0xFFA1CED5),
    onTertiary = Color(0xFF00363C),
    tertiaryContainer = Color(0xFF1F4D53),
    onTertiaryContainer = Color(0xFFBCEBF1),
    background = Color(0xFF10140F),
    onBackground = Color(0xFFE0E4DB),
    surface = Color(0xFF10140F),
    onSurface = Color(0xFFE0E4DB),
    surfaceVariant = Color(0xFF424940),
    onSurfaceVariant = Color(0xFFC2C9BD),
    surfaceTint = Color(0xFFA0D49B),
    inverseSurface = Color(0xFFE0E4DB),
    inverseOnSurface = Color(0xFF2D322C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8C9388),
    outlineVariant = Color(0xFF424940),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF363A34),
    surfaceContainer = Color(0xFF1C211B),
    surfaceContainerHigh = Color(0xFF272B25),
    surfaceContainerHighest = Color(0xFF323630),
    surfaceContainerLow = Color(0xFF181D17),
    surfaceContainerLowest = Color(0xFF0B0F0A),
    surfaceDim = Color(0xFF10140F),
)
