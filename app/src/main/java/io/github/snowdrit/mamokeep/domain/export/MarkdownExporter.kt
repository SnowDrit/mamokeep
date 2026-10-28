package io.github.snowdrit.mamokeep.domain.export

import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.Urgency
import io.github.snowdrit.mamokeep.text.amountText
import io.github.snowdrit.mamokeep.text.consumptionText
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.durationText
import io.github.snowdrit.mamokeep.text.joinSentences
import io.github.snowdrit.mamokeep.text.moneyText
import io.github.snowdrit.mamokeep.text.dateText
import io.github.snowdrit.mamokeep.text.markdownInline
import kotlin.math.abs

/**
 * Выгрузка в Markdown.
 *
 * Почему Markdown, а не JSON: модель читает его отлично, человек перед вставкой видит
 * глазами, что именно отправляет, и токены не тратятся на пунктуацию. JSON остаётся
 * форматом бэкапа — там читателем выступает машина (docs/DECISIONS.md, решение 11).
 *
 * Слова приходят в [ExportTexts]: домен не знает про Android, но выгрузка обязана быть
 * на том же языке, что и экран.
 */
object MarkdownExporter {

    /**
     * @param anonymize скрыть поля VIN и названия машины. Свободный текст остаётся.
     * Подготовка и копирование локальны; отправку в чат выполняет пользователь.
     */
    fun render(
        snapshot: CarSnapshot,
        preset: ExportPreset,
        anonymize: Boolean,
        display: Display,
        texts: ExportTexts,
        question: String = "",
    ): String = buildString {
        appendLine(texts.prompt(preset))
        appendLine()
        if (question.isNotBlank()) {
            appendLine(texts.questionHeader)
            appendLine(question.markdownInline())
            appendLine()
        }
        appendLine(texts.dataNote)
        appendLine()

        appendCar(snapshot, anonymize, texts)
        appendMileage(snapshot, display, texts)
        appendReadings(snapshot, preset, display, texts)
        appendUpcoming(snapshot, display, texts)
        appendHistory(snapshot, preset, display, texts)
        appendFuel(snapshot, preset, display, texts)

        appendLine()
        appendLine("---")
        appendLine(texts.footer(snapshot.today.dateText()))
    }

    private fun StringBuilder.appendCar(
        snapshot: CarSnapshot,
        anonymize: Boolean,
        texts: ExportTexts,
    ) {
        appendLine(texts.carHeader)
        if (!anonymize) appendLine("- ${texts.name}: ${snapshot.carName.markdownInline()}")
        snapshot.make?.takeIf { it.isNotBlank() }?.let { appendLine("- ${texts.make}: ${it.markdownInline()}") }
        snapshot.model?.takeIf { it.isNotBlank() }?.let { appendLine("- ${texts.model}: ${it.markdownInline()}") }
        snapshot.year?.let { appendLine("- ${texts.year}: $it") }
        snapshot.fuelGrade?.let { appendLine("- ${texts.fuelGrade}: ${texts.gradeName(it)}") }
        if (!anonymize) snapshot.vin?.takeIf { it.isNotBlank() }?.let { appendLine("- ${texts.vin}: ${it.markdownInline()}") }
        if (anonymize) appendLine("- ${texts.anonymized}")
        appendLine()
    }

    private fun StringBuilder.appendMileage(
        snapshot: CarSnapshot,
        display: Display,
        texts: ExportTexts,
    ) {
        appendLine(texts.mileageHeader)
        val mileage = snapshot.mileage
        if (mileage == null) {
            appendLine("- ${texts.mileageUnknown}")
            appendLine()
            return
        }

        if (mileage.isExtrapolated) {
            appendLine("- ${texts.mileageNow}: ~${display.distanceText(mileage.km)}")
        }
        appendLine(
            "- ${texts.mileageLastReading}: ${display.distanceText(mileage.lastKnownKm)} " +
                "(${mileage.lastKnownDate.dateText()})"
        )
        mileage.kmPerDay?.let {
            appendLine("- ${texts.mileagePerDay}: ${display.distanceText(display.units.distance(it))}")
        }
        if (mileage.isExtrapolated) {
            // Модель должна понимать, что число расчётное, а не снятое с приборки.
            appendLine("- ${texts.mileageIsEstimate}")
        } else if (mileage.lastKnownDate < snapshot.today) {
            appendLine("- ${texts.mileageNotEstimated}")
        }
        appendLine()
    }

