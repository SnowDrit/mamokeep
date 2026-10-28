package io.github.snowdrit.mamokeep.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.CarProfileDraft
import io.github.snowdrit.mamokeep.domain.FuelGrade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Гараж: профиль машины.
 *
 * Силуэт лежит на том же фоне, что и экран, — он дополняет страницу, а не сидит на
 * отдельной плашке. Тип кузова выбирается сразу с предпоказом: показывать словами то,
 * что человек всё равно оценивает глазами, — лишний шаг.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GarageScreen(
    car: Car,
    onSave: suspend (Car) -> Unit,
    onBack: () -> Unit,
    exitRequested: Boolean,
    onCancelExit: () -> Unit,
    /** Последнюю машину удалить нельзя: экрану будет нечего показывать. */
    canDelete: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteAsked by rememberSaveable(car.id) { mutableStateOf(false) }
    var name by rememberSaveable(car.id) { mutableStateOf(car.name) }
    var make by rememberSaveable(car.id) { mutableStateOf(car.make.orEmpty()) }
    var model by rememberSaveable(car.id) { mutableStateOf(car.model.orEmpty()) }
    var year by rememberSaveable(car.id) { mutableStateOf(car.year?.toString().orEmpty()) }
    var vin by rememberSaveable(car.id) { mutableStateOf(car.vin.orEmpty()) }
    var bodyType by rememberSaveable(car.id) { mutableStateOf(car.bodyType) }
    var fuelGrade by rememberSaveable(car.id) { mutableStateOf(car.fuelGrade) }
    var lineColor by rememberSaveable(car.id) { mutableStateOf(car.silhouetteColor) }
    var exitAsked by rememberSaveable(car.id) { mutableStateOf(false) }
    var saveFailed by rememberSaveable(car.id) { mutableStateOf(false) }
    var saving by remember(car.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Внутри onClick composable-функции уже не вызвать — берём заранее.
    val defaultName = stringResource(R.string.garage_default_name)
    val tint = silhouetteTint(lineColor)
    val draft = CarProfileDraft(name, make, model, year, vin, bodyType, fuelGrade, lineColor)

    fun requestExit() {
        if (saving) return
        if (draft.hasChanges(car)) exitAsked = true else onBack()
    }

    fun cancelExit() {
        if (saving) return
        exitAsked = false
        onCancelExit()
    }

    fun saveAndExit() {
        if (saving) return
        saving = true
        saveFailed = false
        val updated = draft.toCar(car, defaultName)
        scope.launch {
            try {
                onSave(updated)
                // Do not navigate until the database write has actually succeeded.
                onBack()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                saveFailed = true
            } finally {
                saving = false
            }
        }
    }

    BackHandler { requestExit() }
    LaunchedEffect(exitRequested) {
        if (exitRequested) requestExit()
    }

    // Панель такая же, как на трёх вкладках. Без неё экран оставался единственным местом,
    // где вместо панели висела текстовая ссылка «Назад» в пустоте, — и выпадал из
    // остального приложения.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        // Отступы под системные панели посчитаны снаружи, в `MainActivity`.
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.garage_title)) },
                navigationIcon = {
                    IconButton(onClick = ::requestExit, enabled = !saving) {
                        Icon(
                            painterResource(R.drawable.ic_arrow_left),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(inner)
            .verticalScroll(rememberScrollState()),
    ) {
        CarHero(
            bodyType = bodyType,
            tint = tint,
            title = name,
            subtitle = listOfNotNull(
                make.ifBlank { null },
                model.ifBlank { null },
                year.ifBlank { null },
            ).joinToString(" "),
        )

        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedTextField(
                value = name,
                enabled = !saving,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.garage_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = make,
                enabled = !saving,
                onValueChange = { make = it },
                label = { Text(stringResource(R.string.garage_make)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                enabled = !saving,
                onValueChange = { model = it },
                label = { Text(stringResource(R.string.garage_model)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = year,
                enabled = !saving,
                onValueChange = { year = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.garage_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vin,
                enabled = !saving,
                onValueChange = { vin = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(17) },
                label = { Text(stringResource(R.string.garage_vin)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.garage_vin_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Кузов и цвет — после текстовых полей, а не перед ними. Человек заводит
            // машину, чтобы записать, что это за машина; выбор картинки — приятное, но
            // второе. Раньше он стоял между именем и маркой и разрывал ввод пополам.
            Text(stringResource(R.string.garage_body), style = MaterialTheme.typography.labelLarge)

            // Типов много, и в одну строку они не читаются: группируем так, как человек
            // о них и думает — легковые, коммерческие, двухколёсные, прочее.
            BodyTypes.ALL.groupBy { it.group }.forEach { (group, types) ->
                Text(
                    stringResource(group),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_body_preview_gap)),
                ) {
                    items(types, key = { it.key }) { type ->
                        BodyPreview(
                            type = type,
                            tint = tint,
                            selected = type.key == bodyType,
                            enabled = !saving,
                            onClick = { bodyType = type.key },
                        )
                    }
                }

                // Если будущий тип временно разделит чужой рисунок, объяснение появится
                // прямо под его группой. В текущем комплекте легковых заглушек уже нет.
                val shared = types.filter { it.placeholder }.map { stringResource(it.label).lowercase() }
                if (shared.isNotEmpty()) {
                    Text(
                        stringResource(R.string.garage_body_placeholder, shared.joinToString(", ")),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(stringResource(R.string.garage_line_color), style = MaterialTheme.typography.labelLarge)
            LineColorSelector(lineColor, bodyType, onColorChange = { lineColor = it }, enabled = !saving)

            Text(stringResource(R.string.garage_fuel_grade), style = MaterialTheme.typography.labelLarge)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = fuelGrade == null,
                    enabled = !saving,
                    onClick = { fuelGrade = null },
                    label = { Text(stringResource(R.string.garage_fuel_grade_none)) },
                )
                FuelGrade.entries.forEach { grade ->
                    FilterChip(
                        selected = grade.key == fuelGrade,
                        enabled = !saving,
                        onClick = { fuelGrade = grade.key },
                        label = { Text(stringResource(fuelGradeTitle(grade))) },
                    )
                }
            }
            Text(
                stringResource(R.string.garage_fuel_grade_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (saveFailed) {
                Text(stringResource(R.string.garage_save_failed), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = ::saveAndExit,
                enabled = !saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (saving) R.string.garage_saving else R.string.common_save)) }

            if (canDelete) {
                TextButton(onClick = { deleteAsked = true }, enabled = !saving) {
                    Text(stringResource(R.string.garage_delete))
                }
            } else {
                Text(
                    stringResource(R.string.garage_delete_last),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    }

    if (exitAsked) {
        AlertDialog(
            onDismissRequest = ::cancelExit,
            title = { Text(stringResource(R.string.garage_unsaved_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.garage_unsaved_message))
                    if (saveFailed) {
                        Text(stringResource(R.string.garage_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    TextButton(onClick = ::saveAndExit, enabled = !saving) {
                        Text(stringResource(if (saving) R.string.garage_saving else R.string.common_save))
                    }
                    TextButton(onClick = onBack, enabled = !saving) {
                        Text(stringResource(R.string.garage_leave_without_saving))
                    }
                    TextButton(onClick = ::cancelExit, enabled = !saving) {
                        Text(stringResource(R.string.garage_keep_editing))
                    }
                }
            },
        )
    }

    if (deleteAsked) {
        AlertDialog(
            onDismissRequest = { deleteAsked = false },
            title = { Text(stringResource(R.string.garage_delete_title, car.name)) },
            // Каскад забирает всё содержимое машины, и вернуть его нечем: человек должен
            // прочитать, что именно он теряет, а не увидеть «точно?».
            text = { Text(stringResource(R.string.garage_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteAsked = false
                    onDelete()
                }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsked = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun BodyPreview(
    type: BodyType,
    tint: Color,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val imageWidth = dimensionResource(R.dimen.car_body_preview_width)
    val imageHeight = dimensionResource(R.dimen.car_body_preview_height)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .width(92.dp)
                .height(64.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = rememberCarProfilePainter(type.key, imageWidth, imageHeight),
                contentDescription = stringResource(type.label),
                tint = tint,
                // Профили широкие: в квадратном слоте 52×52 dp они фактически
                // занимали лишь около половины кнопки по ширине. Растягиваем только
                // рисунок, сохраняя прежний размер карточки 92×64 dp.
                modifier = Modifier
                    .width(imageWidth)
                    .height(imageHeight),
            )
        }
        Text(
            stringResource(type.label),
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
