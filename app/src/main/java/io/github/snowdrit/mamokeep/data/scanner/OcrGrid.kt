/*
 * Physical-cell geometry adapted from this project's frozen research pipeline:
 * integrated-pipeline-v4/source/{grid_cells,prepare_grid_crops}.py and the TRAIN
 * detection-closed-cells-v3 prototype (23 October 2026). No OCR text or labels
 * participate in geometry. OpenCV operations use its Apache-2.0 licensed runtime.
 */
package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

internal data class GridLineBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal data class GridCellGeometry(
    val index: Int,
    val owner: OcrGridCell,
    val bounds: Rect,
    val contour: List<OcrPoint>,
)

internal data class GridRejectedRow(val table: Int, val row: Int)

internal data class GridAnalysis(
    val tables: List<OcrGridTable>,
    val cells: List<GridCellGeometry>,
    val rescues: List<GridRescue>,
    val rejectedRows: List<GridRejectedRow>,
    val candidateCount: Int,
    val unrectifiableCellIndices: List<Int>,
)

/** A source-anchored crop recipe; no native Mat survives analysis. Caller releases crop(). */
internal class GridRescue internal constructor(
    val cell: OcrGridCell,
    val cellIndex: Int,
    val box: List<OcrPoint>,
    val bounds: GridLineBounds,
    private val recipe: GridRectification,
) {
    val width: Int get() = bounds.right - bounds.left + 6
    val height: Int get() = bounds.bottom - bounds.top + 6

    fun crop(source: Mat): Mat = recipe.crop(source).useGridMat { cellImage ->
        cellImage.submat(bounds.top, bounds.bottom, bounds.left, bounds.right).useGridMat { line ->
            val result = Mat()
            try {
                Core.copyMakeBorder(line, result, 3, 3, 3, 3, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0))
                result
            } catch (error: Throwable) { result.release(); throw error }
        }
    }
}

/** Column-wise rectification follows observed curved cell boundaries, without a page angle. */
internal class GridRectification(
    private val sourceX: Int,
    private val sourceY: Int,
    private val left: Int,
    private val top: IntArray,
    private val bottom: IntArray,
    val width: Int,
    val height: Int,
) {
    private fun sourcePoint(x: Int, y: Int): OcrPoint {
        // numpy.linspace(..., dtype=float32), then the source pipeline's final float32 map.
        val step = if (height == 1) 0.0 else (y.toDouble() / (height - 1)).toFloat().toDouble()
        val column = left + x
        val sy = (sourceY + top[column] + BORDER + step * (bottom[column] - top[column] - 2 * BORDER)).toFloat()
        return OcrPoint((sourceX + column).toDouble(), sy.toDouble())
    }

    fun sourceBox(bounds: GridLineBounds): List<OcrPoint> = listOf(
        sourcePoint(bounds.left, bounds.top), sourcePoint(bounds.right - 1, bounds.top),
        sourcePoint(bounds.right - 1, bounds.bottom - 1), sourcePoint(bounds.left, bounds.bottom - 1),
    )

    fun crop(source: Mat): Mat {
        val mx = FloatArray(width * height)
        val my = FloatArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val point = sourcePoint(x, y)
            mx[y * width + x] = point.x.toFloat()
            my[y * width + x] = point.y.toFloat()
        }
        return Mat(height, width, CvType.CV_32FC1).useGridMat { mapX ->
            Mat(height, width, CvType.CV_32FC1).useGridMat { mapY ->
                mapX.put(0, 0, mx)
                mapY.put(0, 0, my)
                val result = Mat()
                try {
                    Imgproc.remap(source, result, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                    result
                } catch (error: Throwable) { result.release(); throw error }
            }
        }
    }

    companion object { private const val BORDER = 2 }
}

/** Closed-cell detector rescue; ordinary detector boxes always remain authoritative inputs. */
internal object OcrGrid {
    private data class Cell(val bounds: Rect, val contour: List<OcrPoint>, val box: List<OcrPoint>)

