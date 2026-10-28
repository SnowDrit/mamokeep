package io.github.snowdrit.mamokeep.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Country(val code: String, val currencyCode: String)

/** Country and currency are independent of language, units and document templates. */
data class RegionalPreferences(
    val countryCode: String,
    /** Null follows the country; an explicit ISO 4217 code survives country changes. */
    val currencyOverride: String? = null,
)

class RegionalCatalog(val countries: List<Country>) {
    private val currenciesByCountry = countries.associate { it.code to it.currencyCode }
    val currencyCodes: List<String> = countries.map { it.currencyCode }.distinct().sorted()

    fun countryCurrency(countryCode: String): String? = currenciesByCountry[countryCode]

    fun currencyCode(preferences: RegionalPreferences): String? =
        preferences.currencyOverride ?: countryCurrency(preferences.countryCode)

    companion object {
        fun parse(content: String): RegionalCatalog = RegionalCatalog(Json.decodeFromString(content))
    }
}
