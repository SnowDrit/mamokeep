package io.github.snowdrit.mamokeep.domain.export

import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelGrade
import io.github.snowdrit.mamokeep.domain.FuelStats
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.OdometerReading
import java.time.LocalDate

/** Выполненная работа вместе с названием позиции — для выгрузки истории. */
data class DoneWork(
    val title: String,
    val date: LocalDate,
    val km: Int?,
    val kind: ItemKind = ItemKind.MAINTENANCE,
    val note: String? = null,
)

/**
 * Согласованный слепок данных одной машины для выгрузки в модель.
 * Резервная копия собирается отдельно и сохраняет также настройки оформления.
 */
data class CarSnapshot(
    val carName: String,
    val make: String?,
    val model: String?,
    val year: Int?,
    val vin: String?,
    /** Заметка «чем заправляю»: модели полезнее «АИ-95», чем «бензин». */
    val fuelGrade: FuelGrade?,
    val mileage: MileageEstimate?,
    val statuses: List<MaintenanceStatus>,
    val history: List<DoneWork>,
    val fuel: FuelStats,
    val fuelEntries: List<FuelEntry>,
    val today: LocalDate,
    /** Только отдельные записи одометра: показания из заправок уже есть в [fuelEntries]. */
    val odometerReadings: List<OdometerReading> = emptyList(),
)
