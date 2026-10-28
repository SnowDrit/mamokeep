package io.github.snowdrit.mamokeep.data

import android.content.Context
import android.content.res.Resources
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.RegionalPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

/** Как выбирать тему. «Как на телефоне» — значение по умолчанию. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

/**
 * Настройки приложения.
 *
 * Живут на устройстве, как и всё остальное: сервера нет (docs/DECISIONS.md, решение 7).
 */
class SettingsStore internal constructor(private val preferences: DataStore<Preferences>) {
    constructor(context: Context) : this(context.dataStore)

    private val garageInitializedKey = booleanPreferencesKey("garage_initialized")
    val garageInitialized: Flow<Boolean> = preferences.data
        .map { it[garageInitializedKey] ?: false }.distinctUntilChanged()

    /** Mark only after successful seeding; an interrupted first launch can retry. */
    suspend fun initializeGarageIfNeeded(initialize: suspend () -> Unit) {
        preferences.edit { prefs ->
            if (prefs[garageInitializedKey] != true) {
                initialize()
                prefs[garageInitializedKey] = true
            }
        }
    }

    /** An intentionally empty restored garage must remain empty on the next launch. */
    suspend fun markGarageInitialized() {
        preferences.edit { it[garageInitializedKey] = true }
    }


    private val tourHandledKey = booleanPreferencesKey("intro_tour_handled")
    val tourHandled: Flow<Boolean> = preferences.data.map { it[tourHandledKey] ?: false }

    suspend fun finishTour() {
        preferences.edit { it[tourHandledKey] = true }
    }

    private val notificationPermissionRequestedKey = booleanPreferencesKey("notification_permission_requested")

    /** Claim before launching so recreation or overlapping effects cannot repeat a denied prompt. */
    suspend fun claimNotificationPermissionRequest(): Boolean {
        var claimed = false
        preferences.edit { prefs ->
            if (prefs[notificationPermissionRequestedKey] != true) {
                prefs[notificationPermissionRequestedKey] = true
                claimed = true
            }
        }
        return claimed
    }

    private val themeKey = stringPreferencesKey("theme_mode")
    private val backupFolderKey = stringPreferencesKey("backup_folder")
    private val unitsKey = stringPreferencesKey("unit_system")
    private val carKey = longPreferencesKey("selected_car")
    private val regionKey = stringPreferencesKey("region")
    private val currencyKey = stringPreferencesKey("currency_override")

    private val phoneCountry: String
        get() = Resources.getSystem().configuration.locales[0].country.lowercase(Locale.ROOT)

    val defaultRegionalPreferences: RegionalPreferences
        get() = RegionalPreferences(phoneCountry)

    val themeMode: Flow<ThemeMode> = preferences.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        preferences.edit { it[themeKey] = mode.name }
    }

    /**
     * Выбранная машина. `0` — «ещё не выбирали»: тогда открывается первая по счёту.
     *
     * Хранится, а не вычисляется: человек с двумя машинами открывает приложение ради
     * той, которой пользуется, и каждый раз переключаться заново — работа на пустом месте.
     */
    val selectedCarId: Flow<Long> = preferences.data.map { it[carKey] ?: 0L }.distinctUntilChanged()

    suspend fun setSelectedCarId(carId: Long) {
        preferences.edit { it[carKey] = carId }
    }

    /**
     * Набор единиц измерения.
     *
     * Пока человек не выбрал сам — по стране телефона. Это не догадка ради удобства:
     * база наполняется при первом запуске, и без страны имперские интервалы обслуживания
     * не применились бы никогда (см. [UnitSystem.forCountry]).
     */
    val unitSystem: Flow<UnitSystem> = preferences.data.map { prefs ->
        val stored = prefs[unitsKey]
        if (stored == null) {
            UnitSystem.forCountry(phoneCountry)
        } else {
            runCatching { UnitSystem.valueOf(stored) }.getOrDefault(UnitSystem.METRIC)
        }
    }

    suspend fun setUnitSystem(system: UnitSystem) {
        preferences.edit { it[unitsKey] = system.name }
    }

    /** One snapshot keeps the automatic currency in sync with its country. */
    val regionalPreferences: Flow<RegionalPreferences> = preferences.data.map { prefs ->
        RegionalPreferences(prefs[regionKey] ?: phoneCountry, prefs[currencyKey])
    }

    val region: Flow<String> = regionalPreferences.map { it.countryCode }

    suspend fun setCurrency(code: String?) {
        preferences.edit { prefs ->
            if (code == null) prefs.remove(currencyKey) else prefs[currencyKey] = code
        }
    }

    suspend fun setRegion(code: String) {
        preferences.edit { it[regionKey] = code }
    }

    /**
     * Папка для автоматической копии. Пусто — автокопия выключена.
     *
     * Храним строку `content://`-адреса дерева: доступ к нему выдаётся системой один раз
     * и удерживается через `takePersistableUriPermission`.
     */
    val backupFolder: Flow<String?> = preferences.data.map { it[backupFolderKey] }

    /**
     * Запоминает то, что подсказала страна телефона, — один раз, при первом запуске.
     *
     * Без этого набор единиц и страна пересчитывались бы из локали каждый раз, и человек,
     * переключивший **язык** приложения на английский, молча получил бы мили и доллары.
     * Язык и единицы — разные вещи, и менять одно вслед за другим приложение не должно.
     */
    suspend fun rememberDefaults() {
        preferences.edit { prefs ->
            if (prefs[unitsKey] == null) {
                prefs[unitsKey] = UnitSystem.forCountry(phoneCountry).name
            }
            if (prefs[regionKey] == null) {
                prefs[regionKey] = phoneCountry
            }
        }
    }

    suspend fun setBackupFolder(uri: String?) {
        preferences.edit { prefs ->
            if (uri == null) prefs.remove(backupFolderKey) else prefs[backupFolderKey] = uri
        }
    }
}
