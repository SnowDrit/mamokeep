package io.github.snowdrit.mamokeep.data.scanner.stage2dev

import io.github.snowdrit.mamokeep.domain.FuelType
import java.time.DateTimeException
import java.time.LocalDate

internal const val FROZEN_STAGE2_PARSER_SHA256 = "34259f0d76df93d15bc029b3dd412486923372ddbfdebdc2f9eb9e9531b41920"

/** Suggestions only. Unknown or conflicting printed values stay empty for human review. */
data class ReceiptDraft(
    val sourceText: String,
    val date: LocalDate?,
    val odometerKm: Int?,
    val liters: Double?,
    val fuelType: FuelType?,
) {
    companion object {
        fun parse(text: String): ReceiptDraft {
            val lines = text.lineSequence().toList()
            val dateCandidates = lines.flatMapIndexed { index, line ->
                // Only a standalone label on the immediately preceding row can
                // describe a date whose own row starts without a role.
                val previousRole = lines.getOrNull(index - 1)?.trim()?.takeIf {
                    !datePattern.containsMatchIn(it) &&
                        (excludedDateContext.matches(it) || documentDateContext.matches(it))
                }
                var previousDateEnd = 0
                datePattern.findAll(line).mapNotNull { match ->
                    // A role describes the next date on this row, not every later date.
                    val prefix = line.substring(previousDateEnd, match.range.first)
                    val role = if (previousDateEnd == 0 && prefix.isBlank()) previousRole ?: prefix else prefix
                    previousDateEnd = match.range.last + 1
                    if (excludedDateContext.containsMatchIn(role)) return@mapNotNull null
                    val (first, separator, middle, last) = match.destructured
                    val yearFirst = first.length == 4
                    if (!yearFirst && last.length != 4) return@mapNotNull null
                    // Slashes without a year first are ambiguous between US and DMY dates.
                    if (!yearFirst && separator == "/" && first.toInt() <= 12 && middle.toInt() <= 12) return@mapNotNull null
                    try {
                        val date = LocalDate.of(if (yearFirst) first.toInt() else last.toInt(), middle.toInt(), if (yearFirst) last.toInt() else first.toInt())
                        documentDateContext.containsMatchIn(role) to date
                    } catch (_: DateTimeException) { null }
                }.toList()
            }
            val hasDocumentDate = dateCandidates.any { it.first }
            val dates = dateCandidates.filter { it.first == hasDocumentDate }
                .map { it.second }.distinct().singleOrNull()
            val odometers = kmPattern.findAll(text).mapNotNull { match ->
                match.groupValues[1].replace(" ", "").replace("\u00a0", "").toIntOrNull()
            }.distinct().toList().singleOrNull()
            val fuelLines = lines.filter { fuelTypes(it).isNotEmpty() }
            val types = fuelLines.flatMap(::fuelTypes).distinct()
            // A volume must be printed on the one fuel row; receipt totals and unrelated
            // product quantities are not evidence of the amount dispensed.
            val liters = if (refund.containsMatchIn(text)) null else fuelLines.singleOrNull()?.let { line ->
                literPattern.findAll(line).singleOrNull()?.let volume@ { match ->
                    // A trailing operand in "73.85*50 л" or "1/2 л" is not
                    // an independently labelled volume. Do not pick the price
                    // or denominator, or reconstruct the other operand.
                    if (arithmeticBeforeVolume.containsMatchIn(line.substring(0, match.range.first))) return@volume null
                    // Keep a grouped number whole: an unsupported "1 020" must
                    // stay unknown rather than becoming its positive tail "020".
                    match.groupValues[1].replace(',', '.').toDoubleOrNull()
                        ?.takeIf { it.isFinite() && it > 0 }
                }
            }
            return ReceiptDraft(text, dates, odometers, liters, types.singleOrNull())
        }

        private val datePattern = Regex("(?<!\\d)(\\d{4}|\\d{1,2})([./-])(\\d{1,2})\\2(\\d{4}|\\d{1,2})(?!\\d)")
        private val excludedDateContext = Regex(
            "(?<![\\p{L}\\p{N}_])(?:годен(?:\\s+до)?|срок\\s+(?:годности|оплаты|действия|гарантии)(?:\\s+до)?|" +
                "дата\\s+(?:производства|изготовления)|изготовлен[оа]?|произвед[её]н[оа]?|гарантия\\s+до|" +
                "придатн(?:ий|а|е|і)(?:\\s+до)?|термін\\s+(?:придатності|оплати|дії|гарантії)(?:\\s+до)?|" +
                "дата\\s+(?:виробництва|виготовлення)|виготовлено|вироблено|гарантія\\s+до|" +
                "(?:payment\\s+)?due(?:\\s+date)?|expiry(?:\\s+date)?|expiration(?:\\s+date)?|expires?(?:\\s+on)?|" +
                "manufactur(?:ed|ing)(?:\\s+date)?|production\\s+date|warranty\\s+until|" +
                "к\\s+договору.*(?<![\\p{L}\\p{N}_])от)\\s*[:=]?\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val documentDateContext = Regex(
            "(?<![\\p{L}\\p{N}_])(?:дата\\s+(?:документа|документу|чека|заказ[ -]?наряда|составления|оформления|" +
                "замовлення[ -]?наряду|складання|оформлення)|(?:document|receipt|invoice|issue)\\s+date)\\s*[:=]?\\s*$|" +
                "^\\s*(?:дата|date)\\s*[:=]?\\s*$|" +
                "(?<![\\p{L}\\p{N}_])(?:заказ[ -]?наряд|замовлення[ -]?наряд|work\\s+order|invoice).*" +
                "(?<![\\p{L}\\p{N}_])(?:от|від|dated)\\s*$",
            RegexOption.IGNORE_CASE,
        )
        private val kmPattern = Regex("(?:пробег|одометр|пробіг|odometer)\\s*[:=]?\\s*(\\d(?:[ \\u00a0]?\\d){0,6})\\s*[кk][мm](?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val literPattern = Regex("(?<![\\p{L}\\p{N}_.,+-])([+-]?\\d+(?:[ \\u00a0\\u202f]+\\d+)*(?:[.,]\\d+)?)[ \\u00a0\\u202f]*(?:л|литр(?:а|ов|ы)?|l|litres?|liters?)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val arithmeticBeforeVolume = Regex("[*/×xх][ \\u00a0\\u202f]*$", RegexOption.IGNORE_CASE)
        private val petrol = Regex("(?<![\\p{L}\\p{N}_])(?:бензин|бензін|petrol|gasoline|аи[ -]*\\d{2,3}|аі[ -]*\\d{2,3})(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val diesel = Regex("(?<![\\p{L}\\p{N}_])(?:дизель|дизельне|дизельное\\s+топливо|diesel|дт)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val lpg = Regex("(?<![\\p{L}\\p{N}_])(?:пропан|lpg)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val refund = Regex("(?<![\\p{L}\\p{N}_])(?:возврат|повернення|refund|return|сторно)(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        private val nonFuelProduct = Regex(
            "(?<![\\p{L}\\p{N}_])(?:масло|масла|олива|оливи|oil|oils|присадка|присадки|additive|additives)(?![\\p{L}\\p{N}_])",
            RegexOption.IGNORE_CASE,
        )
        private fun fuelTypes(line: String): List<FuelType> = buildList {
            // A fuel name can describe the intended use of oil or an additive.
            // That row does not prove a purchase of fuel or its dispensed volume.
            if (nonFuelProduct.containsMatchIn(line)) return@buildList
            if (petrol.containsMatchIn(line)) add(FuelType.PETROL)
            if (diesel.containsMatchIn(line)) add(FuelType.DIESEL)
            if (lpg.containsMatchIn(line)) add(FuelType.LPG)
        }
    }
}
