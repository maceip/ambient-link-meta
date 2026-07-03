package com.lowkey.ambientlink.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Shared accent + surfaces for the phone settings UI. */
object AmbientTheme {
  /** Section cards lift slightly above the black canvas. */
  val sectionBackground = Color(0xFF252930)

  val pillShape = RoundedCornerShape(12.dp)
  val fieldShape = RoundedCornerShape(12.dp)

  @Composable
  fun accentSelectedBackground(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)

  @Composable
  fun accentSelectedBorder(): Color =
    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)

  @Composable
  fun accentSelectedForeground(): Color = MaterialTheme.colorScheme.primary

  @Composable
  fun accentUnselectedBackground(): Color =
    MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)

  @Composable
  fun switchColors() = SwitchDefaults.colors(
    checkedThumbColor = MaterialTheme.colorScheme.primary,
    checkedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
  )

  @Composable
  fun tonalButtonColors() = ButtonDefaults.filledTonalButtonColors(
    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    contentColor = MaterialTheme.colorScheme.onSurface,
    disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
  )

  fun colorSchemeFor(theme: String): ColorScheme = when (theme) {
    "dracula" -> darkColorScheme(
      primary = Color(0xFFBD93F9),
      onPrimary = Color(0xFF282A36),
      secondary = Color(0xFF50FA7B),
      onSecondary = Color(0xFF282A36),
      background = Color(0xFF282A36),
      surface = Color(0xFF21222C),
      surfaceVariant = Color(0xFF343746),
      onSurface = Color(0xFFF8F8F2),
      onSurfaceVariant = Color(0xFF6272A4),
      outline = Color(0xFF44475A),
      error = Color(0xFFFF5555),
    )
    "tokyo-night" -> darkColorScheme(
      primary = Color(0xFF7AA2F7),
      onPrimary = Color(0xFF1A1B26),
      secondary = Color(0xFF9ECE6A),
      onSecondary = Color(0xFF1A1B26),
      background = Color(0xFF1A1B26),
      surface = Color(0xFF16161E),
      surfaceVariant = Color(0xFF24283B),
      onSurface = Color(0xFFC0CAF5),
      onSurfaceVariant = Color(0xFF565F89),
      outline = Color(0xFF414868),
      error = Color(0xFFF7768E),
    )
    "catppuccin" -> darkColorScheme(
      primary = Color(0xFFCBA6F7),
      onPrimary = Color(0xFF1E1E2E),
      secondary = Color(0xFFA6E3A1),
      onSecondary = Color(0xFF1E1E2E),
      background = Color(0xFF1E1E2E),
      surface = Color(0xFF181825),
      surfaceVariant = Color(0xFF313244),
      onSurface = Color(0xFFCDD6F4),
      onSurfaceVariant = Color(0xFFA6ADC8),
      outline = Color(0xFF45475A),
      error = Color(0xFFF38BA8),
    )
    "nord" -> darkColorScheme(
      primary = Color(0xFF88C0D0),
      onPrimary = Color(0xFF2E3440),
      secondary = Color(0xFFA3BE8C),
      onSecondary = Color(0xFF2E3440),
      background = Color(0xFF2E3440),
      surface = Color(0xFF242933),
      surfaceVariant = Color(0xFF3B4252),
      onSurface = Color(0xFFECEFF4),
      onSurfaceVariant = Color(0xFF81A1C1),
      outline = Color(0xFF4C566A),
      error = Color(0xFFBF616A),
    )
    else -> darkColorScheme(
      primary = Color(0xFF1C84FF),
      onPrimary = Color(0xFFF3F5F8),
      secondary = Color(0xFF3DC97A),
      onSecondary = Color(0xFF0D0F13),
      background = Color(0xFF000000),
      surface = Color(0xFF0D0F13),
      surfaceVariant = Color(0xFF252930),
      onSurface = Color(0xFFF3F5F8),
      onSurfaceVariant = Color(0xFF8C939E),
      outline = Color(0xFF2E323A),
      error = Color(0xFFF0566E),
    )
  }
}
