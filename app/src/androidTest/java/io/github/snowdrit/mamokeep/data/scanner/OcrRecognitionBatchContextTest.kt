package io.github.snowdrit.mamokeep.data.scanner

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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

/** Author-only preview. Opt-in emulator diagnostic; invokes current production recognizer. */
class OcrRecognitionBatchContextTest {
    @Test fun compareExactNativeBatchWithSolo() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Requires the frozen batch-context probe", args.getString("ocrBatchContextProbe") == "stage4-v1")
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        val run = requireNotNull(args.getString("ocrBatchContextRun"))
        require(run.matches(Regex("[a-zA-Z0-9-]+")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "ocr-dev")
        val input = File(root, "recognition-batch-context-v1")
        val manifestFile = File(input, "manifest.json")
        check(sha(manifestFile) == MANIFEST_SHA) { "Manifest pin changed" }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getJSONObject("engine_source").getString("sha256") == ENGINE_SHA)
        check(manifest.getJSONObject("ocr_text_source").getString("sha256") == OCR_TEXT_SHA)
        val output = File(root, run).apply { check(mkdirs()) { "Do not overwrite a diagnostic" } }
        val completion = JSONObject().put("complete", false).put("manifest_sha256", MANIFEST_SHA)
            .put("engine_source_sha256", ENGINE_SHA).put("ocr_text_source_sha256", OCR_TEXT_SHA)
            .put("raw_text_is_not_ground_truth", true).put("independent_acceptance", false)
            .put("model_changed", false).put("detector_run", false)
        val batchResults = JSONArray()
        val soloResults = JSONArray()
        var phase = "reflection_abi"
        val started = SystemClock.elapsedRealtime()
        try {
            // Exact source-derived JVM signature. No method search or copied-engine fallback.
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
            check(OpenCVLoader.initLocal()); Core.setNumThreads(1)
            val dictionarySpec = manifest.getJSONObject("dictionary")
            val dictionaryBytes = context.assets.open(dictionarySpec.getString("asset")).use { it.readBytes() }
            check(sha(dictionaryBytes) == dictionarySpec.getString("sha256"))
            val characters = ocrCharacters(dictionaryBytes.inputStream().bufferedReader(Charsets.UTF_8).use { it.readLines() })
            val modelSpec = manifest.getJSONObject("model")
            val model = File(output, "rec.onnx")
            context.assets.open(modelSpec.getString("asset")).use { source -> model.outputStream().use { source.copyTo(it) } }
            check(sha(model) == modelSpec.getString("sha256"))
            completion.put("model_sha256", sha(model)).put("dictionary_sha256", sha(dictionaryBytes))
            val engine = OcrEngine()
            val environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
            val peerByKey = mutableMapOf<String, JSONObject>()
            val baselineByKey = mutableMapOf<String, OcrLine>()
            val blocks = manifest.getJSONArray("blocks")
            val dummy = Mat() // The frozen region readers ignore it and return exact PNG clones.
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(2); options.setInterOpNumThreads(1); options.setCPUArenaAllocator(false)
                    environment.createSession(model.absolutePath, options).use { session ->
                        try { validate.invoke(engine, session, true) }
                        catch (error: InvocationTargetException) { throw error.targetException }
                        phase = "all_original_batches_before_any_solo"
                        for (bi in 0 until blocks.length()) {
                            currentCoroutineContext().ensureActive()
                            val block = blocks.getJSONObject(bi)
                            val peers = block.getJSONArray("peers")
                            check(peers.length() == block.getInt("peer_count") && peers.length() in 1..6)
                            val loaded = mutableListOf<Mat>()
                            try {
                                val regions = (0 until peers.length()).map { pi ->
                                    val peer = peers.getJSONObject(pi)
                                    check(peerByKey.put(peer.getString("key"), peer) == null)
                                    val pixels = loadPeer(input, peer).also(loaded::add)
                                    val reader: (Mat) -> Mat = { pixels.clone() }
                                    constructor.newInstance(points(peer.getJSONObject("expected_native").getJSONArray("box")),
                                        peer.getInt("width") to peer.getInt("height"), reader)
                                }
                                val actual = invokeRecognize(recognize, engine, environment, session, dummy, regions, characters)
                                check(actual.size == peers.length())
                                val lines = JSONArray()
                                var exact = true
                                actual.forEachIndexed { pi, line ->
                                    val peer = peers.getJSONObject(pi)
                                    val expected = peer.getJSONObject("expected_native")
                                    val same = line.text == expected.getString("text") &&
                                        line.confidence.toBits() == expected.getDouble("confidence").toBits() &&
                                        line.box == points(expected.getJSONArray("box"))
                                    exact = exact && same
                                    baselineByKey[peer.getString("key")] = line
                                    lines.put(lineJson(line).put("key", peer.getString("key")).put("exact_saved_identity", same))
                                }
                                batchResults.put(JSONObject().put("id", block.getString("id"))
                                    .put("batch_width", block.getInt("batch_width")).put("all_exact", exact).put("lines", lines))
                                File(output, "baseline.json").writeText(batchResults.toString(2))
                                check(exact) { "BASELINE_IDENTITY_MISMATCH_STOP: ${block.getString("id")}" }
                            } finally { loaded.forEach { it.release() } }
                        }
                        check(baselineByKey.size == 35)
                        completion.put("all_35_baseline_identity_passed_before_solo", true)
                        phase = "seven_fixed_solo_targets"
                        val targets = manifest.getJSONArray("targets")
                        check(targets.length() == 7)
                        for (ti in 0 until targets.length()) {
                            currentCoroutineContext().ensureActive()
                            val target = targets.getJSONObject(ti)
                            val key = target.getString("key")
                            val peer = peerByKey.getValue(key)
                            val pixels = loadPeer(input, peer)
                            try {
                                val reader: (Mat) -> Mat = { pixels.clone() }
                                val region = constructor.newInstance(points(peer.getJSONObject("expected_native").getJSONArray("box")),
                                    peer.getInt("width") to peer.getInt("height"), reader)
                                val actual = invokeRecognize(recognize, engine, environment, session, dummy, listOf(region), characters).single()
                                val before = baselineByKey.getValue(key)
                                check(actual.box == before.box)
                                val literal = target.getString("expected_literal")
                                val positive = target.getBoolean("historical_baseline_literal_present")
                                check(before.text.contains(literal) == positive)
                                soloResults.put(JSONObject().put("key", key).put("role", target.getString("role"))
                                    .put("batch_width", target.getInt("batch_width")).put("solo_width", target.getInt("solo_width"))
                                    .put("batch_resized_width", target.getInt("batch_resized_width"))
                                    .put("solo_resized_width", target.getInt("solo_resized_width"))
                                    .put("before", lineJson(before)).put("after", lineJson(actual))
                                    .put("whole_text_equal", actual.text == before.text)
                                    .put("confidence_bits_equal", actual.confidence.toBits() == before.confidence.toBits())
                                    .put("literal", literal).put("literal_present_before", before.text.contains(literal))
                                    .put("literal_present_after", actual.text.contains(literal))
                                    .put("known_positive_literal_lost", positive && !actual.text.contains(literal))
                                    .put("any_changed_text_needs_source_review", actual.text != before.text))
                                File(output, "solo.json").writeText(soloResults.toString(2))
                            } finally { pixels.release() }
                        }
                    }
                }
            } finally { dummy.release() }
            completion.put("complete", true).put("status", "DIAGNOSTIC_COMPLETE_NOT_PRODUCT_ACCEPTANCE")
                .put("blocks", batchResults.length()).put("baseline_peers", baselineByKey.size).put("solo_targets", soloResults.length())
                .put("positive_literal_losses", (0 until soloResults.length()).count { soloResults.getJSONObject(it).getBoolean("known_positive_literal_lost") })
                .put("changed_whole_texts", (0 until soloResults.length()).count { !soloResults.getJSONObject(it).getBoolean("whole_text_equal") })
        } catch (error: Throwable) {
            completion.put("status", if (phase == "reflection_abi") "ABI_UNAVAILABLE_NOT_LAUNCHABLE" else "STOP")
                .put("failed_phase", phase).put("error_class", error.javaClass.name).put("error", error.message)
            throw error
        } finally {
            completion.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            File(output, "completion.json").writeText(completion.toString(2))
        }
    }

    private fun loadPeer(input: File, peer: JSONObject): Mat {
        val file = File(input, peer.getString("file"))
        check(sha(file) == peer.getJSONObject("source_png").getString("sha256"))
        val pixels = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR)
        try {
            check(!pixels.empty() && pixels.type() == CvType.CV_8UC3)
            check(pixels.cols() == peer.getInt("width") && pixels.rows() == peer.getInt("height"))
            return pixels
        } catch (error: Throwable) { pixels.release(); throw error }
    }

    private suspend fun invokeRecognize(
        method: Method, engine: OcrEngine, environment: OrtEnvironment, session: OrtSession,
        dummy: Mat, regions: List<Any>, characters: List<String>,
    ): List<OcrLine> = suspendCoroutineUninterceptedOrReturn { continuation: Continuation<List<OcrLine>> ->
        try { method.invoke(engine, environment, session, dummy, regions, characters, null, 0, 0, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    private fun points(box: JSONArray): List<OcrPoint> = (0 until box.length()).map {
        val point = box.getJSONArray(it); OcrPoint(point.getDouble(0), point.getDouble(1))
    }

    private fun lineJson(line: OcrLine): JSONObject = JSONObject().put("text", line.text)
        .put("confidence", line.confidence).put("confidence_double_bits", line.confidence.toBits().toString())
        .put("box", JSONArray(line.box.map { listOf(it.x, it.y) }))

    private fun sha(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val count = stream.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MANIFEST_SHA = "d5d89c8de5b58c8d4e8a4b4ead27bf808f009ace07772b3aa957d4a87ee9c0ff"
        private const val ENGINE_SHA = "e0bc00d84f0dd783b4d3afb5177c98f191c0d2b32bcdf5e957684a085dbc47e1"
        private const val OCR_TEXT_SHA = "4e25a5609a4c9b95cf2301525322b965a04299f3367186f7c5b481d7da3134f7"
    }
}
