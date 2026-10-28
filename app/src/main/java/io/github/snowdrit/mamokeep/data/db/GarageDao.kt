package io.github.snowdrit.mamokeep.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GarageDao {

    @Query("SELECT * FROM cars ORDER BY id LIMIT 1")
    fun observeFirstCar(): Flow<CarEntity?>

    /**
     * Выбранная машина или, если такой уже нет, первая по счёту. `COALESCE` вместо
     * ветвления в коде: иначе после удаления машины экран на мгновение оставался бы пустым.
     */
    @Query("SELECT * FROM cars WHERE id = COALESCE((SELECT id FROM cars WHERE id = :carId), (SELECT id FROM cars ORDER BY id LIMIT 1))")
    fun observeCar(carId: Long): Flow<CarEntity?>

    @Delete
    suspend fun deleteCar(car: CarEntity)

    @Query("SELECT * FROM cars WHERE id = :carId")
    suspend fun car(carId: Long): CarEntity?

    @Query("SELECT * FROM cars ORDER BY id LIMIT 1")
    suspend fun firstCar(): CarEntity?

    @Insert
    suspend fun insertCar(car: CarEntity): Long

    @Update
    suspend fun updateCar(car: CarEntity)

    @Query("SELECT * FROM cars ORDER BY id")
    fun observeCars(): Flow<List<CarEntity>>

    @Query("SELECT * FROM odometer_readings WHERE carId = :carId ORDER BY date")
    fun observeReadings(carId: Long): Flow<List<OdometerReadingEntity>>

    @Query("SELECT * FROM odometer_readings WHERE carId = :carId ORDER BY date")
    suspend fun readings(carId: Long): List<OdometerReadingEntity>

    @Insert
    suspend fun insertReading(reading: OdometerReadingEntity)

    @Query("UPDATE odometer_readings SET km = :km, date = :date WHERE id = :id AND carId = :carId")
    suspend fun updateReading(carId: Long, id: Long, km: Int, date: java.time.LocalDate): Int

    @Query("DELETE FROM odometer_readings WHERE id = :id AND carId = :carId")
    suspend fun deleteReading(carId: Long, id: Long): Int

    @Query("SELECT * FROM maintenance_items WHERE carId = :carId ORDER BY id")
    fun observeItems(carId: Long): Flow<List<MaintenanceItemEntity>>

    @Query("SELECT * FROM maintenance_items WHERE carId = :carId ORDER BY id")
    suspend fun items(carId: Long): List<MaintenanceItemEntity>

    @Query("SELECT * FROM maintenance_items WHERE id = :id")
    suspend fun item(id: Long): MaintenanceItemEntity?

    /** Возвращает id: он нужен, чтобы привязать историю при восстановлении копии. */
    @Insert
    suspend fun insertItem(item: MaintenanceItemEntity): Long

    @Update
    suspend fun updateItem(item: MaintenanceItemEntity)

    @Delete
    suspend fun deleteItem(item: MaintenanceItemEntity)

    @Insert
    suspend fun insertRecord(record: MaintenanceRecordEntity)

    @Query("SELECT * FROM maintenance_records WHERE itemId = :itemId ORDER BY date DESC, id DESC")
    fun observeRecords(itemId: Long): Flow<List<MaintenanceRecordEntity>>

    @Insert
    suspend fun insertFuelEntry(entry: FuelEntryEntity)

    @Query("SELECT * FROM fuel_entries WHERE carId = :carId ORDER BY odometerKm")
    fun observeFuelEntries(carId: Long): Flow<List<FuelEntryEntity>>

    @Query("SELECT * FROM fuel_entries WHERE carId = :carId ORDER BY odometerKm")
    suspend fun fuelEntries(carId: Long): List<FuelEntryEntity>

    /** Документы, заведённые сборками без ключей названий: им ключ проставляется задним числом. */
    @Query("SELECT * FROM maintenance_items WHERE kind = 'DOCUMENT' AND titleKey IS NULL")
    suspend fun documentsWithoutKey(): List<MaintenanceItemEntity>

    @Query("SELECT * FROM fuel_entries WHERE id = :id")
    suspend fun fuelEntry(id: Long): FuelEntryEntity?

    @Update
    suspend fun updateFuelEntry(entry: FuelEntryEntity)

    @Delete
    suspend fun deleteFuelEntry(entry: FuelEntryEntity)

    @Query("SELECT * FROM maintenance_records WHERE itemId = :itemId ORDER BY date, id")
    suspend fun recordsOf(itemId: Long): List<MaintenanceRecordEntity>

    @Query("SELECT * FROM maintenance_records WHERE itemId = :itemId ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun latestRecord(itemId: Long): MaintenanceRecordEntity?

    @Query("SELECT * FROM maintenance_records WHERE itemId = :itemId AND id != :recordId ORDER BY date DESC, id DESC LIMIT 1")
    suspend fun latestRecordExcluding(itemId: Long, recordId: Long): MaintenanceRecordEntity?

    /**
     * Убирает показания одометра, которые дословно повторяют заправку той же машины.
     * Та же чистка, что делает миграция 9 → 10, но для данных, приехавших из старой копии.
     */
    @Query(
        """
        DELETE FROM odometer_readings
        WHERE EXISTS (
            SELECT 1 FROM fuel_entries f
            WHERE f.carId = odometer_readings.carId
              AND f.date = odometer_readings.date
              AND f.odometerKm = odometer_readings.km
        )
        """
    )
    suspend fun dropReadingsDuplicatingFuel()

    /** Восстановление заменяет всё: остальные таблицы уйдут каскадом. */
    @Query("DELETE FROM cars")
    suspend fun deleteAllCars()

    /** История работ вместе с названиями позиций — для выгрузки и истории на экране. */
    @Query(
        """
        SELECT i.title AS title, i.titleKey AS titleKey, r.date AS date, r.km AS km,
               i.kind AS kind, r.note AS note
        FROM maintenance_records r
        JOIN maintenance_items i ON i.id = r.itemId
        WHERE r.carId = :carId
        ORDER BY r.date DESC, r.id DESC
        """
    )
    suspend fun doneWorks(carId: Long): List<DoneWorkRow>

    @Query(
        """
        SELECT r.id AS recordId, r.itemId AS itemId, i.title AS title, i.titleKey AS titleKey,
               r.date AS date, r.km AS km, i.kind AS kind, i.iconKey AS iconKey, r.note AS note
        FROM maintenance_records r
        JOIN maintenance_items i ON i.id = r.itemId
        WHERE r.carId = :carId
        ORDER BY r.date DESC, r.id DESC
        """
    )
    fun observeDoneWorks(carId: Long): Flow<List<DoneWorkDetailRow>>

    @Query("SELECT * FROM maintenance_records WHERE id = :id")
    suspend fun record(id: Long): MaintenanceRecordEntity?

    @Update
    suspend fun updateRecord(record: MaintenanceRecordEntity)

    @Delete
    suspend fun deleteRecord(record: MaintenanceRecordEntity)

    /** Все машины — уведомления приходят по всем, а не только по выбранной (решение 10). */
    @Query("SELECT * FROM cars ORDER BY id")
    suspend fun allCars(): List<CarEntity>
}
