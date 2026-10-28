package io.github.snowdrit.mamokeep.ui.help

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

/** The editable routes must lead to real controls and keep setup before consequential actions. */
class FeatureLessonsTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val lessons = LessonCatalog.parse(File(main, "assets/help/lessons.json").readText())

    private fun route(id: String) = lessons.single { it.id == id }

    private fun state(id: String, initialIndex: Int = -1): LessonState {
        val asset = route(id)
        val lesson = FeatureLesson(asset.id, 1, asset.steps.mapIndexed { index, step ->
            LessonStep(step.target, step.surface, index * 2 + 2, index * 2 + 3, step.reveal)
        })
        return LessonState(lesson, initialIndex)
    }

    @Test fun `three focused lessons reference nonempty translated copy`() {
        assertEquals(setOf("reminders", "export", "regional"), lessons.map { it.id }.toSet())
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(main, "res/values/help_lessons.xml")).getElementsByTagName("string")
        val strings = (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
        val referenced = lessons.flatMap { lesson ->
            listOf(lesson.title) + lesson.steps.flatMap { listOf(it.title, it.summary) }
        } + listOf("help_lesson_start", "help_lesson_close")
        // LocaleResourcesTest verifies these same keys and arguments in every advertised language.
        for (name in referenced) assertFalse("Missing lesson text: $name", strings[name].isNullOrBlank())
    }

    @Test fun `reminders show time days optional thresholds repeats then the real save action`() {
        val steps = route("reminders").steps
        assertEquals(listOf(
            LessonTarget.REMINDER_TIME, LessonTarget.REMINDER_DAYS, LessonTarget.REMINDER_ADVANCED,
            LessonTarget.REMINDER_THRESHOLDS, LessonTarget.REMINDER_REPEATS, LessonTarget.REMINDER_SAVE,
        ), steps.map { it.target })
        assertEquals(setOf(LessonSurface.REMINDERS), steps.map { it.surface }.toSet())
        assertEquals(listOf(null, null, "advanced", "advanced", "advanced", null), steps.map { it.reveal })
    }

    @Test fun `export teaches composition question and privacy before preview and delivery`() {
        val steps = route("export").steps
        assertEquals(listOf(
            LessonTarget.EXPORT_CONTENT, LessonTarget.EXPORT_QUESTION, LessonTarget.EXPORT_PRIVACY,
            LessonTarget.EXPORT_BUILD, LessonTarget.EXPORT_REVIEW, LessonTarget.EXPORT_SEND,
        ), steps.map { it.target })
        assertTrue(steps.take(4).all { it.surface == LessonSurface.EXPORT_SETUP })
        assertTrue(steps.drop(4).all { it.surface == LessonSurface.EXPORT_PREVIEW })
        assertTrue(steps.all { it.reveal == null })
    }

    @Test fun `regional route reveals independent settings before entering the currency picker`() {
        val steps = route("regional").steps
        assertEquals(listOf(
            LessonTarget.REGIONAL_LANGUAGE, LessonTarget.REGIONAL_COUNTRY, LessonTarget.REGIONAL_UNITS,
            LessonTarget.REGIONAL_CURRENCY, LessonTarget.CURRENCY_AUTO, LessonTarget.CURRENCY_MANUAL,
        ), steps.map { it.target })
        assertTrue(steps.take(4).all { it.surface == LessonSurface.REGIONAL })
        assertTrue(steps.drop(4).all { it.surface == LessonSurface.CURRENCY })
        assertEquals(listOf("appearance", "country", "units", "currency", null, null), steps.map { it.reveal })
    }

    @Test fun `each lesson target has a unique measured control in the production screens`() {
        val targets = lessons.flatMap { it.steps }.map { it.target }
        assertEquals(targets.size, targets.toSet().size)
        val sourceRoot = File(main, "java/io/github/snowdrit/mamokeep/ui")
        val constants = Regex("const val (\\w+) = \"([^\"]+)\"")
            .findAll(File(sourceRoot, "help/FeatureLessons.kt").readText())
            .associate { it.groupValues[2] to it.groupValues[1] }
        val screens = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile?.name != "help" }
            .joinToString("\n") { it.readText() }
        val measured = Regex("tourTarget\\(([^\\n]+)\\)").findAll(screens)
            .flatMap { match -> Regex("LessonTarget\\.(\\w+)").findAll(match.groupValues[1]).map { it.groupValues[1] } }
            .toMutableSet()
        // ReminderLevelFields receives its two anchors from the yellow level invocation.
        Regex("(\\w+Target) = LessonTarget\\.(\\w+)").findAll(screens).forEach { assignment ->
            val parameter = assignment.groupValues[1]
            assertTrue("Unmeasured forwarded anchor: $parameter", screens.contains("$parameter?.let { Modifier.tourTarget(it) }"))
            measured += assignment.groupValues[2]
        }
        for (target in targets) {
            val constant = constants[target]
            assertNotNull("Unknown lesson target: $target", constant)
            assertTrue("No measured lesson anchor: $target", constant in measured)
        }
        val scaffold = File(sourceRoot, "EditorScaffold.kt").readText()
        assertTrue("Save target must reach the toolbar control", scaffold.contains("modifier = saveModifier"))
    }

    @Test fun `parser rejects empty routes duplicate ids and duplicate step targets`() {
        val step = """{"target":"control","surface":"REMINDERS","title":"title","summary":"summary"}"""
        val lesson = """{"id":"one","title":"title","steps":[$step]}"""
        for (invalid in listOf(
            "[]", "[$lesson,$lesson]", """[{"id":"one","title":"title","steps":[]}]""",
            """[{"id":"one","title":"title","steps":[$step,$step]}]""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { LessonCatalog.parse(invalid) }
        }
        assertThrows(SerializationException::class.java) {
            LessonCatalog.parse("[$lesson]".replace("REMINDERS", "MISSING_SCREEN"))
        }
    }

    @Test fun `lessons remain opt in and back at the first step closes them`() {
        val state = state("reminders")
        assertFalse(state.active)
        assertNull(state.step)
        state.start()
        assertEquals(LessonTarget.REMINDER_TIME, state.step!!.target)
        state.previous()
        assertFalse(state.active)
        assertNull(state.step)
    }

    @Test fun `finishing closes the lesson and explicit replay starts at the first step`() {
        val state = state("regional")
        state.start()
        repeat(state.lesson.steps.lastIndex) { state.next() }
        assertEquals(LessonTarget.CURRENCY_MANUAL, state.step!!.target)
        state.next()
        assertFalse(state.active)
        assertNull(state.step)
        state.start()
        assertEquals(LessonTarget.REGIONAL_LANGUAGE, state.step!!.target)
    }

    @Test fun `restoring position preserves the export window and allows returning to setup`() {
        val original = state("export")
        original.start()
        repeat(4) { original.next() }
        val restored = LessonState(original.lesson, original.index)
        assertTrue(restored.active)
        assertEquals(LessonTarget.EXPORT_REVIEW, restored.step!!.target)
        assertEquals(LessonSurface.EXPORT_PREVIEW, restored.step!!.surface)
        restored.previous()
        assertEquals(LessonTarget.EXPORT_BUILD, restored.step!!.target)
        assertEquals(LessonSurface.EXPORT_SETUP, restored.step!!.surface)
        restored.close()
        assertFalse(LessonState(restored.lesson, restored.index).active)
    }

    @Test fun `missing export snapshot can return to preparation without resetting the route`() {
        val state = state("export", initialIndex = 5)
        state.show(LessonTarget.EXPORT_BUILD)
        assertEquals(3, state.index)
        state.next()
        assertEquals(LessonTarget.EXPORT_REVIEW, state.step!!.target)
        assertThrows(IllegalArgumentException::class.java) { state.show("missing_target") }
        assertEquals(LessonTarget.EXPORT_REVIEW, state.step!!.target)
    }
}
