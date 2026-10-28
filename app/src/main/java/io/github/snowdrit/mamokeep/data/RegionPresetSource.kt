package io.github.snowdrit.mamokeep.data

import android.content.Context
import io.github.snowdrit.mamokeep.domain.RegionPreset
import io.github.snowdrit.mamokeep.domain.RegionPresets

/**
 * Читает пакет региона из ассетов. Пакеты лежат в `app/src/main/assets/presets/regions/`
 * и принимаются через pull request.
 */
class RegionPresetSource(private val context: Context) {

    fun load(code: String): RegionPreset? = runCatching {
        context.assets.open("presets/regions/$code.json").use { stream ->
            RegionPresets.parse(stream.readBytes().decodeToString())
        }
    }.getOrNull()

    /** Необязательные пакеты документов, которые лежат в сборке. */
    fun presets(): List<RegionPreset> = available().mapNotNull { load(it) }

    fun available(): List<String> = runCatching {
        context.assets.list("presets/regions")
            .orEmpty()
            .filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
    }.getOrDefault(emptyList())
}
