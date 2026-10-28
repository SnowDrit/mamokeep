package io.github.snowdrit.mamokeep

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.snowdrit.mamokeep.ui.help.*
import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.snowdrit.mamokeep.data.SettingsStore
import io.github.snowdrit.mamokeep.data.ThemeMode
import io.github.snowdrit.mamokeep.domain.UnitSystem
import io.github.snowdrit.mamokeep.domain.FuelEntry
import io.github.snowdrit.mamokeep.domain.FuelType
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import io.github.snowdrit.mamokeep.domain.MaintenanceRecord
import io.github.snowdrit.mamokeep.domain.ReminderSettings
import io.github.snowdrit.mamokeep.domain.OdometerReading
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.notifications.BackupWorker
import io.github.snowdrit.mamokeep.notifications.Notifications
import io.github.snowdrit.mamokeep.ui.BackupDialog
import io.github.snowdrit.mamokeep.ui.CarPickerDialog
import io.github.snowdrit.mamokeep.ui.ExportDialog
import io.github.snowdrit.mamokeep.ui.FuelEditor
import io.github.snowdrit.mamokeep.ui.GarageScreen
import io.github.snowdrit.mamokeep.ui.HomeScreen
import io.github.snowdrit.mamokeep.ui.OdometerHistoryDialog
import io.github.snowdrit.mamokeep.ui.scanner.ScannerFlow
import io.github.snowdrit.mamokeep.ui.scanner.ExternalScannerInbox
import io.github.snowdrit.mamokeep.ui.scanner.ScannerImportError
import io.github.snowdrit.mamokeep.ui.ItemEditor
import io.github.snowdrit.mamokeep.ui.HomeViewModel
import io.github.snowdrit.mamokeep.ui.JournalScreen
import io.github.snowdrit.mamokeep.ui.currencySymbol
import io.github.snowdrit.mamokeep.ui.MoreScreen
import io.github.snowdrit.mamokeep.ui.ScannerSettingsSection
import io.github.snowdrit.mamokeep.ui.RecordEditor
import io.github.snowdrit.mamokeep.ui.Remaining
import io.github.snowdrit.mamokeep.ui.ReminderSettingsScreen
import io.github.snowdrit.mamokeep.ui.display
import io.github.snowdrit.mamokeep.ui.documentNotes
import io.github.snowdrit.mamokeep.ui.exportTexts
import io.github.snowdrit.mamokeep.ui.theme.MamoKeepTheme
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * Что сейчас открыто в редакторе.
 *
 * Редакторы живут отдельными экранами поверх вкладок, а не диалогами. Состояние держим
 * здесь, а не внутри экранов: заправку правят из двух мест — с «Машины» и из «Журнала», —
 * и оба должны открывать один и тот же экран, а не две его копии.
 */
internal sealed interface Editor {
    /** `item` пусто — позицию заводят. */
    data class Item(val item: MaintenanceItem?, val kind: ItemKind) : Editor

    /** `entryId` пусто — заправку заводят. */
    data class Fuel(val entryId: Long?) : Editor

    /** Новая отметка «сделано» или «продлено» для позиции. */
    data class NewRecord(val item: MaintenanceItem) : Editor

    /** Правка записи журнала: дата, пробег и позиция. */
    data class EditRecord(val entry: JournalEntry) : Editor

}

