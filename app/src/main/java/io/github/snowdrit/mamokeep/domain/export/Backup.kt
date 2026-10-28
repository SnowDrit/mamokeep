package io.github.snowdrit.mamokeep.domain.export

import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.ReminderRule
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNames
import java.time.LocalDate

/**
 * Формат резервной копии.
 *
 * Читателем здесь выступает машина, поэтому JSON, а не Markdown: восстановление должно
 * быть точным, а не читаемым (docs/DECISIONS.md, решение 7).
 *
 * Записи истории и заправки вложены внутрь машины и позиции, а не лежат плоским списком
 * со ссылками по идентификаторам. Так при восстановлении не нужно ничего перемапливать:
 * новые id раздаст Room, а связи сохранятся структурой файла.
 */
@Serializable
data class BackupFile(
    /** Версия формата. Понадобится, когда структура изменится. */
    @Required val format: Int = FORMAT_VERSION,
    val createdAt: String,
    @Required val cars: List<BackupCar> = emptyList(),
    /** Общие напоминания; старые копии восстанавливают безопасное расписание по умолчанию. */
    val defaultRule: ReminderRule? = null,
    /** Present in v5; null on older copies means their monetary unit was not recorded. */
    val currency: BackupCurrency? = null,
) {
    companion object {
        /**
         * 5 — ISO-код валюты денежных сумм. Страна и единицы остаются настройками устройства.
         * 4 — общее расписание напоминаний и настройки отдельных позиций.
         * Старые копии по-прежнему читаются; история доставки на другое устройство не переносится.
         */
        const val FORMAT_VERSION = 5
    }
}

@Serializable
data class BackupCurrency(
    /** Explicit null records the existing unknown-currency (¤) mode, never a guessed ISO code. */
    @Required val code: String? = null,
) {
    val isValid: Boolean get() = code == null || (code.length == 3 && code.all { it in 'A'..'Z' })
}

class BackupCurrencyMismatchException : Exception("Backup currency does not match the confirmed current currency")

@Serializable
data class BackupCar(
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val vin: String? = null,
    val bodyType: String? = null,
    val silhouetteColor: Int? = null,
    val fuelGrade: String? = null,
    val readings: List<BackupReading> = emptyList(),
    val items: List<BackupItem> = emptyList(),
    val fuel: List<BackupFuel> = emptyList(),
)

@Serializable
data class BackupReading(
    /** День от эпохи — так же, как в базе. Без часовых поясов и разночтений. */
    val epochDay: Long,
    val km: Int,
)

@Serializable
data class BackupItem(
    val kind: ItemKind = ItemKind.MAINTENANCE,
    val title: String,
    /** Ключ названия: без него позиция после восстановления замёрзла бы на одном языке. */
    val titleKey: String? = null,
    /** По умолчанию null: копии, снятые до появления значков, читаются как прежде. */
    val iconKey: String? = null,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastDoneKm: Int? = null,
    val lastDoneEpochDay: Long? = null,
    val warnKm: Int = 0,
    val warnDays: Int = 0,
    val records: List<BackupRecord> = emptyList(),
    /** null — позиция использует общее расписание. Идентификатор Room в файл не попадает. */
    val reminderRule: ReminderRule? = null,
)

@Serializable
data class BackupRecord(
    val epochDay: Long,
    val km: Int? = null,
    val note: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupFuel(
    val epochDay: Long,
    val odometerKm: Int,
    /**
     * Литры, кубометры или киловатт-часы. Старое имя ключа читается наравне с новым:
     * копии, снятые до появления видов топлива, должны восстанавливаться как прежде.
     */
    @JsonNames("liters")
    val amount: Double,
    /** Ключ [FuelType]. В копиях формата 1 вида не было — читаем их как бензин. */
    val fuelType: String = FuelType.PETROL.name,
    val costKopecks: Long,
    val isFull: Boolean = true,
)

object BackupCodec {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(backup: BackupFile): String = json.encodeToString(backup)

    /** `null`, если файл не наш или испорчен: молча ронять восстановление нельзя. */
    fun decode(content: String): BackupFile? =
        runCatching { json.decodeFromString<BackupFile>(content) }
            .getOrNull()
            ?.takeIf { it.format in 1..BackupFile.FORMAT_VERSION }
            ?.takeIf { (it.format < 5 || it.currency != null) && it.currency?.isValid != false }
            ?.takeIf { backup ->
                (backup.defaultRule?.isValid != false) && backup.cars.all { car ->
                    car.readings.all { it.km >= 0 && validDate(it.epochDay) } &&
                        car.fuel.all {
                            it.odometerKm >= 0 && it.amount.isFinite() && it.amount > 0 &&
                                it.costKopecks >= 0 && validDate(it.epochDay)
                        } && car.items.all { item ->
                            item.reminderRule?.isValid != false &&
                                (item.intervalKm == null || item.intervalKm >= 0) &&
                                (item.intervalMonths == null || item.intervalMonths >= 0) &&
                                (item.lastDoneKm == null || item.lastDoneKm >= 0) &&
                                item.warnKm >= 0 && item.warnDays >= 0 &&
                                (item.lastDoneEpochDay == null || validDate(item.lastDoneEpochDay, item.intervalMonths)) &&
                                item.records.all {
                                    (it.km == null || it.km >= 0) && validDate(it.epochDay, item.intervalMonths)
                                }
                        }
                }
            }

    /** A malformed copy must be rejected before replacing any garage data. */
    private fun validDate(epochDay: Long, intervalMonths: Int? = null): Boolean =
        runCatching {
            val date = LocalDate.ofEpochDay(epochDay)
            if (intervalMonths != null) date.plusMonths(intervalMonths.toLong())
        }.isSuccess
}
