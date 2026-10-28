package io.github.snowdrit.mamokeep.data.repo

import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.ItemTitles
import io.github.snowdrit.mamokeep.domain.MaintenanceDefaults
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.UnitSystem

/**
 * Первичное наполнение: машина, универсальный набор позиций обслуживания и документы
 * из пакета региона.
 *
 * Ничего не помечаем выполненным. Приложение честно показывает «не от чего считать» и
 * просит человека сказать, когда он это делал: на этой дате строятся все напоминания,
 * и выдумывать её за него нельзя.
 */
internal suspend fun seedIfEmpty(
    dao: GarageDao,
    region: RegionPreset?,
    units: UnitSystem = UnitSystem.METRIC,
    titles: ItemTitles = ItemTitles { _, stored -> stored },
    /** Имя машины по умолчанию — тоже текст для человека, и тоже приходит из ресурсов. */
    carName: String = "Моя машина",
) {
    if (dao.firstCar() != null) return

    val carId = dao.insertCar(CarEntity(name = carName))
    seedCar(dao, carId, region, units, titles)
}

/**
 * Стартовый набор одной машины: универсальные позиции ТО и документы региона.
 *
 * Вторая машина получает то же самое, что и первая. Иначе после «Добавить машину»
 * человек увидит пустой экран и будет заводить руками ровно то, ради чего приложение
 * и ставят.
 */
suspend fun seedCar(
    dao: GarageDao,
    carId: Long,
    region: RegionPreset?,
    units: UnitSystem,
    titles: ItemTitles,
) {
    MaintenanceDefaults.ALL.forEach { default ->
        dao.insertItem(
            MaintenanceItemEntity(
                carId = carId,
                kind = ItemKind.MAINTENANCE,
                // Пишем и ключ, и текст: ключ переживёт смену языка, а текст останется
                // читаемым, даже если ключ когда-нибудь исчезнет из ресурсов.
                title = titles.resolve(default.titleKey, default.titleKey),
                titleKey = default.titleKey,
                iconKey = default.iconKey,
                // В милях интервалы свои, а не пересчитанные: американец должен увидеть
                // «каждые 5 000 миль», а не «каждые 6 214».
                intervalKm = default.intervalFor(units),
                intervalMonths = default.intervalMonths,
                lastDoneKm = null,
                lastDoneDate = null,
                warnKm = default.warnFor(units),
                warnDays = default.warnDays,
            )
        )
    }

    region?.documents?.forEach { document ->
        dao.insertItem(
            MaintenanceItemEntity(
                carId = carId,
                kind = ItemKind.DOCUMENT,
                // Как и у позиций ТО: ключ переживёт смену языка, текст останется
                // читаемым, даже если пакет когда-нибудь исчезнет из сборки.
                title = titles.resolve(document.key, document.title(region.lang, region.lang)),
                titleKey = document.key,
                iconKey = document.icon,
                // Километровая ось выключена: страховка не зависит от пробега.
                intervalKm = null,
                intervalMonths = document.months,
                lastDoneKm = null,
                lastDoneDate = null,
                warnKm = 0,
                warnDays = document.warnDays,
            )
        )
    }
}
