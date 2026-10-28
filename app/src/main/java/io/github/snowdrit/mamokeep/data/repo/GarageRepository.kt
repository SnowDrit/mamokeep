package io.github.snowdrit.mamokeep.data.repo

import io.github.snowdrit.mamokeep.data.db.CarEntity
import io.github.snowdrit.mamokeep.data.db.FuelEntryEntity
import androidx.room.withTransaction
import io.github.snowdrit.mamokeep.data.ReminderSettingsStore
import io.github.snowdrit.mamokeep.data.db.GarageDao
import io.github.snowdrit.mamokeep.data.db.MamoKeepDatabase
import io.github.snowdrit.mamokeep.data.db.MaintenanceRecordEntity
import io.github.snowdrit.mamokeep.data.db.MaintenanceItemEntity
import io.github.snowdrit.mamokeep.data.db.OdometerReadingEntity
import io.github.snowdrit.mamokeep.domain.FuelCalculator
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelGrade
import io.github.snowdrit.mamokeep.domain.FuelStats
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.ItemTitles
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.domain.MaintenanceCalculator
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.MaintenanceRecord
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.MileageEstimator
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.OdometerBounds
import io.github.snowdrit.mamokeep.domain.OdometerReading
import io.github.snowdrit.mamokeep.domain.boundsAt
import io.github.snowdrit.mamokeep.domain.export.CarSnapshot
import io.github.snowdrit.mamokeep.domain.export.BackupCar
import io.github.snowdrit.mamokeep.domain.export.BackupCodec
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.domain.export.BackupCurrencyMismatchException
import io.github.snowdrit.mamokeep.domain.export.BackupFile
import io.github.snowdrit.mamokeep.domain.export.BackupFuel
import io.github.snowdrit.mamokeep.domain.export.BackupItem
import io.github.snowdrit.mamokeep.domain.export.BackupReading
import io.github.snowdrit.mamokeep.domain.export.BackupRecord
import io.github.snowdrit.mamokeep.domain.export.DoneWork
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.ReminderSettings
import io.github.snowdrit.mamokeep.domain.sortedByUrgency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/** Всё, что нужно главному экрану про одну машину. */
data class CarOverview(
    val carId: Long,
    val carName: String,
    val mileage: MileageEstimate?,
    val statuses: List<MaintenanceStatus>,
    val fuel: FuelStats,
    /** Заметка «чем заправляю»: подсказывает диалогу вид, пока заправок ещё нет. */
    val fuelGrade: FuelGrade?,
)

/** An editable manual reading; fuel odometers retain their own source and editor. */
data class StandaloneOdometerReading(
    val id: Long,
    val carId: Long,
    val date: LocalDate,
    val km: Int,
)

