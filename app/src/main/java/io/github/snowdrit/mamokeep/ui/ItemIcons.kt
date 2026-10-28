package io.github.snowdrit.mamokeep.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.snowdrit.mamokeep.R
import io.github.snowdrit.mamokeep.domain.ItemKind

/**
 * Каталог значков.
 *
 * Домен хранит только ключ — сопоставление с ресурсом живёт здесь, в слое интерфейса.
 * Так пресеты и пользовательские позиции остаются данными, а Android-специфика не
 * протекает в бизнес-логику.
 *
 * Значки — Material Design Icons (Pictogrammers), Apache 2.0. См. LICENSE-NOTICE.md.
 */
data class ItemIcon(
    val key: String,
    /** Название — ресурсом, а не строкой: значок подписан на языке приложения. */
    @StringRes val label: Int,
    @DrawableRes val res: Int,
)

object ItemIcons {

    val ALL: List<ItemIcon> = listOf(
        ItemIcon("oil", R.string.icon_oil, R.drawable.ic_oil),
        ItemIcon("engine", R.string.icon_engine, R.drawable.ic_engine),
        ItemIcon("air_filter", R.string.icon_air_filter, R.drawable.ic_air_filter),
        ItemIcon("air_conditioner", R.string.icon_air_conditioner, R.drawable.ic_air_conditioner),
        ItemIcon("flash", R.string.icon_flash, R.drawable.ic_flash),
        ItemIcon("car_battery", R.string.icon_car_battery, R.drawable.ic_car_battery),
        ItemIcon("car_brake_alert", R.string.icon_car_brake_alert, R.drawable.ic_car_brake_alert),
        ItemIcon("car_brake_fluid_level", R.string.icon_car_brake_fluid_level, R.drawable.ic_car_brake_fluid_level),
        ItemIcon("car_coolant_level", R.string.icon_car_coolant_level, R.drawable.ic_car_coolant_level),
        ItemIcon("car_shift_pattern", R.string.icon_car_shift_pattern, R.drawable.ic_car_shift_pattern),
        ItemIcon("car_tire_alert", R.string.icon_car_tire_alert, R.drawable.ic_car_tire_alert),
        ItemIcon("car_turbocharger", R.string.icon_car_turbocharger, R.drawable.ic_car_turbocharger),
        ItemIcon("car_light_high", R.string.icon_car_light_high, R.drawable.ic_car_light_high),
        ItemIcon("water", R.string.icon_water, R.drawable.ic_water),
        ItemIcon("snowflake", R.string.icon_snowflake, R.drawable.ic_snowflake),
        ItemIcon("filter_variant", R.string.icon_filter_variant, R.drawable.ic_filter_variant),
        ItemIcon("cog", R.string.icon_cog, R.drawable.ic_cog),
        ItemIcon("car_wrench", R.string.icon_car_wrench, R.drawable.ic_car_wrench),
        ItemIcon("wrench", R.string.icon_wrench, R.drawable.ic_wrench),
        ItemIcon("gas_station", R.string.icon_gas_station, R.drawable.ic_gas_station),
        ItemIcon("shield_car", R.string.icon_shield_car, R.drawable.ic_shield_car),
        ItemIcon("clipboard_check_outline", R.string.icon_clipboard_check_outline, R.drawable.ic_clipboard_check_outline),
        ItemIcon("car_key", R.string.icon_car_key, R.drawable.ic_car_key),
        ItemIcon("file_document_outline", R.string.icon_file_document_outline, R.drawable.ic_file_document_outline),
    )

    private val byKey = ALL.associateBy { it.key }

    /** Запасной значок зависит от вида позиции: гаечный ключ документу не подходит. */
    @DrawableRes
    fun resFor(key: String?, kind: ItemKind): Int =
        byKey[key]?.res ?: when (kind) {
            ItemKind.DOCUMENT -> R.drawable.ic_file_document_outline
            ItemKind.MAINTENANCE -> R.drawable.ic_wrench
        }
}

/**
 * Типы кузова: картинка в гараже подбирается по этому ключу.
 *
 * Список широкий намеренно: приложение не только про легковые. Регламенты редактируются,
 * значит трактор, снегоход и тягач обслуживаются в нём ровно так же.
 *
 * Для всех групп используется собственный комплект детальных иллюстраций:
 * профиль вправо в компактных местах и передний ракурс 3/4 в карточке гаража.
 */
data class BodyType(
    val key: String,
    @StringRes val label: Int,
    @StringRes val group: Int,
    /** Компактный профиль для выбора кузова и маленьких заголовков. */
    @DrawableRes val res: Int,
    /** Более выразительный ракурс для большой иллюстрации в гараже. */
    @DrawableRes val heroRes: Int = res,
    /** Рисунок временный и повторяет чужой: заменить при расширении комплекта. */
    val placeholder: Boolean = false,
)

