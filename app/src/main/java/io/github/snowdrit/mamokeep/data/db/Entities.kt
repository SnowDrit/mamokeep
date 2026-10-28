package io.github.snowdrit.mamokeep.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.ItemKind
import java.time.LocalDate

/**
 * Автомобиль.
 *
 * `carId` протаскивается во все дочерние таблицы с первого дня, хотя UI пока работает
 * с одной машиной. Добавить селектор в готовое приложение — день; протащить `carId`
 * через все запросы и экраны постфактум — неделя и куча багов
 * (docs/DECISIONS.md, решение 10).
 */
@Entity(tableName = "cars")
data class CarEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    /** Необязательный атрибут профиля, а не механизм онбординга (решение 6). */
    val vin: String? = null,
    /** Ключ типа кузова: по нему подбирается силуэт в гараже (решение 22). */
    val bodyType: String? = null,
    /** Цвет линий силуэта, ARGB. Пусто — цвет темы. */
    val silhouetteColor: Int? = null,
    /** Ключ [io.github.snowdrit.mamokeep.domain.FuelGrade]: заметка «чем заправляю», в расчётах не участвует. */
    val fuelGrade: String? = null,
)

@Entity(
    tableName = "odometer_readings",
    foreignKeys = [
        ForeignKey(
            entity = CarEntity::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("carId")],
)
data class OdometerReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val date: LocalDate,
    val km: Int,
)

@Entity(
    tableName = "maintenance_items",
    foreignKeys = [
        ForeignKey(
            entity = CarEntity::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("carId")],
)
data class MaintenanceItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val kind: ItemKind = ItemKind.MAINTENANCE,
    val title: String,
    /** Ключ названия у позиции из набора по умолчанию; у переименованной пусто. */
    val titleKey: String? = null,
    val iconKey: String? = null,
    val intervalKm: Int?,
    val intervalMonths: Int?,
    val lastDoneKm: Int?,
    val lastDoneDate: LocalDate?,
    val warnKm: Int,
    val warnDays: Int,
)

/**
 * Факт выполнения работы.
 *
 * Отдельная таблица, а не поле в позиции: история — это то, ради чего приложение держат
 * годами, и она же станет содержимым выгрузки «Для продажи» (docs/DECISIONS.md, решение 11).
 */
@Entity(
    tableName = "maintenance_records",
    foreignKeys = [
        ForeignKey(
            entity = CarEntity::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MaintenanceItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("carId"), Index("itemId")],
)
data class MaintenanceRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val itemId: Long,
    val date: LocalDate,
    /** Пробег на момент выполнения. Может быть неизвестен, если одометр ещё не вводили. */
    val km: Int?,
    val note: String? = null,
)

/**
 * Заправка.
 *
 * Показание одометра лежит здесь же, внутри заправки, и отдельной строки в
 * `odometer_readings` для него нет. Раньше было наоборот, и тогда заправку нельзя было
 * ни перенести на другую машину, ни удалить: показание оставалось висеть на прежней
 * машине и продолжало врать про её пробег. Миграция 9 → 10 вычистила накопленные дубли.
 *
 * Оценка пробега берёт оба источника разом — см. `OdometerReading.merge`.
 */
@Entity(
    tableName = "fuel_entries",
    foreignKeys = [
        ForeignKey(
            entity = CarEntity::class,
            parentColumns = ["id"],
            childColumns = ["carId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("carId")],
)
data class FuelEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val carId: Long,
    val date: LocalDate,
    val odometerKm: Int,
    /**
     * Литры, кубометры или киловатт-часы. Колонка называлась `liters`, пока топливо было
     * только жидким; имя переименовано вместе с появлением видов — поле, которое хранит
     * киловатт-часы под названием «литры», рано или поздно во что-нибудь попадёт.
     */
    val amount: Double,
    /** Вид топлива у КАЖДОЙ заправки, а не у машины (решение 23). */
    @ColumnInfo(defaultValue = "PETROL")
    val fuelType: FuelType = FuelType.PETROL,
    val costKopecks: Long,
    val isFull: Boolean,
)

/** Строка соединения истории с названием позиции. Не таблица — результат запроса. */
data class DoneWorkRow(
    val title: String,
    val titleKey: String?,
    val date: LocalDate,
    val km: Int?,
    val kind: ItemKind,
    val note: String?,
)

/** Строка истории с видом позиции и значком — для журнала. */
data class DoneWorkDetailRow(
    /** Сама запись: по ней строка журнала открывается на правку. */
    val recordId: Long,
    /** Позиция, которой запись принадлежит: от неё пересчитывается «последний раз». */
    val itemId: Long,
    val title: String,
    val titleKey: String?,
    val date: LocalDate,
    val km: Int?,
    val kind: ItemKind,
    val iconKey: String?,
    val note: String?,
)
