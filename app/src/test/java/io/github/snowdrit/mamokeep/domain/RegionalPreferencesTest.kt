package io.github.snowdrit.mamokeep.domain

import java.io.File
import java.util.Currency
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class RegionalPreferencesTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val catalog = RegionalCatalog.parse(File(main, "assets/regional/countries.json").readText())

    @Test fun `automatic currency follows country without requiring a document template`() {
        assertEquals("KZT", catalog.currencyCode(RegionalPreferences("kz")))
        assertEquals("JPY", catalog.currencyCode(RegionalPreferences("jp")))
        assertEquals("EUR", catalog.currencyCode(RegionalPreferences("de")))
    }

    @Test fun `manual currency survives a country change and can return to automatic`() {
        val manual = RegionalPreferences("ru", "USD").copy(countryCode = "jp")
        assertEquals("USD", catalog.currencyCode(manual))
        assertEquals("JPY", catalog.currencyCode(manual.copy(currencyOverride = null)))
    }

    @Test fun `unsupported country never silently becomes rubles but permits manual currency`() {
        assertNull(catalog.currencyCode(RegionalPreferences("")))
        assertNull(catalog.currencyCode(RegionalPreferences("xx")))
        assertEquals("EUR", catalog.currencyCode(RegionalPreferences("xx", "EUR")))
    }

    @Test fun `catalog has valid distinct ISO countries and supported currency codes`() {
        val isoCountries = Locale.getISOCountries().map { it.lowercase(Locale.ROOT) }.toSet()
        assertEquals(catalog.countries.size, catalog.countries.map { it.code }.toSet().size)
        catalog.countries.forEach {
            assertTrue("Country ${it.code}", it.code in isoCountries)
            assertEquals(it.currencyCode, Currency.getInstance(it.currencyCode).currencyCode)
        }
        assertTrue(catalog.countries.map { it.code }.containsAll(
            listOf("ua", "by", "kz", "uz", "de", "fr", "es", "pl", "tr", "cn", "jp", "kr"),
        ))
        // Android 8-era currency tables still say BGN; the bundled data must be current.
        assertEquals("EUR", catalog.countryCurrency("bg"))
    }
}
