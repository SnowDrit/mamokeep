package io.github.snowdrit.mamokeep.data.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import io.github.snowdrit.mamokeep.domain.scanner.ReceiptDraft
import io.github.snowdrit.mamokeep.data.scanner.stage2dev.FROZEN_STAGE2_OCR_TEXT_SHA256
import io.github.snowdrit.mamokeep.data.scanner.stage2dev.FROZEN_STAGE2_PARSER_SHA256
import io.github.snowdrit.mamokeep.data.scanner.stage2dev.ReceiptDraft as Stage2ReceiptDraft
import io.github.snowdrit.mamokeep.data.scanner.stage2dev.groupOcrRows as groupStage2OcrRows
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.opencv.android.Utils
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** Opt-in, emulator-only local diagnostics. Personal inputs never enter APK assets. */
class OcrDevBaselineTest {
    @Test fun independentEvaluationRequiresAnExplicitSeparateHoldoutRun() {
        validateCaptureSplits(listOf("train", "dev"), null)
        validateCaptureSplits(listOf("holdout", "holdout"), "holdout-v1")
        for (splits in listOf(emptyList(), listOf("holdout"), listOf("test"), listOf("train", "holdout"))) {
            assertThrows(IllegalArgumentException::class.java) { validateCaptureSplits(splits, null) }
        }
        for (splits in listOf(emptyList(), listOf("train"), listOf("dev"), listOf("dev", "holdout"))) {
            assertThrows(IllegalArgumentException::class.java) { validateCaptureSplits(splits, "holdout-v1") }
        }
        assertThrows(IllegalArgumentException::class.java) { validateCaptureSplits(listOf("holdout"), "true") }
    }

    private fun validateCaptureSplits(splits: List<String>, evaluation: String?) {
        require(splits.isNotEmpty())
        if (evaluation == null) require(splits.all { it == "train" || it == "dev" })
        else {
            require(evaluation == "holdout-v1")
            require(splits.all { it == "holdout" })
        }
    }

