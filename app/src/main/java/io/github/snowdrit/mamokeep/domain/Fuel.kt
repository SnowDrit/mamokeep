package io.github.snowdrit.mamokeep.domain

import java.time.LocalDate

/**
 * Вид топлива.
 *
 * Принадлежит ЗАПРАВКЕ, а не машине (docs/DECISIONS.md, решение 23). Битопливная машина
 * ездит и на бензине, и на газе; подзаряжаемый гибрид — на бензине и электричестве;
 * грузовик — на дизеле и метане. Сочетание может быть любым, поэтому нигде в коде нет
 * ни одной зашитой пары: есть пять видов, и в заправках встречается сколько встречается.
 *
 * Марки бензина сознательно не разделены. У того, кто льёт то 92-й, то 95-й, расход
 * разъехался бы на два ряда, и ни один не набрал бы двух полных баков: вместо одного
 * честного числа человек получил бы два прочерка. Марка живёт справочным полем в профиле
 * машины ([FuelGrade]) и ни в один расчёт не входит.
 *
 * Названий здесь нет: домен хранит ключ, слова подставляет интерфейс (решение 22).
 */
enum class FuelType(
    /**
     * Жидкость: только у такого топлива объём переводится в галлоны. Метан продают
     * кубометрами, электричество — киловатт-часами; эти единицы не метрические и не
     * имперские, они просто такие. См. [UnitSystem].
     */
    val isLiquid: Boolean,
) {
    PETROL(isLiquid = true),
    DIESEL(isLiquid = true),
    LPG(isLiquid = true),
    CNG(isLiquid = false),
    ELECTRIC(isLiquid = false),
    ;

    /**
     * До 100% электромобиль заряжают редко и намеренно: держать 80% — рекомендация
     * производителя ради ресурса батареи. Формуле нужен не «полный бак», а одинаковый
     * уровень в начале и в конце отрезка, поэтому у электричества галочка называется иначе.
     */
    val chargedToLimit: Boolean get() = this == ELECTRIC

    companion object {
        /**
         * Неизвестный ключ читаем как бензин: до такого файла добирается только тот, кто
         * правил копию руками, и ронять из-за этого восстановление незачем.
         */
        fun of(key: String?): FuelType = FuelType.entries.firstOrNull { it.name == key } ?: PETROL
    }
}

/**
 * Чем заправляю — справочная заметка в профиле машины.
 *
 * Ни в одном расчёте не участвует. Это поле того же рода, что VIN (решение 6): не шаг
 * онбординга и не механизм, а строчка, которую открываешь, когда забыл, 92-й или 95-й.
 * Именно марку на заправке и забывают: «бензин или дизель» помнит любой.
 *
 * Ключ строкой, как у значков и типов кузова (решение 22): список марок — данные,
 * а названия к ним подставляет интерфейс из ресурсов.
 */
enum class FuelGrade(val key: String, val fuelType: FuelType) {
    AI_92("ai_92", FuelType.PETROL),
    AI_95("ai_95", FuelType.PETROL),
    AI_98("ai_98", FuelType.PETROL),
    AI_100("ai_100", FuelType.PETROL),
    DIESEL("diesel", FuelType.DIESEL),
    LPG("lpg", FuelType.LPG),
    CNG("cng", FuelType.CNG),
    ELECTRIC("electric", FuelType.ELECTRIC),
    ;

    companion object {
        fun of(key: String?): FuelGrade? = FuelGrade.entries.firstOrNull { it.key == key }
    }
}

/**
 * Заправка.
 *
 * Деньги храним в копейках целым числом: копить средние по `Double` рублям — верный способ
 * получить «2 899,999999 ₽».
 */
data class FuelEntry(
    val id: Long,
    /** Машина, которой принадлежит заправка: её можно поменять, если записал не туда. */
    val carId: Long,
    val date: LocalDate,
    val odometerKm: Int,
    /** Литры, кубометры или киловатт-часы — какие именно, говорит [fuelType]. */
    val amount: Double,
    val fuelType: FuelType = FuelType.PETROL,
    val costKopecks: Long,
    /** Залит полный бак; у электромобиля — заряжен до обычного предела. */
    val isFull: Boolean,
)

