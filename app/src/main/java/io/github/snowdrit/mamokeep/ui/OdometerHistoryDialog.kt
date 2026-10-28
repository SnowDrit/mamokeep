package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.data.repo.StandaloneOdometerReading
import io.github.snowdrit.mamokeep.domain.Display
import io.github.snowdrit.mamokeep.text.dateText
import io.github.snowdrit.mamokeep.text.distanceText
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Only manual rows are supplied here; fuel readings remain in their own editor. */
@Composable
fun OdometerHistoryDialog(
    carId: Long,
    carName: String,
    display: Display,
    readings: Flow<List<StandaloneOdometerReading>>,
    onUpdate: suspend (Long, Int, LocalDate) -> Unit,
    onDelete: suspend (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val state: OdometerHistoryViewModel = viewModel(key = "odometer-history:$carId")
    val entries by readings.collectAsStateWithLifecycle(initialValue = null)
    val editing = state.editing
    val km = state.enteredKm(display.units)
    val dismiss = {
        if (!state.busy) {
            state.reset()
            onDismiss()
        }
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = {
            Column {
                Text(stringResource(if (editing == null) R.string.odometer_history_title else R.string.odometer_history_edit))
                Text(carName, style = MaterialTheme.typography.bodyMedium)
            }
        },
        text = {
            if (editing == null) {
                Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.odometer_history_spacing))) {
                    Text(stringResource(R.string.odometer_history_explain))
                    val loaded = entries
                    when {
                        loaded == null -> CircularProgressIndicator()
                        loaded.isEmpty() -> Text(stringResource(R.string.odometer_history_empty))
                        else -> LazyColumn(Modifier.heightIn(max = dimensionResource(R.dimen.odometer_history_max_height))) {
                            items(loaded, key = { it.id }) { reading ->
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clickable(role = Role.Button) { state.edit(reading, display.units) }
                                        .padding(vertical = dimensionResource(R.dimen.odometer_history_row_padding)),
                                ) {
                                    Text(display.distanceText(reading.km), style = MaterialTheme.typography.titleMedium)
                                    Text(reading.date.dateText(), style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.odometer_history_row_padding))) {
                    if (state.busy) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.odometer_history_saving))
                    } else if (state.confirmingDelete) {
                        Text(stringResource(R.string.odometer_history_delete_confirm,
                            display.distanceText(editing.km), editing.date.dateText()))
                    } else {
                        OutlinedTextField(
                            value = state.distance,
                            onValueChange = state::changeDistance,
                            label = { Text(stringResource(R.string.home_odometer_field, display.distanceUnit)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = GroupedDigitsTransformation(display.words.groupSeparator),
                        )
                        DateField(state.date, state::changeDate, stringResource(R.string.fuel_date_field), maxDate = LocalDate.now())
                        TextButton(onClick = state::confirmDelete) {
                            Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    if (state.writeFailed) {
                        Text(stringResource(R.string.odometer_history_write_failed), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            when {
                editing == null -> TextButton(onClick = dismiss) { Text(stringResource(R.string.common_close)) }
                state.confirmingDelete -> TextButton(onClick = { state.delete(onDelete) }, enabled = !state.busy) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
                else -> TextButton(onClick = { state.save(checkNotNull(km), onUpdate) }, enabled = !state.busy && km != null) {
                    Text(stringResource(R.string.common_save))
                }
            }
        },
        dismissButton = {
            if (editing != null) {
                TextButton(onClick = { if (state.confirmingDelete) state.cancelDelete() else state.reset() }, enabled = !state.busy) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        },
    )
}
