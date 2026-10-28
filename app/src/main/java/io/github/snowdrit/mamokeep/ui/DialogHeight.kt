package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp

/**
 * Сколько высоты можно отдать содержимому диалога.
 *
 * Раньше у каждого диалога стояло своё число, выбранное на глаз: 420, 440, 480 dp. На
 * высоком экране из-за них обрывались фразы, которые прекрасно помещались: человек видел
 * «точный интервал из сервисной книжки ...» и решал, что текст сломан. А уменьшить числа
 * было нельзя, на низком экране те же диалоги вытолкнули бы кнопки за край.
 *
 * Поэтому считаем от окна, а не от догадки. Остаток высоты уходит заголовку, кнопкам и
 * отступам самого диалога.
 *
 * В палитре учитываем также клавиатуру: поля с кодами прокручиваются внутри окна,
 * а заголовок и кнопки остаются видимыми.
 */
@Composable
fun dialogContentMaxHeight(): Dp {
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val density = LocalDensity.current
    val keyboardHeight = WindowInsets.ime.getBottom(density)
    return with(density) { ((windowHeight - keyboardHeight).coerceAtLeast(0) * CONTENT_SHARE).toInt().toDp() }
}

private const val CONTENT_SHARE = 0.62f
