package io.github.snowdrit.mamokeep.ui

import androidx.compose.runtime.LaunchedEffect
import io.github.snowdrit.mamokeep.ui.help.*
import androidx.compose.foundation.background
import io.github.snowdrit.mamokeep.domain.OdometerBounds
import io.github.snowdrit.mamokeep.domain.OdometerReading
import java.time.LocalDate
import io.github.snowdrit.mamokeep.ui.theme.LocalDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import io.github.snowdrit.mamokeep.data.repo.CarOverview
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.FuelStats
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.text.amountText
import io.github.snowdrit.mamokeep.text.consumptionText
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.moneyText
import io.github.snowdrit.mamokeep.domain.MaintenanceRecord
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.export.ExportPreset
import io.github.snowdrit.mamokeep.domain.Urgency
import io.github.snowdrit.mamokeep.notifications.ReminderTexts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlin.math.roundToInt

/**
 * Экран, пока данные не приехали из базы.
 *
 * Раньше здесь стояла строчка «Готовим машину…» по центру пустоты. Живёт это состояние
 * около трети секунды на релизе — столько человек успевает увидеть форму, но не прочесть
 * слова, — а потом форма менялась на совсем другую, и получался скачок на первом же кадре.
 *
 * Поэтому не сообщение о загрузке, а тот же экран, только ещё пустой: панель на своём
 * месте, карточки той же высоты и в том же порядке. Мерцающего блеска нет намеренно —
 * он привлекает внимание к ожиданию, которого почти нет.
 *
 * Загруженный пустой гараж показывает отдельное состояние с добавлением машины.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                // Панель без заглушки читалась как пустая полоса над карточками.
                // Прямоугольник стоит там же, где потом окажется имя машины.
                title = { SkeletonBlock(height = 28.dp, width = 180.dp) },
                windowInsets = WindowInsets(0),
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Высоты сняты с настоящих карточек на экране, а не выбраны на глаз:
            // иначе содержимое приезжает и всё-таки дёргает раскладку.
            SkeletonBlock(190.dp)
            SkeletonBlock(115.dp)
            // Стоит за заголовок секции «Обслуживание»: без него карточки под ним
            // приезжали на 80 px выше, чем окажутся, и список подпрыгивал.
            SkeletonBlock(height = 20.dp, width = 160.dp)
            SkeletonBlock(75.dp)
            SkeletonBlock(75.dp)
        }
    }
}

@Composable
private fun SkeletonBlock(height: Dp, width: Dp? = null) {
    Box(
        Modifier
            .let { if (width == null) it.fillMaxWidth() else it.width(width) }
            .height(height)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    )
}

/**
 * Главный экран — светофор.
 *
 * Это основной канал доставки ценности, а уведомления — дублирующий. Причина в том,
 * что Huawei, Xiaomi и Samsung агрессивно убивают фоновые задачи: если бы уведомления
 * были единственным каналом, энергосбережение получило бы право сломать продукт целиком
 * (docs/DECISIONS.md, решение 12).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    overview: CarOverview?,
    garageEmpty: Boolean,
    display: Display,
    cars: List<Car>,
    /** Выбор машины живёт в MainActivity: его открывает и журнал, а не только этот экран. */
    onOpenCarPicker: () -> Unit,
    onAddReading: (Long, Int) -> Unit,
    /** Редакторы живут отдельными экранами в `MainActivity`: экран только зовёт их. */
    onEditItem: (MaintenanceItem) -> Unit,
    onNewItem: (ItemKind) -> Unit,
    onNewFuel: () -> Unit,
    onMark: (MaintenanceStatus) -> Unit,
    onOpenGarage: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenOdometerHistory: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var readingDialogOpen by remember { mutableStateOf(false) }


    if (overview == null) {
        if (garageEmpty) EmptyGarage(onOpenCarPicker, modifier) else HomeSkeleton(modifier)
        return
    }
    val currentCar = cars.firstOrNull { it.id == overview.carId }

    // Панель обычная и закреплённая, а не сжимающаяся, и причина в раскладке.
    //
    // У средней и большой панели действия всегда стоят в верхней строке, а заголовок —
    // в нижней: это их устройство. Из-за него машина с её силуэтом оказывалась **под**
    // «Гаражом», хотя это соседи по смыслу и место им в одной строке. У обычной панели
    // заголовок и действия и так на одной высоте.
    //
    // Сжатие при этом теряется, и это не потеря: замер 2026-08-22 показал, что его
    // стоимость и польза одинаково тонут в шуме. Приложением от страницы панель делает
    // не анимация, а то, что она остаётся на месте и подкрашивается, когда содержимое
    // уезжает под неё. Заодно вернулись ~110 px высоты экрана.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val listState = rememberLazyListState()
    val (documents, maintenance) = overview.statuses.partition { it.item.kind == ItemKind.DOCUMENT }
    // Stable keys describe the same list below. Scroll destinations follow its actual data.
    val sectionKeys = buildList {
        add(HelpTarget.MILEAGE)
        add(HelpTarget.FUEL)
        add(HelpTarget.MAINTENANCE)
        addAll(maintenance.map { it.item.id })
        add(HelpTarget.ADD_MAINTENANCE)
        add(HelpTarget.DOCUMENTS)
    }
    val tourTarget = LocalTourAnchors.current?.activeTarget
    LaunchedEffect(tourTarget) {
        val index = sectionKeys.indexOfFirst { it == tourTarget }
        if (index >= 0) listState.animateScrollToItem(index)
        else if (tourTarget == HelpTarget.CAR || tourTarget == HelpTarget.HELP) listState.animateScrollToItem(0)
    }
    // Через derivedStateOf: иначе слот FAB пересобирается на каждое изменение индекса.
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        // Отступы под системные панели и нижнюю навигацию посчитаны снаружи, в
        // `MainActivity`. Свои этот Scaffold добавлять не должен — иначе заголовок
        // уедет под часы дважды.
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.tourTarget(HelpTarget.CAR),
                // «Гараж» справа — действительно другое действие: профиль текущей
                // машины, а не её переключение. Само имя с силуэтом — см. `CarTitle`.
                title = {
                    CarTitle(
                        name = overview.carName,
                        bodyType = currentCar?.bodyType,
                        silhouetteColor = currentCar?.silhouetteColor,
                        onClick = onOpenCarPicker,
                    )
                },
                actions = {
                    IconButton(onClick = onOpenHelp, modifier = Modifier.tourTarget(HelpTarget.HELP)) {
                        Icon(painterResource(R.drawable.ic_help_outline), contentDescription = stringResource(R.string.help_title))
                    }
                    TextButton(onClick = onOpenGarage) {
                        Text(stringResource(R.string.home_garage))
                    }
                },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            // Заправка — самое частое действие, и она же несёт показание одометра.
            // Свёрнутый значок при прокрутке — то самое движение, которого не было
            // нигде, и стоит оно почти ничего по кадрам.
            ExtendedFloatingActionButton(
                modifier = Modifier.tourTarget(HelpTarget.REFUEL),
                onClick = onNewFuel,
                expanded = fabExpanded,
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_gas_station),
                        contentDescription = stringResource(R.string.home_add_fuel),
                    )
                },
                text = { Text(stringResource(R.string.home_fab_fuel)) },
            )
        },
    ) { inner ->
    LazyColumn(
        state = listState,
        flingBehavior = rememberSoftFling(),
        modifier = Modifier.fillMaxSize().padding(inner).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = HelpTarget.MILEAGE) {
            Box(Modifier.tourTarget(HelpTarget.MILEAGE)) {
            MileageCard(
                mileage = overview.mileage,
                display = display,
                onEnterReading = { readingDialogOpen = true },
            )
            }
        }

        item(key = HelpTarget.FUEL) {
            Box(Modifier.tourTarget(HelpTarget.FUEL)) { FuelCard(fuel = overview.fuel, display = display) }
        }

        item(key = HelpTarget.MAINTENANCE) {
            Box(if (maintenance.isEmpty()) Modifier.tourTarget(HelpTarget.MAINTENANCE) else Modifier) {
                SectionHeader(stringResource(R.string.home_maintenance))
            }
        }

        items(maintenance, key = { it.item.id }) { status ->
            MaintenanceCard(
                status = status,
                display = display,
                onMarkDone = { onMark(status) },
                onOpen = { onEditItem(status.item) },
                // Список отсортирован по срочности: отметил «сделано» — карточка меняет
                // место. Без анимации она телепортируется, и глаз теряет, что произошло.
                modifier = Modifier.animateItem().then(if (status == maintenance.firstOrNull()) Modifier.tourTarget(HelpTarget.MAINTENANCE) else Modifier),
            )
        }

        item(key = HelpTarget.ADD_MAINTENANCE) {
            Button(
                onClick = { onNewItem(ItemKind.MAINTENANCE) },
                modifier = Modifier.fillMaxWidth().tourTarget(HelpTarget.ADD_MAINTENANCE),
            ) { Text(stringResource(R.string.home_add_item)) }
        }

        item(key = HelpTarget.DOCUMENTS) {
            Box(Modifier.fillMaxWidth().tourTarget(HelpTarget.DOCUMENTS)) {
                SectionHeader(stringResource(R.string.home_documents))
            }
        }

        // Пустая секция с одной кнопкой ничего не объясняет. Так её видит каждый, чья
        // страна пока без пакета документов, — а это все страны, кроме двух.
        if (documents.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.home_documents_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(documents, key = { it.item.id }) { status ->
            MaintenanceCard(
                status = status,
                display = display,
                onMarkDone = { onMark(status) },
                onOpen = { onEditItem(status.item) },
                modifier = Modifier.animateItem(),
            )
        }

        item {
            Button(
                onClick = { onNewItem(ItemKind.DOCUMENT) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.home_add_document)) }
        }

        // Под FAB, иначе он накрывает последнюю кнопку.
        item { Spacer(Modifier.height(72.dp)) }
    }
    }

    if (readingDialogOpen) {
        OdometerDialog(
            display = display,
            mileage = overview.mileage,
            onDismiss = { readingDialogOpen = false },
            onConfirm = { km ->
                onAddReading(overview.carId, km)
                readingDialogOpen = false
            },
            onHistory = {
                readingDialogOpen = false
                onOpenOdometerHistory(overview.carId)
            },
        )
    }

}


