package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
  darkColorScheme(
    primary = NeonMint,
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFF67FDBB),
    secondary = MossSpruce,
    onSecondary = Color(0xFF0C3623),
    secondaryContainer = Color(0xFF224D38),
    onSecondaryContainer = Color(0xFFB7ECCF),
    tertiary = ChanterelleGold,
    onTertiary = Color(0xFF3B2F00),
    tertiaryContainer = Color(0xFF564500),
    onTertiaryContainer = Color(0xFFFFE084),
    background = DeepForestVoid,
    onBackground = Color(0xFFE1E5E2),
    surface = CharcoalSpruce,
    onSurface = Color(0xFFE1E5E2),
    surfaceVariant = MediumSpruce,
    onSurfaceVariant = Color(0xFFC0C9C2),
    outline = SageOutline
  )

private val LightColorScheme =
  lightColorScheme(
    primary = Color(0xFF006C47),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF67FDBB),
    onPrimaryContainer = Color(0xFF002112),
    secondary = Color(0xFF4C6354),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9D5),
    onSecondaryContainer = Color(0xFF092014),
    tertiary = Color(0xFF705D00),
    onTertiary = Color.White,
    background = Color(0xFFF5FBF6),
    onBackground = Color(0xFF191D1A),
    surface = Color(0xFFF5FBF6),
    onSurface = Color(0xFF191D1A),
    surfaceVariant = Color(0xFFDBE5DC),
    onSurfaceVariant = Color(0xFF404942),
    outline = Color(0xFF707971)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Dynamic color is available on Android 12+ (set default to false to prioritize brand design)
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
