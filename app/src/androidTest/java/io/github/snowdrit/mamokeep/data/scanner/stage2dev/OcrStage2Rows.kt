/*
 * CTC decoding adapted from PaddleOCR / RapidOCR 3.9.2 (Apache-2.0).
 * Copyright (c) 2020 PaddlePaddle Authors. All Rights Reserved.
 * Copyright (c) 2021 RapidOCR Authors. All rights reserved.
 * MamoKeep modifications: pure Kotlin decoder, geometry grouping, synthetic-tested
 * analytic rectangle offset. Full license: assets/licenses/Apache-2.0.txt.
 */
package io.github.snowdrit.mamokeep.data.scanner.stage2dev

import io.github.snowdrit.mamokeep.data.scanner.OcrException
import io.github.snowdrit.mamokeep.data.scanner.OcrFailure
import io.github.snowdrit.mamokeep.data.scanner.OcrGridTable
import io.github.snowdrit.mamokeep.data.scanner.OcrLine
import io.github.snowdrit.mamokeep.data.scanner.OcrPoint
import io.github.snowdrit.mamokeep.data.scanner.OcrRow

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

internal const val FROZEN_STAGE2_OCR_TEXT_SHA256 = "634236ab0b466c69e6b49692b981e7942206dcb4cf9ee02a0d39c2fa58e92215"

/** PP-OCRv5 East Slavic's index order: CTC blank, 517 dictionary characters, space. */
internal fun ocrCharacters(dictionary: List<String>): List<String> {
    if (dictionary.size != 517 || dictionary.distinct().size != 517 ||
        dictionary.any { it.isEmpty() || it == " " || it == "blank" }) {
        throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Unexpected East Slavic character dictionary")
    }
    return listOf("") + dictionary + " "
}

internal data class OcrDecoded(val text: String, val confidence: Double)

/** The recognizer declares a dynamic T; it cannot be inferred with ceil(width / 8). */
internal fun ocrRecognitionSteps(shape: LongArray, batch: Int, characters: Int): Int {
    if (shape.size != 3 || shape[0] != batch.toLong() || shape[2] != characters.toLong() || shape[1] !in 1L..400L) {
        throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Invalid recognizer output extent")
    }
    return shape[1].toInt()
}

/** Greedy CTC, including a blank between identical characters (11 must not become 1). */
internal fun decodeOcr(values: FloatArray, steps: Int, characters: List<String>, offset: Int = 0): OcrDecoded {
    val classes = characters.size
    if (steps <= 0 || classes == 0 || offset < 0 || offset.toLong() + steps.toLong() * classes > values.size) {
        throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Invalid recognizer output extent")
    }
    val text = StringBuilder()
    var previous = -1
    var confidence = 0.0
    var accepted = 0
    for (step in 0 until steps) {
        val start = offset + step * classes
        var selected = 0
        var maximum = Float.NEGATIVE_INFINITY
        for (index in 0 until classes) {
            val probability = values[start + index]
            if (!probability.isFinite() || probability < 0f || probability > 1f) {
                throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Invalid recognizer probability")
            }
            if (probability > maximum) { maximum = probability; selected = index }
        }
        if (selected != 0 && selected != previous) {
            text.append(characters[selected])
            // RapidOCR rounds each accepted probability before averaging.
            confidence += round(maximum.toDouble() * 100000) / 100000
            accepted++
        }
        previous = selected
    }
    return OcrDecoded(text.toString(), if (accepted == 0) 0.0 else round(confidence / accepted * 100000) / 100000)
}

