package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Direction A — Bioluminescent night forest
private val DarkColorScheme =
  darkColorScheme(
    primary = NeonMint,
    onPrimary = Color(0xFF003822),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA0F3CE),
    secondary = ChanterelleGold,
    onSecondary = Color(0xFF362C00),
    secondaryContainer = Color(0xFF503F00),
    onSecondaryContainer = Color(0xFFFFE08C),
    tertiary = MossSpruce,
    onTertiary = Color(0xFF0C3623),
    tertiaryContainer = Color(0xFF224D38),
    onTertiaryContainer = Color(0xFFC1ECD0),
    background = DeepForestVoid,
    onBackground = Color(0xFFD7D9CB),
    surface = CharcoalSpruce,
    onSurface = Color(0xFFD7D9CB),
    surfaceVariant = MediumSpruce,
    onSurfaceVariant = Color(0xFFB9BFAD),
    outline = SageOutline,
    error = Color(0xFFE49E94),
    onError = Color(0xFF410E08),
    errorContainer = Color(0xFF5F2520),
    onErrorContainer = Color(0xFFFFDAD4)
  )

// Direction C — Quiet modernist (light)
private val LightColorScheme =
  lightColorScheme(
    primary = DeepForestGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCFEDDA),
    onPrimaryContainer = Color(0xFF0A2615),
    secondary = WarmHoney,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFAE6BD),
    onSecondaryContainer = Color(0xFF2C1F00),
    tertiary = SoftMoss,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFEDDA),
    onTertiaryContainer = Color(0xFF0A2615),
    background = WarmOffWhite,
    onBackground = Color(0xFF181A17),
    surface = PaperWhite,
    onSurface = Color(0xFF181A17),
    surfaceVariant = SoftStone,
    onSurfaceVariant = Color(0xFF4A4D45),
    outline = WarmStoneOutline,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
  )

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Brand identity matters more than dynamic colours, so default to false.
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
