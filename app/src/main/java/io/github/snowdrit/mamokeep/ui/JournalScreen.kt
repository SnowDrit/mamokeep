package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.ui.help.*

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.text.amountText
import io.github.snowdrit.mamokeep.text.distanceText
import io.github.snowdrit.mamokeep.text.moneyText
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

/**
 * Журнал: всё, что произошло с машиной, одной хронологией.
 *
 * @param onOpenFuel открывает заправку на правку. Через журнал, а не через список внутри
 * редактора заправки: заправка, записанная не на ту машину, лежит в журнале **чужой**
 * машины, и увидеть её можно только здесь.
 * @param onOpenRecord открывает отметку «сделано» или «продлил».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    entries: List<JournalEntry>,
    garageEmpty: Boolean,
    display: Display,
    carName: String?,
    /** Кузов текущей машины: заголовок такой же, как на «Машине». */
    carBodyType: String?,
    carSilhouetteColor: Int?,
    onOpenCarPicker: () -> Unit,
    onOpenFuel: (Long) -> Unit,
    onOpenRecord: (JournalEntry) -> Unit,
    onDeleteMany: (List<JournalEntry>) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (garageEmpty) {
        EmptyGarage(onOpenCarPicker, modifier)
        return
    }
    // Ключи, а не сами записи: лента приходит заново при каждом изменении базы, и
    // выделение, хранимое объектами, разъехалось бы с новым списком.
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val selecting = selected.isNotEmpty()
    // Выделение снимается «назад» — иначе из режима нельзя выйти, не сняв галочки вручную.
    BackHandler(enabled = selecting) { selected = emptySet() }

    // Панель та же, что на «Машине»: закреплённая, подкрашивается, когда лента уезжает
    // под неё. В режиме выделения она же становится контекстной — заголовок сменяется
    // счётчиком, а справа появляются действия над выбранным.
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        // Отступы под системные панели считает `MainActivity`; свои этот Scaffold
        // добавлять не должен, иначе заголовок уедет под часы дважды.
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    if (selecting) {
                        Text(stringResource(R.string.journal_selected, selected.size))
                    } else if (carName != null) {
                        // Имя машины, а не слово «Журнал»: какая это вкладка, уже написано
                        // внизу, а чья это лента — не было написано нигде.
                        Box(Modifier.tourTarget(HelpTarget.JOURNAL_CAR)) {
                            CarTitle(
                                name = carName,
                                bodyType = carBodyType,
                                silhouetteColor = carSilhouetteColor,
                                onClick = onOpenCarPicker,
                            )
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        TextButton(onClick = { selected = emptySet() }) {
                            Text(stringResource(R.string.journal_selection_clear))
                        }
                        TextButton(
                            onClick = { confirmDelete = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) { Text(stringResource(R.string.common_delete)) }
                    }
                },
                windowInsets = WindowInsets(0),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { inner ->
    Column(Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp)) {

        if (entries.isEmpty()) {
            // Одна строка, прижатая влево посреди пустого экрана, читается как незагрузка,
            // а не как «здесь пока пусто». Значок и выравнивание по центру стоят ничего,
            // а экран перестаёт выглядеть недоделанным.
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.tourTarget(HelpTarget.JOURNAL_ENTRIES),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_clipboard_check_outline),
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Text(
                        stringResource(R.string.journal_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Column
        }

        LazyColumn(Modifier.fillMaxSize(), flingBehavior = rememberSoftFling()) {
            items(entries.size) { index ->
                val entry = entries[index]
                val key = selectionKey(entry)

                Column(if (index == 0) Modifier.tourTarget(HelpTarget.JOURNAL_ENTRIES) else Modifier) {
                JournalRow(
                    entry = entry,
                    display = display,
                    selected = key != null && key in selected,
                    selecting = selecting,
                    onToggle = {
                        if (key != null) {
                            selected = if (key in selected) selected - key else selected + key
                        }
                    },
                    onOpenFuel = onOpenFuel,
                    onOpenRecord = onOpenRecord,
                )
                }
                if (index < entries.lastIndex) HorizontalDivider()
            }
        }
    }
    }

    if (confirmDelete) {
        val chosen = entries.filter { selectionKey(it) in selected }

        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.journal_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        selected = emptySet()
                        onDeleteMany(chosen)
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

/**
 * Чем помечена запись в выделении.
 *
 * `null` означает «выделить нельзя»: у строки нет ни заправки, ни отметки, то есть
 * удалять в ней нечего.
 */
private fun selectionKey(entry: JournalEntry): String? =
    entry.fuelEntryId?.let { "f$it" } ?: entry.recordId?.let { "r$it" }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JournalRow(
    entry: JournalEntry,
    display: Display,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onOpenFuel: (Long) -> Unit,
    onOpenRecord: (JournalEntry) -> Unit,
) {
    val background =
        if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface

    Row(
        Modifier
            .fillMaxWidth()
            .background(background)
            .let { base ->
                // Долгое нажатие начинает выделение. Пока оно идёт, обычное нажатие
                // добавляет и убирает записи, а не открывает редактор: иначе первое же
                // касание после выделения выкидывало бы человека в чужой редактор.
                //
                // Нажимается любая строка: заправка открывает свой редактор, отметка
                // «сделано» — свой. Обе записываются одним касанием, значит и промахнуться
                // можно одним касанием.
                val open: (() -> Unit)? = when {
                    entry.fuelEntryId != null -> ({ onOpenFuel(entry.fuelEntryId) })
                    entry.recordId != null -> ({ onOpenRecord(entry) })
                    else -> null
                }

                if (open == null) base
                else base.combinedClickable(
                    onClick = { if (selecting) onToggle() else open() },
                    onLongClick = onToggle,
                )
            }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            painter = painterResource(
                ItemIcons.resFor(
                    entry.iconKey,
                    if (entry.kind == JournalEntry.Kind.DOCUMENT) ItemKind.DOCUMENT else ItemKind.MAINTENANCE,
                )
            ),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )

        Column(Modifier.weight(1f)) {
            Text(
                entry.title ?: stringResource(R.string.fuel_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                buildList {
                    add(entry.date.format(DATE))
                    entry.km?.let { add(display.distanceText(it)) }
                    entry.fuel?.let { add(fuelDetails(it, display)) }
                    if (entry.kind == JournalEntry.Kind.DOCUMENT) {
                        add(stringResource(R.string.journal_renewed))
                    }
                }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        entry.amountKopecks?.let {
            Text(display.moneyText(it), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * «42 л бензина, полный бак».
 *
 * Вид показываем всегда: строку журнала читают в отрыве от машины, и «42 л» без вида
 * у битопливной машины ничего не говорит.
 */
@Composable
private fun fuelDetails(fuel: JournalEntry.Fuel, display: Display): String {
    val amount = display.amountText(fuel.amount, fuel.fuelType)
    val tank = stringResource(
        if (fuel.isFull) fullLabel(fuel.fuelType) else R.string.fuel_partial
    )
    return "$amount ${stringResource(fuelTypeOf(fuel.fuelType))}, $tank"
}
