package io.github.snowdrit.mamokeep.ui.help

import android.content.Context
import androidx.annotation.StringRes
import io.github.snowdrit.mamokeep.R
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Stable semantic anchors shared by the UI and the editable asset catalog. */
object HelpTarget {
    const val HELP = "help"
    const val CAR = "car"
    const val MILEAGE = "mileage"
    const val FUEL = "fuel"
    const val MAINTENANCE = "maintenance"
    const val ADD_MAINTENANCE = "add_maintenance"
    const val DOCUMENTS = "documents"
    const val REFUEL = "refuel"
    const val JOURNAL_CAR = "journal_car"
    const val JOURNAL_ENTRIES = "journal_entries"
    const val APPEARANCE = "appearance"
    const val UNITS = "units"
    const val COUNTRY = "country"
    const val DATA = "data"
    const val REMINDERS = "reminders"
    const val EXPORT = "export"
    const val CURRENCY = "currency"
    const val SCANNER = "scanner"
}

enum class HelpPage(@StringRes val label: Int) {
    CAR(R.string.tab_car), JOURNAL(R.string.tab_journal), MORE(R.string.tab_more)
}

data class HelpTopic(
    val id: String,
    val page: HelpPage,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    @StringRes val detail: Int,
    val showInHelp: Boolean = true,
    val recommended: List<String> = emptyList(),
    val showInTour: Boolean = false,
    val lesson: String? = null,
)

@Serializable
private data class TopicAsset(
    val id: String, val page: HelpPage, val title: String, val summary: String, val detail: String,
    val showInHelp: Boolean = true, val recommended: List<String> = emptyList(),
    val showInTour: Boolean = false,
    val lesson: String? = null,
)

object HelpCatalog {
    fun load(context: Context): List<HelpTopic> {
        val assets = context.assets.open("help/topics.json").bufferedReader().use {
            Json.decodeFromString<List<TopicAsset>>(it.readText())
        }
        require(assets.isNotEmpty() && assets.map { it.id }.distinct().size == assets.size)
        require(assets.any { it.showInTour })
        val articleIds = assets.filter { it.showInHelp }.map { it.id }.toSet()
        require(assets.all { topic -> topic.recommended.all { it in articleIds && it != topic.id } })
        fun stringId(name: String): Int = context.resources
            .getIdentifier(name, "string", context.packageName)
            .also { require(it != 0) { "Missing help resource: $name" } }
        return assets.map {
            HelpTopic(it.id, it.page, stringId(it.title), stringId(it.summary), stringId(it.detail), it.showInHelp, it.recommended, it.showInTour, it.lesson)
        }
    }
}