/** Keep every editor open across rotation/process recreation; drafts are saved by each screen. */
internal val EditorSaver = Saver<Editor?, Bundle>(
    save = { editor ->
        when (editor) {
            null -> null
            is Editor.Item -> (editor.item?.editorBundle() ?: Bundle()).apply {
                putString("type", "item")
                putString("kind", editor.kind.name)
            }
            is Editor.Fuel -> Bundle().apply {
                putString("type", "fuel")
                editor.entryId?.let { putLong("id", it) }
            }
            is Editor.NewRecord -> editor.item.editorBundle().apply { putString("type", "newRecord") }
            is Editor.EditRecord -> Bundle().apply {
                putString("type", "editRecord")
                val entry = editor.entry
                putString("kind", entry.kind.name)
                putLong("day", entry.date.toEpochDay())
                entry.km?.let { putInt("km", it) }
                putString("title", entry.title)
                entry.recordId?.let { putLong("recordId", it) }
                entry.itemId?.let { putLong("itemId", it) }
                putString("iconKey", entry.iconKey)
                putString("note", entry.note)
            }
        }
    },
    restore = { saved ->
        // Saved activities from the preceding version contain only item fields, without a type.
        when (saved.getString("type") ?: "item") {
            "item" -> Editor.Item(
                if (saved.containsKey("id")) saved.editorItem() else null,
                ItemKind.valueOf(requireNotNull(saved.getString("kind"))),
            )
            "fuel" -> Editor.Fuel(if (saved.containsKey("id")) saved.getLong("id") else null)
            "newRecord" -> Editor.NewRecord(saved.editorItem())
            "editRecord" -> Editor.EditRecord(JournalEntry(
                date = LocalDate.ofEpochDay(saved.getLong("day")),
                km = if (saved.containsKey("km")) saved.getInt("km") else null,
                title = saved.getString("title"),
                recordId = if (saved.containsKey("recordId")) saved.getLong("recordId") else null,
                itemId = if (saved.containsKey("itemId")) saved.getLong("itemId") else null,
                amountKopecks = null,
                iconKey = requireNotNull(saved.getString("iconKey")),
                kind = JournalEntry.Kind.valueOf(requireNotNull(saved.getString("kind"))),
                fuel = null,
                note = saved.getString("note"),
            ))
            else -> error("Unknown saved editor type")
        }
    },
)

private fun MaintenanceItem.editorBundle() = Bundle().apply {
    putString("kind", kind.name)
    putLong("id", id)
    putString("title", title)
    putString("titleKey", titleKey)
    putString("iconKey", iconKey)
    intervalKm?.let { putInt("km", it) }
    intervalMonths?.let { putInt("months", it) }
    lastDoneKm?.let { putInt("lastKm", it) }
    lastDoneDate?.let { putLong("lastDay", it.toEpochDay()) }
    putInt("warnKm", warnKm)
    putInt("warnDays", warnDays)
}

private fun Bundle.editorItem() = MaintenanceItem(
    id = getLong("id"), kind = ItemKind.valueOf(requireNotNull(getString("kind"))),
    title = requireNotNull(getString("title")), titleKey = getString("titleKey"), iconKey = getString("iconKey"),
    intervalKm = if (containsKey("km")) getInt("km") else null,
    intervalMonths = if (containsKey("months")) getInt("months") else null,
    lastDoneKm = if (containsKey("lastKm")) getInt("lastKm") else null,
    lastDoneDate = if (containsKey("lastDay")) LocalDate.ofEpochDay(getLong("lastDay")) else null,
    warnKm = getInt("warnKm"), warnDays = getInt("warnDays"),
)

/** Разделы приложения. Три — больше на этом объёме не нужно. */
/**
 * Длительность перехода между вкладками.
 *
 * 200 мс — верх того, что читается как отклик, а не как ожидание. На Unisoc T610 это
 * примерно 18 кадров при 90 Гц; замер запаса показал, что он есть (docs/STATUS.md).
 */
private const val TAB_MS = 200

private enum class Tab(val label: Int, val icon: Int) {
    CAR(R.string.tab_car, R.drawable.ic_car),
    JOURNAL(R.string.tab_journal, R.drawable.ic_clipboard_check_outline),
    MORE(R.string.tab_more, R.drawable.ic_cog),
}