    private fun StringBuilder.appendReadings(
        snapshot: CarSnapshot,
        preset: ExportPreset,
        display: Display,
        texts: ExportTexts,
    ) {
        if (!preset.includeOdometerReadings || snapshot.odometerReadings.isEmpty()) return
        appendLine(texts.readingsHeader)
        snapshot.odometerReadings.sortedWith(compareBy({ it.date }, { it.km })).forEach {
            appendLine("- ${it.date.dateText()}: ${display.distanceText(it.km)}")
        }
        appendLine()
    }

    private fun StringBuilder.appendUpcoming(
        snapshot: CarSnapshot,
        display: Display,
        texts: ExportTexts,
    ) {
        val (documents, maintenance) = snapshot.statuses.partition { it.item.kind == ItemKind.DOCUMENT }

        appendLine(texts.maintenanceHeader)
        appendLine(texts.maintenanceBasis)
        if (maintenance.isEmpty()) appendLine("- ${texts.noItems}")
        maintenance.forEach { appendLine(statusLine(it, display, texts)) }
        appendUnknownNote(maintenance, texts)
        appendLine()

        if (documents.isNotEmpty()) {
            appendLine(texts.documentsHeader)
            documents.forEach { appendLine(statusLine(it, display, texts)) }
            appendUnknownNote(documents, texts)
            appendLine()
        }
    }

    private fun StringBuilder.appendUnknownNote(
        statuses: List<MaintenanceStatus>,
        texts: ExportTexts,
    ) {
        if (statuses.none { it.urgency == Urgency.UNKNOWN }) return
        appendLine()
        appendLine(texts.unknownNote)
    }

    private fun statusLine(status: MaintenanceStatus, display: Display, texts: ExportTexts): String {
        val baseMarker = when (status.urgency) {
            Urgency.OVERDUE -> texts.overdue
            Urgency.SOON -> texts.soon
            Urgency.OK -> texts.ok
            // Модель должна отличать «данных нет» от «всё хорошо». Пояснение даётся
            // один раз на раздел, а не повторяется в каждой строке: девять одинаковых
            // фраз — это раздувание контекста, против которого написано решение 11.
            Urgency.UNKNOWN -> texts.notMarked
        }

        // Позиция бывает просрочена по одной оси и в запасе по другой: масло за 10 000 км
        // или 12 месяцев, проехал больше — просрочено, а год ещё не прошёл. Без указания
        // оси выходило «просрочено, перепробег 1 000 км, осталось 1 г.» — строка спорила
        // сама с собой, и модель читала её так же двусмысленно, как человек.
        val kmOverdue = status.kmLeft?.let { it <= 0 } ?: false
        val timeOverdue = status.daysLeft?.let { it <= 0 } ?: false
        val oneAxisOnly = status.urgency == Urgency.OVERDUE && kmOverdue != timeOverdue

        val marker = when {
            status.urgency == Urgency.OVERDUE &&
                (status.kmLeft == null || status.kmLeft >= 0) &&
                (status.daysLeft == null || status.daysLeft >= 0) -> texts.dueNow
            !oneAxisOnly -> baseMarker
            kmOverdue -> texts.overdueByDistance
            else -> texts.overdueByTime
        }

        val left = buildList {
            status.kmLeft?.let {
                val distance = display.distanceText(abs(it))
                add(
                    when {
                        it == 0 -> texts.dueDistance
                        it < 0 -> texts.overDistance(distance)
                        oneAxisOnly -> texts.stillDistance(distance)
                        else -> texts.leftDistance(distance)
                    }
                )
            }
            status.daysLeft?.let {
                val time = display.durationText(abs(it))
                add(
                    when {
                        it == 0L -> texts.dueTime
                        it < 0 -> texts.overTime(time)
                        oneAxisOnly -> texts.stillTime(time)
                        else -> texts.leftTime(time)
                    }
                )
            }
        }

        val interval = buildList {
            status.item.intervalKm?.let { add(display.distanceText(it)) }
            status.item.intervalMonths?.let { add("$it ${display.words.months}") }
        }

        // Куски склеиваем через общий помощник: они заканчиваются сокращениями
        // с точкой, и на стыке она удваивалась.
        val sentences = buildList {
            add(if (left.isEmpty()) marker else "$marker, ${left.joinToString(", ")}")
            if (interval.isNotEmpty()) {
                add("${texts.interval}: ${interval.joinToString(" ${texts.or} ")}")
            }
            status.item.lastDoneDate?.let { add("${texts.lastTime}: ${it.dateText()}") }
            status.item.lastDoneKm?.let { add("${texts.lastMileage}: ${display.distanceText(it)}") }
            if (status.item.intervalMonths != null && status.item.lastDoneDate != null) {
                add("${texts.dueDate}: ${status.item.lastDoneDate.plusMonths(status.item.intervalMonths.toLong()).dateText()}")
            }
            // Полностью неизвестные сроки объясняются один раз под разделом.
            // При частичном расчёте важно не принять известную ось за полную оценку.
            if (status.urgency != Urgency.UNKNOWN) {
                if (status.item.intervalKm != null && status.kmLeft == null) add(texts.distanceUnknown)
                if (status.item.intervalMonths != null && status.daysLeft == null) add(texts.timeUnknown)
            }
        }

        return "- **${status.item.title.markdownInline()}** – " + joinSentences(sentences)
    }

