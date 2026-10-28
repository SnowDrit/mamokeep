package io.github.snowdrit.mamokeep.domain

/**
 * Автомобиль.
 *
 * Марка, модель и год — свободный текст. Справочник моделей был бы приятнее, но это
 * добыча данных, а не программирование: та же ловушка, что с регламентами. Появится —
 * ляжет пресет-паком поверх этих же полей (docs/DECISIONS.md, решение 5).
 */
data class Car(
    val id: Long,
    val name: String,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val vin: String? = null,
    val bodyType: String? = null,
    /** Цвет линий силуэта, ARGB. Пусто — цвет темы. */
    val silhouetteColor: Int? = null,
    /**
     * Чем заправляю — заметка на память, ключ [FuelGrade]. Ни в одном расчёте не
     * участвует: вид топлива указывается у каждой заправки отдельно (решение 23).
     */
    val fuelGrade: String? = null,
)