/** Printed grid geometry takes precedence over a page-wide text angle. */
internal fun groupOcrRows(
    lines: List<OcrLine>,
    overlap: Double = 0.5,
    grid: List<OcrGridTable>? = null,
): List<OcrRow> {
    val originalRows = overlapOcrRows(lines, overlap)
    if (lines.isEmpty() || grid.isNullOrEmpty()) return originalRows.map { row -> OcrRow(row.map(lines::get)) }
    data class Key(val table: Int, val row: Int)
    data class Owner(val key: Key, val left: Double)
    data class Entry(val slot: Int, val top: Double, val left: Double, val indices: List<Int>)
    data class Block(val top: Double, val left: Double, val order: Int, val rows: List<OcrRow>)
    val owners = arrayOfNulls<Owner>(lines.size)
    val outside = BooleanArray(lines.size) { true }
    val orphans = mutableMapOf<Int, MutableList<Int>>()
    val ambiguousTables = mutableListOf<Int>()
    val cells = grid.flatMap { it.cells }
    for ((index, line) in lines.withIndex()) {
        val area = ocrPolygonArea(line.box)
        if (area <= 0) throw OcrException(OcrFailure.ENGINE, "Degenerate OCR line polygon")
        val weights = cells.map { it to ocrIntersectionArea(line.box, it.box) / area }
            .filter { it.second > 0 }
        val rows = weights.groupBy { Key(it.first.table, it.first.row) }
            .mapValues { (_, members) -> members.sumOf { it.second } }
        val owner = rows.filterValues { it > 0.5 }.keys.singleOrNull()
        if (owner != null) {
            val cell = weights.filter { Key(it.first.table, it.first.row) == owner && it.second > 0.5 }
                .singleOrNull()?.first
            // Same-cell fragments read vertically. A header spanning columns has
            // its own horizontal position; a tiny neighboring overlap is not a cell owner.
            val left = if (cell != null) cell.box.minOf { it.x } else line.box.minOf { it.x }
            owners[index] = Owner(owner, left)
            outside[index] = false
        } else {
            // A missing cell or a tall polygon crossing rows does not prove a row.
            // Keep it separate even when the old overlap heuristic would merge it.
            val tables = grid.filter { ocrIntersectionArea(line.box, it.box) / area > 0.5 }
            if (tables.size == 1) {
                orphans.getOrPut(tables.single().table) { mutableListOf() }.add(index)
                outside[index] = false
            } else if (tables.size > 1) {
                ambiguousTables.add(index)
                outside[index] = false
            }
        }
    }
    val blocks = mutableListOf<Block>()
    for (row in originalRows) {
        val indices = row.filter { outside[it] }
        if (indices.isNotEmpty()) {
            blocks.add(Block(indices.minOf { i -> lines[i].box.minOf { it.y } },
                indices.minOf { i -> lines[i].box.minOf { it.x } }, indices.first(),
                listOf(OcrRow(indices.map(lines::get)))))
        }
    }
    for (table in grid) {
        val physicalRows = table.cells.map { it.row }.distinct().sorted()
        var previousAnchor = Double.NEGATIVE_INFINITY
        val anchors = physicalRows.map { row ->
            val centers = table.cells.filter { it.row == row }.map { cell -> cell.box.map { it.y }.average() }.sorted()
            val middle = centers.size / 2
            val measured = if (centers.size % 2 == 0) (centers[middle - 1] + centers[middle]) / 2 else centers[middle]
            // Physical row IDs provide the order even when a curved edge crosses
            // another row's absolute y coordinate in a distant column.
            previousAnchor = maxOf(previousAnchor, measured)
            previousAnchor
        }
        val entries = mutableListOf<Entry>()
        for ((position, row) in physicalRows.withIndex()) {
            val indices = lines.indices.filter { owners[it]?.key == Key(table.table, row) }
                .sortedWith(compareBy<Int> { requireNotNull(owners[it]).left }
                    .thenBy { i -> lines[i].box.minOf { it.y } }
                    .thenBy { i -> lines[i].box.minOf { it.x } }.thenBy { it })
            if (indices.isNotEmpty()) entries.add(Entry(position * 2 + 1, 0.0, 0.0, indices))
        }
        for (index in orphans[table.table].orEmpty()) {
            val center = lines[index].box.map { it.y }.average()
            val before = anchors.indexOfFirst { it >= center }
            val slot = if (before < 0) anchors.size else before
            entries.add(Entry(slot * 2, center, lines[index].box.minOf { it.x }, listOf(index)))
        }
        val rows = entries.sortedWith(compareBy<Entry> { it.slot }.thenBy { it.top }.thenBy { it.left }
            .thenBy { it.indices.first() }).map { entry -> OcrRow(entry.indices.map(lines::get)) }
        if (rows.isNotEmpty()) blocks.add(Block(table.box.minOf { it.y }, table.box.minOf { it.x },
            lines.size + table.table, rows))
    }
    for (index in ambiguousTables) {
        val line = lines[index]
        blocks.add(Block(line.box.minOf { it.y }, line.box.minOf { it.x }, index, listOf(OcrRow(listOf(line)))))
    }
    return blocks.sortedWith(compareBy<Block> { it.top }.thenBy { it.left }.thenBy { it.order }).flatMap { it.rows }
}

