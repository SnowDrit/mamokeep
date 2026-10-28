package io.github.snowdrit.mamokeep.ui

import android.app.LocaleManager
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.github.snowdrit.mamokeep.R
import org.xmlpull.v1.XmlPullParser
import java.util.Locale

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/** Same bundled language list as the Android system picker. Invalid resources must fail visibly. */
fun appLanguages(resources: Resources): List<String> = buildList {
    resources.getXml(R.xml.locales_config).use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                add(requireNotNull(parser.getAttributeValue(ANDROID_NS, "name")))
            }
        }
    }
}

/** Keep native names recognizable even after choosing an unfamiliar language. */
private fun languageName(locale: Locale): String =
    locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }

/** LocaleManager remains the single source of truth, including scripts such as zh-Hans. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
fun LanguagePicker() {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) }
    val resources = LocalResources.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val options = remember(resources, displayLocale) {
        appLanguages(resources).map { tag ->
            val locale = Locale.forLanguageTag(tag)
            SettingsOption(tag, languageName(locale), locale.getDisplayName(displayLocale))
        }
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val current = manager.applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag().orEmpty()
    val systemTitle = stringResource(R.string.more_language_system)
    val title = stringResource(R.string.more_language)
    SettingsChoiceRow(
        title = title,
        value = if (current.isEmpty()) systemTitle else languageName(Locale.forLanguageTag(current)),
        onClick = { open = true },
    )
    if (open) {
        SettingsSelectionDialog(
            title = title,
            options = listOf(SettingsOption("", systemTitle)) + options,
            selected = current,
            onSelect = { manager.applicationLocales = LocaleList.forLanguageTags(it) },
            onDismiss = { open = false },
        )
    }
}