object BodyTypes {
    val ALL: List<BodyType> = listOf(
        BodyType(
            "sedan", R.string.body_sedan, R.string.body_group_passenger,
            R.drawable.car_sedan_detailed_profile,
            heroRes = R.drawable.car_sedan_detailed_three_quarter,
        ),
        BodyType(
            "hatchback", R.string.body_hatchback, R.string.body_group_passenger,
            R.drawable.car_hatchback_detailed_profile,
            heroRes = R.drawable.car_hatchback_detailed_three_quarter,
        ),
        BodyType(
            "liftback", R.string.body_liftback, R.string.body_group_passenger,
            R.drawable.car_liftback_detailed_profile,
            heroRes = R.drawable.car_liftback_detailed_three_quarter,
        ),
        BodyType(
            "estate", R.string.body_estate, R.string.body_group_passenger,
            R.drawable.car_estate_detailed_profile,
            heroRes = R.drawable.car_estate_detailed_three_quarter,
        ),
        BodyType(
            "crossover",
            R.string.body_crossover,
            R.string.body_group_passenger,
            R.drawable.car_crossover_detailed_profile,
            heroRes = R.drawable.car_crossover_detailed_three_quarter_grille,
        ),
        BodyType(
            "suv", R.string.body_suv, R.string.body_group_passenger,
            R.drawable.car_suv_detailed_profile,
            heroRes = R.drawable.car_suv_detailed_three_quarter,
        ),
        BodyType(
            "coupe", R.string.body_coupe, R.string.body_group_passenger,
            R.drawable.car_coupe_detailed_profile,
            heroRes = R.drawable.car_coupe_detailed_three_quarter,
        ),
        BodyType(
            "convertible", R.string.body_convertible, R.string.body_group_passenger,
            R.drawable.car_convertible_detailed_profile,
            heroRes = R.drawable.car_convertible_detailed_three_quarter,
        ),
        BodyType(
            "pickup", R.string.body_pickup, R.string.body_group_passenger,
            R.drawable.car_pickup_detailed_profile,
            heroRes = R.drawable.car_pickup_detailed_three_quarter,
        ),
        BodyType(
            "minivan", R.string.body_minivan, R.string.body_group_passenger,
            R.drawable.car_minivan_detailed_profile,
            heroRes = R.drawable.car_minivan_detailed_three_quarter,
        ),

        BodyType(
            "van", R.string.body_van, R.string.body_group_commercial,
            R.drawable.car_van_detailed_profile,
            heroRes = R.drawable.car_van_detailed_three_quarter,
        ),
        BodyType(
            "minibus", R.string.body_microbus, R.string.body_group_commercial,
            R.drawable.car_minibus_detailed_profile,
            heroRes = R.drawable.car_minibus_detailed_three_quarter,
        ),
        BodyType(
            "bus", R.string.body_bus, R.string.body_group_commercial,
            R.drawable.car_bus_detailed_profile,
            heroRes = R.drawable.car_bus_detailed_three_quarter,
        ),
        BodyType(
            "truck", R.string.body_truck, R.string.body_group_commercial,
            R.drawable.car_truck_detailed_profile,
            heroRes = R.drawable.car_truck_detailed_three_quarter,
        ),
        BodyType(
            "tractor_unit", R.string.body_tractor_unit, R.string.body_group_commercial,
            R.drawable.car_tractor_unit_detailed_profile,
            heroRes = R.drawable.car_tractor_unit_detailed_three_quarter,
        ),
        BodyType(
            "dump_truck", R.string.body_dump_truck, R.string.body_group_commercial,
            R.drawable.car_dump_truck_detailed_profile,
            heroRes = R.drawable.car_dump_truck_detailed_three_quarter,
        ),
        BodyType(
            "tanker", R.string.body_tanker, R.string.body_group_commercial,
            R.drawable.car_tanker_detailed_profile,
            heroRes = R.drawable.car_tanker_detailed_three_quarter,
        ),
        BodyType(
            "tow_truck", R.string.body_tow_truck, R.string.body_group_commercial,
            R.drawable.car_tow_truck_detailed_profile,
            heroRes = R.drawable.car_tow_truck_detailed_three_quarter,
        ),

        BodyType(
            "motorbike", R.string.body_motorcycle, R.string.body_group_two_wheel,
            R.drawable.car_motorbike_detailed_profile,
            heroRes = R.drawable.car_motorbike_detailed_three_quarter,
        ),
        BodyType(
            "moped", R.string.body_scooter, R.string.body_group_two_wheel,
            R.drawable.car_moped_detailed_profile,
            heroRes = R.drawable.car_moped_detailed_three_quarter,
        ),

        BodyType(
            "atv", R.string.body_quad, R.string.body_group_other,
            R.drawable.car_atv_detailed_profile,
            heroRes = R.drawable.car_atv_detailed_three_quarter,
        ),
        BodyType(
            "snowmobile", R.string.body_snowmobile, R.string.body_group_other,
            R.drawable.car_snowmobile_detailed_profile,
            heroRes = R.drawable.car_snowmobile_detailed_three_quarter,
        ),
        BodyType(
            "rv", R.string.body_motorhome, R.string.body_group_other,
            R.drawable.car_rv_detailed_profile,
            heroRes = R.drawable.car_rv_detailed_three_quarter,
        ),
        BodyType(
            "caravan", R.string.body_caravan, R.string.body_group_other,
            R.drawable.car_caravan_detailed_profile,
            heroRes = R.drawable.car_caravan_detailed_three_quarter,
        ),
        BodyType(
            "tractor", R.string.body_tractor, R.string.body_group_other,
            R.drawable.car_tractor_detailed_profile,
            heroRes = R.drawable.car_tractor_detailed_three_quarter,
        ),
    )

    private val byKey = ALL.associateBy { it.key }

    @DrawableRes
    fun resFor(key: String?): Int = byKey[key]?.res ?: R.drawable.ic_car_side

    @DrawableRes
    fun heroResFor(key: String?): Int = byKey[key]?.heroRes ?: R.drawable.ic_car_side

    /** Сколько рисунков ещё временные — видно и в плане, и в тестах. */
    fun placeholders(): List<BodyType> = ALL.filter { it.placeholder }
}
