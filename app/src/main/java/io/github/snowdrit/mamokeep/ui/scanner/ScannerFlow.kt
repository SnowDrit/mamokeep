package io.github.snowdrit.mamokeep.ui.scanner

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.scanner.ScannerModelManager
import io.github.snowdrit.mamokeep.domain.Car
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.MileageEstimate
import io.github.snowdrit.mamokeep.domain.OdometerBounds
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.ui.DateField
import io.github.snowdrit.mamokeep.ui.EditorScaffold
import io.github.snowdrit.mamokeep.ui.FuelEditor
import io.github.snowdrit.mamokeep.ui.GroupedDigitsTransformation
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerFlow(
    manager: ScannerModelManager,
    cars: List<Car>,
    carId: Long?,
    display: Display,
    currencyCode: String,
    items: List<MaintenanceItem>,
    onSaveFuel: suspend (Long, LocalDate, Int, Double, FuelType, Long, Boolean) -> Unit,
    onSaveWork: suspend (Long, LocalDate, Int?, String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    externalInput: ExternalScannerInput? = null,
    onExternalInputConsumed: (String) -> Unit = {},
    lastKnownKmOf: suspend (Long) -> Int? = { null },
    odometerBoundsOf: suspend (Long, LocalDate) -> OdometerBounds = { _, _ -> OdometerBounds() },
    mileageNowOf: suspend (Long) -> MileageEstimate? = { null },
) {
    val context = LocalContext.current.applicationContext
    val factory = remember(context) { ScannerViewModelFactory(context) }
    val state: ScannerViewModel = viewModel(factory = factory)
    val close = { state.reset(); onDismiss() }
    LaunchedEffect(externalInput?.requestId) {
        externalInput?.let { input ->
            state.importInput(input.source, manager)
            onExternalInputConsumed(input.requestId)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) state.importUri(uri, manager)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        state.cameraResult(success, manager)
    }
    val draft = state.draft
    if (state.route == ScannerRoute.FUEL && draft != null && carId != null) {
        FuelEditor(
            recent = emptyList(), display = display, defaultType = FuelType.PETROL,
            carId = carId, cars = cars,
            onDismiss = { if (state.recordSaved) close() else state.backToSource() },
            onSave = { target, date, km, amount, type, cost, full ->
                onSaveFuel(target, date, km, amount, type, cost, full)
                state.markSaved()
            },
            lastKnownKmOf = lastKnownKmOf, odometerBoundsOf = odometerBoundsOf, mileageNowOf = mileageNowOf,
            scanDraft = draft, scanCurrencyCode = currencyCode,
            scanSource = { ScannerSource(state.image, state.reviewSourceText) },
            modifier = modifier,
        )
        return
    }
    if (state.route == ScannerRoute.WORK && draft != null && carId != null) {
        ScannerWorkReview(state, cars.firstOrNull { it.id == carId }?.name.orEmpty(), items, display, onSaveWork, close, modifier)
        return
    }

    BackHandler { close() }
    Scaffold(
        modifier = modifier.fillMaxSize(), contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ocr_title)) }, windowInsets = WindowInsets(0),
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(painterResource(R.drawable.ic_arrow_left), stringResource(R.string.common_cancel))
                    }
                },
            )
        },
    ) { inner ->
        Column(
            Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.ocr_import_intro), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.ocr_early_access_notice), style = MaterialTheme.typography.bodyMedium)
            state.image?.let { bitmap ->
                Image(bitmap.asImageBitmap(), stringResource(R.string.ocr_photo),
                    Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
            }
            if (state.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.ocr_import_running))
                TextButton(onClick = state::cancelRecognition) { Text(stringResource(R.string.common_cancel)) }
            } else {
                state.error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                if (state.image != null && state.error != null) {
                    OutlinedButton(onClick = { state.retry(manager) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.scanner_retry))
                    }
                }
                if (state.image != null) {
                    TextButton(onClick = { state.rotate(manager) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ocr_rotate))
                    }
                }
                if (state.hasPdfSource && state.error == null) {
                    Text(stringResource(R.string.ocr_pdf_retry_hint), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = { state.recognizeWholePdf(manager) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.ocr_pdf_retry))
                    }
                }
                Button(onClick = {
                    try { picker.launch(arrayOf("image/*", "application/pdf", "text/plain")) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { state.pickerFailed() }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ocr_choose_file)) }
                OutlinedButton(onClick = { state.openCamera { camera.launch(it) } }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ocr_take_photo))
                }
                if (carId == null) Text(stringResource(R.string.ocr_import_no_car))
                OutlinedTextField(
                    value = state.text, onValueChange = state::updateText,
                    label = { Text(stringResource(R.string.ocr_source_text)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 12,
                )
                Text(stringResource(R.string.ocr_review_text), style = MaterialTheme.typography.bodySmall)
                Button(onClick = { state.review(ScannerRoute.FUEL) }, enabled = carId != null && state.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ocr_fuel_action))
                }
                OutlinedButton(onClick = { state.review(ScannerRoute.WORK) }, enabled = carId != null && state.text.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.ocr_work_action))
                }
            }
        }
    }
}

