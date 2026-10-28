package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.QRCodeEncoder

/** Image-based, model-free controls for physical cells and source-anchored crop recipes. */
class OcrGridInstrumentedTest {
    @Before fun initializeOpenCv() { assertTrue(OpenCVLoader.initLocal()) }

    @Test fun tinyValidBlankImageUsesAnOddThresholdBlockLargerThanOne() = runBlocking {
        Mat(64, 64, CvType.CV_8UC3, WHITE).useTestMat { image ->
            val actual = OcrGrid.analyze(image, emptyList())
            assertTrue(actual.tables.isEmpty())
            assertTrue(actual.rescues.isEmpty())
        }
    }

    @Test fun qrAndReceiptTextDoNotCreatePhysicalTables() = runBlocking {
        page().useTestMat { receipt ->
            repeat(12) { row ->
                Imgproc.putText(receipt, "ITEM${row + 1}     ${row + 1}.00   ${row * 10 + 20}.00",
                    Point(100.0, 180.0 + 80 * row), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, BLACK, 2)
            }
            val actual = OcrGrid.analyze(receipt, emptyList())
            assertTrue(actual.tables.isEmpty())
            assertTrue(actual.rescues.isEmpty())
        }
        page().useTestMat { qrPage ->
            Mat().useTestMat { qr ->
                Mat().useTestMat { large ->
                    Mat().useTestMat { color ->
                        QRCodeEncoder.create().encode("MAMOKEEP-OFFLINE-OCR-GEOMETRY-CONTROL-2026", qr)
                        Imgproc.resize(qr, large, Size(620.0, 620.0), 0.0, 0.0, Imgproc.INTER_NEAREST)
                        Imgproc.cvtColor(large, color, Imgproc.COLOR_GRAY2BGR)
                        qrPage.submat(200, 820, 200, 820).useTestMat { color.copyTo(it) }
                    }
                }
            }
            val actual = OcrGrid.analyze(qrPage, emptyList())
            assertTrue(actual.tables.isEmpty())
            assertTrue(actual.rescues.isEmpty())
        }
    }

    @Test fun emptyGridHasRealRowsAndNoFabricatedInk() = runBlocking {
        ruledPage(100).useTestMat { image ->
            val actual = OcrGrid.analyze(image, emptyList())
            assertEquals(1, actual.tables.size)
            assertEquals(6, actual.cells.map { it.owner.row }.distinct().size)
            assertEquals(24, actual.cells.size)
            assertTrue(actual.rescues.isEmpty())
            assertTrue(actual.rejectedRows.isEmpty())
        }
    }

    @Test fun physicalInkIsRecoveredAndExistingWideBoxPreventsDuplicateFragments() = runBlocking {
        ruledPage(40).useTestMat { image ->
            repeat(6) { row ->
                for (x in listOf(120.0, 420.0, 670.0, 870.0)) {
                    Imgproc.putText(image, "ITEM 12.34", Point(x, 326.0 + 40 * row),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, BLACK, 1)
                }
            }
            val missing = OcrGrid.analyze(image, emptyList())
            assertEquals(24, missing.rescues.size)
            val recipe = missing.rescues.first()
            recipe.crop(image).useTestMat { crop ->
                assertEquals(recipe.width, crop.cols())
                assertEquals(recipe.height, crop.rows())
                assertTrue(recipe.box.all { it.x in 100.0..1100.0 && it.y in 300.0..540.0 })
                assertTrue(Core.mean(crop).`val`.take(3).all { it < 255.0 })
                crop.submat(0, 3, 0, crop.cols()).useTestMat { border ->
                    assertTrue(Core.mean(border).`val`.take(3).all { it == 255.0 })
                }
            }
            // One detector box spans the whole first physical row. Its center alone
            // occupies one cell, but its observed area must protect all four cells.
            val existing = listOf(listOf(OcrPoint(99.0, 299.0), OcrPoint(1101.0, 299.0),
                OcrPoint(1101.0, 341.0), OcrPoint(99.0, 341.0)))
            val occupied = OcrGrid.analyze(image, existing)
            assertEquals(20, occupied.rescues.size)
            assertFalse(occupied.rescues.any { it.cell.row == 0 })
        }
    }

