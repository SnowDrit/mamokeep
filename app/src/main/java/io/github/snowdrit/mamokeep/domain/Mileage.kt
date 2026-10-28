package io.github.snowdrit.mamokeep.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** Показание одометра, введённое пользователем. */
data class OdometerReading(
    val date: LocalDate,
    val km: Int,
) {
    companion object {
        /**
         * Сводит показания из разных источников в одну ленту: записанные вручную и те,
         * что человек ввёл, отмечая заправку.
         *
         * Заправка хранит свой одометр внутри себя, отдельной строки в таблице показаний
         * для неё нет. Иначе перенос заправки на другую машину или её удаление оставляли
         * бы показание висеть на прежней машине и врать про её пробег.
         *
         * Совпадающие показания схлопываются: в базе, заведённой прежними сборками,
         * одна и та же заправка успела породить обе строки.
         *
         * Порядок — по дате, а при одной дате по возрастанию километров: за день одометр
         * не убывает, и «последним известным» должно оказаться большее число.
         */
        fun merge(vararg sources: List<OdometerReading>): List<OdometerReading> =
            sources.asSequence()
                .flatten()
                .distinct()
                .sortedWith(compareBy({ it.date }, { it.km }))
                .toList()
    }
}

/**
 * Оценка пробега на сегодня.
 *
 * Хранит не только число, но и то, из чего оно получено: приложение обязано уметь
 * объяснить любую посчитанную цифру человеческим языком (docs/DECISIONS.md, решение 13).
 */
data class MileageEstimate(
    val km: Int,
    val lastKnownKm: Int,
    val lastKnownDate: LocalDate,
    /** Средний дневной пробег, которым достраивались дни после последнего ввода. */
    val kmPerDay: Double?,
    val daysSinceLastReading: Long,
) {
    val isExtrapolated: Boolean get() = km != lastKnownKm
}

/**
 * Достраивает пробег между вводами одометра.
 *
 * Мы сознательно не гонимся за километровой точностью: для напоминания «масло через
 * ~800 км» достаточно ±3%, а всё, что точнее, требует фонового GPS с его расходом
 * батареи и разрешениями (docs/DECISIONS.md, решение 3).
 */
object MileageEstimator {

    /**
     * Окно, по которому считается средний дневной пробег. Год назад человек мог ездить
     * совсем иначе — старые данные скорее вредят, чем помогают.
     */
    private const val WINDOW_DAYS = 180L

    fun estimate(readings: List<OdometerReading>, today: LocalDate): MileageEstimate? {
        if (readings.isEmpty()) return null

        val sorted = readings.sortedBy { it.date }
        val last = sorted.last()
        val kmPerDay = averageKmPerDay(sorted, today)
        val days = ChronoUnit.DAYS.between(last.date, today).coerceAtLeast(0)
        val extrapolated = kmPerDay?.let { (it * days).roundToInt() } ?: 0

        return MileageEstimate(
            km = last.km + extrapolated,
            lastKnownKm = last.km,
            lastKnownDate = last.date,
            kmPerDay = kmPerDay,
            daysSinceLastReading = days,
        )
    }

    /**
     * Пробег на произвольную дату, **в том числе на прошедшую**.
     *
     * Нужен отметке «сделано» задним числом. Человек ставит дату месячной давности, а в
     * поле пробега остаётся сегодняшний одометр — и мы записываем, что масло меняли месяц
     * назад при сегодняшнем пробеге. Следующий срок по оси километров уезжает вперёд
     * ровно на месяц езды, тихо и молча. Это тот же класс дефекта, ради которого отметка
     * задним числом и делалась.
     *
     * Между двумя вводами считаем по прямой между ними: это ровно то, что известно, и
     * ничего сверх. За краями ленты достраиваем средним дневным.
     *
     * `null` означает «нечем считать» — пустое поле честнее выдуманного числа.
     */
    fun kmAt(readings: List<OdometerReading>, date: LocalDate): Int? {
        if (readings.isEmpty()) return null

        val sorted = readings.sortedWith(compareBy({ it.date }, { it.km }))

        // Точное попадание в день ввода: берём наибольшее за этот день — за сутки
        // одометр не убывает, тот же порядок держит и `OdometerReading.merge`.
        sorted.lastOrNull { it.date == date }?.let { return it.km }

        val before = sorted.lastOrNull { it.date.isBefore(date) }
        val after = sorted.firstOrNull { it.date.isAfter(date) }

        return when {
            before != null && after != null -> between(before, after, date)

            // Дата позже всей ленты — та же достройка, что у `estimate`.
            before != null -> {
                val perDay = averageKmPerDay(sorted, date) ?: return before.km
                before.km + (perDay * ChronoUnit.DAYS.between(before.date, date)).roundToInt()
            }

            // Дата раньше всей ленты: отматываем назад тем же средним. Отматывать нечем —
            // значит нечем, и выдумывать мы не будем.
            after != null -> {
                val perDay = averageKmPerDay(sorted, after.date) ?: return null
                val back = (perDay * ChronoUnit.DAYS.between(date, after.date)).roundToInt()
                (after.km - back).coerceAtLeast(0)
            }

            else -> null
        }
    }

    /** Пробег между двумя известными вводами — по прямой между ними. */
    private fun between(before: OdometerReading, after: OdometerReading, date: LocalDate): Int {
        val span = ChronoUnit.DAYS.between(before.date, after.date)
        if (span <= 0) return after.km

        val passed = ChronoUnit.DAYS.between(before.date, date)
        val grown = (after.km - before.km).toDouble() * passed / span
        return before.km + grown.roundToInt()
    }

    /**
     * Средний дневной пробег по последним показаниям. `null`, если данных не хватает —
     * тогда честнее показать последнее известное число, чем выдумывать.
     */
    private fun averageKmPerDay(sorted: List<OdometerReading>, today: LocalDate): Double? {
        if (sorted.size < 2) return null

        val cutoff = today.minusDays(WINDOW_DAYS)
        val window = sorted.filter { !it.date.isBefore(cutoff) }
            .takeIf { it.size >= 2 }
            ?: sorted.takeLast(2)

        val first = window.first()
        val last = window.last()
        val days = ChronoUnit.DAYS.between(first.date, last.date)
        val km = last.km - first.km

        return if (days > 0 && km > 0) km.toDouble() / days else null
    }
}

/**
 * Что записано у машины по обе стороны от даты.
 *
 * Нужно редактору заправки. Заправка — это ещё и показание одометра, а лента показаний
 * сортируется по дате: дата, поставленная мимо, способна сделать «последним известным»
 * меньшее число, и пробег машины уедет назад. Запрещать такое нельзя — человек может
 * чинить как раз ту заправку, которая записана криво, — но сказать об этом он должен
 * успеть до сохранения.
 */
data class OdometerBounds(
    /** Наибольшее показание строго раньше даты. */
    val maxBefore: Int? = null,
    /** Наименьшее показание строго позже даты. */
    val minAfter: Int? = null,
)

/**
 * Показания того же дня в расчёт не берём: за сутки одометр не убывает, и два числа
 * за один день друг другу не противоречат. Тот же порядок держит [OdometerReading.merge].
 */
fun List<OdometerReading>.boundsAt(date: LocalDate): OdometerBounds = OdometerBounds(
    maxBefore = filter { it.date.isBefore(date) }.maxOfOrNull { it.km },
    minAfter = filter { it.date.isAfter(date) }.minOfOrNull { it.km },
)
