package com.blurabbit.drivelogger.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Blue = Color(0xFF2962FF)
private val Teal = Color(0xFF00BFA5)
private val Amber = Color(0xFFFFB300)

private val DarkColors = darkColorScheme(
    primary = Blue, secondary = Teal, tertiary = Amber,
)
private val LightColors = lightColorScheme(
    primary = Blue, secondary = Teal, tertiary = Amber,
)

@Composable
fun DriveLoggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
