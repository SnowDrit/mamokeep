package io.github.snowdrit.mamokeep.data.scanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.IdentityHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Opt-in immutable19 TRAIN snapshot metadata capture; no recognition or user records. */
class OcrRawGridSnapshotCaptureTest {
    @Test fun captureRawGridSnapshots() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the frozen19 snapshot capture", args.getString("ocrRawSnapshotCapture") == "stage4-snapshot19-v1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val run = requireNotNull(args.getString("ocrRawSnapshotRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]+")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "ocr-dev")
        val input = File(root, "stage4-grid-snapshots-v1")
        val manifestFile = File(input, "manifest.json")
        check(sha(manifestFile) == MANIFEST_SHA)
        val cases = JSONObject(manifestFile.readText()).getJSONArray("cases")
        check(cases.length() == 19)
        check(OpenCVLoader.initLocal()); Core.setNumThreads(1)
        val output = File(root, run).apply { check(mkdirs()) { "Do not overwrite a diagnostic" } }
        for (caseIndex in 0 until cases.length()) {
            currentCoroutineContext().ensureActive()
            val case = cases.getJSONObject(caseIndex)
            check(case.getString("split") == "train")
            val id = case.getString("id"); require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val dir = File(input, id)
            val nativeFile = File(dir, "result.json"); val png = File(dir, "bounded.png"); val geometryFile = File(dir, "geometry.json")
            check(sha(nativeFile) == case.getString("native_sha256"))
            check(sha(png) == case.getString("bounded_sha256"))
            check(sha(geometryFile) == case.getString("geometry_sha256"))
            val native = JSONObject(nativeFile.readText()); val expected = JSONObject(geometryFile.readText())
            check(native.getString("id") == id && expected.getString("id") == id)
            check(native.getString("source_sha256") == case.getString("source_sha256"))
            check(expected.getString("source_result_sha256") == case.getString("native_sha256"))
            check(expected.getString("bounded_sha256") == case.getString("bounded_sha256"))
            val records = native.getJSONArray("lines"); check(records.length() == case.getInt("line_count"))
            val lines = (0 until records.length()).map { index ->
                val item = records.getJSONObject(index)
                OcrLine(item.getString("text"), item.getDouble("confidence"), points(item.getJSONArray("box")))
            }
            val image = checkNotNull(BitmapFactory.decodeFile(png.path))
            val rgba = Mat(); val bgr = Mat()
            val destination = File(output, id).apply { check(mkdirs()) }
            try {
                check(image.width == case.getInt("width") && image.height == case.getInt("height"))
                val reencoded = File(destination, "bounded.png")
                reencoded.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(sha(reencoded) == case.getString("bounded_sha256"))
                Utils.bitmapToMat(image, rgba); Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                val grid = OcrGrid.analyze(bgr, lines.map { it.box })
                checkAcceptedGeometry(grid, expected)
                val detect = OcrGrid::class.java.getDeclaredMethod("detectCells", Mat::class.java, Continuation::class.java)
                val tableMethod = OcrGrid::class.java.getDeclaredMethod("tables", List::class.java, java.lang.Double.TYPE, Continuation::class.java)
                val rawObjects: List<Any> = invokeSuspend(detect, bgr)
                check(rawObjects.size == expected.getInt("candidate_count"))
                val raw = rawObjects.map { value -> RawCell(property(value, "getBounds"), property(value, "getContour"), property(value, "getBox")) }
                val scale = maxOf(image.width, image.height).toDouble()
                val privateTables: List<List<List<Int>>> = invokeSuspend(tableMethod, rawObjects, scale)
                val trace = traceGraph(raw, scale); check(trace.tables == privateTables)
                val memberships = mutableMapOf<Int, Triple<Int, Int, Int>>()
                privateTables.forEachIndexed { ti, table -> table.forEachIndexed { ri, row -> row.forEachIndexed { ci, index ->
                    check(memberships.put(index, Triple(ti, ri, ci)) == null)
                } } }
                grid.cells.forEach { accepted ->
                    val cell = raw[accepted.index]
                    check(cell.box == accepted.owner.box && cell.contour == accepted.contour && cell.bounds == accepted.bounds)
                    check(memberships[accepted.index] == Triple(accepted.owner.table, accepted.owner.row, accepted.owner.column))
                }
                val identities = IdentityHashMap<OcrLine, Int>().apply { lines.forEachIndexed { i, line -> put(line, i) } }
                val rows = groupOcrRows(lines, grid = grid.tables).map { row -> row.lines.map { requireNotNull(identities[it]) } }
                check(rows == rowIndices(expected.getJSONArray("rows_current")))
                check(rows == rowIndices(native.getJSONArray("rows")))
                val componentOf = trace.components.flatMapIndexed { component, indices -> indices.map { it to component } }.toMap()
                val result = JSONObject().put("status", "EXACT_ANDROID_SNAPSHOT_RAW_GRID_COMPLETE")
                    .put("id", id).put("manifest_sha256", MANIFEST_SHA).put("native_result_sha256", sha(nativeFile))
                    .put("geometry_sha256", sha(geometryFile)).put("bounded_sha256", sha(reencoded))
                    .put("candidate_count", raw.size).put("accepted_cells", grid.cells.size)
                    .put("all_accepted_geometry_and_current_rows_exact", true).put("private_tables_equal_copied_graph", true)
                    .put("new_ocr_or_model_execution", false).put("production_change", false)
                    .put("cell_detection_prefilter_trace_captured", false)
                    .put("raw_cells", JSONArray(raw.mapIndexed { index, cell ->
                        val member = memberships[index]
                        JSONObject().put("index", index).put("bounds", bounds(cell.bounds)).put("box", points(cell.box))
                            .put("contour", points(cell.contour)).put("quad_owner_domain_valid", convexQuad(cell.box))
                            .put("horizontal_component", requireNotNull(componentOf[index]))
                            .put("table", member?.first ?: JSONObject.NULL).put("row", member?.second ?: JSONObject.NULL)
                            .put("column", member?.third ?: JSONObject.NULL)
                    }))
                    .put("horizontal_edges", edges(trace.horizontal)).put("vertical_edges", edges(trace.vertical))
                    .put("horizontal_components_before_min3", nested(trace.components)).put("rows_after_min3", nested(trace.rows))
                    .put("vertical_row_edges_after_min3", edges(trace.rowEdges)).put("private_tables", JSONArray(privateTables.map(::nested)))
                    .put("current_rows", nested(rows))
                File(destination, "raw-grid.json").writeText(result.toString(2))
                check(sha(nativeFile) == case.getString("native_sha256") && sha(png) == case.getString("bounded_sha256") && sha(geometryFile) == case.getString("geometry_sha256"))
                println("RAW_SNAPSHOT_COMPLETE id=$id cells=${raw.size} accepted=${grid.cells.size}")
            } finally { bgr.release(); rgba.release(); image.recycle() }
        }
        File(output, "completion.json").writeText(JSONObject().put("status", "PASS_ALL19_SNAPSHOT_CAPTURE_GATES")
            .put("cases", 19).put("manifest_sha256", MANIFEST_SHA).put("new_ocr", false).put("production_change", false).toString(2))
    }
    private data class RawCell(val bounds: Rect, val contour: List<OcrPoint>, val box: List<OcrPoint>)
    private data class GraphTrace(val horizontal: List<Pair<Int, Int>>, val vertical: List<Pair<Int, Int>>,
        val components: List<List<Int>>, val rows: List<List<Int>>, val rowEdges: List<Pair<Int, Int>>,
        val tables: List<List<List<Int>>>)

    private suspend fun <T> invokeSuspend(method: Method, vararg arguments: Any?): T =
        suspendCoroutineUninterceptedOrReturn { continuation: Continuation<T> ->
            method.isAccessible = true
            try { method.invoke(OcrGrid, *arguments, continuation) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> property(value: Any, name: String): T =
        value.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(value) as T

    // The following graph expressions are copied exactly from the pinned OcrGrid source.
    private suspend fun traceGraph(cells: List<RawCell>, scale: Double): GraphTrace {
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
        val components = partition(cells.size, horizontal)
        val rows = partition(cells.size, horizontal).filter { it.size >= 3 }
        val owner = rows.flatMapIndexed { row, indices -> indices.map { it to row } }.toMap()
        val rowEdges = vertical.mapNotNull { (a, b) ->
            val ar = owner[a]
            val br = owner[b]
            if (ar != null && br != null && ar != br) ar to br else null
        }
        val result = partition(rows.size, rowEdges).filter { it.size >= 2 }.map { table ->
            table.map { rows[it] }.sortedBy { row -> row.map { cells[it].bounds.y }.average() }
                .map { row -> row.sortedBy { cells[it].bounds.x } }
        }.sortedBy { table -> table[0].map { cells[it].bounds.y }.average() }
        return GraphTrace(horizontal, vertical, components, rows, rowEdges, result)
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

    private fun convexQuad(box: List<OcrPoint>): Boolean {
        if (ocrPolygonArea(box) == 0.0) return false
        val sides = box.indices.map { i ->
            val a = box[i]; val b = box[(i + 1) % box.size]; val c = box[(i + 2) % box.size]
            (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        return sides.all { it >= 0 } || sides.all { it <= 0 }
    }

    private fun points(array: JSONArray): List<OcrPoint> = (0 until array.length()).map {
        val point = array.getJSONArray(it); OcrPoint(point.getDouble(0), point.getDouble(1))
    }
    private fun points(points: List<OcrPoint>): JSONArray = JSONArray(points.map { JSONArray(listOf(it.x, it.y)) })
    private fun bounds(value: Rect): JSONArray = JSONArray(listOf(value.x, value.y, value.width, value.height))
    private fun ints(array: JSONArray): List<Int> = (0 until array.length()).map(array::getInt)
    private fun rowIndices(array: JSONArray): List<List<Int>> = (0 until array.length()).map {
        ints(array.getJSONObject(it).getJSONArray("line_indices"))
    }
    private fun edges(values: List<Pair<Int, Int>>): JSONArray = JSONArray(values.map { JSONArray(listOf(it.first, it.second)) })
    private fun nested(values: List<List<Int>>): JSONArray = JSONArray(values.map { JSONArray(it) })
    private fun sha(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun checkAcceptedGeometry(grid: GridAnalysis, expected: JSONObject) {
        check(grid.candidateCount == expected.getInt("candidate_count"))
        val cells = expected.getJSONArray("cells"); check(cells.length() == grid.cells.size)
        grid.cells.forEachIndexed { index, cell ->
            val saved = cells.getJSONObject(index)
            check(cell.index == saved.getInt("cell_index") && cell.owner.table == saved.getInt("table"))
            check(cell.owner.row == saved.getInt("row") && cell.owner.column == saved.getInt("column"))
            check(cell.owner.box == points(saved.getJSONArray("box")) && cell.contour == points(saved.getJSONArray("contour")))
            check(listOf(cell.bounds.x, cell.bounds.y, cell.bounds.width, cell.bounds.height) == ints(saved.getJSONArray("bounds")))
        }
        val tables = expected.getJSONArray("tables"); check(tables.length() == grid.tables.size)
        grid.tables.forEachIndexed { index, table ->
            val saved = tables.getJSONObject(index)
            check(table.table == saved.getInt("table") && table.box == points(saved.getJSONArray("box")))
            val members = saved.getJSONArray("cells"); check(members.length() == table.cells.size)
            table.cells.forEachIndexed { ci, cell ->
                val item = members.getJSONObject(ci)
                check(cell.row == item.getInt("row") && cell.column == item.getInt("column") && cell.box == points(item.getJSONArray("box")))
            }
        }
        val rejected = expected.getJSONArray("rejected_rows")
        check(grid.rejectedRows.map { it.table to it.row } == (0 until rejected.length()).map { rejected.getJSONObject(it).let { x -> x.getInt("table") to x.getInt("row") } })
        check(grid.unrectifiableCellIndices == ints(expected.getJSONArray("unrectifiable_cell_indices")))
        val rescues = expected.getJSONArray("rescues"); check(rescues.length() == grid.rescues.size)
        grid.rescues.forEachIndexed { i, rescue ->
            val saved = rescues.getJSONObject(i)
            check(rescue.cellIndex == saved.getInt("cell_index") && rescue.cell.table == saved.getInt("table") && rescue.cell.row == saved.getInt("row") && rescue.cell.column == saved.getInt("column"))
            check(rescue.box == points(saved.getJSONArray("box")) && rescue.width == saved.getInt("width") && rescue.height == saved.getInt("height"))
            check(listOf(rescue.bounds.left, rescue.bounds.top, rescue.bounds.right, rescue.bounds.bottom) == ints(saved.getJSONArray("bounds")))
        }
    }
    companion object { private const val MANIFEST_SHA = "d557ebfa2407b6cd7fe2035c31ab886e95f85f530b1091aa0d6d9624e16b8152" }
}
