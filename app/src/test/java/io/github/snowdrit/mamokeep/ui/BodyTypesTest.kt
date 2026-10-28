package io.github.snowdrit.mamokeep.ui

import io.github.snowdrit.mamokeep.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyTypesTest {

    @Test
    fun `ключи кузовов уникальны`() {
        val keys = BodyTypes.ALL.map { it.key }

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `временные рисунки перечислены явно`() {
        assertTrue(BodyTypes.placeholders().isEmpty())
    }

    @Test
    fun `у каждого кузова есть группа и название`() {
        BodyTypes.ALL.forEach { type ->
            // Ноль означал бы «ресурса нет»: подпись потерялась бы молча.
            assertTrue(type.label != 0)
            assertTrue(type.group != 0)
        }
    }

    @Test
    fun `легковые используют профиль в выборе и три четверти в гараже`() {
        val passenger = BodyTypes.ALL.filter { it.group == R.string.body_group_passenger }

        assertEquals(10, passenger.size)
        passenger.forEach { type ->
            assertTrue(type.res != type.heroRes)
        }
        assertEquals(R.drawable.car_sedan_detailed_profile, BodyTypes.resFor("sedan"))
        assertEquals(R.drawable.car_sedan_detailed_three_quarter, BodyTypes.heroResFor("sedan"))
        assertEquals(R.drawable.car_crossover_detailed_profile, BodyTypes.resFor("crossover"))
        assertEquals(
            R.drawable.car_crossover_detailed_three_quarter_grille,
            BodyTypes.heroResFor("crossover"),
        )
    }

    @Test
    fun `коммерческие используют профиль в выборе и три четверти в гараже`() {
        val commercial = BodyTypes.ALL.filter { it.group == R.string.body_group_commercial }

        assertEquals(8, commercial.size)
        commercial.forEach { type ->
            assertTrue(type.res != type.heroRes)
        }
        assertEquals(R.drawable.car_van_detailed_profile, BodyTypes.resFor("van"))
        assertEquals(R.drawable.car_van_detailed_three_quarter, BodyTypes.heroResFor("van"))
        assertEquals(R.drawable.car_tractor_unit_detailed_profile, BodyTypes.resFor("tractor_unit"))
        assertEquals(
            R.drawable.car_tractor_unit_detailed_three_quarter,
            BodyTypes.heroResFor("tractor_unit"),
        )
    }

    @Test
    fun `двухколёсные используют профиль в выборе и три четверти в гараже`() {
        val twoWheel = BodyTypes.ALL.filter { it.group == R.string.body_group_two_wheel }

        assertEquals(2, twoWheel.size)
        twoWheel.forEach { type ->
            assertTrue(type.res != type.heroRes)
        }
        assertEquals(R.drawable.car_motorbike_detailed_profile, BodyTypes.resFor("motorbike"))
        assertEquals(
            R.drawable.car_motorbike_detailed_three_quarter,
            BodyTypes.heroResFor("motorbike"),
        )
        assertEquals(R.drawable.car_moped_detailed_profile, BodyTypes.resFor("moped"))
        assertEquals(R.drawable.car_moped_detailed_three_quarter, BodyTypes.heroResFor("moped"))
    }

    @Test
    fun `прочие используют профиль в выборе и три четверти в гараже`() {
        val other = BodyTypes.ALL.filter { it.group == R.string.body_group_other }

        assertEquals(5, other.size)
        other.forEach { type ->
            assertTrue(type.res != type.heroRes)
        }
        assertEquals(R.drawable.car_atv_detailed_profile, BodyTypes.resFor("atv"))
        assertEquals(R.drawable.car_atv_detailed_three_quarter, BodyTypes.heroResFor("atv"))
        assertEquals(R.drawable.car_rv_detailed_profile, BodyTypes.resFor("rv"))
        assertEquals(R.drawable.car_rv_detailed_three_quarter, BodyTypes.heroResFor("rv"))
        assertEquals(R.drawable.car_tractor_detailed_profile, BodyTypes.resFor("tractor"))
        assertEquals(R.drawable.car_tractor_detailed_three_quarter, BodyTypes.heroResFor("tractor"))
    }
}
