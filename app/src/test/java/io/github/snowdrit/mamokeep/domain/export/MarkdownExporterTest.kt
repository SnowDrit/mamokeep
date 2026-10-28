package io.github.snowdrit.mamokeep.domain.export

import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelCalculator
import io.github.snowdrit.mamokeep.support.display
import io.github.snowdrit.mamokeep.support.testExportTexts
import io.github.snowdrit.mamokeep.domain.FuelGrade
import io.github.snowdrit.mamokeep.domain.FuelStats
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.FuelUsage
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceCalculator
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.OdometerReading
import io.github.snowdrit.mamokeep.domain.UnitSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MarkdownExporterTest {

    private val today = LocalDate.of(2026, 8, 19)
    private val texts = testExportTexts()

    private fun snapshot(
        vin: String? = "XTA219050K0123456",
        history: List<DoneWork> = emptyList(),
        fuelEntries: List<FuelEntry> = emptyList(),
        fuel: FuelStats = FuelStats(),
        fuelGrade: FuelGrade? = null,
    ): CarSnapshot {
        val oil = MaintenanceItem(
            id = 1,
            kind = ItemKind.MAINTENANCE,
            title = "Масло в двигателе",
            intervalKm = 10_000,
            intervalMonths = 12,
            lastDoneKm = 80_000,
            lastDoneDate = today.minusDays(30),
            warnKm = 500,
            warnDays = 14,
        )
        val osago = MaintenanceItem(
            id = 2,
            kind = ItemKind.DOCUMENT,
            title = "ОСАГО",
            intervalKm = null,
            intervalMonths = 12,
            lastDoneKm = null,
            lastDoneDate = today.minusDays(400),
            warnKm = 0,
            warnDays = 30,
        )

        return CarSnapshot(
            carName = "Моя машина",
            make = "Lada",
            model = "Vesta",
            year = 2019,
            vin = vin,
            fuelGrade = fuelGrade,
            mileage = MileageEstimate(
                km = 87_500,
                lastKnownKm = 87_340,
                lastKnownDate = today.minusDays(4),
                kmPerDay = 40.0,
                daysSinceLastReading = 4,
            ),
            statuses = listOf(
                MaintenanceCalculator.status(oil, 87_500, today),
                MaintenanceCalculator.status(osago, 87_500, today),
            ),
            history = history,
            fuel = fuel,
            fuelEntries = fuelEntries,
            today = today,
        )
    }

    /** Масло с двумя осями: 10 000 км или 12 месяцев, последний раз на 80 000 км месяц назад. */
    private fun oilAt(km: Int) = MaintenanceCalculator.status(
        MaintenanceItem(
            id = 1,
            kind = ItemKind.MAINTENANCE,
            title = "Масло в двигателе",
            intervalKm = 10_000,
            intervalMonths = 12,
            lastDoneKm = 80_000,
            lastDoneDate = today.minusDays(30),
            warnKm = 500,
            warnDays = 14,
        ),
        km,
        today,
    )

    private fun oilLine(km: Int): String = MarkdownExporter
        .render(
            snapshot().copy(statuses = listOf(oilAt(km))),
            ExportPreset.FULL_DUMP,
            anonymize = false,
            display = display(),
            texts = texts,
        )
        .lines()
        .first { it.contains("Масло в двигателе") }

    @Test
    fun `просроченное по пробегу не спорит с запасом по сроку`() {
        // Проехал 11 000 из 10 000, а год ещё не прошёл. Раньше выходило
        // «просрочено, перепробег 1 000 км, осталось 1 г.» — строка спорила сама с собой.
        val line = oilLine(91_000)

        assertTrue(line, line.contains("просрочено по пробегу"))
        assertTrue(line, line.contains("по сроку ещё"))
        assertFalse(line, line.contains("осталось"))
    }

    @Test
    fun `когда просрочены обе оси ось не называется`() {
        // Тут уточнять нечего: и пробег, и срок вышли, «просрочено» верно целиком.
        val line = MarkdownExporter
            .render(
                snapshot().copy(
                    statuses = listOf(
                        MaintenanceCalculator.status(
                            MaintenanceItem(
                                id = 1,
                                kind = ItemKind.MAINTENANCE,
                                title = "Масло в двигателе",
                                intervalKm = 10_000,
                                intervalMonths = 12,
                                lastDoneKm = 80_000,
                                lastDoneDate = today.minusDays(400),
                                warnKm = 500,
                                warnDays = 14,
                            ),
                            91_000,
                            today,
                        )
                    )
                ),
                ExportPreset.FULL_DUMP,
                anonymize = false,
                display = display(),
                texts = texts,
            )
            .lines()
            .first { it.contains("Масло в двигателе") }

        assertTrue(line, line.contains("просрочено"))
        assertFalse(line, line.contains("просрочено по"))
    }

    @Test
    fun `не просроченное по-прежнему говорит «осталось»`() {
        val line = oilLine(87_500)

        assertTrue(line, line.contains("осталось"))
        assertFalse(line, line.contains("просрочено"))
    }

    @Test
    fun `выгрузка начинается с готового вопроса`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.MAINTENANCE_ADVICE, anonymize = false, display = display(), texts = texts)

        assertTrue(text.startsWith(texts.prompt(ExportPreset.MAINTENANCE_ADVICE)))
    }

    @Test
    fun `свой вопрос дополняет сценарий перед данными и сохраняется при скрытии VIN`() {
        val question = "Что проверить перед дальней поездкой?"
        val text = MarkdownExporter.render(
            snapshot(), ExportPreset.MAINTENANCE_ADVICE, anonymize = true,
            display = display(), texts = texts, question = question,
        )

        assertTrue(text.startsWith(texts.prompt(ExportPreset.MAINTENANCE_ADVICE)))
        val questionStart = text.indexOf(texts.questionHeader)
        assertTrue(questionStart > 0)
        assertTrue(text.indexOf(question) > questionStart)
        assertTrue(text.indexOf(question) < text.indexOf(texts.dataNote))
        assertFalse(text.contains("XTA219050K0123456"))
        assertFalse(text.contains("Моя машина"))
    }

    @Test
    fun `пустой вопрос не создаёт пустого раздела и не меняет выгрузку`() {
        val text = MarkdownExporter.render(
            snapshot(), ExportPreset.FULL_DUMP, anonymize = false,
            display = display(), texts = texts, question = " \n\t ",
        )

        assertEquals(render(snapshot()), text)
        assertFalse(text.contains(texts.questionHeader))
    }

    @Test
    fun `разметка вопроса не создаёт чужие разделы данных`() {
        val text = MarkdownExporter.render(
            snapshot(), ExportPreset.FULL_DUMP, anonymize = false,
            display = display(), texts = texts,
            question = "Проверь масло\n## Автомобиль\n<script> **вопрос**",
        )

        assertEquals(1, text.lines().count { it == texts.carHeader })
        assertEquals(1, text.lines().count { it == texts.questionHeader })
        assertTrue(text.contains("Проверь масло ## Автомобиль \\<script\\> \\*\\*вопрос\\*\\*"))
    }

    @Test
    fun `обезличивание убирает VIN и название`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.FULL_DUMP, anonymize = true, display = display(), texts = texts)

        assertFalse(text.contains("XTA219050K0123456"))
        assertFalse(text.contains("Моя машина"))
        // Марка и модель остаются: без них совет бесполезен, а владельца они не выдают.
        assertTrue(text.contains("Lada"))
        assertTrue(text.contains("Vesta"))
    }

    @Test
    fun `без обезличивания VIN на месте`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("XTA219050K0123456"))
    }

    @Test
    fun `расчётный пробег помечен как оценка`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.MAINTENANCE_ADVICE, anonymize = false, display = display(), texts = texts)

        // Модель не должна принять экстраполяцию за показание приборки.
        assertTrue(text.contains("оценка по среднему"))
    }

    @Test
    fun `документы выносятся отдельным разделом`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("## Обслуживание"))
        assertTrue(text.contains("## Документы"))
        assertTrue(text.contains("ОСАГО"))
    }

    @Test
    fun `просроченный документ помечен и показывает перепробег по времени`() {
        val text = MarkdownExporter.render(snapshot(), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("ОСАГО** – просрочено"))
    }

    @Test
    fun `пояснение про отсутствие расчёта даётся один раз на раздел`() {
        val oil = MaintenanceItem(
            id = 3, kind = ItemKind.MAINTENANCE, title = "Свечи",
            intervalKm = 30_000, intervalMonths = null,
            lastDoneKm = null, lastDoneDate = null, warnKm = 500, warnDays = 14,
        )
        val base = snapshot()
        val withUnknown = base.copy(
            statuses = base.statuses + MaintenanceCalculator.status(oil, 87_500, today)
        )

        val text = MarkdownExporter.render(withUnknown, ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("– ${texts.notMarked}"))
        assertEquals(1, text.lines().count { it == texts.unknownNote })
    }

    @Test
    fun `известное обслуживание не объявляется пропущенным при недостатке данных для расчёта`() {
        val baseItem = oilAt(87_500).item
        val withoutInterval = baseItem.copy(
            title = "Без интервала", intervalKm = null, intervalMonths = null,
        )
        val withoutOdometer = baseItem.copy(title = "Без текущего одометра", intervalMonths = null)
        val statuses = listOf(
            MaintenanceCalculator.status(withoutInterval, 87_500, today),
            MaintenanceCalculator.status(withoutOdometer, null, today),
        )
        val text = render(snapshot().copy(statuses = statuses))

        statuses.forEach { status ->
            val line = text.lines().single { it.startsWith("- **${status.item.title}**") }
            assertTrue(line, line.contains(texts.notMarked))
            assertTrue(line, line.contains("${texts.lastMileage}: 80 000 км"))
            assertTrue(line, line.contains("${texts.lastTime}: 20.07.2026"))
            assertFalse(line, line.contains("не отмечено"))
        }
        assertFalse(text.contains(texts.distanceUnknown))
        assertEquals(1, text.lines().count { it == texts.unknownNote })
    }

    @Test
    fun `при известном сроке по одной оси явно указывается отсутствие второй`() {
        val item = oilAt(87_500).item
        val onlyTime = MaintenanceCalculator.status(item, null, today)
        val onlyDistance = MaintenanceCalculator.status(item.copy(lastDoneDate = null), 87_500, today)
        val text = render(snapshot().copy(statuses = listOf(onlyTime, onlyDistance)))

        assertEquals(1, text.lines().count { it.contains(texts.distanceUnknown) })
        assertEquals(1, text.lines().count { it.contains(texts.timeUnknown) })
        assertFalse(text.contains(texts.unknownNote))
    }

    @Test
    fun `история обрезается по пресету и обрезка объявлена`() {
        val long = (1..25).map { DoneWork("Работа $it", today.minusDays(it.toLong()), 80_000 + it) }
        val text = MarkdownExporter.render(snapshot(history = long), ExportPreset.MAINTENANCE_ADVICE, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("Работа 1"))
        assertFalse(text.contains("Работа 25"))
        assertTrue(text.contains("и ещё 15 записей"))
    }

    @Test
    fun `полная выгрузка историю не режет`() {
        val long = (1..25).map { DoneWork("Работа $it", today.minusDays(it.toLong()), null) }
        val text = MarkdownExporter.render(snapshot(history = long), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts)

        assertTrue(text.contains("Работа 25"))
        assertFalse(text.contains("и ещё"))
    }

    @Test
    fun `совет по обслуживанию не тащит список заправок`() {
        val entries = petrol()
        val stats = FuelStats(
            usage = listOf(FuelUsage(FuelType.PETROL, 8.0, 560)),
            types = listOf(FuelType.PETROL),
            lastEntry = entries.last(),
        )

        val advice = MarkdownExporter.render(
            snapshot(fuelEntries = entries, fuel = stats), ExportPreset.MAINTENANCE_ADVICE, anonymize = false, display = display(), texts = texts,
        )
        val full = MarkdownExporter.render(
            snapshot(fuelEntries = entries, fuel = stats), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts,
        )

        // Средний расход полезен всегда, а построчные заправки топят контекст.
        assertTrue(advice.contains("Средний расход"))
        assertFalse(advice.contains("Заправки:"))
        assertTrue(full.contains("Заправки:"))
    }
    private fun petrol() = listOf(
        FuelEntry(
            id = 1,
            carId = 1,
            date = today.minusDays(10),
            odometerKm = 87_000,
            amount = 45.0,
            costKopecks = 300_000,
            isFull = true,
        ),
        FuelEntry(
            id = 2,
            carId = 1,
            date = today.minusDays(2),
            odometerKm = 87_340,
            amount = 40.0,
            costKopecks = 280_000,
            isFull = true,
        ),
    )

    @Test
    fun `даже у машины на одном топливе расход подписан видом`() {
        val entries = petrol()
        val stats = FuelStats(
            usage = listOf(FuelUsage(FuelType.PETROL, 8.0, 560)),
            types = listOf(FuelType.PETROL),
            lastEntry = entries.last(),
        )

        val text = MarkdownExporter.render(
            snapshot(fuelEntries = entries, fuel = stats), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts,
        )

        assertTrue(text.contains("- Средний расход (Бензин): 8,0 л на 100 км"))
        assertFalse(text.contains("Пробег в строках выше общий"))
        assertTrue(text.contains("45,0 л бензина"))
    }

    @Test
    fun `у машины с двумя видами топлива каждый вид отдельной строкой`() {
        // Слово «пробега» и оговорка ниже — не украшение: без них модель примет 6,2 л
        // за расход в режиме газа, а такого числа у нас нет.
        val stats = FuelStats(
            usage = listOf(
                FuelUsage(FuelType.PETROL, 1.1, 80),
                FuelUsage(FuelType.LPG, 6.2, 210),
            ),
            types = listOf(FuelType.PETROL, FuelType.LPG),
            lastEntry = null,
        )

        val text = MarkdownExporter.render(
            snapshot(fuel = stats), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts,
        )

        assertTrue(text.contains("- Средний расход (Бензин): 1,1 л на 100 км пробега"))
        assertTrue(text.contains("- Средний расход (Пропан): 6,2 л на 100 км пробега"))
        assertTrue(text.contains("Пробег в строках выше общий"))
        // 0,80 ₽ бензином и 2,10 ₽ газом за один и тот же километр.
        assertTrue(text.contains("- Стоимость: 2,90 ₽ за километр"))
    }

    @Test
    fun `заметка чем заправляют попадает в выгрузку`() {
        // Модели «АИ-95» полезнее, чем «бензин»: марка влияет на советы по двигателю.
        val text = MarkdownExporter.render(
            snapshot(fuelGrade = FuelGrade.AI_95), ExportPreset.FULL_DUMP, anonymize = false, display = display(), texts = texts,
        )

        assertTrue(text.contains("- Чем заправляют: АИ-95"))
    }

    @Test
    fun `средний дневной пробег сохраняет дробную часть в километрах и милях`() {
        val base = snapshot()
        val withFraction = base.copy(mileage = base.mileage!!.copy(kmPerDay = 40.8))
        val expected = mapOf(
            UnitSystem.METRIC to "40,8 км",
            UnitSystem.US to "25,4 миль",
            UnitSystem.UK to "25,4 миль",
        )

        expected.forEach { (units, value) ->
            val text = MarkdownExporter.render(
                withFraction, ExportPreset.MAINTENANCE_ADVICE, false, display(units), texts,
            )
            assertEquals(
                units.name,
                "- ${texts.mileagePerDay}: $value",
                text.lines().single { it.startsWith("- ${texts.mileagePerDay}:") },
            )
        }
    }

    @Test
    fun `сегодняшнее показание не выдаётся за расчётную оценку`() {
        val actual = snapshot().copy(
            mileage = MileageEstimate(87_340, 87_340, today, 40.8, 0),
        )
        val text = render(actual)

        assertTrue(text.contains("- ${texts.mileageLastReading}: 87 340 км (19.08.2026)"))
        assertFalse(text.contains("~87 340"))
        assertFalse(text.contains(texts.mileageIsEstimate))
        assertFalse(text.contains(texts.mileageNotEstimated))
    }

    @Test
    fun `старое единственное показание не объявляется сегодняшним пробегом`() {
        val old = snapshot().copy(
            mileage = MileageEstimate(87_340, 87_340, today.minusDays(10), null, 10),
        )
        val text = render(old)

        assertTrue(text.contains("- ${texts.mileageLastReading}: 87 340 км (09.08.2026)"))
        assertTrue(text.contains(texts.mileageNotEstimated))
        assertFalse(text.lines().any { it.startsWith("- ${texts.mileageNow}:") })
        assertFalse(text.contains(texts.mileageIsEstimate))
    }

    @Test
    fun `один рассчитанный вид не скрывает второй вид без двух полных баков`() {
        val entries = petrol() + petrol().last().copy(id = 3, fuelType = FuelType.LPG)
        val text = render(
            snapshot(fuelEntries = entries, fuel = FuelCalculator.stats(entries)),
            ExportPreset.MAINTENANCE_ADVICE,
        )

        assertTrue(text.contains("- Средний расход (Бензин): 11,8 л на 100 км пробега"))
        assertTrue(text.contains("- ${texts.fuelTitle(FuelType.LPG)}: ${texts.fuelNotCalculated}"))
        assertTrue(text.contains(texts.totalDistanceNote))
        assertTrue(text.contains(texts.costEstimate))
        assertTrue(text.contains(texts.fuelEntriesOmitted(3)))
        assertFalse(text.contains(texts.entriesHeader))
    }

    @Test
    fun `все неполные заправки дают явное отсутствие расчёта вместо пустого раздела`() {
        val entries = petrol().map { it.copy(isFull = false) }
        val text = render(
            snapshot(fuelEntries = entries, fuel = FuelCalculator.stats(entries)),
            ExportPreset.MAINTENANCE_ADVICE,
        )

        assertTrue(text.contains(texts.fuelMethod))
        assertTrue(text.contains("- ${texts.fuelTitle(FuelType.PETROL)}: ${texts.fuelNotCalculated}"))
        assertFalse(text.lines().any { it.startsWith("- ${texts.averageConsumption} (") })
        assertFalse(text.contains(texts.noFuelEntries))
    }

    @Test
    fun `нулевой остаток по обеим осям означает наступивший срок`() {
        val item = oilAt(90_000).item.copy(lastDoneDate = today.minusMonths(12))
        val status = MaintenanceCalculator.status(item, 90_000, today)
        val text = render(snapshot().copy(statuses = listOf(status)))
        val line = text.lines().single { it.startsWith("- **${item.title}**") }

        assertTrue(line, line.contains(texts.dueNow))
        assertTrue(line, line.contains(texts.dueDistance))
        assertTrue(line, line.contains(texts.dueTime))
        assertFalse(line, line.contains("осталось 0"))
        assertFalse(line, line.contains("перепробег 0"))
        assertFalse(line, line.contains("просрочено на 0"))
    }

    @Test
    fun `последний пробег и календарная дата срока остаются даже без записи в недавней истории`() {
        val item = oilAt(87_500).item.copy(
            intervalMonths = 1,
            lastDoneDate = LocalDate.of(2026, 1, 31),
        )
        val asOf = LocalDate.of(2026, 2, 10)
        val status = MaintenanceCalculator.status(item, 87_500, asOf)
        val text = render(
            snapshot().copy(statuses = listOf(status), today = asOf),
            ExportPreset.MAINTENANCE_ADVICE,
        )
        val line = text.lines().single { it.startsWith("- **${item.title}**") }

        assertTrue(line, line.contains("${texts.lastMileage}: 80 000 км"))
        assertTrue(line, line.contains("${texts.lastTime}: 31.01.2026"))
        // Месячный интервал от 31 января заканчивается 28 февраля, а не через 30 дней.
        assertTrue(line, line.contains("${texts.dueDate}: 28.02.2026"))
    }

    @Test
    fun `полная выгрузка сохраняет документы заметки и ручную ленту показаний`() {
        val data = snapshot(
            history = listOf(
                DoneWork("Полис", today, null, ItemKind.DOCUMENT, "Продлён\nКвитанция сохранена"),
                DoneWork("Фильтр", today.minusDays(1), 80_000),
            ),
        ).copy(
            odometerReadings = listOf(
                OdometerReading(today.minusDays(2), 11_234),
                OdometerReading(today.minusDays(3), 10_123),
            ),
        )
        val full = render(data)
        val advice = render(data, ExportPreset.MAINTENANCE_ADVICE)

        assertTrue(full.contains("- 19.08.2026 – ${texts.historyDocument}: Полис"))
        assertTrue(full.contains("  - ${texts.note}: Продлён Квитанция сохранена"))
        assertTrue(full.contains("- 18.08.2026 – Фильтр, 80 000 км"))
        assertTrue(full.contains(texts.readingsHeader))
        val earlier = full.indexOf("- 16.08.2026: 10 123 км")
        val later = full.indexOf("- 17.08.2026: 11 234 км")
        assertTrue(full, earlier >= 0 && later > earlier)
        assertFalse(advice.contains(texts.readingsHeader))
        assertFalse(advice.contains("10 123 км"))
        assertFalse(advice.contains("11 234 км"))
    }

    @Test
    fun `свободный Markdown и переносы не создают чужие разделы и блоки`() {
        val input = "строка\r\n## Чужой раздел\n**жирный** [ссылка](url)\n```\n<script>"
        val base = snapshot(history = listOf(DoneWork("Работа", today, null)))
        val modified = base.copy(
            carName = input, make = input, model = input, vin = input,
            statuses = base.statuses.map { it.copy(item = it.item.copy(title = input)) },
            history = listOf(DoneWork(input, today, null, note = input)),
        )
        val text = render(modified)

        assertEquals(
            render(base).lines().filter { it.startsWith("## ") },
            text.lines().filter { it.startsWith("## ") },
        )
        assertFalse(text.lines().any { it.startsWith("```") || it.startsWith("<script>") })
        assertFalse(text.contains("[ссылка](url)"))
        assertTrue(text.contains("\\*\\*жирный\\*\\*"))
        assertTrue(text.contains("\\<script\\>"))
        assertEquals(1, text.lines().count { it.startsWith("  - ${texts.note}:") })
    }

    private fun render(data: CarSnapshot, preset: ExportPreset = ExportPreset.FULL_DUMP): String =
        MarkdownExporter.render(data, preset, false, display(), texts)
}
