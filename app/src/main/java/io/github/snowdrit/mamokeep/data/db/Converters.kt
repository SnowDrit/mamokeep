package io.github.snowdrit.mamokeep.data.db

import androidx.room.TypeConverter
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.ItemKind
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun kindToString(kind: ItemKind): String = kind.name

    @TypeConverter
    fun stringToKind(value: String): ItemKind = ItemKind.valueOf(value)

    @TypeConverter
    fun fuelTypeToString(type: FuelType): String = type.name

    /** Через [FuelType.of], а не `valueOf`: неизвестный ключ не должен ронять открытие базы. */
    @TypeConverter
    fun stringToFuelType(value: String): FuelType = FuelType.of(value)
}