@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun MileageCard(
    mileage: MileageEstimate?,
    display: Display,
    onEnterReading: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_mileage), style = MaterialTheme.typography.labelLarge)

            if (mileage == null) {
                Text(
                    stringResource(R.string.home_mileage_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    display.distanceText(mileage.km),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    explainMileage(mileage, display),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Синяя строчка внутри карточки читается как ссылка на странице. Главное
            // действие карточки — заполненная кнопка, соседнее по смыслу — обведённая.
            // Кнопка одна и во всю ширину. Второй, «Дальняя поездка», здесь больше нет:
            // она записывала расчётный пробег как наблюдённое показание одометра
            // (docs/DECISIONS.md, решение 3 дополнено 2026-08-24).
            FilledTonalButton(
                onClick = onEnterReading,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.home_enter_odometer_short), maxLines = 1)
            }
        }
    }
}

/**
 * Объяснение, откуда взялась цифра. Пользователь имеет право понимать любое число,
 * которое посчитало приложение.
 */
@Composable
private fun explainMileage(mileage: MileageEstimate, display: Display): String {
    if (!mileage.isExtrapolated) return stringResource(R.string.home_mileage_as_written)

    val perDay = mileage.kmPerDay?.let { display.units.distance(it.roundToInt()) }
        ?: return stringResource(R.string.home_mileage_rough)

    return stringResource(
        R.string.home_mileage_explain,
        display.distanceText(mileage.lastKnownKm),
        mileage.daysSinceLastReading,
        display.distanceText(perDay),
    )
}