    suspend fun analyze(source: Mat, boxes: List<List<OcrPoint>>): GridAnalysis {
        require(source.type() == CvType.CV_8UC3 && !source.empty() && maxOf(source.rows(), source.cols()) <= OCR_MAX_IMAGE_SIDE)
        val cells = detectCells(source)
        val rawTables = tables(cells, maxOf(source.rows(), source.cols()).toDouble())
        val rejected = mutableListOf<GridRejectedRow>()
        val accepted = mutableListOf<GridCellGeometry>()
        val resultTables = rawTables.mapIndexed { ti, table ->
            currentCoroutineContext().ensureActive()
            val owners = mutableListOf<OcrGridCell>()
            table.forEachIndexed { ri, row ->
                // A spanning cell can connect two physical rows. Abstain on that row;
                // do not split it or assign its numbers from horizontal overlap alone.
                val sameColumn = row.indices.any { ai ->
                    row.drop(ai + 1).any { b ->
                        val a = cells[row[ai]].box
                        val other = cells[b].box
                        val shared = minOf(a.maxOf { it.x }, other.maxOf { it.x }) - maxOf(a.minOf { it.x }, other.minOf { it.x })
                        shared / minOf(a.maxOf { it.x } - a.minOf { it.x }, other.maxOf { it.x } - other.minOf { it.x }) >= 0.5
                    }
                }
                if (sameColumn) rejected.add(GridRejectedRow(ti, ri)) else row.forEachIndexed { ci, index ->
                    val cell = cells[index]
                    val owner = OcrGridCell(ti, ri, ci, cell.box)
                    owners.add(owner)
                    accepted.add(GridCellGeometry(index, owner, cell.bounds, cell.contour))
                }
            }
            val points = table.flatten().flatMap { cells[it].box }
            OcrGridTable(ti, extent(points), owners)
        }
        val rescues = mutableListOf<GridRescue>()
        val unrectifiable = mutableListOf<Int>()
        for (cell in accepted) {
            currentCoroutineContext().ensureActive()
            if (occupied(cell.owner.box, boxes)) continue
            val recipe = rectification(cell)
            if (recipe == null) {
                unrectifiable.add(cell.index)
                continue // Physical cells shorter than the declared 2px inset retain owners, but cannot supply a crop.
            }
            recipe.crop(source).useGridMat { crop ->
                for (bounds in textLines(crop)) {
                    rescues.add(GridRescue(cell.owner, cell.index, recipe.sourceBox(bounds), bounds, recipe))
                }
            }
        }
        return GridAnalysis(resultTables, accepted, rescues, rejected, cells.size, unrectifiable)
    }

    private suspend fun detectCells(source: Mat): List<Cell> {
        val contours = mutableListOf<MatOfPoint>()
        try {
            return Mat().useGridMat { gray ->
                Mat().useGridMat { dark ->
                    Mat().useGridMat { hierarchy ->
                        Imgproc.cvtColor(source, gray, Imgproc.COLOR_BGR2GRAY)
                        val h = gray.rows()
                        val w = gray.cols()
                        val scale = maxOf(h, w)
                        Imgproc.adaptiveThreshold(gray, dark, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                            Imgproc.THRESH_BINARY_INV, maxOf(3, (scale * 0.025).toInt() or 1), 12.0)
                        Imgproc.findContours(dark, contours, hierarchy, Imgproc.RETR_TREE, Imgproc.CHAIN_APPROX_SIMPLE)
                        val parents = IntArray(contours.size * 4)
                        if (parents.isNotEmpty()) hierarchy.get(0, 0, parents)
                        val cells = mutableListOf<Cell>()
                        for ((index, contour) in contours.withIndex()) {
                            currentCoroutineContext().ensureActive()
                            var depth = 0
                            var parent = parents[index * 4 + 3]
                            while (parent >= 0) { depth++; parent = parents[parent * 4 + 3] }
                            if (depth % 2 != 1) continue
                            val bounds = Imgproc.boundingRect(contour)
                            val area = Imgproc.contourArea(contour)
                            if (bounds.width < w * 0.008 || bounds.height < h * 0.003 || bounds.height > h * 0.12 ||
                                area < h.toDouble() * w * 0.00006 || area / (bounds.width.toDouble() * bounds.height) < 0.25) continue
                            val points = contour.toArray().map { OcrPoint(it.x, it.y) }
                            cells.add(Cell(bounds, points, corners(points)))
                        }
                        cells
                    }
                }
            }
        } finally { contours.forEach { it.release() } }
    }

    private fun corners(points: List<OcrPoint>): List<OcrPoint> = listOf(
        points.minBy { it.x + it.y }, points.maxBy { it.x - it.y },
        points.maxBy { it.x + it.y }, points.minBy { it.x - it.y },
    )

