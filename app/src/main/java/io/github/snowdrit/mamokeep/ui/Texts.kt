package io.github.snowdrit.mamokeep.ui

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.DisplayWords
import io.github.snowdrit.mamokeep.domain.FuelGrade
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.DocumentPreset
import io.github.snowdrit.mamokeep.domain.ItemTitles
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import io.github.snowdrit.mamokeep.domain.export.ExportTexts

/**
 * Мост между ключами домена и словами из ресурсов.
 *
 * Домен хранит ключи — виды топлива, значки, названия позиций, — а здесь к ним
 * подставляются слова выбранного языка (docs/DECISIONS.md, решение 22). Всё в одном
 * файле нарочно: если такой мост расползётся по экранам, половина ключей рано или
 * поздно останется без перевода.
 */

@StringRes
fun fuelTypeTitle(type: FuelType): Int = when (type) {
    FuelType.PETROL -> R.string.fuel_type_petrol
    FuelType.DIESEL -> R.string.fuel_type_diesel
    FuelType.LPG -> R.string.fuel_type_lpg
    FuelType.CNG -> R.string.fuel_type_cng
    FuelType.ELECTRIC -> R.string.fuel_type_electric
}

/** Родительный падеж: «42 л бензина». По-английски — «42 l of petrol». */
@StringRes
fun fuelTypeOf(type: FuelType): Int = when (type) {
    FuelType.PETROL -> R.string.fuel_of_petrol
    FuelType.DIESEL -> R.string.fuel_of_diesel
    FuelType.LPG -> R.string.fuel_of_lpg
    FuelType.CNG -> R.string.fuel_of_cng
    FuelType.ELECTRIC -> R.string.fuel_of_electric
}

@StringRes
fun fuelGradeTitle(grade: FuelGrade): Int = when (grade) {
    FuelGrade.AI_92 -> R.string.grade_ai_92
    FuelGrade.AI_95 -> R.string.grade_ai_95
    FuelGrade.AI_98 -> R.string.grade_ai_98
    FuelGrade.AI_100 -> R.string.grade_ai_100
    FuelGrade.DIESEL -> R.string.grade_diesel
    FuelGrade.LPG -> R.string.grade_lpg
    FuelGrade.CNG -> R.string.grade_cng
    FuelGrade.ELECTRIC -> R.string.grade_electric
}

@StringRes
fun unitSystemTitle(system: UnitSystem): Int = when (system) {
    UnitSystem.METRIC -> R.string.units_metric
    UnitSystem.UK -> R.string.units_uk
    UnitSystem.US -> R.string.units_us
}

@StringRes
fun unitSystemExplained(system: UnitSystem): Int = when (system) {
    UnitSystem.METRIC -> R.string.units_metric_explained
    UnitSystem.UK -> R.string.units_uk_explained
    UnitSystem.US -> R.string.units_us_explained
}

/** Подпись поля количества: зависит от того, чем меряют этот вид в этом наборе. */
@StringRes
fun amountLabel(system: UnitSystem, type: FuelType): Int =
    when (system.amountUnit(type)) {
        io.github.snowdrit.mamokeep.domain.AmountUnit.LITER -> R.string.fuel_amount_liters
        io.github.snowdrit.mamokeep.domain.AmountUnit.GALLON -> R.string.fuel_amount_gallons
        io.github.snowdrit.mamokeep.domain.AmountUnit.CUBIC_METER -> R.string.fuel_amount_cubic
        io.github.snowdrit.mamokeep.domain.AmountUnit.KWH -> R.string.fuel_amount_kwh
    }

/** «полный бак» или «до обычного предела» — у электромобиля это разные вещи. */
@StringRes
fun fullLabel(type: FuelType): Int =
    if (type.chargedToLimit) R.string.fuel_full_label_charge else R.string.fuel_full_label_tank


@StringRes
fun exportPresetTitle(preset: ExportPreset): Int = when (preset) {
    ExportPreset.MAINTENANCE_ADVICE -> R.string.export_preset_advice
    ExportPreset.FULL_DUMP -> R.string.export_preset_full
}

@StringRes
fun exportPresetSubtitle(preset: ExportPreset): Int = when (preset) {
    ExportPreset.MAINTENANCE_ADVICE -> R.string.export_preset_advice_sub
    ExportPreset.FULL_DUMP -> R.string.export_preset_full_sub
}

