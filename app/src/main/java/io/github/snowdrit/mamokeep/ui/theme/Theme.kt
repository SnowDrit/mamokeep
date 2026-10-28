package io.github.snowdrit.mamokeep.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import io.github.snowdrit.mamokeep.data.ThemeMode

/**
 * Тёмная ли тема сейчас.
 *
 * Отдельно от `isSystemInDarkTheme()` намеренно: пользователь может выбрать тему вручную,
 * и тогда системная настройка ни при чём. Цвета статуса обязаны следовать за темой
 * приложения, иначе получаются светлые оттенки на светлом фоне.
 */
val LocalDarkTheme: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F5B92),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF07164B),
    // Keep all surface roles neutral; Material defaults otherwise reintroduce a purple tint.
    background = Color.White,
    onBackground = Color(0xFF1A1B21),
    surface = Color.White,
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE7E7E7),
    surfaceTint = Color.White,
    surfaceBright = Color.White,
    surfaceDim = Color(0xFFDADADA),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEEEEEE),
    surfaceContainerHighest = Color(0xFFE2E2E2),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF757575),
    outlineVariant = Color(0xFFC7C7C7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    onPrimary = Color(0xFF202C61),
    primaryContainer = Color(0xFF374379),
    onPrimaryContainer = Color(0xFFDCE1FF),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E1E9),
    surfaceContainerHighest = Color(0xFF34343B),
    onSurfaceVariant = Color(0xFFC6C6D0),
)

@Composable
fun MamoKeepTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    // Приложение рисуется под системными панелями (edge-to-edge с targetSdk 35+ включён
    // принудительно), поэтому цвет часов и значков связи задаём сами. Без этого в светлой
    // теме они остаются светлыми и на светлом фоне почти не видны — на каждом экране.
    SystemBarAppearance(dark)

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            content = content,
        )
    }
}

/** Full-screen dialogs have their own window and must use the same system bar colors. */
@Composable
fun SystemBarAppearance(dark: Boolean = LocalDarkTheme.current) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
                ?: (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
