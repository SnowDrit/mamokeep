package io.github.snowdrit.mamokeep.data.scanner

import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.math.ceil

/** Opt-in debug Android recognition component baseline; no detector or parser. */
class OcrManualCropRecognitionTest {
    @Test fun modelOverrideArgumentsAreExplicitAndPinned() {
        val name = "ctc-native-head-pilot25-v1.onnx"
        val hash = "1".repeat(64)
        assertNull(readModelOverride(null, null))
        assertEquals(ModelOverride(name, hash), readModelOverride(name, hash))
        assertThrows(IllegalArgumentException::class.java) { readModelOverride(name, null) }
        assertThrows(IllegalArgumentException::class.java) { readModelOverride(null, hash) }
        for (badName in listOf("../candidate.onnx", "nested/candidate.onnx", "/candidate.onnx",
            "candidate.bin", "candidate.ONNX", ".hidden.onnx", "candidate.rec.onnx", "")) {
            assertThrows(IllegalArgumentException::class.java) { readModelOverride(badName, hash) }
        }
        for (badHash in listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "A".repeat(64))) {
            assertThrows(IllegalArgumentException::class.java) { readModelOverride(name, badHash) }
        }
    }

    @Test fun recognizePinnedManualCrops() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires a frozen manual-crop manifest", args.getString("ocrManualCropProbe") == "stage5-v1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val run = requireNotNull(args.getString("ocrManualCropRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]+")))
        val expectedManifestSha = requireNotNull(args.getString("ocrManualCropManifestSha"))
        require(expectedManifestSha.matches(SHA_PATTERN))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "ocr-dev")
        val input = File(root, "manual-crop-recognition-v1")
        val output = File(root, run).apply { check(mkdirs()) { "Do not overwrite a diagnostic" } }
        val completion = JSONObject().put("complete", false).put("manifest_sha256", expectedManifestSha)
            .put("engine_source_sha256", ENGINE_SHA).put("ocr_text_source_sha256", OCR_TEXT_SHA)
            .put("source_binding_requires_host_build_proof", true)
            .put("component_debug_android_only", true).put("optimized_release_accuracy", false)
            .put("raw_text_is_not_ground_truth", true).put("independent_acceptance", false)
            .put("detector_run", false).put("parser_run", false).put("database_writes", false)
            .put("model_changed", false).put("batch_policy_changed", false)
            .put("batch_metadata_kind", "DERIVED_FROM_PRODUCTION_FORMULA_NOT_TENSOR_TRACE")
        val pagesOutput = JSONArray()
        var phase = "manifest"
        var cropIoMs = 0L
        var recognizedCrops = 0
        val started = SystemClock.elapsedRealtime()
        try {
            phase = "model_override_arguments"
            val modelOverride = readModelOverride(
                args.getString("ocrManualCropModelFile"), args.getString("ocrManualCropModelSha"),
            )
            phase = "manifest"
            val manifestFile = File(input, "manifest.json")
            check(sha(manifestFile) == expectedManifestSha) { "Manifest pin changed" }
            val manifest = JSONObject(manifestFile.readText())
            validateManifest(manifest, input)
            val pages = manifest.getJSONArray("pages")
            completion.put("source_review_freeze_sha256", manifest.getString("source_review_freeze_sha256"))
                .put("source_review_verified_by_runtime", false).put("pages_requested", pages.length())

            phase = "reflection_abi"
            // Reuse the exact current production ABI; no method search or copied-engine fallback.
            val regionClass = Class.forName(OcrEngine::class.java.name + "\$RecognitionRegion")
            val constructor = regionClass.getDeclaredConstructor(List::class.java, Pair::class.java, Function1::class.java)
                .apply { isAccessible = true }
            val recognize = OcrEngine::class.java.getDeclaredMethod(
                "recognizeLines", OrtEnvironment::class.java, OrtSession::class.java, Mat::class.java,
                List::class.java, List::class.java, Function2::class.java,
                Integer.TYPE, Integer.TYPE, Continuation::class.java,
            ).apply { isAccessible = true }
            val validate = OcrEngine::class.java.getDeclaredMethod("validateInput", OrtSession::class.java, java.lang.Boolean.TYPE)
                .apply { isAccessible = true }
            completion.put("reflection_abi_resolved", true)

            phase = "opencv_and_assets"
            check(OpenCVLoader.initLocal()); Core.setNumThreads(1)
            val dictionarySpec = manifest.getJSONObject("dictionary")
            val dictionaryBytes = context.assets.open(dictionarySpec.getString("asset")).use { it.readBytes() }
            check(sha(dictionaryBytes) == dictionarySpec.getString("sha256")) { "Dictionary pin changed" }
            val characters = ocrCharacters(dictionaryBytes.inputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() })
            val modelSpec = manifest.getJSONObject("model")
            val model = File(output, "rec.onnx")
            if (modelOverride == null) {
                context.assets.open(modelSpec.getString("asset")).use { source -> model.outputStream().use { source.copyTo(it) } }
                check(sha(model) == modelSpec.getString("sha256")) { "Model pin changed" }
            } else {
                phase = "private_candidate_model"
                val candidate = candidateModelFile(root, modelOverride)
                check(sha(candidate) == modelOverride.expectedSha) { "Candidate model pin changed" }
                candidate.inputStream().use { source -> model.outputStream().use { source.copyTo(it) } }
                check(sha(model) == modelOverride.expectedSha) { "Copied candidate model pin changed" }
                phase = "opencv_and_assets"
            }
            completion.put("model_id", modelSpec.getString("id")).put("model_version", modelSpec.getString("version"))
                .put("model_asset", modelSpec.getString("asset")).put("model_sha256", sha(model))
                .put("model_origin", if (modelOverride == null) "bundled_asset" else "private_candidate")
                .put("manifest_baseline_model_sha", modelSpec.getString("sha256"))
                .put("actual_override_file", modelOverride?.fileName ?: JSONObject.NULL)
                .put("model_changed", modelOverride != null)
                .put("dictionary_asset", dictionarySpec.getString("asset")).put("dictionary_sha256", sha(dictionaryBytes))

            val engine = OcrEngine()
            val environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
            val dummy = Mat() // Readers ignore this source and load the exact pinned PNG, without rewarping.
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1); options.setCPUArenaAllocator(false)
                    phase = "recognizer_session"
                    environment.createSession(model.absolutePath, options).use { session ->
                        try { validate.invoke(engine, session, true) }
                        catch (error: InvocationTargetException) { throw error.targetException }
                        for (pi in 0 until pages.length()) {
                            currentCoroutineContext().ensureActive()
                            val page = pages.getJSONObject(pi)
                            phase = "page_${page.getString("id")}_recognition"
                            val cropArray = page.getJSONArray("crops")
                            val crops = (0 until cropArray.length()).map { cropArray.getJSONObject(it) }
                            val regions = crops.map { crop ->
                                val reader: (Mat) -> Mat = {
                                    val cropStarted = SystemClock.elapsedRealtime()
                                    try { loadCrop(input, crop) }
                                    finally { cropIoMs += SystemClock.elapsedRealtime() - cropStarted }
                                }
                                constructor.newInstance(points(crop.getJSONArray("native_quad")),
                                    crop.getInt("width") to crop.getInt("height"), reader)
                            }
                            // Exactly one production call/page. Its stable aspect sorting, batches,
                            // resizing, padding, normalization and CTC decoder remain authoritative.
                            val actual = invokeRecognize(recognize, engine, environment, session, dummy, regions, characters)
                            val sorted = crops.sortedBy { it.getInt("width").toDouble() / it.getInt("height") }
                            check(actual.size == sorted.size) { "Recognition count mismatch" }
                            val lines = JSONArray()
                            val batches = JSONArray()
                            var resultIndex = 0
                            sorted.chunked(6).forEachIndexed { batchIndex, batch ->
                                val batchWidth = (48 * maxOf(320.0 / 48,
                                    batch.maxOf { it.getInt("width").toDouble() / it.getInt("height") })).toInt()
                                batches.put(JSONObject().put("index", batchIndex).put("width", batchWidth)
                                    .put("crop_ids", JSONArray(batch.map { it.getString("id") })))
                                batch.forEach { crop ->
                                    val line = actual[resultIndex]
                                    check(line.box == points(crop.getJSONArray("native_quad"))) { "Output identity mismatch" }
                                    val resizedWidth = minOf(batchWidth,
                                        ceil(48 * crop.getInt("width").toDouble() / crop.getInt("height")).toInt())
                                    lines.put(JSONObject().put("crop_id", crop.getString("id"))
                                        .put("visit_rank", crop.getInt("visit_rank")).put("result_index", resultIndex)
                                        .put("source_png_sha256", crop.getString("sha256"))
                                        .put("provenance_sha256", crop.getString("provenance_sha256"))
                                        .put("width", crop.getInt("width")).put("height", crop.getInt("height"))
                                        .put("native_quad", JSONArray(line.box.map { listOf(it.x, it.y) }))
                                        .put("batch_index", batchIndex).put("batch_width", batchWidth)
                                        .put("resized_width", resizedWidth).put("recognition_height", 48)
                                        .put("text", line.text).put("confidence", line.confidence)
                                        .put("confidence_double_bits", line.confidence.toBits().toString()))
                                    resultIndex++
                                }
                            }
                            recognizedCrops += actual.size
                            pagesOutput.put(JSONObject().put("page_id", page.getString("id"))
                                .put("source_group", page.getString("source_group")).put("development_role", page.getString("development_role"))
                                .put("source_sha256", page.getString("source_sha256"))
                                .put("crop_count", actual.size).put("batches", batches).put("lines", lines))
                            File(output, "pages.json").writeText(pagesOutput.toString(2))
                        }
                    }
                }
            } finally { dummy.release() }
            completion.put("complete", true).put("status", "COMPONENT_BASELINE_COMPLETE_NOT_PRODUCT_ACCEPTANCE")
                .put("pages_completed", pagesOutput.length()).put("crops_recognized", recognizedCrops)
        } catch (error: Throwable) {
            completion.put("status", if (phase == "reflection_abi") "ABI_UNAVAILABLE_NOT_LAUNCHABLE" else "STOP")
                .put("failed_phase", phase).put("error_class", error.javaClass.name).put("error", error.message)
            throw error
        } finally {
            val runtime = Runtime.getRuntime()
            completion.put("elapsed_ms", SystemClock.elapsedRealtime() - started).put("crop_io_ms", cropIoMs)
                .put("pages_completed", pagesOutput.length()).put("crops_recognized", recognizedCrops)
                .put("java_heap_bytes_after", runtime.totalMemory() - runtime.freeMemory())
                .put("native_heap_bytes_after", Debug.getNativeHeapAllocatedSize()).put("heap_after_is_not_peak", true)
                .put("android_sdk", android.os.Build.VERSION.SDK_INT).put("android_fingerprint", android.os.Build.FINGERPRINT)
                .put("supported_abis", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
            File(output, "completion.json").writeText(completion.toString(2))
        }
    }

    private data class ModelOverride(val fileName: String, val expectedSha: String)

    private fun readModelOverride(fileName: String?, expectedSha: String?): ModelOverride? {
        if (fileName == null && expectedSha == null) return null
        val name = requireNotNull(fileName) { "ocrManualCropModelFile and ocrManualCropModelSha are required together" }
        val hash = requireNotNull(expectedSha) { "ocrManualCropModelFile and ocrManualCropModelSha are required together" }
        require(name.matches(MODEL_FILE_PATTERN)) { "Candidate model must be a safe .onnx basename" }
        require(hash.matches(SHA_PATTERN)) { "Candidate model SHA must be 64 lowercase hex characters" }
        return ModelOverride(name, hash)
    }

    private fun candidateModelFile(root: File, modelOverride: ModelOverride): File {
        val canonicalRoot = root.canonicalFile
        val candidate = File(canonicalRoot, modelOverride.fileName).canonicalFile
        check(candidate.parentFile == canonicalRoot) { "Candidate model must stay directly inside private ocr-dev root" }
        check(candidate.isFile) { "Candidate model file is missing or is not a regular file" }
        return candidate
    }

    private fun validateManifest(manifest: JSONObject, input: File) {
        keys(manifest, setOf("schema_version", "engine_source_sha256", "ocr_text_source_sha256",
            "source_review_freeze_sha256", "model", "dictionary", "pages"))
        check(manifest.getInt("schema_version") == 1)
        check(manifest.getString("engine_source_sha256") == ENGINE_SHA)
        check(manifest.getString("ocr_text_source_sha256") == OCR_TEXT_SHA)
        hashField(manifest, "source_review_freeze_sha256")
        val model = manifest.getJSONObject("model")
        keys(model, setOf("id", "version", "asset", "sha256"))
        check(model.getString("id").isNotBlank() && model.getString("version").isNotBlank())
        assetField(model); hashField(model, "sha256")
        val dictionary = manifest.getJSONObject("dictionary")
        keys(dictionary, setOf("asset", "sha256")); assetField(dictionary); hashField(dictionary, "sha256")
        val pageIds = mutableSetOf<String>()
        val cropIds = mutableSetOf<String>()
        val pages = manifest.getJSONArray("pages")
        check(pages.length() > 0)
        for (pi in 0 until pages.length()) {
            val page = pages.getJSONObject(pi)
            keys(page, setOf("id", "source_group", "development_role", "split", "source_sha256", "crops"))
            check(page.getString("id").isNotBlank() && pageIds.add(page.getString("id")))
            check(page.getString("source_group").isNotBlank() && page.getString("split") == "train")
            check(page.getString("development_role") in setOf("fit", "diagnostic", "selection"))
            hashField(page, "source_sha256")
            val crops = page.getJSONArray("crops")
            var previousVisit = 0
            for (ci in 0 until crops.length()) {
                val crop = crops.getJSONObject(ci)
                keys(crop, setOf("id", "visit_rank", "file", "sha256", "width", "height", "native_quad", "provenance_sha256"))
                check(crop.getString("id").isNotBlank() && cropIds.add(crop.getString("id")))
                val visit = crop.getInt("visit_rank"); check(visit > previousVisit); previousVisit = visit
                check(crop.getInt("width") > 0 && crop.getInt("height") > 0)
                hashField(crop, "sha256"); hashField(crop, "provenance_sha256")
                points(crop.getJSONArray("native_quad"))
                cropFile(input, crop)
            }
        }
        check(cropIds.isNotEmpty()) { "No manual crops requested" }
    }

    // Exact allowed keys make the inference manifest label-free; unknown/GT fields fail.
    private fun keys(value: JSONObject, expected: Set<String>) {
        check(value.keys().asSequence().toSet() == expected) { "Unexpected or missing manifest keys" }
    }

    private fun hashField(value: JSONObject, key: String) { check(value.getString(key).matches(SHA_PATTERN)) }

    private fun assetField(value: JSONObject) {
        val asset = value.getString("asset")
        check(asset.startsWith("scanner/") && asset.split('/').none { it.isEmpty() || it == "." || it == ".." })
    }

    private fun cropFile(input: File, crop: JSONObject): File {
        val name = crop.getString("file")
        check(!File(name).isAbsolute && name.endsWith(".png"))
        val file = File(input, name).canonicalFile
        check(file.path.startsWith(input.canonicalPath + File.separator)) { "PNG must stay inside staged input" }
        return file
    }

    private fun loadCrop(input: File, crop: JSONObject): Mat {
        val file = cropFile(input, crop)
        check(sha(file) == crop.getString("sha256")) { "PNG pin changed" }
        val pixels = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        try {
            check(!pixels.empty() && pixels.type() == CvType.CV_8UC3)
            check(pixels.cols() == crop.getInt("width") && pixels.rows() == crop.getInt("height"))
            return pixels // Production recognizeLines releases it with useMat, including on failure.
        } catch (error: Throwable) { pixels.release(); throw error }
    }

    private suspend fun invokeRecognize(
        method: Method, engine: OcrEngine, environment: OrtEnvironment, session: OrtSession,
        dummy: Mat, regions: List<Any>, characters: List<String>,
    ): List<OcrLine> = suspendCoroutineUninterceptedOrReturn { continuation: Continuation<List<OcrLine>> ->
        try { method.invoke(engine, environment, session, dummy, regions, characters, null, 0, 0, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun points(box: JSONArray): List<OcrPoint> {
        check(box.length() == 4)
        return (0 until box.length()).map {
            val point = box.getJSONArray(it); check(point.length() == 2)
            val x = point.getDouble(0); val y = point.getDouble(1)
            check(x.isFinite() && y.isFinite())
            OcrPoint(x, y)
        }
    }

    private fun sha(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val SHA_PATTERN = Regex("[0-9a-f]{64}")
        private val MODEL_FILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.onnx")
        private const val ENGINE_SHA = "e0bc00d84f0dd783b4d3afb5177c98f191c0d2b32bcdf5e957684a085dbc47e1"
        private const val OCR_TEXT_SHA = "4e25a5609a4c9b95cf2301525322b965a04299f3367186f7c5b481d7da3134f7"
    }
}
