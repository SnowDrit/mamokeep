package io.github.snowdrit.mamokeep.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegionPresetTest {

    @Test
    fun `разбирает пакет региона`() {
        val preset = RegionPresets.parse(
            """
            {
              "code": "ru",
              "lang": "ru",
              "name": "Россия",
              "documents": [
                {
                  "key": "ru_osago",
                  "titles": {"ru": "ОСАГО", "en": "OSAGO"},
                  "months": 12,
                  "warnDays": 30,
                  "notes": {"ru": "Ровно год по закону."}
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("ru", preset.code)
        assertEquals(1, preset.documents.size)
        assertEquals("ru_osago", preset.documents.first().key)
        assertEquals(12, preset.documents.first().months)
    }

    @Test
    fun `название берётся на языке интерфейса`() {
        val document = document()

        assertEquals("ОСАГО", document.title("ru", "ru"))
        assertEquals("OSAGO", document.title("en", "ru"))
    }

    @Test
    fun `для языка без перевода берётся язык пакета`() {
        // Немец, поставивший русский пакет, увидит «ОСАГО», а не пустую строку.
        assertEquals("ОСАГО", document().title("de", "ru"))
    }

    @Test
    fun `пояснение тоже по языкам и необязательно`() {
        assertEquals("Ровно год по закону.", document().note("ru", "ru"))
        assertNull(document().copy(notes = emptyMap()).note("ru", "ru"))
    }

    @Test
    fun `язык пакета по умолчанию совпадает с кодом страны`() {
        val preset = RegionPresets.parse("""{"code": "xx", "name": "Test"}""")

        assertEquals("xx", preset.lang)
    }

    @Test
    fun `порог предупреждения необязателен`() {
        val preset = RegionPresets.parse(
            """{"code": "xx", "name": "Test", "documents": [{"key": "d", "titles": {"xx": "Doc"}, "months": 6}]}"""
        )

        assertEquals(30, preset.documents.first().warnDays)
    }

    @Test
    fun `пакет без документов допустим`() {
        val preset = RegionPresets.parse("""{"code": "xx", "name": "Test"}""")

        assertTrue(preset.documents.isEmpty())
    }

    @Test
    fun `незнакомые поля не ломают разбор`() {
        // Пакет мог прийти из более новой версии формата — падать на этом нельзя.
        val preset = RegionPresets.parse(
            """{"code": "xx", "name": "Test", "somethingNew": 1, "documents": []}"""
        )

        assertEquals("xx", preset.code)
    }

    private fun document() = DocumentPreset(
        key = "ru_osago",
        titles = mapOf("ru" to "ОСАГО", "en" to "OSAGO"),
        months = 12,
        notes = mapOf("ru" to "Ровно год по закону."),
    )
}
