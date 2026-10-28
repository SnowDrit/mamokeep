package io.github.snowdrit.mamokeep.ui.scanner

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Retains an in-flight database operation across rotation, never replays it after process death. */
internal class EditorOperationViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var completed by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set
    var interrupted by mutableStateOf(savedState.get<Boolean>("in_flight") == true)
        private set

    init { savedState["in_flight"] = false }

    fun attach(session: String) {
        if (savedState.get<String>("session") == session) return
        check(!busy)
        reset()
        savedState["session"] = session
    }

    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        failed = false
        interrupted = false
        savedState["in_flight"] = true
        viewModelScope.launch {
            try {
                action()
                completed = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed = true
            } finally {
                busy = false
                savedState["in_flight"] = false
            }
        }
    }

    fun reset() {
        check(!busy)
        completed = false
        failed = false
        interrupted = false
        savedState["in_flight"] = false
    }
}