/**
 * Названия позиций из набора по умолчанию.
 *
 * Неизвестный ключ отдаёт то, что лежит в базе: позиция могла приехать из копии, снятой
 * версией, где такого ключа ещё не было, и терять её название нельзя.
 */
fun itemTitles(res: Resources, regions: List<RegionPreset> = emptyList()): ItemTitles {
    // Ключ документа → название на всех языках, какие есть в пакетах. Собирается один
    // раз: пакеты лежат в ассетах и на ходу не меняются.
    val documents = regions.flatMap { region ->
        region.documents.map { it.key to (it to region.lang) }
    }.toMap()

    return ItemTitles { key, stored ->
        resolveTitle(res, key, stored, documents)
    }
}

private fun resolveTitle(
    res: Resources,
    key: String?,
    stored: String,
    documents: Map<String, Pair<DocumentPreset, String>>,
): String {
    documents[key]?.let { (document, packLang) ->
        return document.title(uiLanguage(res), packLang)
    }

    val id = when (key) {
        "oil" -> R.string.maint_oil
        "cabin_filter" -> R.string.maint_cabin_filter
        "air_filter" -> R.string.maint_air_filter
        "spark_plugs" -> R.string.maint_spark_plugs
        "brake_pads_front" -> R.string.maint_brake_pads_front
        "brake_fluid" -> R.string.maint_brake_fluid
        "gearbox_oil" -> R.string.maint_gearbox_oil
        "coolant" -> R.string.maint_coolant
        "timing_belt" -> R.string.maint_timing_belt
        else -> null
    }
    return id?.let(res::getString) ?: stored
}

/** Язык интерфейса, а не телефона: приложению можно назначить свой язык отдельно. */
fun uiLanguage(res: Resources): String =
    res.configuration.locales[0].language.ifEmpty { "en" }

/**
 * Пояснение к документу «почему именно такой срок» — на языке интерфейса.
 *
 * Живёт в пакете региона рядом с самим документом: это знание про страну, а не про
 * приложение (решение 8).
 */
fun documentNotes(res: Resources, regions: List<RegionPreset>): (String?) -> String? {
    val lang = uiLanguage(res)
    val notes = regions.flatMap { region ->
        region.documents.map { it.key to it.note(lang, region.lang) }
    }.toMap()

    return { key -> key?.let(notes::get) }
}

/** Слова и знаки для чисел — собираются один раз на запуск. */
fun displayWords(res: Resources): DisplayWords = DisplayWords(
    km = res.getString(R.string.unit_km),
    mile = res.getString(R.string.unit_mile),
    kmNoun = res.getString(R.string.unit_km_noun),
    mileNoun = res.getString(R.string.unit_mile_noun),
    kmNounPer = res.getString(R.string.unit_km_per),
    mileNounPer = res.getString(R.string.unit_mile_per),
    liter = res.getString(R.string.unit_liter),
    gallon = res.getString(R.string.unit_gallon),
    cubicMeter = res.getString(R.string.unit_cubic),
    kwh = res.getString(R.string.unit_kwh),
    mpg = res.getString(R.string.unit_mpg),
    perHundred = res.getString(R.string.unit_per_hundred),
    ofTotal = res.getString(R.string.unit_of_total),
    decimalSeparator = res.getString(R.string.unit_decimal_separator).first(),
    groupSeparator = res.getString(R.string.unit_group_separator).first(),
    days = res.getString(R.string.unit_days_short),
    months = res.getString(R.string.unit_months_short),
    years = res.getString(R.string.unit_years_short),
)

fun display(res: Resources, units: UnitSystem, currency: String): Display =
    Display(units = units, currency = currency, words = displayWords(res))