    private suspend fun tables(cells: List<Cell>, scale: Double): List<List<List<Int>>> {
        val horizontal = mutableListOf<Pair<Int, Int>>()
        val vertical = mutableListOf<Pair<Int, Int>>()
        val gap = scale * 0.006
        for (i in cells.indices) {
            currentCoroutineContext().ensureActive()
            for (j in cells.indices) {
                if (i == j) continue
                val a = cells[i].box
                val b = cells[j].box
                val dx = (b[0].x + b[3].x - a[1].x - a[2].x) / 2
                if (dx > 0 && dx < gap && sideOverlap(a[1].y, a[2].y, b[0].y, b[3].y) >= 0.65) horizontal.add(i to j)
                val dy = (b[0].y + b[1].y - a[2].y - a[3].y) / 2
                if (dy > 0 && dy < gap && sideOverlap(a[3].x, a[2].x, b[0].x, b[1].x) >= 0.65) vertical.add(i to j)
            }
        }
        val rows = partition(cells.size, horizontal).filter { it.size >= 3 }
        val owner = rows.flatMapIndexed { row, indices -> indices.map { it to row } }.toMap()
        val rowEdges = vertical.mapNotNull { (a, b) ->
            val ar = owner[a]
            val br = owner[b]
            if (ar != null && br != null && ar != br) ar to br else null
        }
        return partition(rows.size, rowEdges).filter { it.size >= 2 }.map { table ->
            table.map { rows[it] }.sortedBy { row -> row.map { cells[it].bounds.y }.average() }
                .map { row -> row.sortedBy { cells[it].bounds.x } }
        }.sortedBy { table -> table[0].map { cells[it].bounds.y }.average() }
    }

    private fun sideOverlap(a0: Double, a1: Double, b0: Double, b1: Double): Double {
        val extent = minOf(a1 - a0, b1 - b0)
        return if (extent > 0) (minOf(a1, b1) - maxOf(a0, b0)) / extent else 0.0
    }

    private fun partition(size: Int, edges: List<Pair<Int, Int>>): List<List<Int>> {
        val parents = IntArray(size) { it }
        fun root(value: Int): Int {
            var current = value
            while (parents[current] != current) { parents[current] = parents[parents[current]]; current = parents[current] }
            return current
        }
        for ((a, b) in edges) parents[root(b)] = root(a)
        return (0 until size).groupBy { root(it) }.values.toList()
    }

