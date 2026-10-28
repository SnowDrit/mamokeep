package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.OdometerBounds
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.scanner.ReceiptDraft
import io.github.snowdrit.mamokeep.ui.scanner.EditorOperationViewModel
import io.github.snowdrit.mamokeep.ui.scanner.OcrConfirmation
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.text.amountText
import io.github.snowdrit.mamokeep.text.formatInputDecimal
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.moneyText
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.math.BigDecimal
import kotlin.math.roundToInt

private val DATE_SHORT = DateTimeFormatter.ofPattern("dd.MM.yy")

/**
 * Запись заправки.
 *
 * Порядок полей повторяет порядок действий у колонки: сначала одометр (он перед глазами),
 * потом вид топлива, количество и сумма с чека.
 *
 * Вид выбирается у каждой заправки, а не у машины: битопливная машина ездит и на бензине,
 * и на газе (docs/DECISIONS.md, решение 23). Чтобы это не превращалось в лишний тап для
 * тех, у кого вид один, подставляется тот, которым заправлялись в прошлый раз.
 */
@Composable
fun FuelEditor(
    recent: List<FuelEntry>,
    display: Display,
    defaultType: FuelType,
    carId: Long,
    cars: List<Car>,
    onDismiss: () -> Unit,
    onSave: suspend (carId: Long, date: LocalDate, odometerKm: Int, amount: Double, type: FuelType, costKopecks: Long, isFull: Boolean) -> Unit,
    editing: FuelEntry? = null,
    lastKnownKmOf: suspend (Long) -> Int? = { null },
    /**
     * Что записано у машины по обе стороны от выбранной дня — без самой правимой
     * заправки. По этим числам видно, не уедет ли пробег машины назад.
     */
    odometerBoundsOf: suspend (Long, LocalDate) -> OdometerBounds = { _, _ -> OdometerBounds() },
    /** Расчётный пробег машины на сегодня — подсказкой под полем одометра. */
    mileageNowOf: suspend (Long) -> MileageEstimate? = { null },
    onDelete: (suspend () -> Unit)? = null,
    scanDraft: ReceiptDraft? = null,
    scanCurrencyCode: String? = null,
    scanSource: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val units = display.units
    val initialKm = editing?.odometerKm ?: scanDraft?.odometerKm
    val initialOdometer = initialKm?.let { units.distance(it).roundToInt().toString() }.orEmpty()
    val initialCost = editing?.let { BigDecimal.valueOf(it.costKopecks, 2).stripTrailingZeros().toPlainString() }.orEmpty()
    val initialAmount = editing?.amount ?: scanDraft?.liters
    val initialAmountType = editing?.fuelType ?: scanDraft?.fuelType ?: FuelType.PETROL
    val initialAmountText = initialAmount?.let {
        formatInputDecimal(units.volume(it, initialAmountType), display.words)
    }.orEmpty()
    val session = rememberSaveable(editing?.id) { UUID.randomUUID().toString() }
    val operation: EditorOperationViewModel = viewModel(key = "fuel-editor-operation")
    LaunchedEffect(session) { operation.attach(session) }
    LaunchedEffect(operation.completed) {
        if (operation.completed) {
            operation.reset()
            onDismiss()
        }
    }
    val close = { if (!operation.busy) { operation.reset(); onDismiss() } }

    // В правке поля заранее заполнены тем, что записано. Ключ по заправке: экран один
    // и тот же, а открывают его для разных записей.
    var odometer by rememberSaveable(editing?.id) {
        mutableStateOf(initialOdometer)
    }
    var amount by rememberSaveable(editing?.id) {
        mutableStateOf(initialAmountText)
    }
    var cost by rememberSaveable(editing?.id) {
        mutableStateOf(initialCost)
    }
    var isFull by rememberSaveable(editing?.id) { mutableStateOf(editing?.isFull ?: (scanDraft == null)) }
    var fullConfirmed by rememberSaveable(editing?.id) { mutableStateOf(scanDraft == null) }
    var dateConfirmed by rememberSaveable(editing?.id) { mutableStateOf(scanDraft == null) }
    var reviewed by rememberSaveable(editing?.id) { mutableStateOf(scanDraft == null) }
    // Заправка почти всегда сегодняшняя, поэтому дата подставлена, а не спрашивается.
    var date by rememberSaveable(editing?.id) {
        mutableStateOf(editing?.date ?: scanDraft?.date?.takeUnless { it.isAfter(LocalDate.now()) } ?: LocalDate.now())
    }
    var target by rememberSaveable(editing?.id, carId) { mutableStateOf(editing?.carId ?: carId) }
    var confirmDelete by rememberSaveable(editing?.id) { mutableStateOf(false) }
    // История загружается заново после поворота. Её подсказка не должна сбросить
    // сохранённый выбор человека, когда приедет первый непустой список заправок.
    val suggested = editing?.fuelType ?: scanDraft?.fuelType ?: recent.firstOrNull()?.fuelType ?: defaultType
    var chosenType by rememberSaveable(editing?.id) { mutableStateOf<FuelType?>(null) }
    val type = chosenType ?: suggested

    // Наружу уходит каноническое: километры и литры. Мили и галлоны живут только на экране.
    // Preserve the exact stored kilometre value when a rounded miles field was not edited.
    val km = if (initialKm != null && odometer == initialOdometer) initialKm
        else odometer.toIntOrNull()?.takeIf { it >= 0 }?.let { units.kmFrom(it.toDouble()) }
    val amountValue = fuelAmountForSave(amount, type, units, initialAmount, initialAmountText, initialAmountType)
        ?.takeIf { it.isFinite() && it > 0.0 }
    val costValue = cost.toDecimalOrNull()?.takeIf { it.isFinite() && it >= 0.0 && it < Long.MAX_VALUE / 100.0 }
    val costCents = if (editing != null && cost == initialCost) editing.costKopecks
        else costValue?.let { Math.round(it * 100) }
    val typeConfirmed = scanDraft == null || scanDraft.fuelType != null || chosenType != null
    val canSave = km != null && amountValue != null && costCents != null &&
        typeConfirmed && fullConfirmed && dateConfirmed && reviewed && !operation.busy

    // Границы пересчитываются на смену машины и даты: обе меняют, с чем сравнивать.
    var mileageNow by remember(editing?.id) { mutableStateOf<MileageEstimate?>(null) }
    LaunchedEffect(target) { mileageNow = mileageNowOf(target) }

    var bounds by remember(editing?.id) { mutableStateOf(OdometerBounds()) }
    LaunchedEffect(target, date) { bounds = odometerBoundsOf(target, date) }

    // Вид в списке прошлых заправок нужен только тому, кто заправляется по-разному.
    val mixed = remember(recent) {
        val firstType = recent.firstOrNull()?.fuelType
        recent.any { it.fuelType != firstType }
    }

    EditorScaffold(
        title = if (editing == null) {
            stringResource(R.string.fuel_title)
        } else {
            // Дата в заголовке живая, а не та, с которой экран открыли: поле даты рядом,
            // и заголовок, оставшийся на старом числе, читался бы как «правка не применилась».
            stringResource(R.string.fuel_edit_title, date.format(DATE_SHORT))
        },
        onClose = close,
        onSave = if (!canSave) null else {
            {
                val savedTarget = target
                val savedDate = date
                val savedKm = km!!
                val savedAmount = amountValue!!
                val savedCost = costCents!!
                val savedFull = isFull
                operation.run { onSave(savedTarget, savedDate, savedKm, savedAmount, type, savedCost, savedFull) }
            }
        },
        modifier = modifier,
    ) {
                if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (operation.failed || operation.interrupted) Text(
                    stringResource(if (operation.interrupted) R.string.ocr_save_interrupted else R.string.ocr_save_failed),
                    color = MaterialTheme.colorScheme.error,
                )
                if (scanDraft != null) {
                    scanSource?.invoke()
                    Text(stringResource(R.string.ocr_fuel_review, scanCurrencyCode ?: display.currency, display.distanceUnit),
                        style = MaterialTheme.typography.bodySmall)
                }
                DecimalField(
                    odometer,
                    { odometer = it.filter(Char::isDigit).take(7) },
                    stringResource(R.string.fuel_odometer_field, display.distanceUnit),
                    GroupedDigitsTransformation(display.words.groupSeparator),
                    enabled = !operation.busy,
                )

                // Что приложение считает текущим пробегом. Не подставляем в поле:
                // одометр там обязан быть списанным с панели, а не посчитанным.
                // Но человеку, который его списывает, полезно видеть, с чем сверяться:
                // ошибка в разряде иначе уезжает в базу молча.
                //
                // Только при заведении: в правке число уже стоит и уже принято.
                if (editing == null) {
                    MileageNowHint(mileage = mileageNow, display = display)
                }

                // Выбор машины стоит вплотную к одометру не для красоты: перенося
                // заправку, человек тащит вместе с ней чужое показание одометра, и
                // увидеть это он должен здесь же, а не потом в расходе.
                if (cars.size > 1) {
                    CarPicker(cars = cars, selected = target, onSelect = { target = it }, enabled = !operation.busy)

                    val movedFrom = editing?.carId ?: carId
                    if (target != movedFrom) {
                        var targetKm by remember(target) { mutableStateOf<Int?>(null) }
                        LaunchedEffect(target) { targetKm = lastKnownKmOf(target) }
                        val targetName = cars.firstOrNull { it.id == target }?.name
                        if (targetKm != null && targetName != null) {
                            Text(
                                stringResource(
                                    // В правке в поле лежит одометр прежней машины, и об
                                    // этом надо сказать прямо. При добавлении поле пустое —
                                    // тогда та же фраза была бы неправдой, и остаётся
                                    // только сам пробег для сверки.
                                    if (editing == null) R.string.fuel_car_mileage
                                    else R.string.fuel_car_hint,
                                    targetName,
                                    display.distanceText(targetKm!!),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Дата стоит после машины, а не перед ней: выбор машины намеренно прижат
                // к одометру, и разрывать эту пару нельзя. Календарь не пускает дальше
                // сегодня — заправка это наблюдённый факт, будущей она не бывает, а
                // будущая дата мгновенно становится «сейчас» в оценке пробега.
                DateField(
                    date = date,
                    onDate = { if (!operation.busy) { date = it; dateConfirmed = true } },
                    label = stringResource(R.string.fuel_date_field),
                    maxDate = LocalDate.now(),
                    enabled = !operation.busy,
                )
                if (scanDraft != null) OcrConfirmation(
                    dateConfirmed, { dateConfirmed = it }, stringResource(R.string.ocr_confirm_date), !operation.busy,
                )

                // Одометр за датой не едет — в отличие от отметки «сделано». Там число
                // посчитано нами, здесь оно списано с прибора и нашим никогда не было.
                //
                // Зато о противоречии говорим вслух: лента показаний сортируется по дате,
                // и заправка, уехавшая на чужой день, делает «последним известным» меньшее
                // число. Не запрещаем — человек может чинить как раз ту заправку, что
                // записана криво, и запрет загнал бы его в тупик.
                val before = bounds.maxBefore
                val after = bounds.minAfter
                val warning = when {
                    km == null -> null
                    before != null && km < before -> R.string.fuel_date_warn_before to before
                    after != null && km > after -> R.string.fuel_date_warn_after to after
                    else -> null
                }
                warning?.let { (text, value) ->
                    Text(
                        stringResource(text, display.distanceText(value)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FuelType.entries.forEach { option ->
                        FilterChip(
                            selected = typeConfirmed && option == type,
                            onClick = {
                                if (scanDraft != null && type.isLiquid != option.isLiquid) amount = ""
                                if (scanDraft != null && type != option) fullConfirmed = false
                                chosenType = option
                            },
                            enabled = !operation.busy,
                            label = { Text(stringResource(fuelTypeTitle(option))) },
                        )
                    }
                }

                // Подпись поля меняется вместе с видом: в бак льют литры, в баллон —
                // кубометры, в батарею не льют вовсе.
                if (!typeConfirmed) Text(stringResource(R.string.ocr_choose_fuel_type), style = MaterialTheme.typography.bodySmall)
                DecimalField(amount, { amount = it.filterDecimal() }, stringResource(amountLabel(units, type)), enabled = !operation.busy)
                DecimalField(cost, { cost = it.filterDecimal() }, stringResource(R.string.fuel_cost_field, display.currency), enabled = !operation.busy)

                val fullTankLabel = stringResource(
                    if (type.chargedToLimit) R.string.fuel_charged_to_limit
                    else R.string.fuel_full_tank
                )
                if (scanDraft != null) {
                    Text(fullTankLabel)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(true, false).forEach { value ->
                            FilterChip(
                                selected = fullConfirmed && isFull == value,
                                onClick = { isFull = value; fullConfirmed = true },
                                enabled = !operation.busy,
                                label = { Text(stringResource(if (value) R.string.ocr_yes else R.string.ocr_no)) },
                            )
                        }
                    }
                    OcrConfirmation(reviewed, { reviewed = it }, stringResource(R.string.ocr_confirm_fields), !operation.busy)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isFull,
                        onCheckedChange = { isFull = it },
                        enabled = !operation.busy,
                        modifier = Modifier.semantics { contentDescription = fullTankLabel },
                    )
                    Column {
                        Text(
                            fullTankLabel,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(
                                if (type.chargedToLimit) R.string.fuel_charge_hint
                                else R.string.fuel_full_tank_hint
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (editing == null && recent.isNotEmpty()) {
                    HorizontalDivider()
                    Text(stringResource(R.string.fuel_recent), style = MaterialTheme.typography.labelLarge)
                    recent.take(10).forEach { entry ->
                        // Вид отдельной строкой, а не в конце правой: вместе с датой,
                        // пробегом, литрами и суммой он не влезал в ширину и
                        // склеивался с километрами — «80 500 км40,0 л бензина».
                        Column {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(
                                    "${entry.date.format(DATE_SHORT)} · " +
                                        display.distanceText(entry.odometerKm),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    display.amountText(entry.amount, entry.fuelType) +
                                        " · ${display.moneyText(entry.costKopecks)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (mixed) {
                                Text(
                                    stringResource(fuelTypeTitle(entry.fuelType)).lowercase(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

        // Удаление внизу содержимого, а не в панели рядом с «Сохранить»: соседство
        // сохраняющего и уничтожающего действия — верный способ промахнуться.
        if (onDelete != null) {
            HorizontalDivider()
            TextButton(
                onClick = { confirmDelete = true },
                enabled = !operation.busy,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) { Text(stringResource(R.string.fuel_delete)) }
        }
    }

    // Спрашиваем, потому что вернуть заправку нечем: она уносит с собой и своё показание
    // одометра, а расход считается по расстоянию между заправками.
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.fuel_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        operation.run { onDelete() }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    /**
     * Разряды разделяются только у одометра. У литров и денег дробная часть, и разделять
     * там нечего: «45,0» и «3 500» живут по разным правилам.
     */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        enabled = enabled,
        onValueChange = onValueChange,
        // Единица измерения в подписи, а не в suffix: Material3 прячет suffix у пустого
        // поля, и человек видит «Одометр» без намёка на то, чего от него ждут.
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Выбор машины списком, а не рядом чипов: у человека с пятью машинами ряд уезжает
 * за край экрана, и выбирать приходится вслепую, прокручивая вбок.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CarPicker(cars: List<Car>, selected: Long, onSelect: (Long) -> Unit, enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    val current = cars.firstOrNull { it.id == selected }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
    ) {
        OutlinedTextField(
            value = current?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.fuel_car)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            cars.forEach { car ->
                DropdownMenuItem(
                    text = { Text(car.name) },
                    onClick = {
                        onSelect(car.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Display rounding must not change a recorded or scanned canonical quantity on save. */
internal fun fuelAmountForSave(
    text: String,
    type: FuelType,
    units: UnitSystem,
    initialAmount: Double?,
    initialText: String,
    initialType: FuelType,
): Double? = if (initialAmount != null && text == initialText && type == initialType) initialAmount
    else text.toDecimalOrNull()?.let { units.litersFrom(it, type) }

/** Оставляет цифры и один разделитель — люди набирают и точку, и запятую. */
private fun String.filterDecimal(): String {
    val normalized = replace(',', '.')
    val builder = StringBuilder()
    var separatorUsed = false
    for (char in normalized) {
        when {
            char.isDigit() -> builder.append(char)
            char == '.' && !separatorUsed && builder.isNotEmpty() -> {
                builder.append(char)
                separatorUsed = true
            }
        }
    }
    return builder.toString().take(9)
}

private fun String.toDecimalOrNull(): Double? = replace(',', '.').toDoubleOrNull()

/**
 * «Сейчас примерно 10 900 км» под полем одометра.
 *
 * Два текста, а не один: пока пробег достроен средним дневным, честное слово —
 * «примерно»; если последняя запись сделана сегодня, достраивать нечего, и называть
 * точное число приблизительным было бы враньём в мелочи.
 */
@Composable
fun MileageNowHint(mileage: MileageEstimate?, display: Display) {
    if (mileage == null) return

    Text(
        stringResource(
            if (mileage.isExtrapolated) R.string.mileage_now_estimated
            else R.string.mileage_now_recorded,
            display.distanceText(mileage.km),
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