    @Test fun captureSavedGeometry() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("ocrGridRun")
        assumeTrue("Requires an explicit local geometry run", run != null)
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        require(run!!.matches(Regex("[a-zA-Z0-9-]+")))
        val baseline = requireNotNull(args.getString("ocrGridBaseline"))
        require(baseline.matches(Regex("[a-zA-Z0-9-]+")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "ocr-dev")
        val output = File(root, run).apply { check(mkdirs()) { "Do not overwrite a measurement" } }
        val manifestFile = manifestFile(root)
        val manifest = JSONObject(manifestFile.readText())
        val cases = manifest.getJSONArray("cases")
        check(OpenCVLoader.initLocal())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            require(case.getString("split") in listOf("train", "dev"))
            val id = case.getString("id")
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val source = File(root, case.getString("file"))
            check(sha256(source) == case.getString("sha256"))
            val input = File(root, "$baseline/$id/result.json")
            val saved = JSONObject(input.readText())
            check(saved.getString("source_sha256") == sha256(source))
            check(saved.getString("manifest_sha256") == sha256(manifestFile))
            check(saved.optInt("rotation_clockwise_degrees", 0) == manualRotation(case))
            checkExpectedModels(manifest, saved.getJSONObject("model_sha256"))
            val records = saved.getJSONArray("lines")
            val lines = (0 until records.length()).map { li ->
                val record = records.getJSONObject(li)
                val box = record.getJSONArray("box")
                OcrLine(record.getString("text"), record.getDouble("confidence"),
                    (0 until box.length()).map { bi ->
                        val p = box.getJSONArray(bi)
                        OcrPoint(p.getDouble(0), p.getDouble(1))
                    })
            }
            val boxes = lines.map { it.box }
            val savedBounded = File(root, "$baseline/$id/bounded.png")
            val image = readDevImage(context, source, case)
            val rgba = Mat()
            val bgr = Mat()
            val destination = File(output, id).apply { check(mkdirs()) }
            try {
                val bounded = File(destination, "bounded.png")
                bounded.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                check(image.width == saved.getInt("width") && image.height == saved.getInt("height"))
                check(sha256(bounded) == sha256(savedBounded)) { "Geometry must use the exact captured bitmap" }
                if (saved.has("bounded_sha256")) check(saved.getString("bounded_sha256") == sha256(savedBounded))
                Utils.bitmapToMat(image, rgba)
                Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                val started = SystemClock.elapsedRealtime()
                val grid = OcrGrid.analyze(bgr, boxes)
                val elapsed = SystemClock.elapsedRealtime() - started
                grid.rescues.forEachIndexed { ri, rescue ->
                    val crop = rescue.crop(bgr)
                    val pixels = Mat()
                    val bitmap = Bitmap.createBitmap(crop.cols(), crop.rows(), Bitmap.Config.ARGB_8888)
                    try {
                        Imgproc.cvtColor(crop, pixels, Imgproc.COLOR_BGR2RGBA)
                        Utils.matToBitmap(pixels, bitmap)
                        File(destination, "rescue-$ri.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    } finally { bitmap.recycle(); pixels.release(); crop.release() }
                }
                val currentRows = rows(lines, groupOcrRows(lines, grid = grid.tables))
                checkSameRows(saved.getJSONArray("rows"), currentRows)
                val stage2Rows = rows(lines, groupStage2OcrRows(lines, grid = grid.tables))
                fun text(rows: JSONArray) = (0 until rows.length()).joinToString("\n") {
                    rows.getJSONObject(it).getString("text")
                }
                check(text(currentRows) == saved.getString("text"))
                val currentFields = fields(ReceiptDraft.parse(text(currentRows)))
                for (name in listOf("date", "odometer_km", "liters", "fuel_type")) {
                    val actual = currentFields.get(name)
                    val captured = saved.getJSONObject("fields").get(name)
                    // JSON writes integral doubles as integers; compare their exact numeric value.
                    check(if (actual is Number && captured is Number) actual.toDouble() == captured.toDouble()
                        else actual == captured) { "Current fields differ from capture: $name" }
                }
                val fieldsByRows = JSONObject()
                    .put("current", JSONObject().put("current_parser", currentFields)
                        .put("stage2_parser", fields(Stage2ReceiptDraft.parse(text(currentRows)))))
                    .put("stage2", JSONObject().put("current_parser", fields(ReceiptDraft.parse(text(stage2Rows))))
                        .put("stage2_parser", fields(Stage2ReceiptDraft.parse(text(stage2Rows)))))
                val result = JSONObject().put("source_result_sha256", sha256(input)).put("elapsed_ms", elapsed)
                    .put("id", id).put("source_sha256", sha256(source))
                    .put("manifest_sha256", sha256(manifestFile)).put("model_sha256", saved.getJSONObject("model_sha256"))
                    .put("rotation_clockwise_degrees", manualRotation(case))
                    .put("bounded_sha256", sha256(bounded)).put("width", image.width).put("height", image.height)
                    .put("grid_box_input", "all_saved_recognized_lines_including_rescues")
                    .put("rescue_candidates_are_rerun_not_engine_acceptances", true)
                    .put("current_rows_equal_capture", true)
                    .put("stage2_source_sha256", FROZEN_STAGE2_OCR_TEXT_SHA256)
                    .put("rows_stage2", stage2Rows).put("rows_current", currentRows)
                    .put("fields_by_rows", fieldsByRows).put("current_fields_equal_capture", true)
                    .put("stage2_parser_source_sha256", FROZEN_STAGE2_PARSER_SHA256)
                    .put("current_parser_source_sha256", CURRENT_PARSER_SOURCE_SHA256)
                    .put("current_source_sha256", CURRENT_OCR_TEXT_SOURCE_SHA256)
                    .put("parser_input_sha256_by_rows", JSONObject()
                        .put("current", sha256(text(currentRows)))
                        .put("stage2", sha256(text(stage2Rows))))
                    .put("candidate_count", grid.candidateCount)
                    .put("unrectifiable_cell_indices", JSONArray(grid.unrectifiableCellIndices))
                    .put("cells", JSONArray(grid.cells.map { cell ->
                        JSONObject().put("cell_index", cell.index).put("table", cell.owner.table)
                            .put("row", cell.owner.row).put("column", cell.owner.column).put("box", points(cell.owner.box))
                            .put("contour", points(cell.contour))
                            .put("bounds", JSONArray(listOf(cell.bounds.x, cell.bounds.y, cell.bounds.width, cell.bounds.height)))
                    }))
                    .put("rejected_rows", JSONArray(grid.rejectedRows.map { JSONObject().put("table", it.table).put("row", it.row) }))
                    .put("tables", JSONArray(grid.tables.map { table ->
                        JSONObject().put("table", table.table).put("box", points(table.box))
                            .put("cells", JSONArray(table.cells.map { cell ->
                                JSONObject().put("row", cell.row).put("column", cell.column).put("box", points(cell.box))
                            }))
                    }))
                    .put("rescues", JSONArray(grid.rescues.map { rescue ->
                        JSONObject().put("cell_index", rescue.cellIndex).put("table", rescue.cell.table)
                            .put("row", rescue.cell.row).put("column", rescue.cell.column).put("box", points(rescue.box))
                            .put("width", rescue.width).put("height", rescue.height)
                            .put("bounds", JSONArray(listOf(rescue.bounds.left, rescue.bounds.top, rescue.bounds.right, rescue.bounds.bottom)))
                    }))
                File(destination, "geometry.json").writeText(result.toString(2))
            } finally { bgr.release(); rgba.release(); image.recycle() }
        }
    }

    @Test fun captureWholeDocuments() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val run = args.getString("ocrDevRun")
        assumeTrue("Requires an explicit local dev run", run != null)
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        require(run!!.matches(Regex("[a-zA-Z0-9-]+")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val root = File(context.filesDir, "ocr-dev")
        val manifestFile = manifestFile(root)
        val manifest = JSONObject(manifestFile.readText())
        val pipeline = args.getString("ocrDevPipeline") ?: "current"
        require(pipeline == "current" || pipeline == "original-v1")
        val cases = manifest.getJSONArray("cases")
        val evaluation = args.getString("ocrEvaluationOnly")
        validateCaptureSplits((0 until cases.length()).map { cases.getJSONObject(it).getString("split") }, evaluation)
        val captureInputs = args.getString("ocrCaptureRecognitionInputs")
        if (captureInputs != null) {
            require(captureInputs == "native-f32-v1" && pipeline == "current" && evaluation == null)
            require((0 until cases.length()).all { cases.getJSONObject(it).getString("split") == "train" })
            require(manifest.has("expected_model_sha256"))
        }
        if (evaluation != null) {
            require(pipeline == "current")
            val expectedManifest = requireNotNull(args.getString("ocrEvaluationManifestSha"))
            require(expectedManifest.matches(Regex("[0-9a-f]{64}")))
            check(sha256(manifestFile) == expectedManifest)
            require(manifest.has("expected_model_sha256"))
        }
        val candidate = manifest.optJSONObject("recognizer")
        val candidateDetector = manifest.optJSONObject("detector")
        val output = File(root, run)
        check(!output.exists()) { "Do not overwrite an existing measurement" }
        check(output.mkdirs())
        val models = File(context.cacheDir, "ocr-dev-models")
        check(models.mkdirs() || models.isDirectory)
        try {
            val modelHashes = JSONObject()
            for (name in listOf("det.onnx", "rec.onnx", "rec-characters.txt")) {
                val file = File(models, name)
                val replacement = when (name) {
                    "rec.onnx" -> candidate
                    "det.onnx" -> candidateDetector
                    else -> null
                }
                val input = if (replacement != null) {
                    val candidateName = replacement.getString("file")
                    require(candidateName.matches(Regex("[a-zA-Z0-9-]+\\.onnx")))
                    val candidateFile = File(root, candidateName)
                    check(sha256(candidateFile) == replacement.getString("sha256"))
                    candidateFile.inputStream()
                } else instrumentation.context.assets.open("ocr-v1/$name")
                input.use {
                    file.outputStream().use { input.copyTo(it) }
                }
                modelHashes.put(name, sha256(file))
            }
            checkExpectedModels(manifest, modelHashes)
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val id = case.getString("id")
                require(id.matches(Regex("[a-zA-Z0-9-]+")))
                val source = File(root, case.getString("file"))
                check(sha256(source) == case.getString("sha256"))
                val destination = File(output, id).apply { check(mkdirs()) }
                val image = readDevImage(context, source, case)
                try {
                    val bounded = File(destination, "bounded.png")
                    bounded.outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    val started = SystemClock.elapsedRealtime()
                    var cropIoMs = 0L
                    val recognitionBatches = JSONArray()
                    var capturedInputLines = 0
                    fun saveCrop(lineIndex: Int, mat: Mat) {
                        val rgba = Mat()
                        val bitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
                        try {
                            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
                            Utils.matToBitmap(rgba, bitmap)
                            File(destination, "crop-$lineIndex.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { bitmap.recycle(); rgba.release() }
                    }
                    val document = if (pipeline == "current") {
                        val engine = if (captureInputs == null) OcrEngine() else OcrEngine { firstLineIndex, batchSize, width, input ->
                            val inputStarted = SystemClock.elapsedRealtime()
                            check(firstLineIndex == capturedInputLines)
                            check(input.size == batchSize * 3 * 48 * width)
                            val bytes = ByteArray(input.size * 4)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(input)
                            val file = File(destination, "input-$firstLineIndex.f32.gz")
                            GZIPOutputStream(file.outputStream()).use { it.write(bytes) }
                            val tensorSha = MessageDigest.getInstance("SHA-256").digest(bytes)
                                .joinToString("") { "%02x".format(it) }
                            recognitionBatches.put(JSONObject().put("first_line_index", firstLineIndex)
                                .put("batch_size", batchSize).put("shape", JSONArray(listOf(batchSize, 3, 48, width)))
                                .put("file", file.name).put("uncompressed_bytes", bytes.size)
                                .put("compressed_bytes", file.length()).put("sha256", sha256(file))
                                .put("tensor_sha256", tensorSha))
                            capturedInputLines += batchSize
                            cropIoMs += SystemClock.elapsedRealtime() - inputStarted
                        }
                        engine.recognize(image, models) { lineIndex, mat ->
                            val cropStarted = SystemClock.elapsedRealtime()
                            saveCrop(lineIndex, mat)
                            cropIoMs += SystemClock.elapsedRealtime() - cropStarted
                        }
                    } else {
                        val engine = OcrOriginalDevEngine()
                        val original = engine.recognize(image, models)
                        val cropStarted = SystemClock.elapsedRealtime()
                        val crop = OcrOriginalDevEngine::class.java.getDeclaredMethod("crop", Mat::class.java, List::class.java).apply { isAccessible = true }
                        val rgba = Mat()
                        val bgr = Mat()
                        try {
                            Utils.bitmapToMat(image, rgba)
                            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                            original.lines.forEachIndexed { lineIndex, line ->
                                val mat = crop.invoke(engine, bgr, line.box) as Mat
                                try { saveCrop(lineIndex, mat) } finally { mat.release() }
                            }
                        } finally { bgr.release(); rgba.release() }
                        cropIoMs += SystemClock.elapsedRealtime() - cropStarted
                        original
                    }
                    val totalMs = SystemClock.elapsedRealtime() - started
                    val elapsed = totalMs - cropIoMs
                    val draft = ReceiptDraft.parse(document.text)
                    val result = JSONObject()
                        .put("id", id).put("source_sha256", sha256(source))
                        .put("rotation_clockwise_degrees", manualRotation(case)).put("bounded_sha256", sha256(bounded))
                        .put("manifest_sha256", sha256(manifestFile)).put("model_sha256", modelHashes)
                        .put("source_split", case.getString("split")).put("evaluation_only", evaluation != null)
                        .put("pipeline", pipeline).put("recognizer", candidate ?: "original-v1-test-assets")
                        .put("detector", candidateDetector ?: "original-v1-test-assets")
                        .put("width", document.width).put("height", document.height)
                        .put("elapsed_ms", elapsed).put("crop_io_ms", cropIoMs).put("total_ms", totalMs)
                        .put("native_heap_bytes_after", Debug.getNativeHeapAllocatedSize())
                        .put("lines", JSONArray(document.lines.map { line ->
                            JSONObject().put("text", line.text).put("confidence", line.confidence)
                                .put("box", JSONArray(line.box.map { JSONArray(listOf(it.x, it.y)) }))
                        }))
                        .put("rows", JSONArray(document.rows.map { row ->
                            JSONObject().put("text", row.text).put("line_indices", JSONArray(row.lines.map { line -> document.lines.indexOfFirst { it === line } }))
                        }))
                        .put("text", document.text)
                        .put("fields", fields(draft))
                    if (captureInputs != null) {
                        check(capturedInputLines == document.lines.size)
                        result.put("recognition_input_contract", "GZIP_NCHW_FLOAT32_LITTLE_ENDIAN_BORROWED_PRE_ONNX_INPUT")
                            .put("recognition_batches", recognitionBatches)
                    }
                    File(destination, "result.json").writeText(result.toString(2))
                } finally { image.recycle() }
            }
        } finally { models.deleteRecursively() }
    }

    @Test fun replaySavedFields() {
        val sourceRun = InstrumentationRegistry.getArguments().getString("ocrDevReplay")
        assumeTrue("Requires an explicit saved dev run", sourceRun != null)
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        require(sourceRun!!.matches(Regex("[a-zA-Z0-9-]+")))
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ocr-dev")
        val output = File(root, "$sourceRun-field-replay")
        check(output.mkdirs()) { "Do not overwrite a previous replay" }
        val cases = JSONObject(manifestFile(root).readText()).getJSONArray("cases")
        for (index in 0 until cases.length()) {
            val id = cases.getJSONObject(index).getString("id")
            require(id.matches(Regex("[a-zA-Z0-9-]+")))
            val input = File(root, "$sourceRun/$id/result.json")
            val saved = JSONObject(input.readText())
            val result = JSONObject().put("source_result_sha256", sha256(input))
                .put("before", saved.getJSONObject("fields"))
                .put("after", fields(ReceiptDraft.parse(saved.getString("text"))))
            File(output, "$id.json").writeText(result.toString(2))
        }
    }

    private fun manifestFile(root: File): File {
        val name = InstrumentationRegistry.getArguments().getString("ocrDevManifest") ?: "manifest.json"
        require(name.matches(Regex("[a-zA-Z0-9-]+\\.json")))
        return File(root, name)
    }

    // Existing thirteen-document manifests have no manual rotation field and use0.
    private fun manualRotation(case: JSONObject): Int = case.optInt("rotation_clockwise_degrees", 0).also {
        require(it == 0 || it == 90)
    }

    private suspend fun readDevImage(context: Context, source: File, case: JSONObject): Bitmap {
        val rotation = manualRotation(case)
        var image = readScannerImage(context, Uri.fromFile(source))
        try {
            if (rotation == 90) {
                // Exact manual action in ScannerViewModel.rotate(), after decode/EXIF/bounding.
                val rotated = Bitmap.createBitmap(image, 0, 0, image.width, image.height,
                    Matrix().apply { postRotate(90f) }, true)
                if (rotated !== image) image.recycle()
                image = rotated
            }
            if (case.has("expected_input_dimensions")) {
                val expected = case.getJSONArray("expected_input_dimensions")
                check(image.width == expected.getInt(0) && image.height == expected.getInt(1))
            }
            return image
        } catch (error: Throwable) {
            image.recycle()
            throw error
        }
    }

    private fun checkExpectedModels(manifest: JSONObject, actual: JSONObject) {
        if (manifest.has("current_parser_source_sha256")) {
            check(manifest.getString("current_parser_source_sha256") == CURRENT_PARSER_SOURCE_SHA256)
            check(manifest.getString("current_ocr_text_source_sha256") == CURRENT_OCR_TEXT_SOURCE_SHA256)
        }
        // Legacy thirteen-document manifests remain valid; the new fixed manifests pin all three.
        val expected = manifest.optJSONObject("expected_model_sha256") ?: return
        for (name in listOf("det.onnx", "rec.onnx", "rec-characters.txt")) {
            check(actual.getString(name) == expected.getString(name)) { "Unexpected diagnostic model: $name" }
        }
    }

    private fun points(box: List<OcrPoint>) = JSONArray(box.map { JSONArray(listOf(it.x, it.y)) })

    private fun rows(lines: List<OcrLine>, rows: List<OcrRow>): JSONArray {
        val indices = rows.map { row -> row.lines.map { line ->
            lines.indexOfFirst { it === line }.also { check(it >= 0) }
        } }
        check(indices.flatten().sorted() == lines.indices.toList()) { "Each captured line must occur exactly once" }
        return JSONArray(rows.mapIndexed { index, row ->
            JSONObject().put("text", row.text).put("line_indices", JSONArray(indices[index]))
        })
    }

    private fun checkSameRows(expected: JSONArray, actual: JSONArray) {
        check(expected.length() == actual.length()) { "Replay row count differs from capture" }
        for (ri in 0 until expected.length()) {
            val before = expected.getJSONObject(ri)
            val after = actual.getJSONObject(ri)
            check(before.getString("text") == after.getString("text")) { "Replay row text differs at$ri" }
            val a = before.getJSONArray("line_indices")
            val b = after.getJSONArray("line_indices")
            check(a.length() == b.length()) { "Replay row membership differs at$ri" }
            for (li in 0 until a.length()) check(a.getInt(li) == b.getInt(li)) {
                "Replay line identity/order differs at$ri/$li"
            }
        }
    }

    private fun fields(draft: Stage2ReceiptDraft) = JSONObject()
        .put("date", draft.date?.toString() ?: JSONObject.NULL)
        .put("odometer_km", draft.odometerKm ?: JSONObject.NULL)
        .put("liters", draft.liters ?: JSONObject.NULL)
        .put("fuel_type", draft.fuelType?.name ?: JSONObject.NULL)

    private fun fields(draft: ReceiptDraft) = JSONObject()
        .put("date", draft.date?.toString() ?: JSONObject.NULL)
        .put("odometer_km", draft.odometerKm ?: JSONObject.NULL)
        .put("liters", draft.liters ?: JSONObject.NULL)
        .put("fuel_type", draft.fuelType?.name ?: JSONObject.NULL)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(65536)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

private const val CURRENT_PARSER_SOURCE_SHA256 = "66f776ca50cd725b74dbc58b4e740648dd1878eb85fcb77e7a1bf3752bbd57cb"
private const val CURRENT_OCR_TEXT_SOURCE_SHA256 = "4e25a5609a4c9b95cf2301525322b965a04299f3367186f7c5b481d7da3134f7"