/** Same baseline projection and all-members overlap rule as engines.py:group_rows. */
private fun overlapOcrRows(lines: List<OcrLine>, overlap: Double): List<List<Int>> {
    require(overlap > 0 && overlap <= 1)
    if (lines.isEmpty()) return emptyList()
    lines.forEach { line ->
        if (line.box.size != 4 || line.box.any { !it.x.isFinite() || !it.y.isFinite() } ||
            !line.confidence.isFinite()) throw OcrException(OcrFailure.ENGINE, "Invalid OCR line geometry")
    }
    val angles = lines.map { atan2(it.box[1].y - it.box[0].y, it.box[1].x - it.box[0].x) }.sorted()
    val mid = angles.size / 2
    val angle = if (angles.size % 2 == 0) (angles[mid - 1] + angles[mid]) / 2 else angles[mid]
    val cosine = cos(angle)
    val sine = sin(angle)
    data class Cell(val index: Int, val left: Double, val top: Double, val bottom: Double)
    val cells = lines.mapIndexed { index, line ->
        val points = line.box.map { OcrPoint(it.x * cosine + it.y * sine, -it.x * sine + it.y * cosine) }
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        if (bottom <= top || points.maxOf { it.x } <= left) {
            throw OcrException(OcrFailure.ENGINE, "Degenerate OCR line geometry")
        }
        Cell(index, left, top, bottom)
    }.sortedWith(compareBy<Cell> { it.top }.thenBy { it.left })
    val groups = mutableListOf<MutableList<Cell>>()
    for (cell in cells) {
        val group = groups.asReversed().firstOrNull { members ->
            members.all { other ->
                val shared = minOf(cell.bottom, other.bottom) - maxOf(cell.top, other.top)
                shared / minOf(cell.bottom - cell.top, other.bottom - other.top) >= overlap
            }
        }
        if (group == null) groups.add(mutableListOf(cell)) else group.add(cell)
    }
    return groups.map { group -> group.sortedBy { it.left }.map { it.index } }
}

internal fun ocrPolygonArea(polygon: List<OcrPoint>): Double = kotlin.math.abs(
    polygon.indices.sumOf { index ->
        val next = polygon[(index + 1) % polygon.size]
        polygon[index].x * next.y - next.x * polygon[index].y
    }
) / 2

/** Sutherland-Hodgman clipping; accepted printed cell envelopes are convex. */
internal fun ocrIntersectionArea(subject: List<OcrPoint>, clip: List<OcrPoint>): Double {
    val signed = clip.indices.sumOf { index ->
        val next = clip[(index + 1) % clip.size]
        clip[index].x * next.y - next.x * clip[index].y
    }
    val direction = if (signed >= 0) 1.0 else -1.0
    fun side(a: OcrPoint, b: OcrPoint, point: OcrPoint): Double = direction *
        ((b.x - a.x) * (point.y - a.y) - (b.y - a.y) * (point.x - a.x))
    var points = subject
    for (index in clip.indices) {
        if (points.isEmpty()) break
        val a = clip[index]
        val b = clip[(index + 1) % clip.size]
        val result = mutableListOf<OcrPoint>()
        var previous = points.last()
        var previousSide = side(a, b, previous)
        for (current in points) {
            val currentSide = side(a, b, current)
            if ((currentSide >= 0) != (previousSide >= 0)) {
                val fraction = previousSide / (previousSide - currentSide)
                result.add(OcrPoint(previous.x + fraction * (current.x - previous.x),
                    previous.y + fraction * (current.y - previous.y)))
            }
            if (currentSide >= 0) result.add(current)
            previous = current
            previousSide = currentSide
        }
        points = result
    }
    return ocrPolygonArea(points)
}

/**
 * DB unclip starts with minAreaRect, not an arbitrary contour. The enclosing rectangle of
 * its round offset is this rectangle expanded on all sides by area * ratio / perimeter.
 * Unlike Pyclipper, this keeps floating coordinates until OpenCV; fixtures allow <= 2 px
 * for Pyclipper's integer input/output rounding. No character rectangle is shrunk.
 */
internal fun expandOcrRectangle(box: List<OcrPoint>, ratio: Double): List<OcrPoint> {
    require(box.size == 4 && ratio > 0)
    val width = Math.hypot(box[1].x - box[0].x, box[1].y - box[0].y)
    val height = Math.hypot(box[3].x - box[0].x, box[3].y - box[0].y)
    require(width > 0 && height > 0)
    val distance = width * height * ratio / (2 * (width + height))
    val along = OcrPoint((box[1].x - box[0].x) / width * distance, (box[1].y - box[0].y) / width * distance)
    val down = OcrPoint((box[3].x - box[0].x) / height * distance, (box[3].y - box[0].y) / height * distance)
    return listOf(
        OcrPoint(box[0].x - along.x - down.x, box[0].y - along.y - down.y),
        OcrPoint(box[1].x + along.x - down.x, box[1].y + along.y - down.y),
        OcrPoint(box[2].x + along.x + down.x, box[2].y + along.y + down.y),
        OcrPoint(box[3].x - along.x + down.x, box[3].y - along.y + down.y),
    )
}