/** Расход по одному виду топлива. */
data class FuelUsage(
    val fuelType: FuelType,
    /**
     * Сколько единиц этого вида уходит на 100 км ОБЩЕГО пробега.
     *
     * Это не «расход на газу». Приложение не знает, где машина переключилась с газа на
     * бензин, и пробег именно на газу измерить нечем — такого датчика нет. Честно
     * считается только доля вида в общем пробеге, и подписывать её надо так же.
     */
    val perHundredKm: Double,
    val costPerKmKopecks: Long,
)

data class FuelStats(
    /** По строке на каждый вид, у которого набралось два полных бака ЭТОГО вида. */
    val usage: List<FuelUsage> = emptyList(),
    /** Виды, которые вообще встречались в заправках, — включая те, где расход ещё не считается. */
    val types: List<FuelType> = emptyList(),
    val lastEntry: FuelEntry? = null,
) {
    /**
     * Стоимость километра — сумма по видам: за один и тот же километр человек платит
     * всеми видами сразу. Отрезки у видов могут не совпадать, поэтому сумма — оценка;
     * каждое слагаемое при этом честное, а у машины с одним видом это ровно та же
     * цифра, что была до появления видов.
     */
    val costPerKmKopecks: Long?
        get() = if (usage.isEmpty()) null else usage.sumOf { it.costPerKmKopecks }
}

/**
 * Расход считается только между полными баками — и отдельно по каждому виду топлива.
 *
 * Причина «только между полными»: залив 20 литров в недопитый бак, ты не знаешь, сколько
 * в нём было. Известно лишь одно: от полного до полного израсходовано ровно столько,
 * сколько залито в промежутке. Любая другая формула даёт красивое, но выдуманное число.
 *
 * Причина «отдельно по видам»: сложив литры бензина с литрами газа, получаешь среднее,
 * которого не существует. Виды не смешиваются даже в промежуточных суммах.
 */
object FuelCalculator {

    fun stats(entries: List<FuelEntry>): FuelStats {
        val last = entries.maxWithOrNull(compareBy({ it.date }, { it.odometerKm }))
        val byType = entries.groupBy { it.fuelType }

        // Порядок — по объявлению вида, а не по числу заправок: список не должен
        // перескакивать местами от каждой новой записи.
        return FuelStats(
            usage = FuelType.entries.mapNotNull { type -> byType[type]?.let { usage(type, it) } },
            types = FuelType.entries.filter { byType.containsKey(it) },
            lastEntry = last,
        )
    }

    /** Расход одного вида. `null`, пока у этого вида нет двух полных баков. */
    private fun usage(type: FuelType, entries: List<FuelEntry>): FuelUsage? {
        val sorted = entries.sortedBy { it.odometerKm }

        val firstFull = sorted.indexOfFirst { it.isFull }
        val lastFull = sorted.indexOfLast { it.isFull }
        if (firstFull < 0 || firstFull == lastFull) return null

        val distance = sorted[lastFull].odometerKm - sorted[firstFull].odometerKm
        if (distance <= 0) return null

        // Всё, что залито ПОСЛЕ первого полного бака и до последнего включительно, — это
        // ровно то, что сожжено на пройденном между ними расстоянии. Количество самого
        // первого бака не в счёт: оно сгорело ещё до начала наблюдений.
        //
        // Промежуточные полные баки специально не разбиваются на отрезки: отрезок нулевой
        // длины (две заправки при одном и том же одометре) иначе выпадал бы вместе с
        // залитым в него топливом.
        var amount = 0.0
        var kopecks = 0L
        for (i in firstFull + 1..lastFull) {
            amount += sorted[i].amount
            kopecks += sorted[i].costKopecks
        }

        if (amount <= 0.0) return null

        return FuelUsage(
            fuelType = type,
            perHundredKm = amount / distance * 100,
            costPerKmKopecks = kopecks / distance,
        )
    }
}