@Composable
private fun ScannerWorkReview(
    state: ScannerViewModel,
    carName: String,
    items: List<MaintenanceItem>,
    display: Display,
    onSave: suspend (Long, LocalDate, Int?, String) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier,
) {
    val draft = requireNotNull(state.draft)
    val maintenance = remember(items) { items.filter { it.kind == ItemKind.MAINTENANCE } }
    var itemId by rememberSaveable { mutableStateOf<Long?>(null) }
    var date by rememberSaveable { mutableStateOf(draft.date?.takeUnless { it.isAfter(LocalDate.now()) } ?: LocalDate.now()) }
    var dateConfirmed by rememberSaveable { mutableStateOf(false) }
    var performed by rememberSaveable { mutableStateOf(false) }
    var km by rememberSaveable { mutableStateOf(draft.odometerKm?.let { display.units.distance(it).roundToInt().toString() }.orEmpty()) }
    val session = rememberSaveable { UUID.randomUUID().toString() }
    val operation: EditorOperationViewModel = viewModel(key = "scanner-work-operation")
    LaunchedEffect(session) { operation.attach(session) }
    LaunchedEffect(operation.completed) { if (operation.completed) { operation.reset(); onDone() } }
    val chosen = maintenance.firstOrNull { it.id == itemId }
    val kilometers = scannedOdometerForSave(km, draft.odometerKm, display.units)
    val canSave = chosen != null && dateConfirmed && performed && (km.isBlank() || kilometers != null) && !operation.busy
    EditorScaffold(
        title = stringResource(R.string.ocr_work_action),
        onClose = { if (!operation.busy) { operation.reset(); state.backToSource() } },
        onSave = if (!canSave) null else {
            {
                val savedItem = chosen!!.id
                val savedDate = date
                val savedNote = state.workNote
                operation.run { onSave(savedItem, savedDate, kilometers, savedNote) }
            }
        },
        modifier = modifier,
    ) {
        if (operation.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (operation.failed || operation.interrupted) Text(
            stringResource(if (operation.interrupted) R.string.ocr_save_interrupted else R.string.ocr_save_failed),
            color = MaterialTheme.colorScheme.error,
        )
        Text(carName, style = MaterialTheme.typography.titleMedium)
        ScannerSource(state.image, state.reviewSourceText)
        Text(stringResource(R.string.ocr_work_explanation), style = MaterialTheme.typography.bodySmall)
        if (maintenance.isEmpty()) Text(stringResource(R.string.ocr_no_work_items))
        else WorkItemPicker(maintenance, chosen, enabled = !operation.busy) { itemId = it; performed = false }
        DateField(date, { if (!operation.busy) { date = it; dateConfirmed = true } },
            stringResource(R.string.fuel_date_field), maxDate = LocalDate.now(), enabled = !operation.busy)
        OcrConfirmation(dateConfirmed, { dateConfirmed = it }, stringResource(R.string.ocr_confirm_date), !operation.busy)
        OutlinedTextField(
            value = km, onValueChange = { km = it.filter(Char::isDigit).take(7) },
            visualTransformation = GroupedDigitsTransformation(display.words.groupSeparator),
            label = { Text(stringResource(R.string.record_km, display.distanceUnit)) },
            supportingText = { Text(stringResource(R.string.record_km_hint)) },
            singleLine = true, enabled = !operation.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.workNote, onValueChange = { state.workNote = it },
            label = { Text(stringResource(R.string.ocr_source_text)) },
            enabled = !operation.busy, minLines = 4, maxLines = 12, modifier = Modifier.fillMaxWidth(),
        )
        OcrConfirmation(performed, { performed = it }, stringResource(R.string.ocr_confirm_work), !operation.busy && chosen != null)
    }
}

/** Rounded miles are presentation only until the scanned odometer is edited. */
internal fun scannedOdometerForSave(text: String, initialKm: Int?, units: UnitSystem): Int? {
    val initialText = initialKm?.let { units.distance(it).roundToInt().toString() }
    return if (initialKm != null && text == initialText) initialKm
        else text.toIntOrNull()?.let { units.kmFrom(it.toDouble()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkItemPicker(items: List<MaintenanceItem>, selected: MaintenanceItem?, enabled: Boolean, onSelect: (Long) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected?.title.orEmpty(), onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(stringResource(R.string.record_item)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            items.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { onSelect(item.id); expanded = false }) }
        }
    }
}
