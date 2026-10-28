package io.github.snowdrit.mamokeep.domain.scanner

import io.github.snowdrit.mamokeep.domain.FuelType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReceiptDraftTest {
    @Test fun `explicit kilometre unit tolerates visually identical mixed scripts`() {
        for (unit in listOf("км", "km", "кm", "kм", "КM", "KМ")) {
            assertEquals(unit, 241000, ReceiptDraft.parse("Пробег: 241000 $unit.").odometerKm)
        }
        for (text in listOf("241000 КM", "Пробег: 241000 mi", "Пробег: 241000 к", "Пробег: 241000 КMfoo")) {
            assertNull(text, ReceiptDraft.parse(text).odometerKm)
        }
        assertNull(ReceiptDraft.parse("Пробег: 241000 КM\nПробег: 240000 км").odometerKm)
    }
    @Test fun `fuel grade accepts repeated printed separators without repairing letters`() {
        for (grade in listOf("АИ--95-К5", "Аи - 95-К5", "АІ--95-К5")) {
            val draft = ReceiptDraft.parse("ТРК-4 $grade")
            assertEquals(grade, FuelType.PETROL, draft.fuelType)
            assertNull(draft.liters)
        }
        for (text in listOf("АМ-95-К5", "AI-95-K5", "АИ--К5", "АИ/95", "НЕАИ--95", "Присадка АИ--95 1 л")) {
            assertNull(text, ReceiptDraft.parse(text).fuelType)
        }
        assertNull(ReceiptDraft.parse("АИ--95\nДТ 20 л").fuelType)
    }
    @Test fun `a multiplication price or fraction tail is not a printed fuel volume`() {
        for (operator in listOf("*", "×", "x", "х", "/")) {
            for (space in listOf("", " ", "\u00a0", "\u202f")) {
                assertNull(ReceiptDraft.parse("АИ-95-К5 73.85$operator${space}50 л =3692.50").liters)
            }
        }
        assertNull(ReceiptDraft.parse("Бензин 1/2 л").liters)
        assertEquals(20.5, ReceiptDraft.parse("Бензин АИ-95 20,5 л × 60,00").liters!!, 0.0)
    }
    @Test fun `a clean pump header owns the complete immediately following sale line`() {
        val quantities = listOf(
            "62.24 X 36.07 Л ≡2245.00 РУБ" to 36.07,
            "70.09 X 13.34 Л ≡935.00 РУБ" to 13.34,
            "69.69 X 32.8 Л ≡2285.83 РУБ" to 32.8,
            "69.69 X 18.15 Л ≡1264.87 РУБ" to 18.15,
        )
        for ((sale, liters) in quantities) {
            val draft = ReceiptDraft.parse("ТРК-4 Аи--95-К5\n$sale")
            assertEquals(sale, FuelType.PETROL, draft.fuelType)
            assertEquals(sale, liters, draft.liters!!, 0.0)
        }
        assertEquals(36.07,
            ReceiptDraft.parse("ТРК-4 АІ-95-К5\n62,24 × 36,07 L =2245,00 RUB").liters!!, 0.0)
    }
    @Test fun `pump continuation requires a complete printed expression and one fuel owner`() {
        val header = "ТРК-4 АИ-95-К5"
        val sale = "62.24 X 36.07 Л ≡2245.00 РУБ"
        listOf(
            "$header $sale",
            "АИ-95-К5 73.85*50 л =3692.50",
            "$header\n73.85*50 л",
            "$header\n73.85*50 л =3692.50",
            "$header\n20.50 л",
            "$header\n$sale\n$header\n$sale",
            "$header\n$sale\nДТ 20 л",
            "Возврат\n$header\n$sale",
            "$header ≡2245.00 РУБ\n$sale",
            "$header\n62.24 X 1/2 л =2245 РУБ",
            "$header\n62.24 X 1 020 Л =2245 РУБ",
            "$header\n1 020 X 36.07 Л =2245 РУБ",
            "$header\n62.24 X 36.07 Л =2 245 РУБ",
            "$header\n62.24 X -36.07 Л =2245 РУБ",
            "$header\n62.24 X 0 Л =0 РУБ",
            "$header\n62.24 X 36.07 Л 20 Л =2245 РУБ",
            "$header\n62.24 / 36.07 Л =2245 РУБ",
        ).forEach { text -> assertNull(text, ReceiptDraft.parse(text).liters) }
    }
    @Test fun `pump continuation does not skip gaps page breaks or another product`() {
        val header = "ТРК-4 АИ-95-К5"
        val sale = "62.24 X 36.07 Л ≡2245.00 РУБ"
        listOf(
            "$header\n\n$sale",
            "$header\nКоличество\n$sale",
            "$header\u000c\n$sale",
            "$header\n\u000c$sale",
            "$header\n\u000c\n$sale",
            "$header\nМасло 4 л =2000 РУБ",
            "$header\nКофе 1 л =200 РУБ",
            "$header\nМасло\n1 X 4 л =2000 РУБ",
            "$header\nМасло $sale",
            "$header\n$sale Масло",
        ).forEach { text -> assertNull(text, ReceiptDraft.parse(text).liters) }
    }
    @Test fun `a pump continuation preserves source text and does not borrow a later oil quantity`() {
        val text = "ТРК-4 АИ-95-К5\r\n62.24 X 36.07 Л ≡2245.00 РУБ\r\nМасло 4 л"
        val draft = ReceiptDraft.parse(text)
        assertEquals(text, draft.sourceText)
        assertEquals(36.07, draft.liters!!, 0.0)
        assertEquals(FuelType.PETROL, draft.fuelType)
        assertNull(draft.date)
        assertNull(draft.odometerKm)
    }
    @Test fun `a pump header mixed with a different amount stays unknown`() {
        listOf(
            "ТРК-4 Аи-95-К5 ≡1530.61 РУБ\n59.93 X 25.54 Л ≡276.01 РУБ",
            "ТРК-4 Аи--95-К5 ≡3550.24 РУБ\n71.29 X 49.8 Л ≡640.21 РУБ",
        ).forEach { text -> assertNull(text, ReceiptDraft.parse(text).liters) }
    }
    @Test fun `printed fuel row suggests liters but not receipt total or full tank`() {
        val draft = ReceiptDraft.parse("22.10.2026\nБензин АИ-95 20,50 л x 60,00 = 1230,00\nКофе 200,00\nИТОГ 1430,00")
        assertEquals(LocalDate.of(2026, 10, 22), draft.date)
        assertEquals(20.5, draft.liters!!, 0.0)
        assertEquals(FuelType.PETROL, draft.fuelType)
        assertNull(draft.odometerKm)
    }
    @Test fun `ambiguous dates and conflicting fuel rows stay unknown`() {
        val ambiguous = ReceiptDraft.parse("10/11/2026\nPetrol 20 L\nDiesel 10 L")
        assertNull(ambiguous.date)
        assertNull(ambiguous.liters)
        assertNull(ambiguous.fuelType)
        assertNull(ReceiptDraft.parse("21.10.2026\n22.10.2026").date)
    }
    @Test fun `odometer requires a label and explicit kilometers`() {
        assertEquals(15000, ReceiptDraft.parse("Одометр: 15 000 км").odometerKm)
        assertNull(ReceiptDraft.parse("ИНН 1500000\nПробег 15000 mi\nПробег 15000").odometerKm)
        assertNull(ReceiptDraft.parse("Пробег 15000 км\nПробег 16000 км").odometerKm)
    }
    @Test fun `foreign units unrelated products and conflicting quantities are not liters`() {
        assertNull(ReceiptDraft.parse("Diesel 20 gal").liters)
        assertNull(ReceiptDraft.parse("Бензин АИ-95\nМасло 4 л").liters)
        assertNull(ReceiptDraft.parse("Бензин 20 л 40 л").liters)
        assertNull(ReceiptDraft.parse("Бензин -20 л").liters)
        assertNull(ReceiptDraft.parse("Возврат\nБензин 20 л").liters)
    }
    @Test fun `a fused short year and hour do not become a four digit year`() {
        listOf(
            "ПРИХОД#(04.10.2511:56",
            "04.10.2511:56",
            "04-10-2511:56",
            "04.10.2025:11",
            "2025-10-04:11",
            "04.10.25 11:56",
        ).forEach { text ->
            val draft = ReceiptDraft.parse(text)
            assertNull(text, draft.date)
            assertEquals(text, draft.sourceText)
        }
    }
    @Test fun `four digit dates preserve explicit time boundaries without a year cap`() {
        listOf(
            "04.10.2025 11:56",
            "04.10.2025T11:56",
            "2025-10-04 11:56",
            "2025-10-04T11:56",
            "04.10.2025:",
            "04.10.2025: касса",
            "04.10.2025\n11:56",
        ).forEach { text -> assertEquals(text, LocalDate.of(2025, 10, 4), ReceiptDraft.parse(text).date) }
        assertEquals(LocalDate.of(2511, 10, 4), ReceiptDraft.parse("04.10.2511").date)
    }
    @Test fun `a malformed fused date does not conflict with a separate printed date`() {
        val text = "ПРИХОД#(04.10.2511:56\r\n22.10.2026\r\nПробег: 241000 км\r\nБензин АИ-95 20.5 л"
        val draft = ReceiptDraft.parse(text)
        assertEquals(LocalDate.of(2026, 10, 22), draft.date)
        assertEquals(241000, draft.odometerKm)
        assertEquals(20.5, draft.liters!!, 0.0)
        assertEquals(FuelType.PETROL, draft.fuelType)
        assertEquals(text, draft.sourceText)
    }
    @Test fun `invalid and non ISO short years stay empty`() {
        assertNull(ReceiptDraft.parse("31.02.2026").date)
        assertNull(ReceiptDraft.parse("22.10.26").date)
        assertEquals(LocalDate.of(2026, 10, 22), ReceiptDraft.parse("2026-10-22").date)
    }
    @Test fun `expiry manufacture warranty and payment dates are not document dates`() {
        val labels = listOf(
            "Годен до", "Срок годности", "Дата изготовления", "Изготовлено",
            "Дата производства", "Гарантия до", "Срок оплаты",
            "Придатний до", "Термін придатності", "Дата виготовлення", "Вироблено",
            "Гарантія до", "Термін оплати",
            "Expiry date", "Expiration date", "Manufactured", "Production date",
            "Warranty until", "Due date",
        )
        labels.forEach { label -> assertNull(label, ReceiptDraft.parse("$label: 01.12.2026").date) }
    }
    @Test fun `excluded dates do not conflict with an unlabelled document date`() {
        val draft = ReceiptDraft.parse("22.10.2026\nГоден до 01.12.2026\nДата изготовления: 01.01.2026")
        assertEquals(LocalDate.of(2026, 10, 22), draft.date)
    }
    @Test fun `explicit document dates take priority over unlabelled dates`() {
        listOf("Дата документа", "Дата оформлення", "Receipt date").forEach { label ->
            val draft = ReceiptDraft.parse("$label: 22.10.2026\n21.10.2026\nГоден до 01.12.2026")
            assertEquals(label, LocalDate.of(2026, 10, 22), draft.date)
        }
    }
    @Test fun `date roles are local to each date on the same row`() {
        val texts = listOf(
            "Дата чека: 22.10.2026 Годен до 01.12.2026",
            "Годен до: 01.12.2026 Дата чека: 22.10.2026",
            "Придатний до: 01.12.2026 Дата документа: 22.10.2026",
            "Expiry date: 2026-12-01 Receipt date: 2026-10-22",
        )
        texts.forEach { text -> assertEquals(text, LocalDate.of(2026, 10, 22), ReceiptDraft.parse(text).date) }
    }
    @Test fun `work order date after document number has an explicit role`() {
        val texts = listOf(
            "Заказ-наряд № 785 от 22.10.2026\nДата приёмки 21.10.2026",
            "Замовлення-наряд № 785 від 22.10.2026\nДата приймання 21.10.2026",
            "Work order #785 dated 2026-10-22\nReceived 2026-10-21",
        )
        texts.forEach { text -> assertEquals(text, LocalDate.of(2026, 10, 22), ReceiptDraft.parse(text).date) }
    }
    @Test fun `conflicting explicit document dates stay unknown across rows and roles`() {
        assertNull(ReceiptDraft.parse("Дата чека: 22.10.2026\nInvoice date: 2026-10-21\nГоден до 01.12.2026").date)
        assertNull(ReceiptDraft.parse("Дата документа: 22.10.2026 Receipt date: 2026-10-21").date)
    }
    @Test fun `an act does not borrow the date of its referenced contract`() {
        val reference = "Акт на выполненные работы\nК договору заказ-наряда № 18280 от 01.12.2025"
        assertNull(ReceiptDraft.parse(reference).date)
        assertEquals(LocalDate.of(2025, 12, 9), ReceiptDraft.parse("Дата документа: 09.12.2025\n$reference").date)
    }
    @Test fun `Russian litre spellings are explicit units`() {
        listOf("литр", "литра", "литров", "литры", "ЛИТРОВ").forEach { unit ->
            assertEquals(unit, 20.5, ReceiptDraft.parse("Бензин АИ-95 20,50 $unit").liters!!, 0.0)
        }
        assertNull(ReceiptDraft.parse("Бензин 20 литровый").liters)
    }
    @Test fun `Russian diesel fuel phrase does not match diesel oil adjective`() {
        val draft = ReceiptDraft.parse("Дизельное топливо 20 литров")
        assertEquals(FuelType.DIESEL, draft.fuelType)
        assertEquals(20.0, draft.liters!!, 0.0)
        val oil = ReceiptDraft.parse("Масло дизельное 4 л")
        assertNull(oil.fuelType)
        assertNull(oil.liters)
    }
    @Test fun `standalone date role applies only to the immediately following bare date`() {
        listOf("Годен до", "Дата изготовления", "Expiry date").forEach { label ->
            assertNull(label, ReceiptDraft.parse("$label:\n01.12.2026").date)
        }
        assertEquals(LocalDate.of(2026, 10, 22),
            ReceiptDraft.parse("Дата чека:\n22.10.2026\n21.10.2026").date)
        assertEquals(LocalDate.of(2026, 10, 22),
            ReceiptDraft.parse("Годен до:\n01.12.2026\n22.10.2026").date)
        assertEquals(LocalDate.of(2026, 10, 22),
            ReceiptDraft.parse("Годен до:\nДата чека: 22.10.2026").date)
    }
    @Test fun `date role does not search across gaps or inherit a prior dated row`() {
        assertNull(ReceiptDraft.parse("Годен до\n\n01.12.2026\n22.10.2026").date)
        assertNull(ReceiptDraft.parse("Годен до 01.12.2026\n22.10.2026\n21.10.2026").date)
        assertNull(ReceiptDraft.parse("Товар годен до\n22.10.2026\n21.10.2026").date)
        assertNull(ReceiptDraft.parse("Дата чека:\n22.10.2026\nReceipt date:\n2026-10-21").date)
    }
    @Test fun `grouped volume is never truncated to a positive numeric tail`() {
        listOf(" ", "\u00a0", "\u202f").forEach { separator ->
            listOf("л", "литров", "L").forEach { unit ->
                assertNull(ReceiptDraft.parse("Бензин 1${separator}020 $unit").liters)
                assertNull(ReceiptDraft.parse("Бензин -1${separator}020 $unit").liters)
                assertNull(ReceiptDraft.parse("Бензин 1${separator}020,50 $unit").liters)
            }
        }
        assertNull(ReceiptDraft.parse("Бензин 1 020 л 20 л").liters)
        assertNull(ReceiptDraft.parse("Бензин -20 л 40 л").liters)
        assertNull(ReceiptDraft.parse("Бензин 0 л 40 л").liters)
        assertNull(ReceiptDraft.parse("Бензин -1 020 л 40 л").liters)
        assertNull(ReceiptDraft.parse("Бензин 20\nл").liters)
        assertEquals(20.5, ReceiptDraft.parse("Бензин АИ-95 20,50 л").liters!!, 0.0)
    }
    @Test fun `oil and additives using a fuel name are not fuel purchases`() {
        listOf(
            "Присадка в дизельное топливо 1 л",
            "Присадка в бензин 1 литр",
            "Diesel engine oil 4 L",
            "Diesel additive 1 litre",
            "Олива дизельне 4 л",
        ).forEach { text ->
            val draft = ReceiptDraft.parse(text)
            assertNull(text, draft.fuelType)
            assertNull(text, draft.liters)
        }
        val mixed = ReceiptDraft.parse("Дизельное топливо 20 литров\nDiesel engine oil 4 L")
        assertEquals(FuelType.DIESEL, mixed.fuelType)
        assertEquals(20.0, mixed.liters!!, 0.0)
    }
}