    @Test fun aSpanningCellCannotJoinTwoPhysicalRows() = runBlocking {
        Mat(2200, 1650, CvType.CV_8UC3, WHITE).useTestMat { image ->
            for (y in 300..900 step 100) Imgproc.line(image,
                Point(if (y == 400) 400.0 else 100.0, y.toDouble()), Point(1100.0, y.toDouble()), BLACK, 2)
            for (x in listOf(100, 400, 650, 850, 1100)) Imgproc.line(image,
                Point(x.toDouble(), 300.0), Point(x.toDouble(), 900.0), BLACK, 2)
            val actual = OcrGrid.analyze(image, emptyList())
            assertEquals(listOf(GridRejectedRow(0, 0)), actual.rejectedRows)
            assertEquals(4, actual.cells.map { it.owner.row }.distinct().size)
            assertFalse(actual.cells.any { it.owner.row == 0 })
            assertTrue(actual.rescues.isEmpty())
            // Rejected-row geometry remains within the table envelope so its OCR
            // cannot fall back into ordinary grouping with another physical row.
            assertEquals(301.0, actual.tables.single().box.minOf { it.y }, 0.0)
        }
    }

    @Test fun smallDigitsInTallCellsRemainAnExplicitMissedRecoveryLimit() = runBlocking {
        ruledPage(100).useTestMat { image ->
            repeat(6) { row ->
                for ((column, x) in listOf(130.0, 440.0, 690.0, 890.0).withIndex()) {
                    Imgproc.putText(image, "${row + 1}.${column + 1}", Point(x, 360.0 + 100 * row),
                        Imgproc.FONT_HERSHEY_SIMPLEX, 0.8, BLACK, 2)
                }
            }
            val actual = OcrGrid.analyze(image, emptyList())
            assertEquals(24, actual.cells.size)
            assertTrue(actual.rescues.isEmpty())
        }
    }

    @Test fun aCellTooShortForTheInsetKeepsGeometryWithoutAnInvalidCrop() = runBlocking {
        page().useTestMat { image ->
            for (y in 300..324 step 4) Imgproc.line(image,
                Point(100.0, y.toDouble()), Point(1100.0, y.toDouble()), BLACK, 1)
            for (x in listOf(100, 400, 650, 850, 1100)) Imgproc.line(image,
                Point(x.toDouble(), 300.0), Point(x.toDouble(), 324.0), BLACK, 1)
            val actual = OcrGrid.analyze(image, emptyList())
            assertEquals(24, actual.cells.size)
            assertEquals(6, actual.cells.map { it.owner.row }.distinct().size)
            assertEquals(24, actual.unrectifiableCellIndices.size)
            assertTrue(actual.rescues.isEmpty())
        }
    }

    private fun page() = Mat(1600, 1200, CvType.CV_8UC3, WHITE)

    private fun ruledPage(rowHeight: Int): Mat = page().also { image ->
        for (y in 300..300 + 6 * rowHeight step rowHeight) Imgproc.line(image,
            Point(100.0, y.toDouble()), Point(1100.0, y.toDouble()), BLACK, 2)
        for (x in listOf(100, 400, 650, 850, 1100)) Imgproc.line(image,
            Point(x.toDouble(), 300.0), Point(x.toDouble(), (300 + 6 * rowHeight).toDouble()), BLACK, 2)
    }

    companion object {
        private val WHITE = Scalar(255.0, 255.0, 255.0)
        private val BLACK = Scalar(0.0, 0.0, 0.0)
    }
}

private inline fun <T : Mat, R> T.useTestMat(block: (T) -> R): R = try { block(this) } finally { release() }
