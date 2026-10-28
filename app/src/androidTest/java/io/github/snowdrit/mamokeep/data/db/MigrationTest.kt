package io.github.snowdrit.mamokeep.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Миграции базы.
 *
 * С 2026-08-22 в `MamoKeepDatabase` нет `fallbackToDestructiveMigration`: забытая миграция
 * не стирает историю обслуживания молча, а роняет приложение. Это правильный обмен, но он
 * работает, только если забытую миграцию кто-то ловит **до** публикации. Ловят эти тесты.
 *
 * Тесты инструментальные, и это не выбор, а ограничение Room: у android-варианта
 * `MigrationTestHelper` каждый конструктор требует `Instrumentation`, а схемы читает из
 * ассетов тестового APK. Попытка обойтись юнит-тестами разобрана в `docs/STATUS.md`.
 *
 * Гонять так: `./gradlew :app:connectedDebugAndroidTest` при поднятом эмуляторе.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MamoKeepDatabase::class.java,
    )

    /**
     * Главный тест: база нижней поддерживаемой версии доезжает до текущей.
     *
     * Версия берётся из `@Database`, а не вписана числом, и в этом весь смысл. Следующий
     * заход поднимет версию до 11, забудет миграцию — и тест покраснеет **сам**, без единой
     * правки теста. Тест, который надо не забыть дописать, однажды забудут дописать.
     */
    @Test
    fun база_нижней_версии_доезжает_до_текущей() {
        helper.createDatabase(TEST_DB, FLOOR_VERSION).close()
        helper.runMigrationsAndValidate(TEST_DB, currentVersion, true, *MamoKeepDatabase.MIGRATIONS)
            .close()
    }

    @Test
    fun everySupportedOldVersionPreservesGarageHistoryAndCanonicalUnits() {
        for (version in FLOOR_VERSION until currentVersion) {
            val name = "migration-data-$version.db"
            helper.createDatabase(name, version).use { db ->
                db.execSQL("INSERT INTO cars (id, name, vin, bodyType, silhouetteColor) VALUES (1, 'Legacy', 'TESTVIN', 'suv', -12345)")
                db.execSQL("INSERT INTO cars (id, name) VALUES (2, 'Other car')")
                db.execSQL("INSERT INTO maintenance_items (id, carId, kind, title, iconKey, intervalKm, intervalMonths, lastDoneKm, lastDoneDate, warnKm, warnDays) VALUES (3, 1, 'MAINTENANCE', 'Oil', 'oil', 10000, 12, 23000, $FUEL_DAY, 500, 14)")
                db.execSQL("INSERT INTO maintenance_records (id, carId, itemId, date, km, note) VALUES (4, 1, 3, $FUEL_DAY, 23000, 'Preserved note')")
                val amountColumn = if (version == 7) "liters" else "amount"
                db.execSQL("INSERT INTO fuel_entries (id, carId, date, odometerKm, $amountColumn, costKopecks, isFull) VALUES (5, 1, $FUEL_DAY, 23345, 42.25, 123456, 1)")
                db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (1, $FUEL_DAY, 23345)")
                db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (2, $FUEL_DAY, 23345)")
                db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (1, $OTHER_DAY, 24000)")
            }
            helper.runMigrationsAndValidate(name, currentVersion, true, *MamoKeepDatabase.MIGRATIONS).use { db ->
                db.query("SELECT name, vin, bodyType, silhouetteColor FROM cars WHERE id = 1").use { rows ->
                    rows.moveToFirst()
                    assertEquals("Legacy", rows.getString(0))
                    assertEquals("TESTVIN", rows.getString(1))
                    assertEquals("suv", rows.getString(2))
                    assertEquals(-12345, rows.getInt(3))
                }
                db.query("SELECT carId, itemId, date, km, note FROM maintenance_records WHERE id = 4").use { rows ->
                    rows.moveToFirst()
                    assertEquals(1L, rows.getLong(0))
                    assertEquals(3L, rows.getLong(1))
                    assertEquals(FUEL_DAY, rows.getInt(2))
                    assertEquals(23000, rows.getInt(3))
                    assertEquals("Preserved note", rows.getString(4))
                }
                db.query("SELECT amount, fuelType, costKopecks FROM fuel_entries WHERE id = 5").use { rows ->
                    rows.moveToFirst()
                    assertEquals(42.25, rows.getDouble(0), 0.0)
                    assertEquals("PETROL", rows.getString(1))
                    assertEquals(123456L, rows.getLong(2))
                }
                db.query("SELECT carId, km FROM odometer_readings ORDER BY carId").use { rows ->
                    assertEquals(2, rows.count)
                    rows.moveToFirst()
                    assertEquals(1L, rows.getLong(0))
                    assertEquals(24000, rows.getInt(1))
                    rows.moveToNext()
                    assertEquals(2L, rows.getLong(0))
                    assertEquals(23345, rows.getInt(1))
                }
                db.query("PRAGMA foreign_key_check").use { rows -> assertEquals(0, rows.count) }
            }
        }
    }

    /**
     * Миграция 9 → 10 — единственная рукописная, и проверкой схемы она не проверяется
     * никак: схему она не трогает вовсе, это чистый `DELETE`. `runMigrationsAndValidate`
     * пропустил бы её, даже будь там `DELETE FROM odometer_readings` без условий.
     *
     * Заодно тест закрепляет **намеренную** потерю: показание, вбитое руками в тот же день
     * с тем же числом, что и заправка, удаляется сознательно — это и есть та самая
     * заправка. Без теста следующий заход прочтёт это как ошибку и «починит».
     */
    @Test
    fun миграция_9_10_убирает_дубль_заправки_и_не_трогает_остальное() {
        helper.createDatabase(TEST_DB, 9).use { db ->
            db.execSQL("INSERT INTO cars (id, name) VALUES (1, 'Моя машина')")
            db.execSQL(
                "INSERT INTO fuel_entries " +
                    "(carId, date, odometerKm, amount, fuelType, costKopecks, isFull) " +
                    "VALUES (1, $FUEL_DAY, 23345, 45.0, 'PETROL', 350000, 1)"
            )
            // Дубль, накопленный прежними сборками: та же машина, тот же день, то же число.
            db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (1, $FUEL_DAY, 23345)")
            // Показание за другой день — своё, руками записанное, трогать его нельзя.
            db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (1, $OTHER_DAY, 24000)")
            // Тот же день, но другой пробег: человек доехал и записал ещё раз. Не дубль.
            db.execSQL("INSERT INTO odometer_readings (carId, date, km) VALUES (1, $FUEL_DAY, 23400)")
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            10,
            true,
            MamoKeepDatabase.DROP_DUPLICATE_READINGS,
        ).use { db ->
            db.query("SELECT date, km FROM odometer_readings ORDER BY date, km").use { rows ->
                assertEquals("остаться должны ровно два показания", 2, rows.count)

                rows.moveToNext()
                assertEquals(FUEL_DAY, rows.getInt(0))
                assertEquals("показание того же дня с другим пробегом — не дубль", 23400, rows.getInt(1))

                rows.moveToNext()
                assertEquals(OTHER_DAY, rows.getInt(0))
                assertEquals(24000, rows.getInt(1))
            }

            // Сама заправка на месте: миграция чистит показания, а не историю заправок.
            db.query("SELECT COUNT(*) FROM fuel_entries").use { rows ->
                rows.moveToNext()
                assertEquals(1, rows.getInt(0))
            }
        }
    }

    /**
     * Схему могли не выгрузить — тогда предыдущие тесты сверяют вчерашнюю форму и
     * радостно зеленеют. Один этот `assertEquals` ловит такое состояние, а заодно
     * обратное: схему выгрузили, а версию потом откатили, и в репозитории остался
     * файл от версии, которой больше нет.
     */
    @Test
    fun выгруженная_схема_не_отстаёт_от_версии_базы() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val exported = assets.list(MamoKeepDatabase::class.java.canonicalName!!)
            .orEmpty()
            .mapNotNull { it.removeSuffix(".json").toIntOrNull() }
            .maxOrNull()

        assertEquals(
            "версия базы и выгруженные схемы разошлись: соберите проект заново, " +
                "а лишний файл в app/schemas удалите",
            currentVersion,
            exported,
        )
    }

    /**
     * Текущая версия базы — у самого Room, а не из аннотации: у `@Database` retention
     * `CLASS`, рефлексией её не прочесть (проверено, возвращает `null`).
     *
     * База в памяти, а не настоящая: спрашиваем версию, а не трогаем чужие данные.
     */
    private val currentVersion: Int by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val probe = Room.inMemoryDatabaseBuilder(context, MamoKeepDatabase::class.java).build()
        try {
            probe.openHelper.readableDatabase.version
        } finally {
            probe.close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"

        /**
         * Ниже 7 миграций нет вовсе — эти версии существовали только до публикации, и
         * такие базы `fallbackToDestructiveMigrationFrom` заводит заново.
         * См. docs/DECISIONS.md, решение 26.
         */
        const val FLOOR_VERSION = 7

        /** Дни от эпохи; конкретные числа не важны, важно, что дни разные. */
        const val FUEL_DAY = 20_600
        const val OTHER_DAY = 20_610
    }
}
