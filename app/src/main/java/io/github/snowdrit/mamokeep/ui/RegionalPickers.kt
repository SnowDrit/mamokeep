package io.github.snowdrit.mamokeep.ui

import android.content.res.Resources
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.ui.help.LessonState
import io.github.snowdrit.mamokeep.ui.help.LessonSurface
import io.github.snowdrit.mamokeep.domain.RegionalCatalog
import io.github.snowdrit.mamokeep.domain.RegionalPreferences
import java.text.Collator
import java.util.Currency
import java.util.Locale

fun currencySymbol(resources: Resources, code: String?): String =
    code?.let { Currency.getInstance(it).getSymbol(resources.configuration.locales[0]) }
        ?: resources.getString(R.string.currency_unset_symbol)

@Composable
fun countryTitle(code: String): String = if (code.isEmpty()) {
    stringResource(R.string.settings_country_unset)
} else {
    Locale.Builder().setRegion(code).build().getDisplayCountry(LocalConfiguration.current.locales[0])
}

@Composable
fun CountryPicker(code: String, catalog: RegionalCatalog, onSelect: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val options = remember(catalog, locale) {
        val collator = Collator.getInstance(locale)
        catalog.countries.map {
            SettingsOption(
                it.code,
                Locale.Builder().setRegion(it.code).build().getDisplayCountry(locale),
                resources.getString(R.string.settings_currency_code_symbol, it.code.uppercase(Locale.ROOT), it.currencyCode),
            )
        }.sortedWith { a, b -> collator.compare(a.title, b.title) }
    }
    val title = stringResource(R.string.more_country)
    SettingsChoiceRow(title, countryTitle(code)) { open = true }
    if (open) {
        SettingsSelectionDialog(title, options, code, onSelect, { open = false })
    }
}

@Composable
fun currencySummary(preferences: RegionalPreferences, catalog: RegionalCatalog): String {
    val code = catalog.currencyCode(preferences)
        ?: return stringResource(R.string.settings_currency_choose_country)
    return stringResource(
        if (preferences.currencyOverride == null) R.string.settings_currency_auto else R.string.settings_currency_manual,
        code,
    )
}

@Composable
fun CurrencyPicker(preferences: RegionalPreferences, catalog: RegionalCatalog, onSelect: (String?) -> Unit, lesson: LessonState? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val options = remember(catalog, locale) {
        catalog.currencyCodes.map { code ->
            SettingsOption(
                code,
                resources.getString(R.string.settings_currency_code_symbol, code, currencySymbol(resources, code)),
                Currency.getInstance(code).getDisplayName(locale),
            )
        }
    }
    val learningCurrency = lesson?.step?.surface == LessonSurface.CURRENCY
    LaunchedEffect(lesson?.step?.surface) {
        if (lesson?.active == true) open = learningCurrency
    }
    val title = stringResource(R.string.settings_currency)
    MoreRow(title, currencySummary(preferences, catalog)) { open = true }
    if ((open && lesson?.active != true) || learningCurrency) {
        val automatic = SettingsOption(
            "",
            stringResource(R.string.settings_currency_by_country),
            catalog.countryCurrency(preferences.countryCode) ?: stringResource(R.string.settings_currency_choose_country),
        )
        SettingsSelectionDialog(
            title = title,
            options = listOf(automatic) + options,
            selected = preferences.currencyOverride.orEmpty(),
            onSelect = { onSelect(it.takeIf(String::isNotEmpty)) },
            onDismiss = { if (lesson?.active == true) lesson.previous() else open = false },
            lesson = lesson,
            hint = stringResource(R.string.settings_currency_hint),
        )
    }
}
