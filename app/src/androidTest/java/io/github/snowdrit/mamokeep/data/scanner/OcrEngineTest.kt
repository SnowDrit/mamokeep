package io.github.snowdrit.mamokeep.data.scanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.FloatBuffer
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Model assets belong only to the test APK. These tests perform actual CPU inference. */
class OcrEngineTest {
    @Test fun bentTextUsesContourSupportWithoutReplacingOriginalRegions() = runBlocking<Unit> {
        check(OpenCVLoader.initLocal())
        val engine = OcrEngine()
        val method = OcrEngine::class.java.getDeclaredMethod("detectorBoxes", Mat::class.java,
            Integer.TYPE, Integer.TYPE, Continuation::class.java).apply { isAccessible = true }
        suspend fun counts(map: Mat): Pair<Int, Int> {
            val result = suspendCoroutineUninterceptedOrReturn<Any> { continuation ->
                try { method.invoke(engine, map, map.cols(), map.rows(), continuation) }
                catch (error: InvocationTargetException) { throw error.targetException }
            }
            fun count(getter: String) = (result.javaClass.getDeclaredMethod(getter)
                .apply { isAccessible = true }.invoke(result) as List<*>).size
            return count("getOriginal") to count("getContours")
        }
        val map = Mat.zeros(96, 256, CvType.CV_32FC1)
        val band = MatOfPoint(Point(20.0, 20.0), Point(128.0, 10.0), Point(236.0, 20.0),
            Point(236.0, 28.0), Point(128.0, 18.0), Point(20.0, 28.0))
        val shortBand = MatOfPoint(Point(20.0, 20.0), Point(60.0, 10.0), Point(100.0, 20.0),
            Point(100.0, 28.0), Point(60.0, 18.0), Point(20.0, 28.0))
        try {
            Imgproc.fillPoly(map, listOf(shortBand), Scalar(.9))
            assertEquals(0 to 0, counts(map))
            map.setTo(Scalar(0.0))
            Imgproc.fillPoly(map, listOf(band), Scalar(.9))
            assertEquals(0 to 1, counts(map))
            Core.multiply(map, Scalar(.4 / .9), map)
            assertEquals(0 to 0, counts(map))
            map.setTo(Scalar(0.0))
            Imgproc.rectangle(map, Point(20.0, 20.0), Point(100.0, 32.0), Scalar(.9), -1)
            assertEquals(1 to 0, counts(map))
            map.setTo(Scalar(0.0))
            assertEquals(0 to 0, counts(map))
        } finally { shortBand.release(); band.release(); map.release() }
    }

    @Test fun publishedV1ReadsSyntheticDocumentAndBlankPageIsEmpty() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val modelDirectory = File(instrumentation.targetContext.cacheDir, "ocr-instrumentation-models")
        modelDirectory.mkdirs()
        try {
            for (name in listOf("det.onnx", "rec.onnx", "rec-characters.txt")) {
                instrumentation.context.assets.open("ocr-v1/$name").use { input ->
                    File(modelDirectory, name).outputStream().use { input.copyTo(it) }
                }
            }
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(1)
                environment.createSession(File(modelDirectory, "rec.onnx").absolutePath, options).use { session ->
                    for ((width, expectedSteps) in listOf(321 to 40, 324 to 40, 325 to 41)) {
                        OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(3 * 48 * width)), longArrayOf(1, 3, 48, width.toLong())).use { input ->
                            session.run(mapOf("x" to input)).use { result ->
                                val output = result.get("fetch_name_0").get() as OnnxTensor
                                assertEquals(expectedSteps, ocrRecognitionSteps(output.info.shape, 1, 519))
                            }
                        }
                    }
                }
            }
            val image = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(image)
                canvas.drawColor(Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 54f
                    typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                }
                canvas.drawText("TOTAL 1234.50", 60f, 130f, paint)
                canvas.drawText("12.10.2026", 60f, 220f, paint)
                val actual = OcrEngine().recognize(image, modelDirectory)
                assertTrue("Recognized: ${actual.text}", actual.text.contains("1234.50"))
                assertTrue("Recognized: ${actual.text}", actual.text.contains("12.10.2026"))
                assertEquals(960, actual.width)
                assertEquals(320, actual.height)
                assertTrue(actual.lines.all { it.box.size == 4 && it.confidence in 0.0..1.0 })
                var observedLines = 0
                val observed = OcrEngine { firstLineIndex, batchSize, width, input ->
                    assertEquals(observedLines, firstLineIndex)
                    assertTrue(batchSize in 1..6 && width in 320..3200)
                    assertEquals(batchSize * 3 * 48 * width, input.size)
                    assertTrue(input.all { it.isFinite() && it in -1f..1f })
                    observedLines += batchSize
                }.recognize(image, modelDirectory)
                assertEquals(actual.lines.size, observedLines)
                assertEquals("Reading input tensors must preserve the complete document", actual, observed)
                canvas.drawColor(Color.WHITE)
                val blank = OcrEngine().recognize(image, modelDirectory)
                assertTrue(blank.lines.isEmpty())
                assertEquals("", blank.text)
            } finally { image.recycle() }
        } finally { modelDirectory.deleteRecursively() }
    }
}
