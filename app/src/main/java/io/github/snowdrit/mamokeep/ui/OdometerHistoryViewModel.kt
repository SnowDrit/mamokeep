package io.github.snowdrit.mamokeep.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.snowdrit.mamokeep.data.repo.StandaloneOdometerReading
import io.github.snowdrit.mamokeep.domain.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Keeps the draft and write alive during rotation; the editor closes only after success. */
internal class OdometerHistoryViewModel : ViewModel() {
    var editing by mutableStateOf<StandaloneOdometerReading?>(null)
        private set
    var distance by mutableStateOf("")
        private set
    var date by mutableStateOf(LocalDate.now())
        private set
    var confirmingDelete by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var writeFailed by mutableStateOf(false)
        private set
    private var originalDistance = ""

    fun edit(reading: StandaloneOdometerReading, units: UnitSystem) {
        check(!busy)
        editing = reading
        originalDistance = units.distance(reading.km).roundToInt().toString()
        distance = originalDistance
        date = reading.date
        confirmingDelete = false
        writeFailed = false
    }

    fun changeDistance(value: String) {
        if (!busy) distance = value.filter(Char::isDigit).take(7)
    }

    fun changeDate(value: LocalDate) {
        if (!busy) date = value
    }

    fun enteredKm(units: UnitSystem): Int? {
        val reading = editing ?: return null
        // Opening a miles-based editor must not round-trip an unchanged kilometre value.
        return if (distance == originalDistance) reading.km
        else distance.toIntOrNull()?.let { units.kmFrom(it.toDouble()) }
    }

    fun confirmDelete() {
        check(!busy)
        confirmingDelete = true
        writeFailed = false
    }

    fun cancelDelete() {
        check(!busy)
        confirmingDelete = false
        writeFailed = false
    }

    fun reset() {
        check(!busy)
        editing = null
        confirmingDelete = false
        writeFailed = false
    }

    fun save(km: Int, update: suspend (Long, Int, LocalDate) -> Unit) {
        val reading = checkNotNull(editing)
        val changedDate = date
        write { update(reading.id, km, changedDate) }
    }

    fun delete(remove: suspend (Long) -> Unit) {
        val reading = checkNotNull(editing)
        write { remove(reading.id) }
    }

    private fun write(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        writeFailed = false
        viewModelScope.launch {
            try {
                operation()
                editing = null
                confirmingDelete = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Log.e("OdometerHistory", "Could not change odometer reading", failure)
                writeFailed = true
            } finally {
                busy = false
            }
        }
    }
}
