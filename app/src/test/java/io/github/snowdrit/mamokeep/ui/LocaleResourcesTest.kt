package io.github.snowdrit.mamokeep.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

/** A language advertised by Android must include the entire interface and safe format arguments. */
class LocaleResourcesTest {
    private val res = File("src/main/res").takeIf { it.exists() } ?: File("app/src/main/res")
    private val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val placeholders = Regex("%(?:\\d+\\$)?[-#+ 0,(<]*\\d*(?:\\.\\d+)?[a-zA-Z%]")

    private fun arguments(text: String) = placeholders.findAll(text)
        .map { it.value }.filter { it != "%%" }.sorted().toList()

    private fun entries(directory: File): Map<String, Element> = buildMap {
        directory.listFiles { file -> file.extension == "xml" }.orEmpty().forEach { file ->
            val children = xml.parse(file).documentElement.childNodes
            for (index in 0 until children.length) {
                val entry = children.item(index) as? Element ?: continue
                if (entry.tagName !in setOf("string", "string-array", "plurals")) continue
                if (entry.getAttribute("translatable") == "false") continue
                if (entry.tagName == "string-array") {
                    val items = entry.getElementsByTagName("item")
                    if ((0 until items.length).all { items.item(it).textContent.startsWith("@string/") }) continue
                }
                val name = entry.getAttribute("name")
                assertNull("Duplicate $directory/$name", put(name, entry))
            }
        }
    }

    @Test fun `every declared locale has all text and preserves format arguments`() {
        val baseline = entries(File(res, "values"))
        val locales = xml.parse(File(res, "xml/locales_config.xml")).getElementsByTagName("locale")
        for (index in 0 until locales.length) {
            val tag = (locales.item(index) as Element).getAttribute("android:name")
            if (tag == "en") continue
            val folder = if (tag.contains('-')) "values-b+${tag.replace('-', '+')}" else "values-$tag"
            val translated = entries(File(res, folder))
            assertEquals("Keys for $tag", baseline.keys, translated.keys)
            baseline.forEach { (key, original) ->
                val actual = translated.getValue(key)
                assertEquals("Type $tag/$key", original.tagName, actual.tagName)
                assertTrue("Empty $tag/$key", actual.textContent.isNotEmpty())
                when (original.tagName) {
                    "string" -> assertEquals("Arguments $tag/$key", arguments(original.textContent), arguments(actual.textContent))
                    "string-array" -> {
                        val before = original.getElementsByTagName("item")
                        val after = actual.getElementsByTagName("item")
                        assertEquals("Array size $tag/$key", before.length, after.length)
                        for (i in 0 until before.length) {
                            assertEquals("Array args $tag/$key/$i", arguments(before.item(i).textContent), arguments(after.item(i).textContent))
                        }
                    }
                    "plurals" -> {
                        val before = original.getElementsByTagName("item")
                        val quantities = (0 until before.length).associate {
                            val item = before.item(it) as Element
                            item.getAttribute("quantity") to arguments(item.textContent)
                        }
                        val after = actual.getElementsByTagName("item")
                        val forms = (0 until after.length).map { after.item(it) as Element }
                        assertTrue("Other plural $tag/$key", forms.any { it.getAttribute("quantity") == "other" })
                        forms.forEach { item ->
                            val quantity = item.getAttribute("quantity")
                            assertEquals("Plural args $tag/$key/$quantity", quantities[quantity] ?: quantities.getValue("other"), arguments(item.textContent))
                        }
                    }
                }
            }
        }
    }
}
