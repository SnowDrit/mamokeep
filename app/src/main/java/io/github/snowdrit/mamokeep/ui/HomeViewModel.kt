package io.github.snowdrit.mamokeep.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.repo.CarOverview
import io.github.snowdrit.mamokeep.data.repo.GarageRepository
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.domain.MaintenanceRecord
import io.github.snowdrit.mamokeep.domain.OdometerReading
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import io.github.snowdrit.mamokeep.domain.export.BackupCurrency
import io.github.snowdrit.mamokeep.domain.export.ExportTexts
import io.github.snowdrit.mamokeep.domain.export.MarkdownExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(
    private val repository: GarageRepository,
    private val settings: SettingsStore,
) : ViewModel() {

    /**
     * Счётчик пересчёта. Нужен смене языка: названия позиций подставляются на границе
     * базы, а лента `overview` их уже выдала — и сама по себе не перевыдаст, пока не
     * изменится база. `ViewModel` при смене языка переживает пересоздание экрана, так
     * что без внешнего толчка на экране остались бы названия на прежнем языке.
     */
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val overview: StateFlow<CarOverview?> = combine(settings.selectedCarId, refresh) { carId, _ -> carId }
        .flatMapLatest { carId -> repository.observeOverview(carId) { LocalDate.now() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Зовётся экраном, когда язык приложения оказался не тем, с которым лента собиралась. */
    fun reread() {
        refresh.value++
    }

    fun selectCar(carId: Long) {
        viewModelScope.launch { settings.setSelectedCarId(carId) }
    }

    /**
     * Новая машина получает стартовый набор и сразу становится открытой: человек нажал
     * «добавить», значит дальше он занимается именно ей.
     */
    fun addCar(name: String, region: RegionPreset?, units: UnitSystem) {
        viewModelScope.launch {
            settings.setSelectedCarId(repository.addCar(name, region, units))
        }
    }

    /**
     * Удаляет машину со всем её содержимым и открывает первую из оставшихся.
     *
     * @param cancelNotifications получает позиции удаляемой машины **до** удаления:
     * иначе её напоминания остаются висеть в шторке и зовут обслуживать машину,
     * которой уже нет. Гасит их вызывающая сторона — уведомления это Android,
     * а не дело модели.
     */
    fun deleteCar(carId: Long, cancelNotifications: (List<Long>) -> Unit) {
        viewModelScope.launch {
            val itemIds = repository.itemIdsOf(carId)
            repository.deleteCar(carId)
            settings.setSelectedCarId(0L)
            cancelNotifications(itemIds)
        }
    }

    fun addOdometerReading(carId: Long, km: Int) {
        viewModelScope.launch {
            repository.addOdometerReading(carId, km, LocalDate.now())
        }
    }

    /**
     * Отметка «сделано». Дата приходит снаружи: её может выбрать человек, купивший
     * полис за месяц до установки приложения.
     */
    suspend fun markDone(itemId: Long, atKm: Int?, date: LocalDate, onSaved: () -> Unit = {}) {
        repository.markDone(itemId, atKm, date)
        onSaved()
    }

    suspend fun odometerReadings(carId: Long): List<OdometerReading> =
        repository.odometerReadings(carId)

    /** Текст выгрузки. Пустая строка, если машины ещё нет — рисовать нечего. */
    suspend fun exportText(
        carId: Long,
        preset: ExportPreset,
        anonymize: Boolean,
        display: Display,
        texts: ExportTexts,
        question: String = "",
    ): String {
        val snapshot = repository.snapshot(carId, LocalDate.now()) ?: return ""
        // Выгрузку читает нейросеть, но пишет её человек: числа в тех единицах и слова
        // на том языке, которые он видит у себя на экране.
        return withContext(Dispatchers.Default) {
            MarkdownExporter.render(snapshot, preset, anonymize, display, texts, question)
        }
    }

    suspend fun missingRegionDocuments(carId: Long, preset: RegionPreset): List<String> =
        repository.missingRegionDocuments(carId, preset)

    fun addRegionDocuments(carId: Long, preset: RegionPreset) {
        viewModelScope.launch { repository.addRegionDocuments(carId, preset) }
    }

    suspend fun buildBackup(): String = withContext(Dispatchers.Default) {
        repository.exportBackup(LocalDate.now())
    }

    suspend fun restoreBackup(content: String, confirmedCurrency: BackupCurrency): Int? = withContext(Dispatchers.Default) {
        repository.importBackup(content, confirmedCurrency).also { restored ->
            if (restored != null) settings.markGarageInitialized()
        }
    }

    fun fuelEntriesOf(carId: Long): Flow<List<FuelEntry>> = repository.observeFuelEntries(carId)

    suspend fun fuelEntry(id: Long): FuelEntry? = repository.fuelEntry(id)

    /**
     * Правка заправки, в том числе перенос на другую машину: одометр хранится внутри
     * заправки и уезжает вместе с ней.
     */
    suspend fun saveFuelEntry(
        id: Long,
        carId: Long,
        date: LocalDate,
        odometerKm: Int,
        amount: Double,
        fuelType: FuelType,
        costKopecks: Long,
        isFull: Boolean,
    ) {
        repository.updateFuelEntry(id, carId, date, odometerKm, amount, fuelType, costKopecks, isFull)
    }

    /**
     * Границы одометра вокруг даты — по ним редактор заправки предупреждает, что пробег
     * машины после сохранения уедет назад.
     */
    suspend fun odometerBounds(carId: Long, date: LocalDate, exceptFuelId: Long? = null) =
        repository.odometerBounds(carId, date, exceptFuelId)

    suspend fun deleteFuelEntry(id: Long) = repository.deleteFuelEntry(id)

    /** Удаление пачкой из журнала: заправки и отметки вперемешку. */
    fun deleteJournalEntries(entries: List<JournalEntry>) {
        viewModelScope.launch {
            repository.deleteJournalEntries(
                fuelIds = entries.mapNotNull { it.fuelEntryId },
                recordIds = entries.mapNotNull { it.recordId },
            )
        }
    }

    suspend fun lastKnownKm(carId: Long): Int? = repository.lastKnownKm(carId)

    /** Правка отметки «сделано»: дата и пробег. «Последний раз» пересчитается сам. */
    /**
     * Правка отметки: дата, пробег и позиция. «Последний раз» пересчитается у обеих
     * позиций сам — и у той, куда отметка уехала, и у той, откуда ушла.
     */
    suspend fun saveRecord(recordId: Long, itemId: Long?, date: LocalDate, km: Int?, note: String?) =
        repository.updateRecord(recordId, itemId, date, km, note)

    /** Расчётный пробег машины на сегодня — подсказкой под полем одометра. */
    suspend fun mileageNow(carId: Long) = repository.mileageNow(carId)

    /** Что останется у покинутой позиции, если отметка уедет. */
    suspend fun lastDoneWithout(recordId: Long): LocalDate? = repository.lastDoneWithout(recordId)

    suspend fun deleteRecord(recordId: Long) = repository.deleteRecord(recordId)

    suspend fun addFuelEntry(
        carId: Long,
        date: LocalDate,
        odometerKm: Int,
        amount: Double,
        fuelType: FuelType,
        costKopecks: Long,
        isFull: Boolean,
    ) {
        repository.addFuelEntry(
                carId = carId,
                date = date,
                odometerKm = odometerKm,
                amount = amount,
                fuelType = fuelType,
                costKopecks = costKopecks,
                isFull = isFull,
        )
    }

    /** null means the first database result has not arrived; an empty list is a loaded garage. */
    val cars: StateFlow<List<Car>?> = repository.observeCars()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun saveCar(car: Car) {
        repository.saveCar(car)
    }

    fun journalOf(carId: Long): Flow<List<JournalEntry>> = repository.observeJournal(carId)

    fun recordsOf(itemId: Long): Flow<List<MaintenanceRecord>> = repository.observeRecords(itemId)

    fun standaloneReadings(carId: Long) = repository.observeStandaloneOdometerReadings(carId)
    suspend fun updateOdometerReading(carId: Long, id: Long, km: Int, date: LocalDate) =
        repository.updateOdometerReading(carId, id, km, date)
    suspend fun deleteOdometerReading(carId: Long, id: Long) = repository.deleteOdometerReading(carId, id)
    suspend fun saveScannedWork(itemId: Long, date: LocalDate, km: Int?, note: String) =
        repository.markDone(itemId, km, date, note)

    /** Новая позиция приходит с `id == 0` — Room раздаёт идентификаторы сам. */
    fun saveItem(carId: Long, item: MaintenanceItem) {
        viewModelScope.launch {
            if (item.id == 0L) repository.addItem(carId, item) else repository.updateItem(item)
        }
    }

    suspend fun saveItemWithReminder(carId: Long, item: MaintenanceItem, rule: ReminderRule?) =
        repository.saveItemWithReminder(carId, item, rule)

    fun deleteItem(itemId: Long, onDeleted: () -> Unit = {}) {
        viewModelScope.launch { repository.deleteItem(itemId); onDeleted() }
    }
}
