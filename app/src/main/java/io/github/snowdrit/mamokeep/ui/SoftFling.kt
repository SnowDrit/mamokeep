package io.github.snowdrit.mamokeep.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.abs

/**
 * Прокрутка с чуть большей инерцией, чем по умолчанию.
 *
 * **Это отход от системной физики, и он сознательный.** Человек листает десяток
 * приложений в день с одной и той же инерцией, и чужая читается как «что-то не так»,
 * даже когда объективно приятнее. Поэтому отступаем немного: трение 0,7 от обычного —
 * список докатывается заметно дольше, но не «уезжает».
 *
 * Если покажется чужим — менять здесь, в одном числе.
 */
private const val FRICTION = 0.7f

@Composable
fun rememberSoftFling(): FlingBehavior {
    val decay = remember { exponentialDecay<Float>(frictionMultiplier = FRICTION) }
    return remember(decay) { SoftFling(decay) }
}

private class SoftFling(private val decay: DecayAnimationSpec<Float>) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        // Медленный отпуск пальца — это не бросок, а остановка: докатывать нечего.
        if (abs(initialVelocity) <= 1f) return initialVelocity

        var velocityLeft = initialVelocity
        var lastValue = 0f

        AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
            .animateDecay(decay) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = this.velocity

                // Список упёрся в край — гасим движение, иначе оно продолжится вхолостую.
                if (abs(delta - consumed) > 0.5f) cancelAnimation()
            }

        return velocityLeft
    }
}