/** Все слова выгрузки. Собираются на месте вызова — выгрузка редкая, экономить нечего. */
fun exportTexts(res: Resources): ExportTexts = ExportTexts(
    prompt = { preset ->
        res.getString(
            when (preset) {
                ExportPreset.MAINTENANCE_ADVICE -> R.string.export_prompt_advice
                ExportPreset.FULL_DUMP -> R.string.export_prompt_full
            }
        )
    },
    questionHeader = res.getString(R.string.export_question_header),
    dataNote = res.getString(R.string.export_data_note),
    mileageNotEstimated = res.getString(R.string.export_mileage_not_estimated),
    readingsHeader = res.getString(R.string.export_readings_header),
    maintenanceBasis = res.getString(R.string.export_maintenance_basis),
    lastMileage = res.getString(R.string.export_last_mileage),
    dueDate = res.getString(R.string.export_due_date),
    dueNow = res.getString(R.string.export_due_now),
    dueDistance = res.getString(R.string.export_due_distance),
    dueTime = res.getString(R.string.export_due_time),
    distanceUnknown = res.getString(R.string.export_distance_unknown),
    timeUnknown = res.getString(R.string.export_time_unknown),
    noHistory = res.getString(R.string.export_no_history),
    historyDocument = res.getString(R.string.export_history_document),
    note = res.getString(R.string.export_note),
    fuelMethod = res.getString(R.string.export_fuel_method),
    fuelNotCalculated = res.getString(R.string.export_fuel_not_calculated),
    noFuelEntries = res.getString(R.string.export_no_fuel_entries),
    costEstimate = res.getString(R.string.export_cost_estimate),
    fuelEntriesOmitted = { res.getString(R.string.export_fuel_entries_omitted, it) },
    carHeader = res.getString(R.string.export_car_header),
    name = res.getString(R.string.export_name),
    make = res.getString(R.string.export_make),
    model = res.getString(R.string.export_model),
    year = res.getString(R.string.export_year),
    fuelGrade = res.getString(R.string.export_fuel_grade),
    vin = res.getString(R.string.export_vin),
    anonymized = res.getString(R.string.export_anonymized),
    mileageHeader = res.getString(R.string.export_mileage_header),
    mileageUnknown = res.getString(R.string.export_mileage_unknown),
    mileageNow = res.getString(R.string.export_mileage_now),
    mileageLastReading = res.getString(R.string.export_mileage_last),
    mileagePerDay = res.getString(R.string.export_mileage_per_day),
    mileageIsEstimate = res.getString(R.string.export_mileage_estimate),
    maintenanceHeader = res.getString(R.string.export_maintenance_header),
    noItems = res.getString(R.string.export_no_items),
    documentsHeader = res.getString(R.string.export_documents_header),
    unknownNote = res.getString(R.string.export_unknown_note),
    overdue = res.getString(R.string.export_overdue),
    overdueByDistance = res.getString(R.string.export_overdue_by_distance),
    overdueByTime = res.getString(R.string.export_overdue_by_time),
    stillDistance = { res.getString(R.string.export_still_distance, it) },
    stillTime = { res.getString(R.string.export_still_time, it) },
    soon = res.getString(R.string.export_soon),
    ok = res.getString(R.string.export_ok),
    notMarked = res.getString(R.string.export_not_marked),
    leftDistance = { res.getString(R.string.export_left_distance, it) },
    overDistance = { res.getString(R.string.export_over_distance, it) },
    leftTime = { res.getString(R.string.export_left_time, it) },
    overTime = { res.getString(R.string.export_over_time, it) },
    interval = res.getString(R.string.export_interval),
    or = res.getString(R.string.common_or),
    lastTime = res.getString(R.string.export_last_time),
    historyHeader = res.getString(R.string.export_history_header),
    andMore = { res.getString(R.string.export_and_more, it) },
    fuelHeader = res.getString(R.string.export_fuel_header),
    averageConsumption = res.getString(R.string.export_average),
    totalDistanceNote = res.getString(R.string.export_total_note),
    costPer = { money, unit -> res.getString(R.string.export_cost_per, money, unit) },
    entriesHeader = res.getString(R.string.export_entries),
    fullTank = res.getString(R.string.fuel_full_label_tank),
    chargedToLimit = res.getString(R.string.fuel_full_label_charge),
    partial = res.getString(R.string.fuel_partial),
    fuelOf = { res.getString(fuelTypeOf(it)) },
    fuelTitle = { res.getString(fuelTypeTitle(it)) },
    gradeName = { res.getString(fuelGradeTitle(it)) },
    forWord = res.getString(R.string.export_for),
    footer = { res.getString(R.string.export_footer, it) },
)
