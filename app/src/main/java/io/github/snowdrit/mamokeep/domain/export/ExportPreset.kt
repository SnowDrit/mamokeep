package io.github.snowdrit.mamokeep.domain.export

/**
 * Сценарные пресеты выгрузки.
 *
 * Обычный человек не знает, какие данные нужны нейросети: он не догадывается, что при
 * диагностике важен расход, а при подборе запчастей — история работ. Пресет — это наша
 * экспертиза, зашитая в кнопку (docs/DECISIONS.md, решение 11).
 *
 * Вместе с данными кладём готовый вопрос: человек вставляет всё в чат и сразу получает
 * осмысленный ответ, а не «чем могу помочь?». Сами тексты живут в ресурсах и приходят
 * в выгрузку через [ExportTexts] — иначе выгрузка говорила бы по-русски в английском
 * приложении.
 */
enum class ExportPreset(
    val historyLimit: Int,
    val fuelLimit: Int,
    val includeFuelEntries: Boolean,
    val includeOdometerReadings: Boolean,
) {
    MAINTENANCE_ADVICE(historyLimit = 10, fuelLimit = 0, includeFuelEntries = false, includeOdometerReadings = false),
    FULL_DUMP(historyLimit = Int.MAX_VALUE, fuelLimit = Int.MAX_VALUE, includeFuelEntries = true, includeOdometerReadings = true),
}
