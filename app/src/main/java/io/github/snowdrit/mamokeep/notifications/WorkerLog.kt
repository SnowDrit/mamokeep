package io.github.snowdrit.mamokeep.notifications

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Журнал запусков ежесуточной проверки.
 *
 * Нужен ради единственного открытого вопроса вертикального среза: проснётся ли
 * `WorkManager` на живом телефоне через две недели или его убьёт энергосбережение
 * (docs/ROADMAP.md, v0.1, пункт 1). На эмуляторе это не проверяется в принципе.
 *
 * Пишем в файл, а не в logcat: logcat не переживает ни перезагрузку телефона, ни
 * отключение кабеля, а опыт длится две недели.
 */
object WorkerLog {

    private const val FILE = "worker-runs.log"
    private const val MAX_LINES = 200
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun record(context: Context, note: String) {
        val file = File(context.filesDir, FILE)
        val line = "${LocalDateTime.now().format(STAMP)} $note"

        runCatching {
            val kept = if (file.exists()) file.readLines().takeLast(MAX_LINES - 1) else emptyList()
            file.writeText((kept + line).joinToString("\n"))
        }
    }

    fun read(context: Context): List<String> = runCatching {
        File(context.filesDir, FILE).readLines()
    }.getOrDefault(emptyList())
}
