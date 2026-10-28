package io.github.snowdrit.mamokeep.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RenameColumn
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CarEntity::class,
        OdometerReadingEntity::class,
        MaintenanceItemEntity::class,
        MaintenanceRecordEntity::class,
        FuelEntryEntity::class,
    ],
    version = 10,
    exportSchema = true,
    // Переход 7 → 8 Room пишет сам: обе новые колонки с умолчанием, а переименование
    // описано спецификацией ниже. Данные при обновлении переживают правку схемы —
    // на телефоне владельца уже лежит история, и стирать её незачем.
    autoMigrations = [
        AutoMigration(from = 7, to = 8, spec = MamoKeepDatabase.FuelAmountRename::class),
        // 9: у позиции появился ключ названия — чтобы её можно было показать на языке
        // приложения, а не на том, на котором её однажды завели.
        AutoMigration(from = 8, to = 9),
        // 10 схему не меняет: это чистка данных, см. DROP_DUPLICATE_READINGS.
    ],
)
@TypeConverters(Converters::class)
abstract class MamoKeepDatabase : RoomDatabase() {

    abstract fun garageDao(): GarageDao

    /** `liters` стало `amount`: в колонке теперь бывают и кубометры, и киловатт-часы. */
    @RenameColumn(tableName = "fuel_entries", fromColumnName = "liters", toColumnName = "amount")
    class FuelAmountRename : AutoMigrationSpec

    companion object {

        /** A committed garage must survive a device crash, not just an app restart. */
        internal val DURABLE_WRITES = object : Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                // WAL + NORMAL can lose a recent commit after power loss while the
                // separately synced garage_initialized preference already survives.
                if (db.isExecPerConnectionSQLSupported) {
                    db.execPerConnectionSQL("PRAGMA synchronous = FULL", null)
                } else {
                    // API 26–29: configure the primary writer before Room exposes it.
                    // This helper does not enable idle connection closing or change
                    // WAL mode later; a full reopen runs this callback again.
                    db.execSQL("PRAGMA synchronous = FULL")
                }
            }
        }

        /**
         * Схему не трогает, чистит данные.
         *
         * Прежние сборки на каждую заправку писали ещё и отдельную строку показания
         * одометра. Теперь заправка хранит свой одометр сама, и такая строка стала
         * дублем: в базе, заведённой до этой версии, каждая заправка лежит дважды.
         *
         * Удаляем только точные совпадения — та же машина, та же дата, то же число.
         * Показание, записанное руками в тот же день с тем же пробегом, — это и есть
         * та самая заправка, потери нет.
         */
        /**
         * Все рукописные миграции одним списком.
         *
         * Список, а не перечисление на месте: по нему же ходит тест миграций, и разойтись
         * они не могут в принципе. Забыть добавить миграцию в тест теперь нельзя — её
         * можно забыть только написать, а это тест и ловит.
         */
        val MIGRATIONS: Array<Migration> get() = arrayOf(DROP_DUPLICATE_READINGS)

        val DROP_DUPLICATE_READINGS = Migration(9, 10) { db: SupportSQLiteDatabase ->
            db.execSQL(
                """
                DELETE FROM odometer_readings
                WHERE EXISTS (
                    SELECT 1 FROM fuel_entries f
                    WHERE f.carId = odometer_readings.carId
                      AND f.date = odometer_readings.date
                      AND f.odometerKm = odometer_readings.km
                )
                """.trimIndent()
            )
        }

        fun build(context: Context): MamoKeepDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MamoKeepDatabase::class.java,
                "mamokeep.db",
            )
                // `fallbackToDestructiveMigration` здесь больше нет, и это не забывчивость.
                //
                // Она молча стирала базу, если для правки схемы забыли написать миграцию.
                // До публикации это была страховка, после — способ потерять историю
                // обслуживания, то есть худшее, что это приложение может сделать человеку.
                //
                // Цена решения: любая правка схемы без миграции теперь роняет приложение
                // на существующей установке вместо тихой потери данных. Так и задумано —
                // падение видно сразу, а пропажа истории обнаружится через год.
                .addMigrations(*MIGRATIONS)
                //
                // Версии базы с 1 по 6 существовали только до публикации, и пути миграции
                // с них не существует вовсе — есть только 7 → 8, 8 → 9 и 9 → 10. Без этой
                // строки такая база роняет приложение навсегда, с ней — заводится заново.
                //
                // Это **перечисление**, а не общее правило, и разница принципиальная:
                // прежний `fallbackToDestructiveMigration` стирал бы и базу версии 15
                // через год после релиза, а список из шести чисел не тронет ничего, кроме
                // этих шести. Страховка остаётся снятой ровно там, где её сняли.
                // См. docs/DECISIONS.md, решение 26.
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1, 2, 3, 4, 5, 6)
                .addCallback(DURABLE_WRITES)
                .build()
    }
}
