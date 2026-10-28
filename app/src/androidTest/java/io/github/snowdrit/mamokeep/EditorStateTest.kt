package io.github.snowdrit.mamokeep

import android.os.Bundle
import android.os.Parcel
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.snowdrit.mamokeep.domain.ItemKind
import io.github.snowdrit.mamokeep.domain.JournalEntry
import io.github.snowdrit.mamokeep.domain.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class EditorStateTest {
    private val item = MaintenanceItem(
        id = 42, kind = ItemKind.MAINTENANCE, title = "Масло", titleKey = "engine_oil",
        iconKey = "oil", intervalKm = 10_000, intervalMonths = 12,
        lastDoneKm = 23_456, lastDoneDate = LocalDate.of(2026, 5, 12), warnKm = 1_000, warnDays = 14,
    )

    @Test
    fun newAndExistingFuelEditorsSurviveActivityStateParcel() {
        assertRoundTrip(Editor.Fuel(null))
        assertRoundTrip(Editor.Fuel(123))
    }

    @Test
    fun newAndExistingItemsKeepTheirBaselineAndNullableFields() {
        assertRoundTrip(Editor.Item(null, ItemKind.DOCUMENT))
        assertRoundTrip(Editor.Item(item, item.kind))
        assertRoundTrip(Editor.Item(item.copy(intervalKm = null, lastDoneKm = null, lastDoneDate = null), item.kind))
    }

    @Test
    fun newServiceRecordKeepsItsTargetItem() {
        assertRoundTrip(Editor.NewRecord(item))
    }

    @Test
    fun existingServiceAndDocumentRecordsKeepTheirIdsAndDates() {
        val entry = JournalEntry(
            date = LocalDate.of(2026, 5, 12), km = 23_456, title = "Масло",
            recordId = 321, itemId = item.id, fuel = null, amountKopecks = null,
            iconKey = "oil", kind = JournalEntry.Kind.WORK,
            note = "Checked service order\nOil and filter replaced",
        )
        assertRoundTrip(Editor.EditRecord(entry))
        assertRoundTrip(Editor.EditRecord(entry.copy(km = null, title = "Полис", kind = JournalEntry.Kind.DOCUMENT)))
    }

    @Test
    fun precedingVersionItemStateStillRestoresAfterUpdate() {
        val saved = save(Editor.Item(item, item.kind)).apply { remove("type") }
        assertEquals(Editor.Item(item, item.kind), EditorSaver.restore(parcel(saved)))
    }

    private fun assertRoundTrip(editor: Editor) {
        assertEquals(editor, EditorSaver.restore(parcel(save(editor))))
    }

    private fun save(editor: Editor): Bundle = with(EditorSaver) {
        requireNotNull(object : SaverScope {
            override fun canBeSaved(value: Any): Boolean = value is Bundle
        }.save(editor))
    }

    private fun parcel(saved: Bundle): Bundle {
        val parcel = Parcel.obtain()
        return try {
            saved.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }
}
