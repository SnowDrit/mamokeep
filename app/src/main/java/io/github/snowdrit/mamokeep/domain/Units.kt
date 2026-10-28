package io.github.snowdrit.mamokeep.domain

import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val KM_PER_MILE = 1.609344
private const val LITERS_PER_US_GALLON = 3.785411784
private const val LITERS_PER_UK_GALLON = 4.54609

/** Чем меряется расстояние. Как оно называется словом — дело [Display], а не домена. */
enum class DistanceUnit { KM, MILE }

/** Чем меряется количество топлива. */
enum class AmountUnit { LITER, GALLON, CUBIC_METER, KWH }

/**
 * Набор единиц измерения.
 *
 * Три набора, и каждый — реальная страна, а не абстракция «метрическая против имперской».
 * Британия смешанная: топливо там продают литрами, расстояние меряют милями, а расход
 * называют в милях на **имперский** галлон, который на 20% больше американского. Один
 * тумблер «метрика/имперская» соврал бы либо британцу про литры, либо американцу про
 * размер галлона.
 *
 * Хранение всегда каноническое — километры, литры, копейки. Набор меняет только то, что
 * человек видит и вводит. Пересчитывать историю при смене единиц нельзя: записанное
 * однажды число должно остаться тем, что человек записал.
 *
 * Названий единиц здесь нет намеренно: домен хранит ключ, слова подставляет интерфейс
 * из ресурсов (docs/DECISIONS.md, решение 22) — иначе «л» и «gal» пришлось бы держать
 * в коде и переводить правкой кода.
 */
enum class UnitSystem(
    val distanceUnit: DistanceUnit,
    private val kmPerDistance: Double,
    private val liquidUnit: AmountUnit,
    private val litersPerLiquid: Double,
    /**
     * Литров в галлоне, которым принято мерить расход. `null` — расход показывается как
     * «объём на 100 единиц расстояния», а не как «расстояние на объём».
     */
    private val litersPerMpgGallon: Double?,
) {
    METRIC(DistanceUnit.KM, 1.0, AmountUnit.LITER, 1.0, null),
    UK(DistanceUnit.MILE, KM_PER_MILE, AmountUnit.LITER, 1.0, LITERS_PER_UK_GALLON),
    US(DistanceUnit.MILE, KM_PER_MILE, AmountUnit.GALLON, LITERS_PER_US_GALLON, LITERS_PER_US_GALLON),
    ;

    /** Расстояние меряется милями: от этого зависят и дефолтные интервалы обслуживания. */
    val milesBased: Boolean get() = distanceUnit == DistanceUnit.MILE

    /** Километры из базы → то, что видит человек. */
    fun distance(km: Int): Double = km / kmPerDistance

    /** Средний дневной пробег сохраняет дробную часть до форматирования. */
    fun distance(km: Double): Double = km / kmPerDistance

    /** То, что ввёл человек, → километры для хранения. */
    fun kmFrom(distance: Double): Int = (distance * kmPerDistance).roundToInt()

    /**
     * Литры из базы → то, что видит человек. Метан и электричество не переводятся никогда:
     * кубометр остаётся кубометром, киловатт-час — киловатт-часом. Меняется только
     * расстояние, на которое их делят.
     */
    fun volume(liters: Double, type: FuelType): Double =
        if (type.isLiquid) liters / litersPerLiquid else liters

    /** Обратно: введённый объём → литры для хранения. */
    fun litersFrom(volume: Double, type: FuelType): Double =
        if (type.isLiquid) volume * litersPerLiquid else volume

    fun amountUnit(type: FuelType): AmountUnit = when {
        type.isLiquid -> liquidUnit
        type == FuelType.CNG -> AmountUnit.CUBIC_METER
        else -> AmountUnit.KWH
    }

    /** Копейки за километр → копейки за километр или милю. */
    fun costPerDistance(kopecksPerKm: Long): Long = (kopecksPerKm * kmPerDistance).roundToLong()

    /**
     * Расход одного вида топлива в единицах этого набора.
     *
     * @param perHundredKm сколько единиц вида уходит на 100 км — как считает [FuelCalculator].
     */
    fun consumption(perHundredKm: Double, type: FuelType): Consumption {
        val gallon = litersPerMpgGallon
        if (gallon != null && type.isLiquid && perHundredKm > 0.0) {
            // «Миль на галлон» — величина обратная: в числителе расстояние, а не объём.
            val kmPerLiter = 100.0 / perHundredKm
            return Consumption(value = kmPerLiter / KM_PER_MILE * gallon, amountUnit = null)
        }
        return Consumption(
            value = volume(perHundredKm * kmPerDistance, type),
            amountUnit = amountUnit(type),
        )
    }

    companion object {
        /**
         * Набор по умолчанию — по стране телефона.
         *
         * Иначе имперские дефолты интервалов не применились бы никогда: база наполняется
         * при первом запуске, задолго до того, как человек доберётся до настроек.
         * Мьянма и Либерия формально тоже имперские, но там нашего пользователя нет.
         */
        fun forCountry(country: String?): UnitSystem = when (country?.uppercase()) {
            "US" -> US
            "GB" -> UK
            else -> METRIC
        }
    }
}

/**
 * Расход, готовый к показу.
 *
 * `amountUnit == null` означает mpg: там расстояние уже в числителе, и делить на сто
 * ничего не надо. Это две разные величины, а не две записи одной.
 */
data class Consumption(val value: Double, val amountUnit: AmountUnit?)
