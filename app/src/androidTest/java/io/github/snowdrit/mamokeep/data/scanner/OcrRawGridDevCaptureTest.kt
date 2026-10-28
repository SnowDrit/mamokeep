package io.github.snowdrit.mamokeep.data.scanner

import android.graphics.Bitmap
import android.net.Uri
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

/** One opt-in emulator-only reflection capture. No production hook, model or OCR. */
class OcrRawGridDevCaptureTest {
    @Test fun captureFixedWork007RawGrid() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the frozen local raw-grid diagnostic", args.getString("ocrRawGridCapture") == "work007-native-v1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val run = requireNotNull(args.getString("ocrRawGridRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]+")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "ocr-dev")
        val source = File(root, "work-007.jpeg")
        val nativeFile = File(root, "stage4-baseline13-v5-v1/work-007/result.json")
        val savedPng = File(root, "stage4-baseline13-v5-v1/work-007/bounded.png")
        val geometryFile = File(root, "stage4-geometry13-v1/work-007/geometry.json")
        check(sha(source) == SOURCE_SHA)
        check(sha(nativeFile) == NATIVE_SHA)
        check(sha(savedPng) == BOUNDED_SHA)
        check(sha(geometryFile) == GEOMETRY_SHA)
        val native = JSONObject(nativeFile.readText())
        val expected = JSONObject(geometryFile.readText())
        check(expected.getString("source_result_sha256") == NATIVE_SHA)
        check(expected.getString("bounded_sha256") == BOUNDED_SHA)
        val records = native.getJSONArray("lines")
        check(records.length() == 229)
        val lines = (0 until records.length()).map { index ->
            val item = records.getJSONObject(index)
            OcrLine(item.getString("text"), item.getDouble("confidence"), points(item.getJSONArray("box")))
        }
        check(OpenCVLoader.initLocal())
        Core.setNumThreads(1)
        val output = File(root, run).apply { check(mkdirs()) { "Do not overwrite an existing diagnostic" } }
        val image = readScannerImage(context, Uri.fromFile(source))
        val rgba = Mat()
        val bgr = Mat()
        try {
            check(image.width == 1650 && image.height == 2200)
            val bounded = File(output, "bounded.png")
            bounded.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(sha(bounded) == BOUNDED_SHA) { "Raw capture must use the exact native bitmap" }
            Utils.bitmapToMat(image, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            val started = SystemClock.elapsedRealtime()
            val grid = OcrGrid.analyze(bgr, lines.map { it.box })
            checkAcceptedGeometry(grid, expected)
            val detect = OcrGrid::class.java.getDeclaredMethod("detectCells", Mat::class.java, Continuation::class.java)
            val tables = OcrGrid::class.java.getDeclaredMethod("tables", List::class.java, java.lang.Double.TYPE, Continuation::class.java)
            val rawObjects: List<Any> = invokeSuspend(detect, bgr)
            check(rawObjects.size == 281 && rawObjects.size == grid.candidateCount)
            val raw = rawObjects.map { value -> RawCell(property(value, "getBounds"),
                property(value, "getContour"), property(value, "getBox")) }
            val privateTables: List<List<List<Int>>> = invokeSuspend(tables, rawObjects, 2200.0)
            val trace = traceGraph(raw, 2200.0)
            check(trace.tables == privateTables) { "Copied diagnostic expressions must equal the private method" }
            val memberships = mutableMapOf<Int, Triple<Int, Int, Int>>()
            privateTables.forEachIndexed { ti, table -> table.forEachIndexed { ri, row -> row.forEachIndexed { ci, index ->
                check(memberships.put(index, Triple(ti, ri, ci)) == null)
            } } }
            check(memberships.size == 265)
            grid.cells.forEach { accepted ->
                val cell = raw[accepted.index]
                check(cell.box == accepted.owner.box && cell.contour == accepted.contour && cell.bounds == accepted.bounds)
                check(memberships[accepted.index] == Triple(accepted.owner.table, accepted.owner.row, accepted.owner.column))
                check(convexQuad(cell.box)) { "Current ownership contract requires convex nondegenerate accepted quads" }
            }
            val identities = IdentityHashMap<OcrLine, Int>().apply { lines.forEachIndexed { i, line -> put(line, i) } }
            val currentRows = groupOcrRows(lines, grid = grid.tables).map { row -> row.lines.map { requireNotNull(identities[it]) } }
            check(currentRows == rowIndices(expected.getJSONArray("rows_current")))
            check(currentRows == rowIndices(native.getJSONArray("rows")))
            val componentOf = trace.components.flatMapIndexed { component, indices -> indices.map { it to component } }.toMap()
            val retainedOf = trace.rows.flatMapIndexed { row, indices -> indices.map { it to row } }.toMap()
            val rawRecords = JSONArray(raw.mapIndexed { index, cell ->
                val member = memberships[index]
                val component = requireNotNull(componentOf[index])
                JSONObject().put("index", index).put("bounds", bounds(cell.bounds))
                    .put("box", points(cell.box)).put("contour", points(cell.contour))
                    .put("quad_area", ocrPolygonArea(cell.box)).put("quad_owner_domain_valid", convexQuad(cell.box))
                    .put("horizontal_component", component).put("horizontal_component_size", trace.components[component].size)
                    .put("retained_row", retainedOf[index] ?: JSONObject.NULL)
                    .put("table", member?.first ?: JSONObject.NULL).put("row", member?.second ?: JSONObject.NULL)
                    .put("column", member?.third ?: JSONObject.NULL)
                    .put("drop_stage", when {
                        member != null -> "accepted_table_member"
                        trace.components[component].size < 3 -> "horizontal_component_under3_before_vertical_table_linking"
                        else -> "retained_horizontal_row_not_in_minimum2row_table"
                    })
            })
            val owners = JSONArray()
            for ((index, line) in lines.withIndex()) {
                currentCoroutineContext().ensureActive()
                val lineArea = ocrPolygonArea(line.box)
                check(lineArea > 0)
                val rawWeights = JSONArray()
                val componentWeights = mutableMapOf<Int, Double>()
                val acceptedWeights = mutableMapOf<Pair<Int, Int>, Double>()
                raw.forEachIndexed { ci, cell ->
                    // Raw candidates have not passed the table/quad domain contract.
                    // Preserve invalid geometry in rawRecords, without treating it as a convex owner mask.
                    if (convexQuad(cell.box)) {
                        val fraction = ocrIntersectionArea(line.box, cell.box) / lineArea
                        if (fraction > 0) {
                            val component = requireNotNull(componentOf[ci])
                            rawWeights.put(JSONObject().put("cell", ci).put("component", component).put("fraction", fraction))
                            componentWeights[component] = (componentWeights[component] ?: 0.0) + fraction
                            memberships[ci]?.let { member ->
                                val key = member.first to member.second
                                acceptedWeights[key] = (acceptedWeights[key] ?: 0.0) + fraction
                            }
                        }
                    }
                }
                val envelopeWeights = grid.tables.map { it.table to ocrIntersectionArea(line.box, it.box) / lineArea }
                val ownedRows = acceptedWeights.filterValues { it > 0.5 }.keys
                val inside = envelopeWeights.filter { it.second > 0.5 }
                owners.put(JSONObject().put("line_index", index).put("source_box", points(line.box)).put("area", lineArea)
                    .put("raw_cell_fractions", rawWeights)
                    .put("raw_component_fractions", JSONArray(componentWeights.map { (key, value) ->
                        JSONObject().put("component", key).put("fraction", value).put("strict_majority", value > 0.5)
                    }))
                    .put("accepted_row_fractions", JSONArray(acceptedWeights.map { (key, value) ->
                        JSONObject().put("table", key.first).put("row", key.second).put("fraction", value)
                    }))
                    .put("table_envelope_fractions", JSONArray(envelopeWeights.map { (table, value) ->
                        JSONObject().put("table", table).put("fraction", value)
                    }))
                    .put("current_disposition", when {
                        ownedRows.size == 1 -> "accepted_row_owner"
                        inside.size == 1 -> "table_orphan_singleton"
                        inside.size > 1 -> "ambiguous_table_singleton"
                        else -> "outside_grid_fallback"
                    }))
            }
            val result = JSONObject().put("status", "EXACT_CURRENT_GEOMETRY_RAW_CAPTURE_COMPLETE")
                .put("case", "work-007").put("source_sha256", SOURCE_SHA).put("native_result_sha256", NATIVE_SHA)
                .put("bounded_sha256", BOUNDED_SHA).put("actual_geometry_sha256", GEOMETRY_SHA)
                .put("host_geometry13_completion_sha256", COMPLETION_SHA).put("declared_grid_source_sha256", GRID_SHA)
                .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("candidate_count", raw.size).put("all265_accepted_cells_and_tables_exact", true)
                .put("private_tables_equal_copied_graph", true).put("current_all229_rows_exact", true)
                .put("native_models_or_ocr_executed", false).put("production_source_changed", false)
                .put("cell_detection_prefilter_trace_captured", false)
                .put("raw_cells", rawRecords).put("horizontal_edges", edges(trace.horizontal)).put("vertical_edges", edges(trace.vertical))
                .put("horizontal_components_before_min3", nested(trace.components)).put("rows_after_min3", nested(trace.rows))
                .put("vertical_row_edges_after_min3", edges(trace.rowEdges)).put("private_tables", JSONArray(privateTables.map(::nested)))
                .put("accepted_table_envelopes", JSONArray(grid.tables.map { JSONObject().put("table", it.table).put("box", points(it.box)) }))
                .put("line_owner_masks", owners).put("old_row_indices", nested(currentRows))
            File(output, "raw-grid.json").writeText(result.toString(2))
            File(output, "completion.json").writeText(JSONObject().put("status", "PASS_FIXED_RAW_CAPTURE_GATES")
                .put("raw_grid_sha256", sha(File(output, "raw-grid.json"))).put("raw_cells", 281)
                .put("accepted_cells", 265).put("lines", 229).put("policy_changed", false).toString(2))
            check(sha(source) == SOURCE_SHA && sha(nativeFile) == NATIVE_SHA && sha(savedPng) == BOUNDED_SHA && sha(geometryFile) == GEOMETRY_SHA)
            println("RAW_GRID_CAPTURE_COMPLETE cells=${raw.size} accepted=${grid.cells.size}")
        } finally { bgr.release(); rgba.release(); image.recycle() }
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

    private fun checkAcceptedGeometry(grid: GridAnalysis, expected: JSONObject) {
        check(grid.candidateCount == 281 && grid.cells.size == 265 && grid.tables.size == 2)
        check(grid.rejectedRows.isEmpty() && grid.unrectifiableCellIndices.isEmpty() && grid.rescues.isEmpty())
        val cells = expected.getJSONArray("cells")
        check(cells.length() == grid.cells.size)
        grid.cells.forEachIndexed { index, cell ->
            val saved = cells.getJSONObject(index)
            check(cell.index == saved.getInt("cell_index") && cell.owner.table == saved.getInt("table"))
            check(cell.owner.row == saved.getInt("row") && cell.owner.column == saved.getInt("column"))
            check(cell.owner.box == points(saved.getJSONArray("box")))
            check(cell.contour == points(saved.getJSONArray("contour")))
            check(listOf(cell.bounds.x, cell.bounds.y, cell.bounds.width, cell.bounds.height) == ints(saved.getJSONArray("bounds")))
        }
        val tables = expected.getJSONArray("tables")
        check(tables.length() == grid.tables.size)
        grid.tables.forEachIndexed { index, table ->
            val saved = tables.getJSONObject(index)
            check(table.table == saved.getInt("table") && table.box == points(saved.getJSONArray("box")))
            val members = saved.getJSONArray("cells")
            check(members.length() == table.cells.size)
            table.cells.forEachIndexed { ci, cell ->
                val item = members.getJSONObject(ci)
                check(cell.row == item.getInt("row") && cell.column == item.getInt("column"))
                check(cell.box == points(item.getJSONArray("box")))
            }
        }
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

    companion object {
        private const val SOURCE_SHA = "5b31c4173b3866bf3ce4ce51b9666b6ecd3385681785182f6787a9025f854208"
        private const val NATIVE_SHA = "d16615cb43d6c52dd4ca848f2fc5769cc96ba4943e17d8b928aa64ed3b743016"
        private const val BOUNDED_SHA = "c4fbcbc802602ac704df17ed31d54e836f70c5c834093cec92e31a5f18a95380"
        private const val GEOMETRY_SHA = "37538edde722f5873dad81b92f7f9710a8561128cc04b0487a24c0f047d18454"
        private const val COMPLETION_SHA = "57edcbd46c9e8bce6b480db7f0e97cd66f9c784a0c3aaee512d9f4a836345956"
        private const val GRID_SHA = "cc2fa2b0caf587ea6020b4e41f7c7192eb4386b930dda9d91cfd8b02bb2ad0db"
    }
}
