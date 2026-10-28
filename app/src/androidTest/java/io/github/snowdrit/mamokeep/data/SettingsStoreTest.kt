package io.github.snowdrit.mamokeep.data

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.domain.UnitSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    @Test
    fun unrelatedPreferencesDoNotEmitSelectedCarAgainAndChangingCarStillEmits() = runBlocking {
        assumeTrue(
            "This test temporarily changes app preferences and is emulator-only",
            Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
                Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("Emulator"),
        )
        val store = SettingsStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val beforeCar = store.selectedCarId.first()
        val beforeTheme = store.themeMode.first()
        val beforeUnits = store.unitSystem.first()
        val events = Channel<Long>(Channel.UNLIMITED)
        store.setSelectedCarId(11L)
        val collector = launch(Dispatchers.Default) { store.selectedCarId.collect { events.send(it) } }
        try {
            assertEquals(11L, withTimeout(5_000) { events.receive() })
            val theme = if (beforeTheme == ThemeMode.DARK) ThemeMode.LIGHT else ThemeMode.DARK
            val units = if (beforeUnits == UnitSystem.US) UnitSystem.METRIC else UnitSystem.US
            store.setThemeMode(theme)
            store.setUnitSystem(units)
            assertEquals(theme, store.themeMode.first())
            assertEquals(units, store.unitSystem.first())
            assertNull("Theme and units do not select a different car", withTimeoutOrNull(300) { events.receive() })

            store.setSelectedCarId(12L)
            assertEquals(12L, withTimeout(5_000) { events.receive() })
        } finally {
            collector.cancelAndJoin()
            store.setSelectedCarId(beforeCar)
            store.setThemeMode(beforeTheme)
            store.setUnitSystem(beforeUnits)
        }
    }
}
