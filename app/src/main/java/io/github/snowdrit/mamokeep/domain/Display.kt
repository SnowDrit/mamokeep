package io.github.snowdrit.mamokeep.domain

/**
 * Всё, что нужно, чтобы показать число человеку: пересчёт, знак валюты и слова.
 *
 * Слова приходят из ресурсов и складываются сюда в интерфейсе — домен их не выдумывает.
 * Поэтому выгрузка и уведомления говорят на том же языке, что экран, хотя ни то, ни
 * другое про Android не знает.
 *
 * Ходит одним объектом: полтора десятка отдельных параметров пришлось бы протаскивать
 * через каждый composable по очереди, и рано или поздно один из них потерялся бы.
 */
data class Display(
    val units: UnitSystem,
    val currency: String,
    val words: DisplayWords,
) {
    val distanceUnit: String get() = words.distance(units.distanceUnit)

    val distanceNoun: String get() = words.distanceNoun(units.distanceUnit)

    /**
     * То же существительное, но в середине фразы: «за километр», «per mile».
     * Отдельное слово, а не смена регистра: в английском в начале фразы нужен артикль,
     * а в середине — нет.
     */
    val distanceNounPer: String get() = words.distanceNounPer(units.distanceUnit)

    fun amountUnit(type: FuelType): String = words.amount(units.amountUnit(type))

    /** У mpg своей единицы количества нет: расстояние там уже в числителе. */
    fun consumptionUnit(consumption: Consumption): String =
        consumption.amountUnit?.let { words.amount(it) } ?: words.mpg
}

/**
 * Слова и знаки, зависящие от языка.
 *
 * Умолчаний нет намеренно: значение по умолчанию означало бы русский текст, зашитый
 * в код, — ровно то, от чего мы уходим. Собирается один раз из ресурсов.
 */
data class DisplayWords(
    val km: String,
    val mile: String,
    /** «Километр обходится в 5,60 ₽» — существительное, а не сокращение. */
    val kmNoun: String,
    val mileNoun: String,
    val kmNounPer: String,
    val mileNounPer: String,
    val liter: String,
    val gallon: String,
    val cubicMeter: String,
    val kwh: String,
    val mpg: String,
    /** «на 100» в «8,0 л на 100 км». */
    val perHundred: String,
    /** «пробега» — приписка для машины с несколькими видами топлива. */
    val ofTotal: String,
    /** Разделитель дробной части: запятая по-русски, точка по-английски. */
    val decimalSeparator: Char,
    /** Разделитель разрядов. */
    val groupSeparator: Char,
    val days: String,
    val months: String,
    val years: String,
) {
    fun distance(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> km
        DistanceUnit.MILE -> mile
    }

    fun distanceNoun(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> kmNoun
        DistanceUnit.MILE -> mileNoun
    }

    fun distanceNounPer(unit: DistanceUnit): String = when (unit) {
        DistanceUnit.KM -> kmNounPer
        DistanceUnit.MILE -> mileNounPer
    }

    fun amount(unit: AmountUnit): String = when (unit) {
        AmountUnit.LITER -> liter
        AmountUnit.GALLON -> gallon
        AmountUnit.CUBIC_METER -> cubicMeter
        AmountUnit.KWH -> kwh
    }
}