    private fun StringBuilder.appendHistory(
        snapshot: CarSnapshot,
        preset: ExportPreset,
        display: Display,
        texts: ExportTexts,
    ) {
        appendLine(texts.historyHeader)
        if (snapshot.history.isEmpty()) appendLine("- ${texts.noHistory}")
        snapshot.history.sortedByDescending { it.date }.take(preset.historyLimit).forEach { work ->
            val km = work.km?.let { ", ${display.distanceText(it)}" }.orEmpty()
            val kind = if (work.kind == ItemKind.DOCUMENT) "${texts.historyDocument}: " else ""
            appendLine("- ${work.date.dateText()} – $kind${work.title.markdownInline()}$km")
            work.note?.takeIf { it.isNotBlank() }?.let {
                appendLine("  - ${texts.note}: ${it.markdownInline()}")
            }
        }
        if (snapshot.history.size > preset.historyLimit) {
            appendLine("- ${texts.andMore(snapshot.history.size - preset.historyLimit)}")
        }
        appendLine()
    }

    private fun StringBuilder.appendFuel(
        snapshot: CarSnapshot,
        preset: ExportPreset,
        display: Display,
        texts: ExportTexts,
    ) {
        val fuel = snapshot.fuel
        appendLine(texts.fuelHeader)
        val types = (fuel.types + fuel.usage.map { it.fuelType } + snapshot.fuelEntries.map { it.fuelType }).distinct()
        if (types.isEmpty()) {
            appendLine("- ${texts.noFuelEntries}")
            appendLine()
            return
        }
        appendLine(texts.fuelMethod)
        val several = types.size > 1
        fuel.usage.forEach { usage ->
            val what = "${texts.averageConsumption} (${texts.fuelTitle(usage.fuelType)})"
            val consumption = display.units.consumption(usage.perHundredKm, usage.fuelType)
            appendLine("- $what: " + display.consumptionText(consumption, ofTotal = several))
        }
        types.filter { type -> fuel.usage.none { it.fuelType == type } }.forEach {
            appendLine("- ${texts.fuelTitle(it)}: ${texts.fuelNotCalculated}")
        }
        if (several) {
            // Модель не должна принять это за расход в режиме газа: такого числа у нас нет.
            appendLine("- ${texts.totalDistanceNote}")
        }
        fuel.costPerKmKopecks?.let {
            appendLine(
                "- " + texts.costPer(
                    display.moneyText(display.units.costPerDistance(it)),
                    display.distanceNounPer,
                )
            )
            if (several) appendLine("- ${texts.costEstimate}")
        }

        if (!preset.includeFuelEntries && snapshot.fuelEntries.isNotEmpty()) {
            appendLine("- ${texts.fuelEntriesOmitted(snapshot.fuelEntries.size)}")
        }
        if (preset.includeFuelEntries && snapshot.fuelEntries.isNotEmpty()) {
            appendLine("- ${texts.entriesHeader}")
            snapshot.fuelEntries.sortedWith(compareByDescending<FuelEntry> { it.date }.thenByDescending { it.odometerKm }).take(preset.fuelLimit).forEach { entry ->
                val tank = when {
                    !entry.isFull -> texts.partial
                    entry.fuelType.chargedToLimit -> texts.chargedToLimit
                    else -> texts.fullTank
                }
                appendLine(
                    "  - ${entry.date.dateText()}, ${display.distanceText(entry.odometerKm)}: " +
                        display.amountText(entry.amount, entry.fuelType) +
                        " ${texts.fuelOf(entry.fuelType)} ${texts.forWord} " +
                        "${display.moneyText(entry.costKopecks)} ($tank)"
                )
            }
        }
        appendLine()
    }
}
