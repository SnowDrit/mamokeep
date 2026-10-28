package io.github.snowdrit.mamokeep

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.github.snowdrit.mamokeep.data.RegionPresetSource
import io.github.snowdrit.mamokeep.data.ReminderSettingsStore
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.data.repo.GarageRepository
import io.github.snowdrit.mamokeep.domain.ItemTitles
import io.github.snowdrit.mamokeep.domain.RegionalCatalog
import io.github.snowdrit.mamokeep.ui.itemTitles as buildItemTitles
import kotlinx.coroutines.flow.first

/**
 * Ручная сборка зависимостей.
 *
 * DI-фреймворк на этом объёме — лишняя зависимость и лишняя магия. Понадобится —
 * перейдём, container уже задаёт границу.
 */
class AppContainer(private val context: Context) {
    val scannerModels by lazy { io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager(context) }
    private val database = MamoKeepDatabase.build(context)
    val garageDao = database.garageDao()

    /**
     * Пакеты регионов разбираются **один раз за запуск** и дальше живут здесь.
     * Раньше их читали четырьмя разными местами — контейнер, экран и два места в
     * `MamoKeepApp`, — то есть семь чтений файлов на каждый холодный старт.
     */
    val regionPresets = RegionPresetSource(context).presets()
    val regionalCatalog by lazy {
        context.assets.open("regional/countries.json").bufferedReader().use {
            RegionalCatalog.parse(it.readText())
        }
    }

    /**
     * Названия позиций подставляются на границе базы: дальше и экран, и уведомление,
     * и выгрузка получают их уже на языке приложения.
     *
     * **Язык проверяется при каждом обращении, а не запоминается при старте.** Смена
     * языка внутри приложения пересоздаёт экран, но не процесс, и `Resources` у
     * `Application` остаются со старой локалью — причём остаются и после
     * `onConfigurationChanged`, это проверено на живом телефоне 2026-08-22. Из-за них
     * интерфейс становился английским, а названия позиций оставались русскими.
     *
     * Поэтому ресурсы для названий строятся под текущую локаль приложения и
     * пересобираются, только когда локаль действительно сменилась.
     */
    private var titlesLocale: String? = null
    private var titles: ItemTitles = ItemTitles { _, stored -> stored }

    /** Обёртка стабильна: репозиторий получает её один раз и переживает смену языка. */
    val itemTitles = ItemTitles { key, stored -> titlesFor(currentLocales()).resolve(key, stored) }

    val reminderSettings = ReminderSettingsStore(context)
    private val settings = SettingsStore(context)
    val garageRepository = GarageRepository(database, garageDao, itemTitles, reminderSettings) {
        regionalCatalog.currencyCode(settings.regionalPreferences.first())
    }

    /**
     * Локаль приложения: сначала своя (её ставит и наш список, и системный экран),
     * а если своей нет — язык телефона.
     */
    private fun currentLocales(): LocaleList {
        val own = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales
        } else {
            LocaleList.getEmptyLocaleList()
        }
        return if (own.isEmpty) Resources.getSystem().configuration.locales else own
    }

    private fun titlesFor(locales: LocaleList): ItemTitles {
        val tag = locales.toLanguageTags()
        if (tag != titlesLocale) {
            val config = Configuration(context.resources.configuration).apply { setLocales(locales) }
            titles = buildItemTitles(context.createConfigurationContext(config).resources, regionPresets)
            titlesLocale = tag
        }
        return titles
    }
}
