package io.github.snowdrit.mamokeep.domain

/**
 * Универсальные дефолты по типу расходника.
 *
 * Это НЕ регламент производителя. Смысл в том, чтобы приложение было полезным для любой
 * машины с нулевого дня — включая ту, для которой пресета никогда не появится. Пресеты по
 * моделям лягут поверх и перекроют эти цифры (docs/DECISIONS.md, решение 5).
 *
 * Каждое значение пользователь может изменить, и в UI оно подписано честно.
 */
data class MaintenanceDefault(
    /** Ключ названия: слово к нему подставит интерфейс (см. [ItemTitles]). */
    val titleKey: String,
    val iconKey: String,
    val intervalKm: Int?,
    /**
     * Тот же пункт в милях — **не пересчёт** километрового интервала, а число из
     * американской практики. «Менять масло каждые 6 214 миль» человек читает как
     * опечатку и правит руками все девять позиций подряд.
     */
    val intervalMiles: Int?,
    val intervalMonths: Int?,
    val warnKm: Int,
    val warnMiles: Int,
    val warnDays: Int,
) {
    /** Интервал в километрах: хранится он всегда в них, каким бы ни был набор единиц. */
    fun intervalFor(system: UnitSystem): Int? =
        if (system.milesBased) intervalMiles?.let { system.kmFrom(it.toDouble()) } else intervalKm

    fun warnFor(system: UnitSystem): Int =
        if (system.milesBased) system.kmFrom(warnMiles.toDouble()) else warnKm
}

object MaintenanceDefaults {

    /** Обычный порог предупреждения. */
    private const val WARN_KM = 500
    private const val WARN_MILES = 300
    private const val WARN_DAYS = 14

    /** Для дорогих и долгих работ предупреждаем заранее — их надо планировать. */
    private const val WARN_KM_MAJOR = 2_000
    private const val WARN_MILES_MAJOR = 1_500
    private const val WARN_DAYS_MAJOR = 60

    /**
     * Мильные интервалы взяты из общей американской практики, а не пересчитаны из
     * километровых, и подписаны тем же честным предупреждением: это рекомендация,
     * а не регламент конкретной модели.
     */
    val ALL: List<MaintenanceDefault> = listOf(
        MaintenanceDefault("oil", "oil", 10_000, 5_000, 12, WARN_KM, WARN_MILES, WARN_DAYS),
        MaintenanceDefault("cabin_filter", "air_conditioner", 15_000, 15_000, 12, WARN_KM, WARN_MILES, WARN_DAYS),
        MaintenanceDefault("air_filter", "air_filter", 20_000, 15_000, 24, WARN_KM, WARN_MILES, WARN_DAYS),
        MaintenanceDefault("spark_plugs", "flash", 30_000, 30_000, 48, WARN_KM, WARN_MILES, WARN_DAYS),
        MaintenanceDefault("brake_pads_front", "car_brake_alert", 30_000, 30_000, null, WARN_KM, WARN_MILES, WARN_DAYS),
        // Тормозная жидкость гигроскопична: меняется по времени независимо от пробега.
        // Ровно тот случай, ради которого интервал сделан двумерным.
        MaintenanceDefault("brake_fluid", "car_brake_fluid_level", 60_000, 30_000, 24, WARN_KM_MAJOR, WARN_MILES_MAJOR, WARN_DAYS_MAJOR),
        MaintenanceDefault("gearbox_oil", "car_shift_pattern", 60_000, 60_000, 60, WARN_KM_MAJOR, WARN_MILES_MAJOR, WARN_DAYS_MAJOR),
        MaintenanceDefault("coolant", "car_coolant_level", 90_000, 50_000, 60, WARN_KM_MAJOR, WARN_MILES_MAJOR, WARN_DAYS_MAJOR),
        MaintenanceDefault("timing_belt", "engine", 90_000, 60_000, 60, WARN_KM_MAJOR, WARN_MILES_MAJOR, WARN_DAYS_MAJOR),
    )

    // Текст оговорки про универсальные интервалы жил здесь константой и не использовался
    // ни разу: экран берёт его из ресурсов (`maint_disclaimer`), иначе он не переводился бы.
    // Убран, чтобы не расходился со своей единственной настоящей копией.
}
