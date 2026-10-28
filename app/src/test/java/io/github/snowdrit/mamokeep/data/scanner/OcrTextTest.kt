package io.github.snowdrit.mamokeep.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OcrTextTest {
    @Test fun `dynamic recognizer time extent is accepted but batch class and resource limits are checked`() {
        assertEquals(40, ocrRecognitionSteps(longArrayOf(1, 40, 519), 1, 519))
        assertEquals(41, ocrRecognitionSteps(longArrayOf(6, 41, 519), 6, 519))
        for (shape in listOf(longArrayOf(1, 0, 519), longArrayOf(1, 401, 519), longArrayOf(2, 40, 519), longArrayOf(1, 40, 518), longArrayOf(40, 519))) {
            assertThrows(OcrException::class.java) { ocrRecognitionSteps(shape, 1, 519) }
        }
    }

    @Test fun `ctc keeps a repeated digit separated by blank and collapses adjacent duplicates`() {
        val characters = listOf("", "1", "0", " ")
        val path = listOf(1, 1, 0, 1, 3, 3, 2)
        val probabilities = FloatArray(path.size * characters.size) { 0.01f }
        path.forEachIndexed { step, symbol -> probabilities[step * characters.size + symbol] = 0.97f }
        val result = decodeOcr(probabilities, path.size, characters)
        assertEquals("11 0", result.text)
        assertEquals(0.97, result.confidence, 0.00001)
    }

    @Test fun `ctc all blank is an empty low confidence result`() {
        assertEquals(OcrDecoded("", 0.0), decodeOcr(floatArrayOf(1f, 0f, 1f, 0f), 2, listOf("", "a")))
    }

    @Test fun `ctc validates later batch offsets and invalid probabilities`() {
        assertEquals("a", decodeOcr(floatArrayOf(1f, 0f, 0f, 1f), 1, listOf("", "a"), 2).text)
        assertThrows(OcrException::class.java) { decodeOcr(floatArrayOf(Float.NaN, 1f), 1, listOf("", "a")) }
        assertThrows(OcrException::class.java) { decodeOcr(floatArrayOf(0f, 1f), 2, listOf("", "a")) }
    }

    @Test fun `dictionary preserves order and adds blank and space at exact positions`() {
        val dictionary = (0 until 517).map { "symbol$it" }
        val actual = ocrCharacters(dictionary)
        assertEquals(519, actual.size)
        assertEquals("", actual[0])
        assertEquals(dictionary[0], actual[1])
        assertEquals(dictionary[516], actual[517])
        assertEquals(" ", actual[518])
        assertThrows(OcrException::class.java) { ocrCharacters(dictionary.dropLast(1)) }
        assertThrows(OcrException::class.java) { ocrCharacters(dictionary.toMutableList().apply { this[1] = this[0] }) }
    }

    @Test fun `slanted distant columns stay in their physical row`() {
        fun tilted(text: String, x: Double, y: Double): OcrLine {
            val angle = 0.15
            val box = listOf(OcrPoint(x, y), OcrPoint(x + 40, y), OcrPoint(x + 40, y + 10), OcrPoint(x, y + 10))
                .map { OcrPoint(it.x * cos(angle) - it.y * sin(angle), it.x * sin(angle) + it.y * cos(angle)) }
            return OcrLine(text, 0.9, box)
        }
        val rows = groupOcrRows(listOf(tilted("20", 400.0, 30.0), tilted("Oil", 0.0, 0.0), tilted("10", 400.0, 0.0), tilted("Filter", 0.0, 30.0)))
        assertEquals(listOf("Oil 10", "Filter 20"), rows.map { it.text })
    }

    @Test fun `a tall cell cannot bridge independent rows`() {
        fun cell(text: String, x: Double, top: Double, bottom: Double) = OcrLine(text, .9,
            listOf(OcrPoint(x, top), OcrPoint(x + 10, top), OcrPoint(x + 10, bottom), OcrPoint(x, bottom)))
        val rows = groupOcrRows(listOf(cell("first", 0.0, 0.0, 10.0), cell("tall", 20.0, 0.0, 30.0), cell("second", 0.0, 20.0, 30.0)))
        assertEquals(listOf("first tall", "second"), rows.map { it.text })
    }

    private fun rectangle(x: Double, y: Double, width: Double, height: Double) = listOf(
        OcrPoint(x, y), OcrPoint(x + width, y), OcrPoint(x + width, y + height), OcrPoint(x, y + height),
    )

    private fun line(text: String, x: Double, y: Double, width: Double = 60.0, height: Double = 12.0) =
        OcrLine(text, .9, rectangle(x, y, width, height))

    @Test fun `same column slanted footer totals separate without changing line objects under rotation and input reversal`() {
        // Frozen TRAIN geometry: the global AABBs overlap, but the actual quad
        // bands share less than half their height at the common column.
        val first = OcrLine("parts total", .9, listOf(OcrPoint(171.0, 1887.0), OcrPoint(644.0, 1834.0),
            OcrPoint(649.0, 1879.0), OcrPoint(176.0, 1933.0)))
        val second = OcrLine("grand total", .9, listOf(OcrPoint(307.0, 1919.0), OcrPoint(660.0, 1874.0),
            OcrPoint(665.0, 1918.0), OcrPoint(313.0, 1963.0)))
        val anchors = listOf(line("a", 800.0, 0.0), line("b", 800.0, 100.0), line("c", 800.0, 200.0))
        for (angle in listOf(0.0, Math.toRadians(12.0), Math.toRadians(-12.0))) {
            val rotated = (listOf(first, second) + anchors).map { value -> value.copy(box = value.box.map {
                OcrPoint(it.x * cos(angle) - it.y * sin(angle), it.x * sin(angle) + it.y * cos(angle))
            }) }
            for (input in listOf(rotated, rotated.reversed())) {
                val rows = groupOcrRows(input)
                assertTrue(rows.none { row -> row.lines.any { it.text == "parts total" } && row.lines.any { it.text == "grand total" } })
                assertEquals(input.size, rows.sumOf { it.lines.size })
                assertTrue(input.all { source -> rows.flatMap { it.lines }.count { it === source } == 1 })
            }
        }
    }

    @Test fun `adjacent row fragments keep overlapping detector padding and the existing half overlap boundary`() {
        assertEquals(listOf("left right"), groupOcrRows(listOf(
            line("left", 0.0, 0.0, 80.0, 20.0), line("right", 70.0, 0.0, 80.0, 20.0),
        )).map { it.text })
        assertEquals(listOf("left right"), groupOcrRows(listOf(
            line("left", 0.0, 0.0, 80.0, 20.0), line("right", 10.0, 10.0, 80.0, 20.0),
        )).map { it.text })
    }

    @Test fun `short axis aligned fragments retain their page projected row under rotation and input reversal`() {
        // Frozen TRAIN geometry: these short boxes have zero own angle, but
        // CID and its value occupy one printed row on the slanted receipt.
        val fragments = listOf(
            OcrLine("CID:", .9, rectangle(283.0, 853.0, 92.0, 50.0)),
            OcrLine("80", .9, rectangle(1042.0, 900.0, 60.0, 48.0)),
        )
        val pageAngle = Math.toRadians(4.042920714306298)
        val anchors = (0..2).map { index ->
            OcrLine("anchor$index", .9, rectangle(0.0, index * 150.0, 500.0, 20.0).map {
                OcrPoint(it.x * cos(pageAngle) - it.y * sin(pageAngle),
                    it.x * sin(pageAngle) + it.y * cos(pageAngle))
            })
        }
        for (angle in listOf(0.0, Math.toRadians(12.0), Math.toRadians(-12.0))) {
            val rotated = (fragments + anchors).map { source -> source.copy(box = source.box.map {
                OcrPoint(it.x * cos(angle) - it.y * sin(angle), it.x * sin(angle) + it.y * cos(angle))
            }) }
            for (input in listOf(rotated, rotated.reversed())) {
                val rows = groupOcrRows(input)
                assertTrue(rows.any { it.text == "CID: 80" })
                assertEquals(input.size, rows.sumOf { it.lines.size })
                assertTrue(input.all { source -> rows.flatMap { it.lines }.count { it === source } == 1 })
            }
        }
    }

    @Test fun `refinement never reattaches a split fragment to a different old row`() {
        val first = OcrLine("first", .9, listOf(OcrPoint(0.0, 0.0), OcrPoint(120.0, -24.0),
            OcrPoint(120.0, -4.0), OcrPoint(0.0, 20.0)))
        val second = OcrLine("second", .9, listOf(OcrPoint(10.0, 20.0), OcrPoint(130.0, -4.0),
            OcrPoint(130.0, 16.0), OcrPoint(10.0, 40.0)))
        val input = listOf(first, second, line("unowned", 200.0, 20.0, 40.0, 20.0),
            line("a", 400.0, 100.0), line("b", 400.0, 200.0), line("c", 400.0, 300.0))
        val rows = groupOcrRows(input)
        assertTrue(rows.any { it.text == "first" })
        assertTrue(rows.any { it.text == "second" })
        assertTrue(rows.any { it.text == "unowned" })
        assertEquals(input.size, rows.sumOf { it.lines.size })
    }

    @Test fun `physical cell ownership bypasses fallback quad refinement for multiline text`() {
        val first = OcrLine("top", .9, listOf(OcrPoint(0.0, 0.0), OcrPoint(120.0, -24.0),
            OcrPoint(120.0, -4.0), OcrPoint(0.0, 20.0)))
        val second = OcrLine("bottom", .9, listOf(OcrPoint(10.0, 20.0), OcrPoint(130.0, -4.0),
            OcrPoint(130.0, 16.0), OcrPoint(10.0, 40.0)))
        val table = OcrGridTable(0, rectangle(-10.0, -30.0, 150.0, 80.0),
            listOf(OcrGridCell(0, 0, 0, rectangle(-10.0, -30.0, 150.0, 80.0))))
        val input = listOf(first, second, line("a", 400.0, 100.0), line("b", 400.0, 200.0), line("c", 400.0, 300.0))
        assertTrue(groupOcrRows(input, grid = listOf(table)).any { it.text == "top bottom" })
    }

    @Test fun `printed curved rows keep numbers with their own description`() {
        val cells = listOf(
            OcrGridCell(0, 0, 0, rectangle(0.0, 0.0, 100.0, 20.0)),
            OcrGridCell(0, 0, 1, rectangle(200.0, 20.0, 100.0, 20.0)),
            OcrGridCell(0, 1, 0, rectangle(0.0, 20.0, 100.0, 20.0)),
            OcrGridCell(0, 1, 1, rectangle(200.0, 40.0, 100.0, 20.0)),
        )
        val input = listOf(line("ring", 10.0, 4.0), line("oil", 10.0, 24.0),
            line("100", 220.0, 24.0), line("950", 220.0, 44.0))
        assertEquals(listOf("ring", "oil 100", "950"), groupOcrRows(input).map { it.text })
        val rows = groupOcrRows(input, grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 300.0, 60.0), cells)))
        assertEquals(listOf("ring 100", "oil 950"), rows.map { it.text })
        assertEquals(input.toSet(), rows.flatMap { it.lines }.toSet())
        assertEquals(input.size, rows.sumOf { it.lines.size })
    }

    @Test fun `a later cleaner row never borrows the preceding rod total`() {
        val cells = listOf(
            OcrGridCell(0, 13, 0, rectangle(0.0, 0.0, 100.0, 20.0)),
            OcrGridCell(0, 13, 1, rectangle(200.0, 20.0, 100.0, 20.0)),
            OcrGridCell(0, 14, 0, rectangle(0.0, 20.0, 100.0, 20.0)),
            OcrGridCell(0, 14, 1, rectangle(200.0, 40.0, 100.0, 20.0)),
        )
        val rows = groupOcrRows(listOf(line("rod", 10.0, 4.0), line("cleaner", 10.0, 24.0),
            line("5000", 220.0, 24.0), line("900", 220.0, 44.0)),
            grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 300.0, 60.0), cells)))
        assertEquals(listOf("rod 5000", "cleaner 900"), rows.map { it.text })
    }

    @Test fun `tall ambiguous OCR polygon stays separate from both printed rows`() {
        val cells = (0..1).flatMap { row -> (0..1).map { column ->
            OcrGridCell(0, row, column, rectangle(column * 100.0, row * 20.0, 100.0, 20.0))
        } }
        val rows = groupOcrRows(listOf(line("top", 110.0, 4.0), line("bottom", 110.0, 24.0),
            line("tall", 10.0, 0.0, height = 40.0)),
            grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 200.0, 40.0), cells)))
        assertEquals(listOf("top", "tall", "bottom"), rows.map { it.text })
    }

    @Test fun `missing cell inside an accepted table never uses a nearby row`() {
        val cells = listOf(OcrGridCell(0, 0, 0, rectangle(0.0, 0.0, 100.0, 20.0)))
        val rows = groupOcrRows(listOf(line("description", 10.0, 4.0), line("10700", 220.0, 4.0)),
            grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 300.0, 20.0), cells)))
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.text == "description" })
        assertTrue(rows.any { it.text == "10700" })
    }

    @Test fun `a rejected spanning grid row keeps its OCR lines separate`() {
        // Grid analysis rejects a raw row bridged by a spanning cell. Its envelope
        // remains, so the original overlap rule cannot merge the rejected lines.
        val rows = groupOcrRows(listOf(line("first", 10.0, 4.0), line("second", 110.0, 4.0)),
            grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 200.0, 20.0), emptyList())))
        assertEquals(listOf("first", "second"), rows.map { it.text })
    }

    @Test fun `independent table columns use stable blocks and physical row order`() {
        val tables = (0..1).map { table ->
            OcrGridTable(table, rectangle(table * 200.0, 0.0, 100.0, 40.0), (0..1).map { row ->
                OcrGridCell(table, row, 0, rectangle(table * 200.0, row * 20.0, 100.0, 20.0))
            })
        }
        val input = listOf(line("R1", 210.0, 24.0), line("L1", 10.0, 24.0),
            line("R0", 210.0, 4.0), line("L0", 10.0, 4.0))
        assertEquals(listOf("L0", "L1", "R0", "R1"), groupOcrRows(input, grid = tables.reversed()).map { it.text })
    }

    @Test fun `same cell multiline fragments read vertically before the next column`() {
        val cells = listOf(OcrGridCell(0, 0, 0, rectangle(0.0, 0.0, 100.0, 40.0)),
            OcrGridCell(0, 0, 1, rectangle(100.0, 0.0, 100.0, 40.0)))
        val input = listOf(line("second", 5.0, 22.0), line("value", 110.0, 12.0), line("first", 15.0, 4.0))
        assertEquals(listOf("first second value"),
            groupOcrRows(input, grid = listOf(OcrGridTable(0, rectangle(0.0, 0.0, 200.0, 40.0), cells))).map { it.text })
    }

    @Test fun `a header spanning columns keeps its left position despite a tiny neighboring overlap`() {
        fun box(vararg points: Pair<Int, Int>) = points.map { OcrPoint(it.first.toDouble(), it.second.toDouble()) }
        val cells = listOf(
            box(107 to 773, 255 to 770, 258 to 795, 111 to 799),
            box(258 to 771, 835 to 765, 836 to 789, 261 to 796),
            box(838 to 766, 889 to 765, 890 to 789, 838 to 790),
            box(892 to 766, 950 to 766, 951 to 790, 893 to 791),
            box(953 to 767, 1044 to 767, 1046 to 791, 953 to 791),
            box(1047 to 769, 1141 to 768, 1142 to 791, 1049 to 793),
            box(1144 to 769, 1260 to 767, 1261 to 791, 1145 to 792),
            box(1263 to 768, 1365 to 767, 1367 to 790, 1264 to 792),
            box(1368 to 768, 1479 to 766, 1480 to 791, 1370 to 792),
        ).mapIndexed { column, points -> OcrGridCell(0, 0, column, points) }
        // Saved development geometry: the spanning fragment overlaps only 0.34%
        // of the description cell, but starts eight pixels above its text.
        val input = listOf(
            OcrLine("code", .9, box(114 to 771, 253 to 768, 254 to 800, 115 to 803)),
            OcrLine("description", .9, box(445 to 768, 669 to 768, 669 to 794, 445 to 794)),
            OcrLine("coefficients", .9, box(835 to 760, 1051 to 764, 1050 to 797, 834 to 793)),
            OcrLine("time", .9, box(1055 to 766, 1134 to 766, 1134 to 794, 1055 to 794)),
            OcrLine("cost", .9, box(1142 to 767, 1263 to 767, 1263 to 793, 1142 to 793)),
            OcrLine("discount", .9, box(1272 to 764, 1357 to 766, 1357 to 796, 1271 to 794)),
            OcrLine("sum", .9, box(1385 to 764, 1466 to 764, 1466 to 795, 1385 to 795)),
        )
        val rows = groupOcrRows(input, grid = listOf(OcrGridTable(0, rectangle(107.0, 765.0, 1373.0, 40.0), cells)))
        assertEquals(listOf("code description coefficients time cost discount sum"), rows.map { it.text })
        assertEquals(input.size, rows.sumOf { it.lines.size })
    }

    @Test fun `receipts without accepted grids use the same fallback grouping`() {
        val input = listOf(line("Fuel", 0.0, 0.0), line("20L", 200.0, 0.0), line("Total", 0.0, 30.0))
        val original = groupOcrRows(input)
        assertEquals(original, groupOcrRows(input, grid = emptyList()))
        val distant = OcrGridTable(0, rectangle(500.0, 500.0, 100.0, 100.0), emptyList())
        assertEquals(original, groupOcrRows(input, grid = listOf(distant)))
        val repeated = line("same", 10.0, 10.0)
        val table = OcrGridTable(0, rectangle(0.0, 0.0, 100.0, 40.0),
            listOf(OcrGridCell(0, 0, 0, rectangle(0.0, 0.0, 100.0, 40.0))))
        assertEquals(2, groupOcrRows(listOf(repeated, repeated), grid = listOf(table)).sumOf { it.lines.size })
    }

    @Test fun `polygon membership is invariant to envelope winding`() {
        val text = rectangle(10.0, 10.0, 20.0, 20.0)
        val cell = rectangle(0.0, 0.0, 20.0, 40.0)
        assertEquals(200.0, ocrIntersectionArea(text, cell), 0.000001)
        assertEquals(200.0, ocrIntersectionArea(text, cell.reversed()), 0.000001)
    }

    @Test fun `unclip matches synthetic Pyclipper enclosing rectangles within integer rounding tolerance`() {
        // Generated from installed RapidOCR 3.9.2 / Pyclipper 1.4.0 / OpenCV 5.0.0.
        // Inputs are synthetic rotated rectangles, never private receipt images.
        val fixtures = listOf(
            listOf(80.0 to 85.0, 80.0 to 75.0, 120.0 to 75.0, 120.0 to 85.0) to listOf(126.0 to 91.0, 74.0 to 91.0, 74.0 to 69.0, 126.0 to 69.0),
            listOf(298.626343 to 294.556366, 306.499634 to 260.4534, 501.373657 to 305.443634, 493.500366 to 339.5466) to listOf(510.056213 to 366.089874, 270.943848 to 310.910126, 288.943848 to 232.910126, 528.056213 to 288.089874),
            listOf(68.859695 to 116.356369, 66.237442 to 109.866089, 131.140305 to 83.643631, 133.762558 to 90.133911) to listOf(65.0 to 121.999992, 59.0 to 106.999992, 134.0 to 76.999992, 140.0 to 91.999992),
            listOf(35.871922 to 102.947868, 60.297855 to 73.838188, 64.128075 to 77.052132, 39.702145 to 106.161812) to listOf(39.868347 to 110.47271, 30.175308 to 102.116646, 59.131653 to 68.52729, 68.824692 to 76.883354),
            listOf(89.393402 to 100.0, 100.0 to 89.393402, 110.606598 to 100.0, 100.0 to 110.606598) to listOf(99.500015 to 118.500008, 81.000015 to 100.000008, 100.000015 to 81.000008, 118.500015 to 99.500008),
            listOf(100.84623 to 80.000244, 106.787834 to 79.165207, 109.15377 to 95.999756, 103.212166 to 96.834793) to listOf(100.520744 to 99.777367, 96.343391 to 77.498116, 108.47924 to 75.222649, 112.656593 to 97.5019),
        )
        fixtures.forEach { (input, expected) ->
            val actual = expandOcrRectangle(input.map { OcrPoint(it.first, it.second) }, 1.5)
            for ((x, y) in expected) {
                val difference = actual.minOf { Math.hypot(it.x - x, it.y - y) }
                assertTrue("Pyclipper rounding difference $difference", difference <= 2.0)
            }
        }
    }
}