    private fun extent(points: List<OcrPoint>): List<OcrPoint> {
        val left = points.minOf { it.x }
        val top = points.minOf { it.y }
        val right = points.maxOf { it.x }
        val bottom = points.maxOf { it.y }
        return listOf(OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom))
    }

    private fun occupied(cell: List<OcrPoint>, boxes: List<List<OcrPoint>>): Boolean =
        MatOfPoint2f(*cell.map { Point(it.x, it.y) }.toTypedArray()).useGridMat { polygon ->
            val cellArea = Imgproc.contourArea(polygon)
            boxes.any { box ->
                val center = Point(box.map { it.x }.average(), box.map { it.y }.average())
                if (Imgproc.pointPolygonTest(polygon, center, false) >= 0) true else {
                    MatOfPoint2f(*box.map { Point(it.x, it.y) }.toTypedArray()).useGridMat { other ->
                        Mat().useGridMat { intersection ->
                            Imgproc.intersectConvexConvex(polygon, other, intersection) /
                                minOf(cellArea, Imgproc.contourArea(other)) >= 0.5
                        }
                    }
                }
            }
        }

    private fun rectification(cell: GridCellGeometry): GridRectification? {
        val bounds = cell.bounds
        return Mat.zeros(bounds.height, bounds.width, CvType.CV_8UC1).useGridMat { mask ->
            MatOfPoint(*cell.contour.map { Point(it.x - bounds.x, it.y - bounds.y) }.toTypedArray()).useGridMat { contour ->
                Imgproc.fillPoly(mask, listOf(contour), Scalar(255.0))
            }
            val pixels = ByteArray(bounds.width * bounds.height)
            mask.get(0, 0, pixels)
            val top = IntArray(bounds.width)
            val bottom = IntArray(bounds.width) { bounds.height - 1 }
            for (x in 0 until bounds.width) {
                top[x] = (0 until bounds.height).firstOrNull { pixels[it * bounds.width + x].toInt() != 0 } ?: 0
                bottom[x] = (bounds.height - 1 downTo 0).firstOrNull { pixels[it * bounds.width + x].toInt() != 0 } ?: bounds.height - 1
            }
            val widths = (0 until bounds.width).filter { bottom[it] - top[it] > 4 }
            if (widths.isEmpty()) return@useGridMat null
            val left = widths.first() + 2
            val right = widths.last() - 2
            if (right < left) return@useGridMat null
            val heights = (left..right).map { bottom[it] - top[it] }.sorted()
            val middle = heights.size / 2
            val median = if (heights.size % 2 == 0) (heights[middle - 1] + heights[middle]) / 2.0 else heights[middle].toDouble()
            if (median.toInt() <= 4) return@useGridMat null
            GridRectification(bounds.x, bounds.y, left, top, bottom, right - left + 1, median.toInt() - 4)
        }
    }

    private fun textLines(crop: Mat): List<GridLineBounds> = Mat().useGridMat { gray ->
        Mat().useGridMat { ink ->
            Imgproc.cvtColor(crop, gray, Imgproc.COLOR_BGR2GRAY)
            val h = gray.rows()
            val w = gray.cols()
            Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV, maxOf(3, minOf(h, w) / 2 * 2 + 1), 12.0)
            if (Core.countNonZero(ink).toDouble() / (h * w) < 0.008) return@useGridMat emptyList()
            Mat().useGridMat { labels ->
                Mat().useGridMat { stats ->
                    Mat().useGridMat { centroids ->
                        val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8)
                        val characters = BooleanArray(count)
                        val components = IntArray(count * 5)
                        stats.get(0, 0, components)
                        for (i in 1 until count) {
                            val x = components[i * 5]
                            val y = components[i * 5 + 1]
                            val cw = components[i * 5 + 2]
                            val ch = components[i * 5 + 3]
                            val area = components[i * 5 + 4]
                            val boundaryLine = (y == 0 && y + ch == h && cw < 0.2 * h) || (x == 0 && x + cw == w && ch < 0.1 * w)
                            characters[i] = !boundaryLine && ch >= maxOf(3.0, h * 0.25) &&
                                cw >= maxOf(2.0, h * 0.05) && area >= maxOf(3.0, h * h * 0.015)
                        }
                        if (characters.none { it }) return@useGridMat emptyList()
                        val labelPixels = IntArray(h * w)
                        labels.get(0, 0, labelPixels)
                        val seedPixels = ByteArray(h * w) { if (characters[labelPixels[it]]) 1 else 0 }
                        Mat(h, w, CvType.CV_8UC1).useGridMat { seed ->
                            Mat().useGridMat { support ->
                                seed.put(0, 0, seedPixels)
                                val radius = maxOf(2, (h * 0.25).toInt())
                                Mat.ones(radius * 2 + 1, radius * 2 + 1, CvType.CV_8UC1).useGridMat { kernel -> Imgproc.dilate(seed, support, kernel) }
                                val supportPixels = ByteArray(h * w)
                                val inkPixels = ByteArray(h * w)
                                support.get(0, 0, supportPixels)
                                ink.get(0, 0, inkPixels)
                                for (i in inkPixels.indices) if (supportPixels[i].toInt() == 0) inkPixels[i] = 0
                                lineBounds(inkPixels, w, h)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun lineBounds(ink: ByteArray, w: Int, h: Int): List<GridLineBounds> {
        val projected = ByteArray(h) { y ->
            val count = (0 until w).count { ink[y * w + it].toInt() != 0 }
            if (count >= maxOf(2.0, w * 0.004)) 1 else 0
        }
        return Mat(h, 1, CvType.CV_8UC1).useGridMat { projection ->
            projection.put(0, 0, projected)
            Mat.ones(3, 1, CvType.CV_8UC1).useGridMat { kernel -> Imgproc.morphologyEx(projection, projection, Imgproc.MORPH_CLOSE, kernel) }
            projection.get(0, 0, projected)
            val lines = mutableListOf<GridLineBounds>()
            var start = -1
            for (y in 0..h) {
                val active = y < h && projected[y].toInt() != 0
                if (active && start < 0) start = y
                if (!active && start >= 0) {
                    if (y - start >= maxOf(3.0, h * 0.15)) {
                        val xs = (0 until w).filter { x -> (start until y).any { ink[it * w + x].toInt() != 0 } }
                        if (xs.size >= 2) lines.add(GridLineBounds(maxOf(0, xs.first() - 2), maxOf(0, start - 2), minOf(w, xs.last() + 3), minOf(h, y + 2)))
                    }
                    start = -1
                }
            }
            lines
        }
    }
}

private inline fun <T : Mat, R> T.useGridMat(block: (T) -> R): R = try { block(this) } finally { release() }
