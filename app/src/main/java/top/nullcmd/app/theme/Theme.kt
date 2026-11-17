package top.nullcmd.app.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.TextStyle
import top.nullcmd.app.data.settings.AppTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColorScheme =
  lightColorScheme(
    primary = LightOnSurface,
    onPrimary = LightBackground,
    primaryContainer = LightSurfaceHigh,
    onPrimaryContainer = LightOnSurface,
    secondary = LightOnSurfaceVariant,
    onSecondary = LightBackground,
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = LightOnSurface,
    tertiary = LightOnSurface,
    onTertiary = LightBackground,
    tertiaryContainer = LightSurfaceHigh,
    onTertiaryContainer = LightOnSurface,
    // M3 tints tonal components with the primary hue. Left at its default it is
    // purple, which is what made the chips and dialogs look like a different app
    // even after every surface had been set to grey.
    surfaceTint = LightBackground,
    inverseSurface = LightOnSurface,
    inverseOnSurface = LightBackground,
    inversePrimary = LightSurfaceVariant,
    scrim = Color(0xFF000000),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurfaceLow,
    surfaceContainerLow = LightSurfaceLow,
    surfaceContainerHigh = LightSurfaceHigh,
    surfaceContainerHighest = LightSurfaceHigh,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = ErrorRed,
    errorContainer = LightSurfaceHigh,
    onErrorContainer = LightOnSurface,
  )

private val DarkColorScheme =
  darkColorScheme(
    primary = DarkOnSurface,
    onPrimary = DarkBackground,
    primaryContainer = DarkSurfaceHigh,
    onPrimaryContainer = DarkOnSurface,
    secondary = DarkOnSurfaceVariant,
    onSecondary = DarkBackground,
    secondaryContainer = DarkSurfaceHigh,
    onSecondaryContainer = DarkOnSurface,
    tertiary = DarkOnSurface,
    onTertiary = DarkBackground,
    tertiaryContainer = DarkSurfaceHigh,
    onTertiaryContainer = DarkOnSurface,
    surfaceTint = DarkBackground,
    inverseSurface = DarkOnSurface,
    inverseOnSurface = DarkSurface,
    inversePrimary = DarkSurfaceVariant,
    scrim = Color(0xFF000000),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurface,
    surfaceContainerLow = DarkBackground,
    surfaceContainerHigh = DarkSurfaceHigh,
    surfaceContainerHighest = DarkSurfaceHigh,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = ErrorRedDark,
    errorContainer = DarkSurfaceHigh,
    onErrorContainer = DarkOnSurface,
  )

@Composable
fun DemoAppTheme(
  theme: AppTheme = AppTheme.SYSTEM,
  /**
   * Text size as a multiple of what the system asks for.
   *
   * Applied on top of the device's own setting rather than instead of it. A user
   * who has already made text larger everywhere should not find this app smaller
   * than everything else on the phone.
   */
  fontScale: Float = 1f,
  content: @Composable () -> Unit,
) {
  val darkTheme =
    when (theme) {
      AppTheme.SYSTEM -> isSystemInDarkTheme()
      AppTheme.LIGHT -> false
      AppTheme.DARK -> true
    }

  // No dynamic colour, deliberately: the page is grey by design, and a native
  // screen tinted from the wallpaper would look like a different app the moment
  // it opened over it.
  val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

  // Every style scaled, rather than one size applied everywhere, so headings stay
  // headings and body text stays body text. Enlarging only one of them flattens
  // the hierarchy the screen is read by.
  val scaledTypography =
    if (fontScale == 1f) {
      Typography
    } else {
      Typography.copy(
        displayLarge = Typography.displayLarge.scaled(fontScale),
        displayMedium = Typography.displayMedium.scaled(fontScale),
        displaySmall = Typography.displaySmall.scaled(fontScale),
        headlineLarge = Typography.headlineLarge.scaled(fontScale),
        headlineMedium = Typography.headlineMedium.scaled(fontScale),
        headlineSmall = Typography.headlineSmall.scaled(fontScale),
        titleLarge = Typography.titleLarge.scaled(fontScale),
        titleMedium = Typography.titleMedium.scaled(fontScale),
        titleSmall = Typography.titleSmall.scaled(fontScale),
        bodyLarge = Typography.bodyLarge.scaled(fontScale),
        bodyMedium = Typography.bodyMedium.scaled(fontScale),
        bodySmall = Typography.bodySmall.scaled(fontScale),
        labelLarge = Typography.labelLarge.scaled(fontScale),
        labelMedium = Typography.labelMedium.scaled(fontScale),
        labelSmall = Typography.labelSmall.scaled(fontScale),
      )
    }

  MaterialTheme(colorScheme = colorScheme, typography = scaledTypography, content = content)
}

/**
 * The same style, larger or smaller.
 *
 * `lineHeight` is scaled with the size. Scaling only the size would leave the
 * lines spaced for the smaller text, and they collide as soon as the letters
 * grow -- which is the failure mode of every "font size" setting that only sets
 * a font size.
 */
private fun TextStyle.scaled(factor: Float): TextStyle =
  copy(
    fontSize = fontSize * factor,
    lineHeight = lineHeight * factor,
    letterSpacing = letterSpacing * factor,
  )
