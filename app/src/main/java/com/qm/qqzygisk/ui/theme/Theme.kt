package com.qm.qqzygisk.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import com.qm.qqzygisk.R

@Composable
private fun resourceColorScheme(darkTheme: Boolean): ColorScheme {
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colorResource(R.color.qqz_primary),
        onPrimary = colorResource(R.color.qqz_on_primary),
        primaryContainer = colorResource(R.color.qqz_primary_container),
        onPrimaryContainer = colorResource(R.color.qqz_on_primary_container),
        secondary = colorResource(R.color.qqz_secondary),
        onSecondary = colorResource(R.color.qqz_on_secondary),
        secondaryContainer = colorResource(R.color.qqz_secondary_container),
        onSecondaryContainer = colorResource(R.color.qqz_on_secondary_container),
        tertiary = colorResource(R.color.qqz_tertiary),
        onTertiary = colorResource(R.color.qqz_on_tertiary),
        tertiaryContainer = colorResource(R.color.qqz_tertiary_container),
        onTertiaryContainer = colorResource(R.color.qqz_on_tertiary_container),
        error = colorResource(R.color.qqz_error),
        onError = colorResource(R.color.qqz_on_error),
        errorContainer = colorResource(R.color.qqz_error_container),
        onErrorContainer = colorResource(R.color.qqz_on_error_container),
        background = colorResource(R.color.qqz_background),
        onBackground = colorResource(R.color.qqz_on_background),
        surface = colorResource(R.color.qqz_surface),
        onSurface = colorResource(R.color.qqz_on_surface),
        surfaceVariant = colorResource(R.color.qqz_surface_variant),
        onSurfaceVariant = colorResource(R.color.qqz_on_surface_variant),
        surfaceTint = colorResource(R.color.qqz_primary),
        outline = colorResource(R.color.qqz_outline),
        outlineVariant = colorResource(R.color.qqz_outline_variant),
        scrim = colorResource(R.color.qqz_scrim),
        inverseSurface = colorResource(R.color.qqz_inverse_surface),
        inverseOnSurface = colorResource(R.color.qqz_inverse_on_surface),
        inversePrimary = colorResource(R.color.qqz_inverse_primary),
        surfaceDim = colorResource(R.color.qqz_surface_dim),
        surfaceBright = colorResource(R.color.qqz_surface_bright),
        surfaceContainerLowest = colorResource(R.color.qqz_surface_container_lowest),
        surfaceContainerLow = colorResource(R.color.qqz_surface_container_low),
        surfaceContainer = colorResource(R.color.qqz_surface_container),
        surfaceContainerHigh = colorResource(R.color.qqz_surface_container_high),
        surfaceContainerHighest = colorResource(R.color.qqz_surface_container_highest),
    )
}

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        resourceColorScheme(darkTheme)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
