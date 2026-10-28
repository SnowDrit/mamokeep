package io.github.snowdrit.mamokeep.ui.help

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.EmptyCoroutineContext

/** Exercises the real rememberSaveable/SideEffect boundary without replacing the app's garage. */
@RunWith(AndroidJUnit4::class)
class LessonRequestLifecycleTest {
    @Test
    fun completedLessonDoesNotReplayWhenItsScreenIsRemovedAndOpenedAgain() = onMain {
        for (id in listOf("regional", "reminders", "export")) {
            val lesson = lesson(id)
            var requested: String? = id
            var consumed = 0
            val consume: () -> Unit = { requested = null; consumed++ }
            Host(lesson, requested == id, consume).use { host ->
                assertTrue(host.state.active)
                assertNull(requested)
                assertEquals(1, consumed)
                repeat(lesson.steps.size) { host.state.next() }
                assertFalse(host.state.active)
            }
            // Scanner replaces MoreScreen entirely; returning creates a fresh composition.
            Host(lesson, requested == id, consume).use { host ->
                assertFalse(host.state.active)
                assertEquals(1, consumed)
            }
            // A new explicit request from Help must still start the lesson.
            requested = id
            Host(lesson, requested == id, consume).use { host ->
                assertEquals(0, host.state.index)
                assertNull(requested)
                assertEquals(2, consumed)
            }
        }
    }

    @Test
    fun consumingTheRequestPreservesActiveAndCompletedStateAcrossSavedStateRestoration() = onMain {
        val lesson = lesson("regional")
        var requested: String? = lesson.id
        var consumed = 0
        val consume: () -> Unit = { requested = null; consumed++ }
        val activeSaved = Host(lesson, requested == lesson.id, consume).use { host ->
            repeat(4) { host.state.next() }
            host.save()
        }
        assertNull(requested)
        val completedSaved = Host(lesson, requested == lesson.id, consume, activeSaved).use { host ->
            assertEquals(4, host.state.index)
            assertEquals(LessonSurface.CURRENCY, host.state.step!!.surface)
            assertEquals(1, consumed)
            repeat(2) { host.state.next() }
            host.save()
        }
        Host(lesson, requested == lesson.id, consume, completedSaved).use { host ->
            assertFalse(host.state.active)
            assertEquals(1, consumed)
        }
    }

    @Test
    fun ordinaryScreenEntryDoesNotConsumeOrStartALesson() = onMain {
        Host(lesson("regional"), false, { error("No lesson was requested") }).use { host ->
            assertFalse(host.state.active)
        }
    }

    private fun lesson(id: String) = FeatureLesson(id, 1, List(6) { index ->
        LessonStep("target-$index", if (index < 4) LessonSurface.REGIONAL else LessonSurface.CURRENCY, 1, 1)
    })

    private fun onMain(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private class Host(
        lesson: FeatureLesson,
        start: Boolean,
        onStartConsumed: () -> Unit,
        restored: Map<String, List<Any?>>? = null,
    ) : AutoCloseable {
        private val registry = SaveableStateRegistry(restored) { it is Int }
        private val recomposer = Recomposer(EmptyCoroutineContext)
        private val composition = Composition(NoNodesApplier(), recomposer)
        lateinit var state: LessonState
            private set

        init {
            composition.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    state = rememberLessonState(lesson, start, onStartConsumed)
                }
            }
        }

        fun save(): Map<String, List<Any?>> = registry.performSave()

        override fun close() {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoNodesApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = error("State must not emit UI nodes")
        override fun insertBottomUp(index: Int, instance: Unit) = error("State must not emit UI nodes")
        override fun move(from: Int, to: Int, count: Int) = error("State must not emit UI nodes")
        override fun remove(index: Int, count: Int) = error("State must not emit UI nodes")
        override fun onClear() = Unit
    }
}
