package io.github.snowdrit.mamokeep.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Пакет региона.
 *
 * Регион — это данные, а не ветка в коде. Новая страна добавляется одним файлом через
 * pull request и не требует ни строчки правок в приложении (docs/DECISIONS.md, решение 8).
 *
 * Формат — JSON, а не YAML, как предполагалось изначально: YAML потребовал бы отдельного
 * парсера, а выигрыш для файла из десятка строк нулевой.
 */
@Serializable
data class RegionPreset(
    val code: String,
    /**
     * Название страны — на её собственном языке и не переводится: «Deutschland» узнаётся
     * в списке лучше, чем «Германия» или «Germany». Это про страну, а не про интерфейс.
     */
    val name: String,
    /**
     * Язык, на котором написан пакет. По умолчанию совпадает с кодом страны, но это
     * не одно и то же: у Швейцарии код `ch`, а язык может быть любым из четырёх.
     * Используется как запасной, когда перевода на язык интерфейса в пакете нет.
     */
    val lang: String = code,
    val documents: List<DocumentPreset> = emptyList(),
)

@Serializable
data class DocumentPreset(
    /**
     * Стабильный ключ документа, уникальный среди всех пакетов.
     *
     * Он и лежит в базе у заведённой позиции — ровно как у девяти позиций ТО. Благодаря
     * ему название следует за языком интерфейса, а не застывает тем, на котором документ
     * однажды завели.
     */
    val key: String,
    /**
     * Названия по языкам: `{"ru": "ОСАГО", "en": "OSAGO …"}`.
     *
     * Переводы живут здесь, а не в ресурсах приложения, потому что новая страна обязана
     * добавляться одним файлом и без правок кода (решение 8). Обязателен только язык
     * самого пакета — он же и запасной вариант.
     */
    val titles: Map<String, String>,
    /** Типичный срок действия. Пользователь может поставить свой. */
    val months: Int,
    val warnDays: Int = 30,
    /** Ключ значка. Необязателен: без него подставится общий для документов. */
    val icon: String? = null,
    /** Пояснение «почему именно столько», по языкам. Показывается в редакторе позиции. */
    val notes: Map<String, String> = emptyMap(),
) {
    /** @param lang язык интерфейса; @param fallback язык пакета. */
    fun title(lang: String, fallback: String): String =
        titles[lang] ?: titles[fallback] ?: titles.values.firstOrNull().orEmpty()

    fun note(lang: String, fallback: String): String? = notes[lang] ?: notes[fallback]
}

object RegionPresets {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /** Разбор вынесен отдельно от чтения файла, чтобы его можно было проверить без Android. */
    fun parse(content: String): RegionPreset = json.decodeFromString(content)
}
