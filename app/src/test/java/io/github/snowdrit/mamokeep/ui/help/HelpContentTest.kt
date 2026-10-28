package io.github.snowdrit.mamokeep.ui.help

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Asset references must survive edits/translations; a typo otherwise crashes first launch. */
class HelpContentTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val topics = Json.parseToJsonElement(File(main, "assets/help/topics.json").readText()).jsonArray
    private fun strings(folder: String): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(main, "res/$folder/help.xml")).getElementsByTagName("string")
        return (0 until nodes.length).associate { index ->
            val node = nodes.item(index)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }

    // LocaleResourcesTest checks completeness and format arguments for all declared languages.
    @Test fun `все темы ссылаются на непустые строки справки`() {
        val content = strings("values")
        for (topic in topics) for (field in listOf("title", "summary", "detail")) {
            val key = topic.jsonObject.getValue(field).jsonPrimitive.content
            assertTrue("Missing help resource: $key", !content[key].isNullOrBlank())
        }
    }

    @Test fun `первый запуск показывает короткий маршрут а остальные темы доступны отдельно`() {
        val tour = topics.filter { it.jsonObject["showInTour"]?.jsonPrimitive?.boolean == true }
        assertTrue(tour.size in 1..7)
        assertEquals(HelpTarget.HELP, tour.last().jsonObject.getValue("id").jsonPrimitive.content)
        assertTrue(tour.any { it.jsonObject.getValue("id").jsonPrimitive.content == HelpTarget.REMINDERS })
        val pages = tour.dropLast(1).map { HelpPage.valueOf(it.jsonObject.getValue("page").jsonPrimitive.content).ordinal }
        assertEquals(pages.sorted(), pages)
        assertEquals(HelpPage.entries.map { it.ordinal }.toSet(), pages.toSet())
        val articles = topics.filter { it.jsonObject["showInHelp"]?.jsonPrimitive?.boolean != false }
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        assertTrue(articles.containsAll(setOf(HelpTarget.REMINDERS, HelpTarget.EXPORT, HelpTarget.CURRENCY)))
    }

    @Test fun `каждая тема ведёт к существующей зоне экрана без повторов`() {
        val ids = topics.map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        val anchorSource = File(main, "java/io/github/snowdrit/mamokeep/ui/help/HelpCatalog.kt").readText()
        val constants = Regex("const val (\\w+) = \"([^\"]+)\"")
            .findAll(anchorSource).associate { it.groupValues[2] to it.groupValues[1] }
        val screens = File(main, "java/io/github/snowdrit/mamokeep/ui").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.parentFile?.name != "help" }
            .joinToString("\n") { it.readText() }
        for (id in ids) {
            val constant = constants[id]
            assertNotNull("Unknown target: $id", constant)
            assertTrue("No measured anchor: $id", screens.contains("tourTarget(HelpTarget.$constant)"))
        }
    }

    @Test fun `сценарий идёт по вкладкам последовательно`() {
        assertEquals(HelpTarget.HELP, topics.last().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(HelpPage.CAR.name, topics.last().jsonObject.getValue("page").jsonPrimitive.content)
        assertEquals(HelpTarget.SCANNER, topics.dropLast(1).last().jsonObject.getValue("id").jsonPrimitive.content)
        val pages = topics.dropLast(1).map { HelpPage.valueOf(it.jsonObject.getValue("page").jsonPrimitive.content).ordinal }
        assertEquals(pages.sorted(), pages)
        assertEquals(HelpPage.entries.map { it.ordinal }.toSet(), pages.toSet())
    }
    @Test fun `рекомендации ведут к существующим статьям справки`() {
        val articles = topics.filter { it.jsonObject["showInHelp"]?.jsonPrimitive?.boolean != false }
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
        val recommendations = topics.flatMap { it.jsonObject["recommended"]?.jsonArray.orEmpty() }
            .map { it.jsonPrimitive.content }
        assertTrue(recommendations.isNotEmpty())
        assertTrue(articles.containsAll(recommendations))
        val finalRecommendations = topics.last().jsonObject.getValue("recommended").jsonArray
            .map { it.jsonPrimitive.content }.toSet()
        assertTrue(finalRecommendations.containsAll(setOf(HelpTarget.FUEL, HelpTarget.MAINTENANCE, HelpTarget.EXPORT)))
    }

    @Test fun `статьи новых возможностей открывают подходящее пошаговое обучение`() {
        val lessons = LessonCatalog.parse(File(main, "assets/help/lessons.json").readText()).map { it.id }.toSet()
        val linked = topics.mapNotNull { topic ->
            val lesson = topic.jsonObject["lesson"]?.jsonPrimitive?.content ?: return@mapNotNull null
            assertTrue("Unknown lesson: $lesson", lesson in lessons)
            assertTrue("Lesson entry must be visible in help", topic.jsonObject["showInHelp"]?.jsonPrimitive?.boolean != false)
            topic.jsonObject.getValue("id").jsonPrimitive.content to lesson
        }.toMap()
        assertEquals(lessons, linked.values.toSet())
        assertEquals("reminders", linked[HelpTarget.REMINDERS])
        assertEquals("export", linked[HelpTarget.EXPORT])
        for (target in listOf(HelpTarget.APPEARANCE, HelpTarget.COUNTRY, HelpTarget.UNITS, HelpTarget.CURRENCY)) {
            assertEquals("regional", linked[target])
        }
    }

}