class GarageRepository(
    private val database: MamoKeepDatabase,
    private val dao: GarageDao,
    /**
     * Название позиции подставляется здесь, на границе базы и приложения. Так и выгрузка,
     * и уведомления, и журнал получают его уже на языке приложения — им самим ничего
     * знать не надо.
     */
    private val titles: ItemTitles = ItemTitles { _, stored -> stored },
    private val reminderSettings: ReminderSettingsStore? = null,
    private val currentCurrencyCode: suspend () -> String?,
) {

    /** Воркер не должен отправить напоминание по состоянию до обслуживания или импорта. */
    private suspend fun <T> withReminderLock(block: suspend () -> T): T =
        if (reminderSettings == null) block() else reminderSettings.withGarageLock(block)

    /**
     * @param carId выбранная машина. Если её уже нет — берётся первая: экран не должен
     * оставаться пустым из-за того, что человек удалил именно ту, что была открыта.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeOverview(carId: Long, today: () -> LocalDate): Flow<CarOverview?> =
        dao.observeCar(carId).distinctUntilChanged().flatMapLatest { car ->
            if (car == null) {
                flowOf(null)
            } else {
                combine(
                    dao.observeReadings(car.id),
                    dao.observeItems(car.id),
                    dao.observeFuelEntries(car.id),
                ) { readings, items, fuel ->
                    overview(car, odometerOf(readings, fuel), items, fuel.map { it.toDomain() }, today())
                }
            }
        }

    suspend fun overviewsForAllCars(today: LocalDate): List<CarOverview> =
        dao.allCars().map { car ->
            val readings = dao.readings(car.id)
            val items = dao.items(car.id)
            val fuel = dao.fuelEntries(car.id)
            overview(car, odometerOf(readings, fuel), items, fuel.map { it.toDomain() }, today)
        }

    private fun overview(
        car: CarEntity,
        readings: List<OdometerReading>,
        items: List<MaintenanceItemEntity>,
        fuel: List<FuelEntry>,
        today: LocalDate,
    ): CarOverview {
        val mileage = MileageEstimator.estimate(
            readings = readings,
            today = today,
        )
        return CarOverview(
            carId = car.id,
            carName = car.name,
            mileage = mileage,
            statuses = items
                .map { entity -> MaintenanceCalculator.status(entity.toDomain(), mileage?.km, today) }
                .sortedByUrgency(),
            fuel = FuelCalculator.stats(fuel),
            fuelGrade = FuelGrade.of(car.fuelGrade),
        )
    }

    /**
     * Заправка. Одометр, введённый у колонки, хранится **внутри самой заправки** и
     * оттуда же участвует в расчёте пробега: человек смотрит на приборку, стоя у
     * колонки, и это единственный момент, когда он делает это сам (docs/DECISIONS.md,
     * решение 9).
     *
     * Отдельной строки в таблице показаний не пишем. Раньше писали — и тогда заправку
     * нельзя было ни перенести на другую машину, ни удалить: показание оставалось
     * висеть на прежней машине и продолжало врать про её пробег.
     */
    suspend fun addFuelEntry(
        carId: Long,
        date: LocalDate,
        odometerKm: Int,
        amount: Double,
        fuelType: FuelType,
        costKopecks: Long,
        isFull: Boolean,
    ) {
        dao.insertFuelEntry(
            FuelEntryEntity(
                carId = carId,
                date = date,
                odometerKm = odometerKm,
                amount = amount,
                fuelType = fuelType,
                costKopecks = costKopecks,
                isFull = isFull,
            )
        )
    }

    /**
     * Правка заправки, включая перенос на другую машину.
     *
     * Одометр уезжает вместе с заправкой, потому что хранится внутри неё. Число при
     * этом почти всегда чужое — его показывают человеку в редакторе рядом с пробегом
     * машины-получателя, чтобы он поправил осознанно.
     */
    suspend fun updateFuelEntry(
        id: Long,
        carId: Long,
        date: LocalDate,
        odometerKm: Int,
        amount: Double,
        fuelType: FuelType,
        costKopecks: Long,
        isFull: Boolean,
    ) {
        val existing = dao.fuelEntry(id) ?: return
        dao.updateFuelEntry(
            existing.copy(
                carId = carId,
                date = date,
                odometerKm = odometerKm,
                amount = amount,
                fuelType = fuelType,
                costKopecks = costKopecks,
                isFull = isFull,
            )
        )
    }

    /**
     * Расчётный пробег машины на сегодня.
     *
     * Нужен подсказкой под полем одометра: человек списывает число с панели приборов,
     * и рядом должно стоять то, что приложение считает текущим пробегом. Ошибка в
     * разряде видна сразу, а без сверки уезжает в базу молча.
     *
     * Подставлять это число в само поле нельзя: одометр там обязан быть наблюдённым
     * фактом, а не оценкой (docs/DECISIONS.md, решение 3).
     */
    suspend fun mileageNow(carId: Long, today: LocalDate = LocalDate.now()): MileageEstimate? =
        MileageEstimator.estimate(odometerOf(carId), today)

    /**
     * Что записано у машины по обе стороны от даты — для предупреждения в редакторе
     * заправки.
     *
     * Саму правимую заправку из ленты выбрасываем: сравнивать её с собой бессмысленно,
     * а на своей прежней дате она бы всегда «противоречила» новой.
     */
    suspend fun odometerBounds(
        carId: Long,
        date: LocalDate,
        exceptFuelId: Long? = null,
    ): OdometerBounds = odometerOf(
        readings = dao.readings(carId),
        fuel = dao.fuelEntries(carId).filter { it.id != exceptFuelId },
    ).boundsAt(date)

    suspend fun deleteFuelEntry(id: Long) {
        dao.fuelEntry(id)?.let { dao.deleteFuelEntry(it) }
    }

    /**
     * Пакетное удаление записей журнала.
     *
     * Не цикл из `deleteRecord` снаружи: тот пересчитывает «последний раз» после каждой
     * записи, и на десятке отметок одной позиции пересчёт шёл бы десять раз подряд, каждый
     * раз по всё ещё неполной истории. Здесь позиции пересчитываются один раз в конце,
     * когда удалено уже всё.
     */
    suspend fun deleteJournalEntries(fuelIds: List<Long>, recordIds: List<Long>) = withReminderLock {
        database.withTransaction {
            val touchedItems = mutableSetOf<Long>()

            recordIds.forEach { id ->
                val record = dao.record(id) ?: return@forEach
                touchedItems += record.itemId
                dao.deleteRecord(record)
            }

            fuelIds.forEach { id -> dao.fuelEntry(id)?.let { dao.deleteFuelEntry(it) } }

            touchedItems.forEach { refreshLastDone(it) }
        }
    }

    suspend fun fuelEntry(id: Long): FuelEntry? = dao.fuelEntry(id)?.toDomain()

    /**
     * Последнее известное показание одометра машины — без экстраполяции.
     *
     * Нужно редактору заправки: перенося её на другую машину, человек должен видеть,
     * с каким пробегом он сравнивает введённое число.
     */
    suspend fun lastKnownKm(carId: Long): Int? = odometerOf(carId).lastOrNull()?.km

    /**
     * Показания одометра машины наружу: по ним отметка «сделано» считает пробег на
     * выбранную задним числом дату.
     */
    suspend fun odometerReadings(carId: Long): List<OdometerReading> = odometerOf(carId)

    /**
     * Все показания одометра машины одной лентой: записанные вручную плюс те, что
     * человек ввёл, отмечая заправку.
     */
    private fun odometerOf(
        readings: List<OdometerReadingEntity>,
        fuel: List<FuelEntryEntity>,
    ): List<OdometerReading> = OdometerReading.merge(
        readings.map { OdometerReading(it.date, it.km) },
        fuel.map { OdometerReading(it.date, it.odometerKm) },
    )

    private suspend fun odometerOf(carId: Long): List<OdometerReading> =
        odometerOf(dao.readings(carId), dao.fuelEntries(carId))

    fun observeCars(): Flow<List<Car>> = dao.observeCars().map { cars -> cars.map { it.toDomain() } }

    suspend fun saveCar(car: Car) {
        val existing = dao.car(car.id)
        if (existing == null) {
            dao.insertCar(
                CarEntity(
                    name = car.name,
                    make = car.make,
                    model = car.model,
                    year = car.year,
                    vin = car.vin,
                    bodyType = car.bodyType,
                    silhouetteColor = car.silhouetteColor,
                    fuelGrade = car.fuelGrade,
                )
            )
        } else {
            dao.updateCar(
                existing.copy(
                    name = car.name,
                    make = car.make,
                    model = car.model,
                    year = car.year,
                    vin = car.vin,
                    bodyType = car.bodyType,
                    silhouetteColor = car.silhouetteColor,
                    fuelGrade = car.fuelGrade,
                )
            )
        }
    }

    private fun CarEntity.toDomain() = Car(
        id = id,
        name = name,
        make = make,
        model = model,
        year = year,
        vin = vin,
        bodyType = bodyType,
        silhouetteColor = silhouetteColor,
        fuelGrade = fuelGrade,
    )

    /** Garage data and its monetary unit; device preferences are not transferred. */
    suspend fun exportBackup(today: LocalDate): String = withReminderLock {
        val reminders = reminderSettings?.exportSettings() ?: ReminderSettings()
        val cars = database.withTransaction {
            dao.allCars().map { car ->
                BackupCar(
                    name = car.name,
                    make = car.make,
                    model = car.model,
                    year = car.year,
                    vin = car.vin,
                    bodyType = car.bodyType,
                    silhouetteColor = car.silhouetteColor,
                    fuelGrade = car.fuelGrade,
                    readings = dao.readings(car.id).map { BackupReading(it.date.toEpochDay(), it.km) },
                    items = dao.items(car.id).map { item ->
                        BackupItem(
                            kind = item.kind,
                            title = item.title,
                            titleKey = item.titleKey,
                            iconKey = item.iconKey,
                            intervalKm = item.intervalKm,
                            intervalMonths = item.intervalMonths,
                            lastDoneKm = item.lastDoneKm,
                            lastDoneEpochDay = item.lastDoneDate?.toEpochDay(),
                            warnKm = item.warnKm,
                            warnDays = item.warnDays,
                            records = dao.recordsOf(item.id).map {
                                BackupRecord(it.date.toEpochDay(), it.km, it.note)
                            },
                            reminderRule = reminders.itemOverrides[item.id],
                        )
                    },
                    fuel = dao.fuelEntries(car.id).map {
                        BackupFuel(
                            epochDay = it.date.toEpochDay(),
                            odometerKm = it.odometerKm,
                            amount = it.amount,
                            fuelType = it.fuelType.name,
                            costKopecks = it.costKopecks,
                            isFull = it.isFull,
                        )
                    },
                )
            }
        }

        val currency = BackupCurrency(currentCurrencyCode())
        check(currency.isValid) { "Invalid current currency code" }
        BackupCodec.encode(BackupFile(
            createdAt = today.toString(),
            cars = cars,
            defaultRule = reminders.defaultRule,
            currency = currency,
        ))
    }

    /**
     * Восстановление ЗАМЕНЯЕТ всё содержимое. Слияние здесь было бы хуже: пользователь
     * не сможет предсказать, что получится из двух пересекающихся историй.
     *
     * Возвращает число восстановленных машин или `null`, если файл не наш.
     */
    suspend fun importBackup(content: String, confirmedCurrency: BackupCurrency): Int? = withReminderLock {
        val backup = BackupCodec.decode(content) ?: return@withReminderLock null
        val current = BackupCurrency(currentCurrencyCode())
        // Confirmation is tied to the current effective ISO, not to an earlier UI snapshot.
        // Legacy and explicit unknown-currency copies require the caller's explicit confirmation.
        if (!confirmedCurrency.isValid || confirmedCurrency != current ||
            (backup.currency?.code != null && backup.currency != confirmedCurrency)) {
            throw BackupCurrencyMismatchException()
        }
        val importedOverrides = mutableMapOf<Long, ReminderRule>()

        database.withTransaction {
            dao.deleteAllCars()

            backup.cars.forEach { car ->
                val carId = dao.insertCar(
                    CarEntity(
                        name = car.name,
                        make = car.make,
                        model = car.model,
                        year = car.year,
                        vin = car.vin,
                        bodyType = car.bodyType,
                        silhouetteColor = car.silhouetteColor,
                        fuelGrade = car.fuelGrade,
                    )
                )

                car.readings.forEach {
                    dao.insertReading(
                        OdometerReadingEntity(
                            carId = carId,
                            date = LocalDate.ofEpochDay(it.epochDay),
                            km = it.km,
                        )
                    )
                }

                car.items.forEach { item ->
                    val itemId = dao.insertItem(
                        MaintenanceItemEntity(
                            carId = carId,
                            kind = item.kind,
                            title = item.title,
                            titleKey = item.titleKey,
                            iconKey = item.iconKey,
                            intervalKm = item.intervalKm,
                            intervalMonths = item.intervalMonths,
                            lastDoneKm = item.lastDoneKm,
                            lastDoneDate = item.lastDoneEpochDay?.let(LocalDate::ofEpochDay),
                            warnKm = item.warnKm,
                            warnDays = item.warnDays,
                        )
                    )
                    if (backup.format >= 4) {
                        item.reminderRule?.let { importedOverrides[itemId] = it }
                    }
                    item.records.forEach { record ->
                        dao.insertRecord(
                            MaintenanceRecordEntity(
                                carId = carId,
                                itemId = itemId,
                                date = LocalDate.ofEpochDay(record.epochDay),
                                km = record.km,
                                note = record.note,
                            )
                        )
                    }
                }

                car.fuel.forEach {
                    dao.insertFuelEntry(
                        FuelEntryEntity(
                            carId = carId,
                            date = LocalDate.ofEpochDay(it.epochDay),
                            odometerKm = it.odometerKm,
                            amount = it.amount,
                            fuelType = FuelType.of(it.fuelType),
                            costKopecks = it.costKopecks,
                            isFull = it.isFull,
                        )
                    )
                }
            }

            // Копия, снятая прежними сборками, несёт показания одометра, которые тогда
            // писались на каждую заправку. Сейчас заправка хранит одометр сама, и такие
            // строки вернулись бы дублями. На расчёт они не влияют — совпадения
            // схлопывает OdometerReading.merge, — но и лежать без дела им незачем.
            dao.dropReadingsDuplicatingFuel()

            // Пишем последним: ошибка DataStore откатывает изменения Room. Общего
            // коммита у двух хранилищ нет; при аварии между этой записью и коммитом
            // возможна смена настроек без смены гаража. Чужие id воркер отбрасывает.
            reminderSettings?.replaceSettings(ReminderSettings(
                defaultRule = if (backup.format >= 4) backup.defaultRule ?: ReminderRule() else ReminderRule(),
                itemOverrides = importedOverrides,
            ))
        }

        backup.cars.size
    }

    /** Все разделы выгрузки читаются из одного состояния базы. */
    suspend fun snapshot(carId: Long, today: LocalDate): CarSnapshot? = database.withTransaction {
        val car = dao.car(carId) ?: return@withTransaction null
        val readings = dao.readings(carId)
        val items = dao.items(carId)
        val fuelEntities = dao.fuelEntries(carId)
        val fuel = fuelEntities.map { it.toDomain() }

        val overview = overview(car, odometerOf(readings, fuelEntities), items, fuel, today)

        CarSnapshot(
            carName = car.name,
            make = car.make,
            model = car.model,
            year = car.year,
            vin = car.vin,
            fuelGrade = FuelGrade.of(car.fuelGrade),
            mileage = overview.mileage,
            statuses = overview.statuses,
            history = dao.doneWorks(carId).map {
                DoneWork(titles.resolve(it.titleKey, it.title), it.date, it.km, it.kind, it.note)
            },
            fuel = overview.fuel,
            fuelEntries = fuel,
            today = today,
            odometerReadings = readings.map { OdometerReading(it.date, it.km) },
        )
    }

    /** Единая лента событий: заправки, работы и продления документов вперемешку по дате. */
    fun observeJournal(carId: Long): Flow<List<JournalEntry>> = combine(
        dao.observeFuelEntries(carId),
        dao.observeDoneWorks(carId),
    ) { fuel, works ->
        val fuelEntries = fuel.map { entry ->
            JournalEntry(
                date = entry.date,
                km = entry.odometerKm,
                // Заголовок и слово «продлён» подставляет экран: репозиторий не знает,
                // на каком языке сейчас приложение.
                title = null,
                fuelEntryId = entry.id,
                // Вид и количество уходят наверх числами: собрать из них строку может
                // только экран, который знает выбранные единицы измерения.
                fuel = JournalEntry.Fuel(entry.amount, entry.fuelType, entry.isFull),
                amountKopecks = entry.costKopecks,
                iconKey = "gas_station",
                kind = JournalEntry.Kind.FUEL,
            )
        }

        val workEntries = works.map { work ->
            JournalEntry(
                date = work.date,
                km = work.km,
                title = titles.resolve(work.titleKey, work.title),
                recordId = work.recordId,
                itemId = work.itemId,
                fuel = null,
                amountKopecks = null,
                iconKey = work.iconKey ?: if (work.kind == ItemKind.DOCUMENT) "file_document_outline" else "wrench",
                kind = if (work.kind == ItemKind.DOCUMENT) JournalEntry.Kind.DOCUMENT else JournalEntry.Kind.WORK,
                note = work.note,
            )
        }

        (fuelEntries + workEntries).sortedWith(
            compareByDescending<JournalEntry> { it.date }.thenByDescending { it.km ?: 0 }
        )
    }

    fun observeFuelEntries(carId: Long): Flow<List<FuelEntry>> =
        dao.observeFuelEntries(carId).map { entries -> entries.map { it.toDomain() } }

    private fun FuelEntryEntity.toDomain() = FuelEntry(
        id = id,
        carId = carId,
        date = date,
        odometerKm = odometerKm,
        amount = amount,
        fuelType = fuelType,
        costKopecks = costKopecks,
        isFull = isFull,
    )

    /**
     * Документы пакета региона, которых у машины ещё нет — уже на языке интерфейса.
     *
     * Сверка по ключу, а название берётся вторым шансом: документы, заведённые сборками
     * до появления ключей, лежат в базе одним текстом, и без этой поблажки регион долил
     * бы их второй раз.
     */
    suspend fun missingRegionDocuments(carId: Long, preset: RegionPreset): List<String> {
        val items = dao.items(carId)
        val keys = items.mapNotNull { it.titleKey }.toSet()
        val names = items.map { it.title.trim().lowercase() }.toSet()

        return preset.documents
            .filter { it.key !in keys && it.title(preset.lang, preset.lang).trim().lowercase() !in names }
            .map { titles.resolve(it.key, it.title(preset.lang, preset.lang)) }
    }

    /**
     * Новая машина со стартовым набором. Возвращает её идентификатор — на неё же
     * приложение и переключится.
     */
    suspend fun addCar(
        name: String,
        region: RegionPreset?,
        units: UnitSystem,
    ): Long = database.withTransaction {
        val carId = dao.insertCar(CarEntity(name = name))
        seedCar(dao, carId, region, units, titles)
        carId
    }

    /** A failed first launch must not leave a car that permanently skips the rest of its defaults. */
    suspend fun seedIfEmpty(region: RegionPreset?, units: UnitSystem, carName: String) = database.withTransaction {
        seedIfEmpty(dao = dao, region = region, units = units, titles = titles, carName = carName)
    }

    /**
     * Удаление машины забирает с собой всё её содержимое: показания, позиции, историю
     * и заправки (каскад в схеме). Восстановить это нечем, поэтому спрашивать должен
     * тот, кто вызывает.
     */
    suspend fun deleteCar(carId: Long) = withReminderLock {
        database.withTransaction {
            val itemIds = dao.items(carId).map { it.id }
            dao.car(carId)?.let { dao.deleteCar(it) }
            reminderSettings?.removeItems(itemIds)
        }
    }

    /**
     * Проставляет ключи документам, заведённым до того, как ключи появились.
     *
     * Без этого правка названий на другой язык не доходит до тех, у кого приложение уже
     * стоит: в базе лежит текст «ОСАГО», и он так и остаётся текстом. Сверка идёт по
     * любому из языков пакета — документ могли завести и на немецкой локали.
     *
     * Переименованный документ не трогаем: имени в пакете он не совпадает, а человек,
     * назвавший полис по-своему, знает, что делает.
     */
    suspend fun linkRegionDocuments(regions: List<RegionPreset>) {
        val byName = regions
            .flatMap { region -> region.documents }
            .flatMap { document -> document.titles.values.map { it.trim().lowercase() to document.key } }
            .toMap()

        dao.documentsWithoutKey().forEach { item ->
            val key = byName[item.title.trim().lowercase()] ?: return@forEach
            dao.updateItem(item.copy(titleKey = key, title = titles.resolve(key, item.title)))
        }
    }

    /** Позиции машины — нужны, чтобы погасить её уведомления до удаления. */
    suspend fun itemIdsOf(carId: Long): List<Long> = dao.items(carId).map { it.id }

    /**
     * Доливает недостающие документы региона. Ничего не удаляет и не меняет: человек,
     * ткнувший в список стран из любопытства, не должен потерять историю продлений —
     * это единственные данные, которые нельзя восстановить по памяти.
     *
     * Возвращает, сколько добавлено.
     */
    suspend fun addRegionDocuments(carId: Long, preset: RegionPreset): Int {
        val missing = missingRegionDocuments(carId, preset).toSet()

        preset.documents
            .filter { titles.resolve(it.key, it.title(preset.lang, preset.lang)) in missing }
            .forEach { document ->
            dao.insertItem(
                MaintenanceItemEntity(
                    carId = carId,
                    kind = ItemKind.DOCUMENT,
                    title = titles.resolve(document.key, document.title(preset.lang, preset.lang)),
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

        return missing.size
    }

    suspend fun addOdometerReading(carId: Long, km: Int, date: LocalDate) {
        dao.insertReading(OdometerReadingEntity(carId = carId, date = date, km = km))
    }

    fun observeStandaloneOdometerReadings(carId: Long): Flow<List<StandaloneOdometerReading>> =
        dao.observeReadings(carId).map { readings ->
            readings.sortedWith(compareByDescending<OdometerReadingEntity> { it.date }.thenByDescending { it.id })
                .map { StandaloneOdometerReading(it.id, it.carId, it.date, it.km) }
        }

    suspend fun updateOdometerReading(carId: Long, id: Long, km: Int, date: LocalDate) = withReminderLock {
        require(km >= 0) { "Odometer must not be negative" }
        check(dao.updateReading(carId, id, km, date) == 1) { "Odometer reading no longer belongs to this car" }
    }

    suspend fun deleteOdometerReading(carId: Long, id: Long) = withReminderLock {
        check(dao.deleteReading(carId, id) == 1) { "Odometer reading no longer belongs to this car" }
    }

    /**
     * Отметить позицию выполненной. Пробег берётся расчётный на сегодня — человек,
     * нажимающий «Сделано», не обязан идти к машине сверять одометр.
     */
    suspend fun markDone(itemId: Long, atKm: Int?, date: LocalDate, note: String? = null) = withReminderLock {
        database.withTransaction {
            val item = checkNotNull(dao.item(itemId)) { "Maintenance item no longer exists" }
            dao.insertRecord(
                MaintenanceRecordEntity(carId = item.carId, itemId = item.id, date = date, km = atKm, note = note)
            )
            refreshLastDone(itemId, forceReminderReset = true)
        }
    }

    /**
     * Правка записи в истории: дата и пробег.
     *
     * Отметить «Сделано» можно одним касанием, в том числе из шторки уведомления, —
     * значит и промахнуться можно одним касанием. Без правки человеку оставалось бы
     * жить с неверной датой обслуживания, от которой считается весь следующий срок.
     */
    suspend fun updateRecord(recordId: Long, date: LocalDate, km: Int?) =
        updateRecord(recordId, itemId = null, date = date, km = km)

    /**
     * Правка записи, включая **перенос на другую позицию**.
     *
     * Перепутать позиции легко: на главном экране они лежат подряд, а в шторке уведомлений
     * «Сделано» жмётся одним касанием у любой из двух просроченных. Без переноса человеку
     * оставалось бы удалить отметку и завести её заново.
     *
     * «Последний раз» пересчитывается у **обеих** позиций, и это не перестраховка: у
     * покинутой он должен откатиться на предыдущую запись, иначе она молча останется с
     * чужим сроком — и следующее напоминание не придёт вовремя. Провал был бы невидим на
     * экране, поэтому его держит тест.
     *
     * Чужая машина в перенос не принимается: отметка несёт пробег **своей** машины, и
     * такой перенос требовал бы отдельного разговора про одометр, как у заправки.
     */
    suspend fun updateRecord(recordId: Long, itemId: Long?, date: LocalDate, km: Int?, note: String? = null) = withReminderLock {
        database.withTransaction {
            val record = checkNotNull(dao.record(recordId)) { "Maintenance record no longer exists" }
            val target = itemId?.let { dao.item(it) }?.takeIf { it.carId == record.carId }
            val movedTo = target?.id ?: record.itemId

            // Older callers edit only date/odometer. Empty text explicitly clears a note.
            dao.updateRecord(record.copy(itemId = movedTo, date = date, km = km, note = note ?: record.note))

            refreshLastDone(record.itemId)
            if (movedTo != record.itemId) refreshLastDone(movedTo)
        }
    }

    /**
     * Что останется у позиции, если эта запись из неё уйдёт: дата предыдущей отметки или
     * `null` — «не останется ничего».
     *
     * Нужно редактору, чтобы сказать последствие вслух **до** сохранения. Иначе человек
     * переносит отметку и не видит, что у покинутой позиции только что уехал срок.
     */
    suspend fun lastDoneWithout(recordId: Long): LocalDate? {
        val record = dao.record(recordId) ?: return null
        return dao.latestRecordExcluding(record.itemId, recordId)?.date
    }

    /** Удаляет запись и откатывает «последний раз» на предыдущую — или на «не отмечено». */
    suspend fun deleteRecord(recordId: Long) = withReminderLock {
        database.withTransaction {
            val record = dao.record(recordId) ?: return@withTransaction
            dao.deleteRecord(record)
            refreshLastDone(record.itemId)
        }
    }

    /**
     * Приводит «последний раз» позиции в соответствие с её историей.
     *
     * Единственный источник правды — записи: пока они и `lastDone*` жили порознь,
     * удалить ошибочную отметку было нельзя, не соврав про срок следующего обслуживания.
     * Записей не осталось — позиция снова «не отмечена», и это честно: считать не от чего.
     */
    private suspend fun refreshLastDone(itemId: Long, forceReminderReset: Boolean = false) {
        val item = dao.item(itemId) ?: return
        val last = dao.latestRecord(itemId)
        dao.updateItem(item.copy(lastDoneKm = last?.km, lastDoneDate = last?.date))
        if (forceReminderReset || item.lastDoneKm != last?.km || item.lastDoneDate != last?.date) {
            reminderSettings?.clearHistory(itemId)
        }
    }

    /**
     * Отметка «сделано» из уведомления: пробег берём расчётный на сегодня для той машины,
     * которой принадлежит позиция.
     */
    suspend fun markDoneAtCurrentMileage(itemId: Long, date: LocalDate) {
        val item = dao.item(itemId) ?: return
        val mileage = MileageEstimator.estimate(
            readings = odometerOf(item.carId),
            today = date,
        )
        markDone(itemId, mileage?.km, date)
    }

    fun observeRecords(itemId: Long): Flow<List<MaintenanceRecord>> =
        dao.observeRecords(itemId).map { records ->
            records.map { MaintenanceRecord(it.id, it.date, it.km) }
        }

    suspend fun addItem(carId: Long, item: MaintenanceItem): Long =
        dao.insertItem(
            MaintenanceItemEntity(
                carId = carId,
                kind = item.kind,
                title = item.title,
                titleKey = item.titleKey,
                iconKey = item.iconKey,
                intervalKm = item.intervalKm,
                intervalMonths = item.intervalMonths,
                lastDoneKm = item.lastDoneKm,
                lastDoneDate = item.lastDoneDate,
                warnKm = item.warnKm,
                warnDays = item.warnDays,
            )
        )

    /** Сохраняет расписание с новым id, поэтому новая позиция не теряет свои настройки. */
    suspend fun saveItemWithReminder(carId: Long, item: MaintenanceItem, rule: ReminderRule?): Long = withReminderLock {
        require(rule?.isValid != false)
        database.withTransaction {
            val existing = if (item.id == 0L) null else dao.item(item.id)
            val itemId = if (item.id == 0L) {
                addItem(carId, item)
            } else {
                check(existing != null && existing.carId == carId)
                updateItem(existing, item)
                item.id
            }
            reminderSettings?.setItemRule(itemId, rule)
            // Смена времени, порогов или лимита не должна разрешать второе уведомление
            // сегодня. История начинается заново только с нового обслуживания.
            if (existing == null) {
                reminderSettings?.clearHistory(itemId)
            }
            itemId
        }
    }

    suspend fun updateItem(item: MaintenanceItem) {
        val existing = dao.item(item.id) ?: return
        updateItem(existing, item)
    }

    private suspend fun updateItem(existing: MaintenanceItemEntity, item: MaintenanceItem) {
        // The editor changes metadata; retain service history updated while it was open.
        dao.updateItem(
            existing.copy(
                kind = item.kind,
                title = item.title,
                titleKey = item.titleKey,
                iconKey = item.iconKey,
                intervalKm = item.intervalKm,
                intervalMonths = item.intervalMonths,
                warnKm = item.warnKm,
                warnDays = item.warnDays,
            )
        )
    }

    suspend fun deleteItem(itemId: Long) = withReminderLock {
        database.withTransaction {
            dao.item(itemId)?.let { dao.deleteItem(it) }
            reminderSettings?.removeItems(listOf(itemId))
        }
    }

    private fun MaintenanceItemEntity.toDomain() = MaintenanceItem(
        id = id,
        kind = kind,
        title = titles.resolve(titleKey, title),
        titleKey = titleKey,
        iconKey = iconKey,
        intervalKm = intervalKm,
        intervalMonths = intervalMonths,
        lastDoneKm = lastDoneKm,
        lastDoneDate = lastDoneDate,
        warnKm = warnKm,
        warnDays = warnDays,
    )
}
