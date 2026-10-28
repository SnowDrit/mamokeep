package io.github.snowdrit.mamokeep.domain

/** Значения полей профиля до сохранения, включая ещё не разобранный год. */
data class CarProfileDraft(
    val name: String,
    val make: String,
    val model: String,
    val year: String,
    val vin: String,
    val bodyType: String?,
    val fuelGrade: String?,
    val silhouetteColor: Int?,
) {
    /** Сравнивается сам ввод: возврат исходного значения отменяет изменение. */
    fun hasChanges(car: Car): Boolean = this != fromCar(car)

    /** Изменяет только поля профиля, сохраняя идентификатор и прочие данные машины. */
    fun toCar(original: Car, defaultName: String): Car = original.copy(
        name = name.trim().ifBlank { defaultName },
        make = make.trim().ifBlank { null },
        model = model.trim().ifBlank { null },
        year = year.toIntOrNull(),
        vin = vin.trim().ifBlank { null },
        bodyType = bodyType?.trim()?.ifBlank { null },
        fuelGrade = fuelGrade?.trim()?.ifBlank { null },
        silhouetteColor = silhouetteColor,
    )

    companion object {
        fun fromCar(car: Car): CarProfileDraft = CarProfileDraft(
            name = car.name,
            make = car.make.orEmpty(),
            model = car.model.orEmpty(),
            year = car.year?.toString().orEmpty(),
            vin = car.vin.orEmpty(),
            bodyType = car.bodyType,
            fuelGrade = car.fuelGrade,
            silhouetteColor = car.silhouetteColor,
        )
    }
}