class MainActivity : ComponentActivity() {
    private val scannerInbox: ExternalScannerInbox by viewModels()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        scannerInbox.receive(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restored Activity already handled this Intent. The inbox reports an
        // interrupted pending delivery after process death instead of replaying it.
        if (savedInstanceState == null) scannerInbox.receive(intent)
        // Рисуем под системными панелями сами. На Android 15+ это включено принудительно,
        // а на 13 система красила полосу статуса своим серым из XML-темы — на живом
        // Realme она выходила RGB(117,117,117) поверх почти чёрного приложения.
        // Отступы под панели уже считает Scaffold, цвет значков — MamoKeepTheme.
        enableEdgeToEdge()

        val container = (application as MamoKeepApp).container
        val settings = SettingsStore(applicationContext)
        val regionalCatalog = container.regionalCatalog
        // Пакеты лежат в ассетах и не меняются на ходу — разобраны один раз в AppContainer.
        val regionPresets = container.regionPresets

        setContent {
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(ThemeMode.SYSTEM)
            val backupFolder by settings.backupFolder.collectAsStateWithLifecycle(null)
            val units by settings.unitSystem.collectAsStateWithLifecycle(UnitSystem.METRIC)
            val regionalPreferences by settings.regionalPreferences.collectAsStateWithLifecycle(settings.defaultRegionalPreferences)
            val reminderSettings by container.reminderSettings.settings
                .collectAsStateWithLifecycle<ReminderSettings?>(null)
            val scope = rememberCoroutineScope()

            val regionPreset = regionPresets.firstOrNull { it.code == regionalPreferences.countryCode }
            val resources = LocalResources.current
            val display = display(resources, units, currencySymbol(resources, regionalCatalog.currencyCode(regionalPreferences)))

            MamoKeepTheme(themeMode) {
                val factory: ViewModelProvider.Factory = viewModelFactory {
                    initializer { HomeViewModel(container.garageRepository, settings) }
                }
                val viewModel: HomeViewModel = viewModel(factory = factory)
                val overview by viewModel.overview.collectAsStateWithLifecycle()

                var tab by rememberSaveable { mutableStateOf(Tab.CAR) }
                var exportOpen by rememberSaveable { mutableStateOf(false) }
                var backupOpen by rememberSaveable { mutableStateOf(false) }
                var scannerOpen by rememberSaveable { mutableStateOf(false) }
                var odometerHistoryCarId by rememberSaveable { mutableStateOf<Long?>(null) }
                var garageOpen by rememberSaveable { mutableStateOf(false) }
                var garageExitTarget by rememberSaveable { mutableStateOf<Tab?>(null) }
                var carPickerOpen by remember { mutableStateOf(false) }
                var editor by rememberSaveable(stateSaver = EditorSaver) { mutableStateOf<Editor?>(null) }
                var remindersOpen by rememberSaveable { mutableStateOf(false) }
                var savingReminder by remember { mutableStateOf(false) }
                var reminderSaveError by remember { mutableStateOf<String?>(null) }
                val reminderErrorText = stringResource(R.string.reminder_save_failed)
                // Пояснения к документам лежат в пакетах региона и на ходу не меняются.
                val documentNoteOf = remember { documentNotes(resources, regionPresets) }
                val loadedCars by viewModel.cars.collectAsStateWithLifecycle()
                val cars = loadedCars.orEmpty()
                val garageInitialized by settings.garageInitialized.collectAsStateWithLifecycle(false)
                val garageEmpty = garageInitialized && loadedCars?.isEmpty() == true

                val helpTopics = remember { HelpCatalog.load(applicationContext) }
                val lessons = remember { LessonCatalog.load(applicationContext) }
                var requestedLesson by rememberSaveable { mutableStateOf<String?>(null) }
                val anchors = remember { TourAnchors() }
                val tourHandled by settings.tourHandled.collectAsStateWithLifecycle<Boolean?>(null)
                var tourIndex by rememberSaveable { mutableStateOf<Int?>(null) }
                var tourTopicId by rememberSaveable { mutableStateOf<String?>(null) }
                val tourTopics = remember(helpTopics, tourTopicId) {
                    helpTopics.filter { if (tourTopicId == null) it.showInTour else it.id == tourTopicId }
                }
                var dismissedThisSession by rememberSaveable { mutableStateOf(false) }
                var helpOpen by rememberSaveable { mutableStateOf(false) }
                var moreImportBlocked by remember { mutableStateOf(false) }
                val externalInput = scannerInbox.input
                LaunchedEffect(externalInput?.requestId) {
                    if (externalInput != null && !scannerInbox.dispatched) {
                        val blocked = editor != null || scannerOpen || garageOpen || helpOpen ||
                            remindersOpen || exportOpen || backupOpen || carPickerOpen ||
                            odometerHistoryCarId != null || tourIndex != null || requestedLesson != null || moreImportBlocked
                        if (blocked) scannerInbox.reject(externalInput.requestId)
                        else {
                            scannerInbox.dispatch(externalInput.requestId)
                            scannerOpen = true
                        }
                    }
                }
                val closeTour = {
                    tourIndex = null
                    dismissedThisSession = true
                    scope.launch { settings.finishTour() }
                    Unit
                }
                LaunchedEffect(tourHandled, overview != null, scannerOpen, externalInput != null) {
                    if (tourHandled == false && overview != null && !dismissedThisSession && tourIndex == null &&
                        !scannerOpen && externalInput == null) {
                        tourIndex = 0
                    }
                }
                val activeTopic = tourIndex?.let { tourTopics[it] }
                SideEffect { anchors.activeTarget = activeTopic?.id }
                LaunchedEffect(activeTopic?.id) {
                    activeTopic?.let {
                        anchors.settledTarget = null
                        tab = Tab.valueOf(it.page.name)
                    }
                }
                // The system permission dialog must not cover the guided first launch.
                if (tourHandled == true && tourIndex == null && !helpOpen && !scannerOpen && externalInput == null) {
                    RequestNotificationPermissionOnce(settings)
                }

                // Язык мог смениться, пока экран пересоздавался: `ViewModel` это переживает,
                // а названия позиций подставляются на границе базы и в уже выданной ленте
                // остались на прежнем языке. Толкаем пересчёт ровно на смену локали.
                val localeTag = LocalConfiguration.current.locales.toLanguageTags()
                LaunchedEffect(localeTag) { viewModel.reread() }

                // «Назад» — это отмена шага внутри приложения, а не выход из него.
                // Раньше с «Журнала» или из гаража она закрывала приложение целиком:
                // человек, зашедший посмотреть ленту, вылетал на рабочий стол.
                //
                // Диалоги сюда не попадают: `AlertDialog` перехватывает «назад» сам и
                // закрывается через `onDismissRequest`. Отключаем обработчик на главной
                // вкладке — там выход и есть правильный ответ.
                // Редактор сюда не попадает: у него свой `BackHandler` внутри
                // `EditorScaffold`, и он глубже в дереве, поэтому срабатывает раньше.
                // Garage owns its back action so every exit can protect unsaved edits.
                BackHandler(enabled = !garageOpen && tab != Tab.CAR) {
                    tab = Tab.CAR
                }

                CompositionLocalProvider(LocalTourAnchors provides anchors) {
                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    modifier = if (tourIndex != null) Modifier.clearAndSetSemantics { } else Modifier,
                    bottomBar = {
                        // Пока открыт редактор, вкладок нет: нажатие на «Журнал» посреди
                        // заполнения молча выбросило бы введённое.
                        if (editor == null && !helpOpen && !remindersOpen && !scannerOpen) NavigationBar {
                            Tab.entries.forEach { entry ->
                                NavigationBarItem(
                                    selected = tab == entry,
                                    onClick = {
                                        requestedLesson = null
                                        if (garageOpen) garageExitTarget = entry else tab = entry
                                    },
                                    icon = {
                                        Icon(painterResource(entry.icon), contentDescription = null)
                                    },
                                    label = { Text(stringResource(entry.label)) },
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    val content = Modifier.padding(innerPadding)

                    val currentCar = cars.firstOrNull { it.id == overview?.carId }

                    val openEditor = editor
                    val car = overview
                    if (scannerOpen) {
                        ScannerFlow(
                            manager = container.scannerModels,
                            cars = cars,
                            carId = car?.carId,
                            display = display,
                            currencyCode = regionalCatalog.currencyCode(regionalPreferences) ?: display.currency,
                            items = car?.statuses.orEmpty().map { it.item }.filter { it.kind == ItemKind.MAINTENANCE },
                            externalInput = externalInput?.takeIf { scannerInbox.dispatched },
                            onExternalInputConsumed = scannerInbox::consume,
                            onSaveFuel = viewModel::addFuelEntry,
                            lastKnownKmOf = viewModel::lastKnownKm,
                            odometerBoundsOf = { target, date -> viewModel.odometerBounds(target, date) },
                            mileageNowOf = { viewModel.mileageNow(it) },
                            onSaveWork = { itemId, date, km, note ->
                                viewModel.saveScannedWork(itemId, date, km, note)
                                Notifications.cancelItems(applicationContext, listOf(itemId))
                            },
                            onDismiss = {
                                scannerInbox.input?.let { scannerInbox.consume(it.requestId) }
                                scannerOpen = false
                            },
                            modifier = content,
                        )
                    } else if (helpOpen) {
                        HelpScreen(
                            topics = helpTopics,
                            lessons = lessons,
                            onLesson = { id ->
                                helpOpen = false
                                requestedLesson = id
                                tab = Tab.MORE
                                when (id) {
                                    "reminders" -> { reminderSaveError = null; remindersOpen = true }
                                    "export" -> exportOpen = true
                                }
                            },
                            onClose = { helpOpen = false },
                            onReplay = { helpOpen = false; tourTopicId = null; tourIndex = 0 },
                            onShow = { topic -> helpOpen = false; tourTopicId = topic.id; tourIndex = 0 },
                            modifier = content,
                        )
                    } else if (remindersOpen && reminderSettings != null) {
                        ReminderSettingsScreen(
                            lessonDefinition = lessons.first { it.id == "reminders" },
                            initial = reminderSettings!!.defaultRule,
                            startLesson = requestedLesson == "reminders",
                            onLessonStartConsumed = { requestedLesson = null },
                            display = display,
                            onDismiss = { if (!savingReminder) { remindersOpen = false; requestedLesson = null } },
                            saving = savingReminder,
                            error = reminderSaveError,
                            onSave = { rule ->
                                scope.launch {
                                    savingReminder = true
                                    reminderSaveError = null
                                    try {
                                        container.reminderSettings.withGarageLock {
                                            container.reminderSettings.setDefaultRule(rule)
                                        }
                                        remindersOpen = false
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { reminderSaveError = reminderErrorText }
                                    finally { savingReminder = false }
                                }
                            },
                            modifier = content,
                        )
                    } else if (openEditor is Editor.Item && car != null && reminderSettings != null) {
                        val records by remember(openEditor.item?.id) {
                            val existing = openEditor.item
                            if (existing == null) {
                                emptyFlow<List<MaintenanceRecord>>()
                            } else {
                                viewModel.recordsOf(existing.id)
                            }
                        }.collectAsStateWithLifecycle(emptyList())

                        ItemEditor(
                            item = openEditor.item,
                            kind = openEditor.kind,
                            records = records,
                            display = display,
                            onDismiss = { editor = null },
                            defaultReminderRule = reminderSettings!!.defaultRule,
                            reminderOverride = openEditor.item?.id?.let { reminderSettings!!.itemOverrides[it] },
                            saving = savingReminder,
                            saveError = reminderSaveError,
                            onSave = { saved, rule ->
                                scope.launch {
                                    savingReminder = true
                                    reminderSaveError = null
                                    try {
                                        viewModel.saveItemWithReminder(car.carId, saved, rule)
                                        editor = null
                                    } catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { reminderSaveError = reminderErrorText }
                                    finally { savingReminder = false }
                                }
                            },
                            note = documentNoteOf(openEditor.item?.titleKey),
                            onDelete = openEditor.item?.let { existing ->
                                {
                                    viewModel.deleteItem(existing.id) {
                                        Notifications.cancelItems(applicationContext, listOf(existing.id))
                                    }
                                    editor = null
                                }
                            },
                            modifier = content,
                        )
                    } else if (openEditor is Editor.Fuel && car != null) {
                        // Заправку правят и с «Машины», и из «Журнала» — экран один.
                        val entry by produceState<FuelEntry?>(null, openEditor.entryId) {
                            value = openEditor.entryId?.let { viewModel.fuelEntry(it) }
                        }
                        val existing = entry
                        val recent by remember(car.carId, openEditor.entryId) {
                            if (openEditor.entryId == null) {
                                viewModel.fuelEntriesOf(car.carId)
                            } else {
                                emptyFlow()
                            }
                        }.collectAsStateWithLifecycle(emptyList())

                        // Правку показываем только когда запись доехала из базы: иначе
                        // экран мигнёт пустыми полями и подставит в них сегодняшний день.
                        if (openEditor.entryId == null || existing != null) {
                            FuelEditor(
                                recent = remember(recent) { recent.sortedByDescending { it.odometerKm } },
                                display = display,
                                // Заправок ещё нет — берём заметку из гаража, нет и там — бензин.
                                defaultType = existing?.fuelType
                                    ?: car.fuelGrade?.fuelType
                                    ?: FuelType.PETROL,
                                carId = existing?.carId ?: car.carId,
                                cars = cars,
                                editing = existing,
                                lastKnownKmOf = viewModel::lastKnownKm,
                                // Саму правимую заправку из сравнения выбрасываем: на своей
                                // прежней дате она бы всегда «противоречила» новой.
                                odometerBoundsOf = { target, date ->
                                    viewModel.odometerBounds(target, date, exceptFuelId = existing?.id)
                                },
                                mileageNowOf = { viewModel.mileageNow(it) },
                                onDismiss = { editor = null },
                                onSave = { targetCar, date, km, amount, type, kopecks, isFull ->
                                    if (existing == null) {
                                        viewModel.addFuelEntry(targetCar, date, km, amount, type, kopecks, isFull)
                                    } else {
                                        viewModel.saveFuelEntry(existing.id, targetCar, date, km, amount, type, kopecks, isFull)
                                    }
                                },
                                onDelete = existing?.let { found ->
                                    {
                                        viewModel.deleteFuelEntry(found.id)
                                    }
                                },
                                modifier = content,
                            )
                        }
                    } else if (openEditor is Editor.NewRecord && car != null) {
                        // Показания грузим до показа экрана: иначе первая же смена даты
                        // не нашла бы, по чему считать пробег, и человек увидел бы
                        // сегодняшний одометр у прошлой даты.
                        val readings by produceState<List<OdometerReading>?>(null, openEditor.item.id) {
                            value = viewModel.odometerReadings(car.carId)
                        }

                        readings?.let { known ->
                            RecordEditor(
                                resetKey = openEditor.item.id,
                                title = openEditor.item.title,
                                document = openEditor.item.kind == ItemKind.DOCUMENT,
                                initialDate = LocalDate.now(),
                                initialKm = car.mileage?.km,
                                display = display,
                                readings = known,
                                kmIsEstimated = true,
                                onDismiss = { editor = null },
                                // Позиция здесь задана: отметку только заводят,
                                // переносить ещё нечего.
                                onSave = { _, date, km, _ ->
                                    viewModel.markDone(openEditor.item.id, km, date) {
                                        Notifications.cancelItems(applicationContext, listOf(openEditor.item.id))
                                    }
                                },
                                modifier = content,
                            )
                        }
                    } else if (openEditor is Editor.EditRecord && openEditor.entry.recordId != null) {
                        val recordId = openEditor.entry.recordId
                        // Что останется у покинутой позиции — считаем до показа экрана:
                        // строка про последствие должна быть верной с первого кадра,
                        // а не моргнуть неправдой «станет неотмеченной».
                        val remaining by produceState<Remaining?>(null, recordId) {
                            value = Remaining(viewModel.lastDoneWithout(recordId))
                        }

                        RecordEditor(
                            entry = openEditor.entry,
                            display = display,
                            // Позиции только текущей машины: отметка несёт её пробег.
                            items = overview?.statuses.orEmpty().map { it.item },
                            remaining = remaining,
                            onDismiss = { editor = null },
                            onSave = { itemId, date, km, note ->
                                viewModel.saveRecord(recordId, itemId, date, km, note)
                            },
                            onDelete = {
                                viewModel.deleteRecord(recordId)
                            },
                            modifier = content,
                        )
                    } else if (garageOpen && currentCar != null) {
                        GarageScreen(
                            car = currentCar,
                            onSave = viewModel::saveCar,
                            onBack = {
                                garageExitTarget?.let { tab = it }
                                garageExitTarget = null
                                garageOpen = false
                            },
                            exitRequested = garageExitTarget != null,
                            onCancelExit = { garageExitTarget = null },
                            canDelete = cars.size > 1,
                            onDelete = {
                                viewModel.deleteCar(currentCar.id) { itemIds ->
                                    Notifications.cancelItems(applicationContext, itemIds)
                                }
                                garageOpen = false
                                garageExitTarget = null
                            },
                            modifier = content,
                        )
                    } else AnimatedContent(
                        targetState = tab,
                        // Содержимое уезжает в ту сторону, откуда пришло: вкладки стоят в
                        // ряд, и переход должен показывать, куда именно человек шагнул.
                        // Сдвиг на четверть ширины, а не на всю: экран не «пролетает»,
                        // а подаётся — на медленном телефоне полный пролёт выглядит рывком.
                        transitionSpec = {
                            val forward = targetState.ordinal > initialState.ordinal
                            val shift = { width: Int -> if (forward) width / 4 else -width / 4 }

                            (slideInHorizontally(tween(TAB_MS)) { shift(it) } + fadeIn(tween(TAB_MS)))
                                .togetherWith(
                                    slideOutHorizontally(tween(TAB_MS)) { -shift(it) } +
                                        fadeOut(tween(TAB_MS))
                                )
                        },
                        label = "tab",
                    ) { shown ->
                    when {
                        shown == Tab.CAR -> HomeScreen(
                            overview = overview,
                            garageEmpty = garageEmpty,
                            display = display,
                            cars = cars,
                            onOpenCarPicker = { carPickerOpen = true },
                            onAddReading = viewModel::addOdometerReading,
                            onOpenOdometerHistory = { odometerHistoryCarId = it },

                            onEditItem = { reminderSaveError = null; editor = Editor.Item(it, it.kind) },
                            onNewItem = { reminderSaveError = null; editor = Editor.Item(null, it) },
                            onNewFuel = { editor = Editor.Fuel(null) },
                            onMark = { editor = Editor.NewRecord(it.item) },
                            onOpenGarage = { garageOpen = true },
                            onOpenHelp = { requestedLesson = null; helpOpen = true },
                            modifier = content,
                        )

                        shown == Tab.JOURNAL -> {
                            val carId = overview?.carId
                            val entries by remember(carId) {
                                if (carId == null) emptyFlow() else viewModel.journalOf(carId)
                            }.collectAsStateWithLifecycle(emptyList())

                            JournalScreen(
                                entries = entries,
                                garageEmpty = garageEmpty,
                                display = display,
                                carName = overview?.carName,
                                carBodyType = currentCar?.bodyType,
                                carSilhouetteColor = currentCar?.silhouetteColor,
                                onOpenCarPicker = { carPickerOpen = true },
                                onOpenFuel = { editor = Editor.Fuel(it) },
                                onOpenRecord = { editor = Editor.EditRecord(it) },
                                onDeleteMany = viewModel::deleteJournalEntries,
                                modifier = content,
                            )
                        }

                        else -> MoreScreen(
                            lessonDefinition = lessons.first { it.id == "regional" },
                            startLesson = requestedLesson == "regional",
                            onLessonStartConsumed = { requestedLesson = null },
                            onImportBlockedChanged = { moreImportBlocked = it },
                            onReminders = { requestedLesson = null; reminderSaveError = null; remindersOpen = true },
                            themeMode = themeMode,
                            onThemeMode = { scope.launch { settings.setThemeMode(it) } },
                            units = units,
                            onUnits = { scope.launch { settings.setUnitSystem(it) } },
                            regionalPreferences = regionalPreferences,
                            regionalCatalog = regionalCatalog,
                            onCurrency = { scope.launch { settings.setCurrency(it) } },
                            regions = regionPresets,
                            onRegion = { scope.launch { settings.setRegion(it) } },
                            missingDocuments = { preset ->
                                overview?.carId
                                    ?.let { viewModel.missingRegionDocuments(it, preset) }
                                    .orEmpty()
                            },
                            onAddDocuments = { preset ->
                                overview?.carId?.let { viewModel.addRegionDocuments(it, preset) }
                            },
                            onExport = { requestedLesson = null; exportOpen = true },
                            onBackup = { backupOpen = true },
                            backupFolder = backupFolder,
                            scannerSection = { expanded, onToggle ->
                                // Initialize and observe models only when their settings row is composed.
                                val models = remember { container.scannerModels }
                                val state by models.state.collectAsStateWithLifecycle()
                                ScannerSettingsSection(
                                    state = state,
                                    expanded = expanded,
                                    onToggle = onToggle,
                                    onDownload = models::download,
                                    onCancel = models::cancel,
                                    onRemove = models::remove,
                                    onRefresh = models::refresh,
                                    onSelectVersion = models::selectVersion,
                                    onCheckCatalog = models::checkCatalog,
                                    onRollback = models::rollback,
                                    onScan = { scannerOpen = true },
                                )
                            },
                            onBackupFolder = { folder ->
                                scope.launch {
                                    settings.setBackupFolder(folder)
                                    if (folder == null) BackupWorker.cancel(applicationContext)
                                    else BackupWorker.schedule(applicationContext)
                                }
                            },
                            modifier = content,
                        )
                    }
                    }
                }

                var lastTourStep by remember { mutableStateOf(0) }
                SideEffect { tourIndex?.let { lastTourStep = it } }
                AnimatedVisibility(
                    visible = tourIndex != null,
                    enter = fadeIn(tween(resources.getInteger(R.integer.help_transition_ms))),
                    exit = fadeOut(tween(resources.getInteger(R.integer.help_transition_ms))),
                ) {
                    val step = tourIndex ?: lastTourStep
                    TourOverlay(
                        topics = tourTopics, catalog = helpTopics, index = step, anchors = anchors,
                        onPrevious = { tourIndex = (step - 1).coerceAtLeast(0) },
                        onNext = { if (step == tourTopics.lastIndex) closeTour() else tourIndex = step + 1 },
                        onClose = closeTour,
                    )
                }
                }
                }

                // Переключатель машин. Живёт здесь, а не в экране: его открывает и
                // «Машина», и «Журнал».
                if (carPickerOpen) {
                    CarPickerDialog(
                        cars = cars,
                        selectedId = overview?.carId ?: 0L,
                        onDismiss = { carPickerOpen = false },
                        onSelect = {
                            viewModel.selectCar(it)
                            carPickerOpen = false
                        },
                        onAdd = {
                            viewModel.addCar(
                                name = resources.getString(R.string.car_new_name),
                                region = regionPreset,
                                units = units,
                            )
                            // Новая машина открывается сразу: человек нажал «добавить»,
                            // значит дальше он занимается именно ей.
                            carPickerOpen = false
                            tab = Tab.CAR
                            garageOpen = true
                        },
                    )
                }

                val exportCar = overview
                if (exportOpen && exportCar != null) {
                    ExportDialog(
                        lessonDefinition = lessons.first { it.id == "export" },
                        carName = exportCar.carName,
                        startLesson = requestedLesson == "export",
                        onLessonStartConsumed = { requestedLesson = null },
                        onDismiss = { exportOpen = false; requestedLesson = null },
                        buildText = { preset, anonymize, question ->
                            viewModel.exportText(exportCar.carId, preset, anonymize, display, exportTexts(resources), question)
                        },
                    )
                } else if (exportOpen && garageEmpty) {
                    val closeEmptyExport = { exportOpen = false; requestedLesson = null }
                    AlertDialog(
                        onDismissRequest = closeEmptyExport,
                        title = { Text(stringResource(R.string.export_screen_title)) },
                        text = { Text(stringResource(R.string.garage_empty)) },
                        confirmButton = {
                            TextButton(onClick = { closeEmptyExport(); carPickerOpen = true }) {
                                Text(stringResource(R.string.car_add))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = closeEmptyExport) { Text(stringResource(R.string.common_close)) }
                        },
                    )
                }

                odometerHistoryCarId?.let { carId ->
                    val historyCar = cars.firstOrNull { it.id == carId }
                    if (historyCar != null) OdometerHistoryDialog(
                        carId = carId,
                        carName = historyCar.name,
                        display = display,
                        readings = remember(carId) { viewModel.standaloneReadings(carId) },
                        onUpdate = { id, km, date -> viewModel.updateOdometerReading(carId, id, km, date) },
                        onDelete = { id -> viewModel.deleteOdometerReading(carId, id) },
                        onDismiss = { odometerHistoryCarId = null },
                    )
                }

                if (backupOpen) {
                    BackupDialog(
                        onDismiss = { backupOpen = false },
                        buildBackup = viewModel::buildBackup,
                        restoreBackup = viewModel::restoreBackup,
                        regionalPreferences = regionalPreferences,
                        regionalCatalog = regionalCatalog,
                        onCurrency = { code -> scope.launch { settings.setCurrency(code) } },
                    )
                }
                scannerInbox.error?.let { error ->
                    val message = when (error) {
                        ScannerImportError.UNSUPPORTED -> R.string.scanner_import_unsupported
                        ScannerImportError.MULTIPLE -> R.string.scanner_import_multiple
                        ScannerImportError.TOO_LARGE -> R.string.scanner_import_too_large
                        ScannerImportError.BUSY -> R.string.scanner_import_busy
                        ScannerImportError.INTERRUPTED -> R.string.ocr_interrupted
                    }
                    AlertDialog(
                        onDismissRequest = scannerInbox::dismissError,
                        title = { Text(stringResource(R.string.ocr_title)) },
                        text = { Text(stringResource(message)) },
                        confirmButton = {
                            TextButton(onClick = scannerInbox::dismissError) {
                                Text(stringResource(R.string.common_close))
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Разрешение на уведомления спрашиваем один раз после знакомства с приложением.
 * А вот исключение из энергосбережения будем просить отложенно, при настройке первого
 * напоминания (docs/DECISIONS.md, решение 12).
 */
@Composable
private fun RequestNotificationPermissionOnce(settings: SettingsStore) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* отказ не ломает приложение: главный экран остаётся источником правды */ }

    LaunchedEffect(settings) {
        if (settings.claimNotificationPermissionRequest()) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
