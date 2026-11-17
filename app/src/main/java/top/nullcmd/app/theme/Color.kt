package top.nullcmd.app.theme

import androidx.compose.ui.graphics.Color

/**
 * The palette, in the same greys the web front end uses.
 *
 * These were Material's sample purples. The page the app now opens with is
 * black, white and grey, so a purple model manager was not a style difference --
 * it read as a different app, which is exactly what opening a native screen from
 * the page looked like.
 *
 * The values are the page's own ramp: #FFFFFF / #FAFAFA / #EEEEEE / #D3D3D3 for
 * the light surfaces, #121212 / #1F1F1F / #262626 for the dark ones, text at
 * #121212 and #EEEEEE.
 */
internal val LightBackground = Color(0xFFFFFFFF)
internal val LightSurface = Color(0xFFFFFFFF)
internal val LightSurfaceLow = Color(0xFFFAFAFA)
internal val LightSurfaceVariant = Color(0xFFF3F3F3)
internal val LightSurfaceHigh = Color(0xFFEEEEEE)

internal val LightOnSurface = Color(0xFF121212)
internal val LightOnSurfaceVariant = Color(0xFF666666)
internal val LightOutline = Color(0xFFD3D3D3)
internal val LightOutlineVariant = Color(0xFFE7E7E7)

internal val DarkBackground = Color(0xFF121212)
internal val DarkSurface = Color(0xFF1F1F1F)
internal val DarkSurfaceVariant = Color(0xFF262626)
internal val DarkSurfaceHigh = Color(0xFF2E2E2E)

internal val DarkOnSurface = Color(0xFFEEEEEE)
internal val DarkOnSurfaceVariant = Color(0xFFB2B2B2)
internal val DarkOutline = Color(0xFF464646)
internal val DarkOutlineVariant = Color(0xFF333333)

/** Red stays red: an error is the one thing that must not read as a shade of grey. */
internal val ErrorRed = Color(0xFFB3261E)
internal val ErrorRedDark = Color(0xFFF2B8B5)
