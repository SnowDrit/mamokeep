package io.github.snowdrit.mamokeep.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.snowdrit.mamokeep.domain.MaintenanceStatus
import io.github.snowdrit.mamokeep.domain.ReminderHistory
import io.github.snowdrit.mamokeep.domain.ReminderPolicy
import io.github.snowdrit.mamokeep.domain.ReminderRule
import io.github.snowdrit.mamokeep.domain.ReminderSettings
import io.github.snowdrit.mamokeep.domain.ReminderStage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.ZonedDateTime

private val Context.reminderDataStore by preferencesDataStore("reminders")

@Serializable
private data class StoredReminders(
    val settings: ReminderSettings = ReminderSettings(),
    val history: Map<Long, ReminderHistory> = emptyMap(),
)

/** Settings and delivery claims are saved together, including across process restarts. */
class ReminderSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.reminderDataStore
    private val stateKey = stringPreferencesKey("state_v1")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<ReminderSettings> = dataStore.data.map { decode(it[stateKey]).settings }
        .distinctUntilChanged()

    /** Serializes garage mutations/backups with notification reads and OS posting. */
    suspend fun <T> withGarageLock(block: suspend () -> T): T = garageMutex.withLock { block() }

    suspend fun setDefaultRule(rule: ReminderRule) {
        require(rule.isValid)
        update { it.copy(settings = it.settings.copy(defaultRule = rule)) }
    }

    suspend fun setItemRule(itemId: Long, rule: ReminderRule?) {
        require(itemId > 0 && (rule == null || rule.isValid))
        update { state ->
            val overrides = if (rule == null) state.settings.itemOverrides - itemId
            else state.settings.itemOverrides + (itemId to rule)
            state.copy(settings = state.settings.copy(itemOverrides = overrides))
        }
    }

    suspend fun clearHistory(itemId: Long) = update { it.copy(history = it.history - itemId) }

    suspend fun removeItems(itemIds: Collection<Long>) = update { state ->
        state.copy(
            settings = state.settings.copy(itemOverrides = state.settings.itemOverrides - itemIds.toSet()),
            history = state.history - itemIds.toSet(),
        )
    }

    /** Remove orphaned settings after deletions or an interrupted backup restore. */
    suspend fun retainItems(validIds: Set<Long>) = update { state ->
        state.copy(
            settings = state.settings.copy(itemOverrides = state.settings.itemOverrides.filterKeys { it in validIds }),
            history = state.history.filterKeys { it in validIds },
        )
    }

    suspend fun exportSettings(): ReminderSettings = settings.first()

    /** Imported item IDs have already been mapped to the new local IDs by the caller. */
    suspend fun replaceSettings(settings: ReminderSettings) {
        require(settings.isValid)
        update { StoredReminders(settings = settings) }
    }

    /**
     * Atomic check + claim keeps a manual debug check and a scheduled worker from
     * delivering the same notice together. Claim only after checking OS permission.
     */
    suspend fun claimNotification(status: MaintenanceStatus, now: ZonedDateTime): ReminderClaim? {
        var claim: ReminderClaim? = null
        update { state ->
            val id = status.item.id
            val rule = state.settings.ruleFor(id)
            val previous = state.history[id]
            if (!ReminderPolicy.shouldNotify(status, rule, previous, now)) return@update state
            val stage = requireNotNull(ReminderPolicy.stage(status, rule))
            val updated = ReminderPolicy.afterDelivery(status, stage, previous, now)
            claim = ReminderClaim(id, stage, previous, updated)
            state.copy(history = state.history + (id to updated))
        }
        return claim
    }

    /** A failed OS post does not consume the user's repeat limit. */
    suspend fun releaseClaim(claim: ReminderClaim) = update { state ->
        if (state.history[claim.itemId] != claim.updated) return@update state
        val history = if (claim.previous == null) state.history - claim.itemId
        else state.history + (claim.itemId to claim.previous)
        state.copy(history = history)
    }

    private fun decode(raw: String?): StoredReminders {
        if (raw == null) return StoredReminders()
        // Invalid settings must not turn into an unexpected daily reminder schedule.
        val state = runCatching { json.decodeFromString<StoredReminders>(raw) }.getOrNull()
        return state?.takeIf { it.settings.isValid }
            ?: StoredReminders(settings = ReminderSettings(defaultRule = ReminderRule(enabled = false)))
    }

    private suspend fun update(transform: (StoredReminders) -> StoredReminders) {
        dataStore.edit { prefs ->
            val state = decode(prefs[stateKey])
            val updated = transform(state)
            if (updated != state) prefs[stateKey] = json.encodeToString(updated)
        }
    }

    private companion object {
        val garageMutex = Mutex()
    }
}

data class ReminderClaim internal constructor(
    val itemId: Long,
    val stage: ReminderStage,
    internal val previous: ReminderHistory?,
    internal val updated: ReminderHistory,
)