@Composable
private fun FuelCard(fuel: FuelStats, display: Display) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_fuel), style = MaterialTheme.typography.labelLarge)

            // Строк столько, сколько видов топлива человек реально заливал: у обычной
            // машины одна, у машины с ГБО или у подзаряжаемого гибрида — две.
            val several = fuel.usage.size > 1

            if (fuel.usage.isEmpty()) {
                val charge = fuel.types == listOf(FuelType.ELECTRIC)
                Text(
                    stringResource(
                        if (charge) R.string.home_fuel_wait_charge else R.string.home_fuel_wait_tank
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                fuel.usage.forEach { usage ->
                    val consumption = display.units.consumption(usage.perHundredKm, usage.fuelType)
                    val value = display.consumptionText(consumption, ofTotal = several)
                    if (several) {
                        Text(
                            "${stringResource(fuelTypeTitle(usage.fuelType))}: $value",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    } else {
                        Text(value, style = MaterialTheme.typography.headlineMedium)
                    }
                }

                if (several) {
                    // Иначе человек сравнит «6,2 л газа» со своими прежними «9,5 л
                    // бензина» и решит, что машина стала есть вдвое меньше.
                    Text(
                        stringResource(R.string.home_fuel_total_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                fuel.costPerKmKopecks?.let { perKm ->
                    Text(
                        stringResource(
                            R.string.home_fuel_cost,
                            display.distanceNoun,
                            display.moneyText(display.units.costPerDistance(perKm)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            fuel.lastEntry?.let { last ->
                Text(
                    stringResource(
                        R.string.home_fuel_last,
                        display.amountText(last.amount, last.fuelType),
                        stringResource(fuelTypeOf(last.fuelType)),
                        display.moneyText(last.costKopecks),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        }
    }
}

@Composable
private fun MaintenanceCard(
    status: MaintenanceStatus,
    display: Display,
    onMarkDone: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = urgencyColor(status.urgency)
    val surface = MaterialTheme.colorScheme.surfaceContainerHighest

    // Градиент и текст под заголовком считаются заново только когда изменились сами
    // данные. Раньше и то и другое пересобиралось при каждой композиции карточки —
    // на прокрутке это двенадцать лишних заливок и двенадцать сборок строки за кадр.
    val brush = remember(status.wear, surface, accent) { wearBrush(status.wear, surface, accent) }
    val resources = LocalResources.current
    val body = remember(status, display, resources) { ReminderTexts.body(resources, status, display) }

    // `Card(onClick=)` сам обрезает и себя, и всплеск нажатия по своей форме. Прежний
    // `.clip(...)` поверх карточки давал вторую обрезку со скруглением на каждую строку.
    //
    // Тени здесь нет намеренно: в тёмной теме её не видно вовсе, а рисуется она каждый
    // кадр. Уровень карточке даёт `surfaceContainerHighest` в самом градиенте.
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(brush),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.width(52.dp).height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(ItemIcons.resFor(status.item.iconKey, status.item.kind)),
                    contentDescription = null,
                    tint = accent,
                )
            }

            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(status.item.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = onMarkDone) {
                Text(stringResource(if (status.item.kind == ItemKind.DOCUMENT) R.string.home_mark_renewed else R.string.home_mark_done))
            }

            // Насыщенный цвет живёт только там, где нет текста: узкой полосой у края.
            Box(Modifier.width(5.dp).height(64.dp).background(accent))
        }
    }
}

/**
 * Заливка карточки как шкала износа.
 *
 * Точка, с которой начинается цвет, едет вместе с израсходованным ресурсом: свежая позиция
 * тронута цветом только у самого края, просроченная залита целиком. Прозрачность держим
 * низкой — текст лежит поверх и не должен терять контраст ни при каком статусе.
 */
private fun wearBrush(wear: Double?, surface: Color, accent: Color): Brush {
    if (wear == null) return Brush.horizontalGradient(listOf(surface, surface))

    val start = (1f - wear.toFloat()).coerceIn(0f, 0.92f)
    return Brush.horizontalGradient(
        0f to surface,
        start to surface,
        1f to accent.copy(alpha = 0.28f),
    )
}

@Composable
private fun urgencyColor(urgency: Urgency): Color {
    val dark = LocalDarkTheme.current
    return when (urgency) {
        // В тёмной теме те же оттенки становятся грязными, поэтому пара значений на статус.
        Urgency.OVERDUE -> if (dark) Color(0xFFF2837F) else Color(0xFFD32F2F)
        Urgency.SOON -> if (dark) Color(0xFFF6C453) else Color(0xFFE59100)
        Urgency.OK -> if (dark) Color(0xFF8CC474) else Color(0xFF3F8F3B)
        Urgency.UNKNOWN -> if (dark) Color(0xFF9A958F) else Color(0xFF8A8A8A)
    }
}

@Composable
private fun OdometerDialog(
    display: Display,
    /** Что приложение считает текущим пробегом: с этим человек и сверяет панель. */
    mileage: MileageEstimate?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
    onHistory: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // Человек вводит то, что видит на панели; наружу уходят километры — в них хранение.
    val entered = text.toIntOrNull()
    val km = entered?.let { display.units.kmFrom(it.toDouble()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_odometer_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { new -> text = new.filter(Char::isDigit).take(7) },
                    label = { Text(stringResource(R.string.home_odometer_field, display.distanceUnit)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = GroupedDigitsTransformation(display.words.groupSeparator),
                )
                Text(
                    stringResource(R.string.home_odometer_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MileageNowHint(mileage = mileage, display = display)
                TextButton(onClick = onHistory) {
                    Text(stringResource(R.string.odometer_history_title))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { km?.let(onConfirm) }, enabled = km != null) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * Выбор машины.
 *
 * Список, а не выпадающее меню: у человека с двумя машинами меню выглядит одинаково
 * с любым другим, а здесь важно видеть, что машин несколько и какая открыта сейчас.
 */
@Composable
fun CarPickerDialog(
    cars: List<Car>,
    selectedId: Long,
    onDismiss: () -> Unit,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.car_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_picker_spacing))) {
                cars.forEach { car ->
                    val tint = silhouetteTint(car.silhouetteColor)
                    val iconSize = dimensionResource(R.dimen.car_picker_icon_size)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(car.id) }
                            .padding(vertical = dimensionResource(R.dimen.car_picker_row_padding_vertical)),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_picker_row_spacing)),
                    ) {
                        Icon(
                            painter = rememberCarProfilePainter(car.bodyType, iconSize, iconSize),
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(iconSize),
                        )
                        Text(
                            car.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (car.id == selectedId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onAdd) { Text(stringResource(R.string.car_add)) } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        },
    )
}
