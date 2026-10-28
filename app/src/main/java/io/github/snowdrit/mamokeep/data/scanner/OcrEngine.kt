/*
 * Pre/postprocessing adapted from PaddleOCR / RapidOCR 3.9.2 (Apache-2.0).
 * Copyright (c) 2020 PaddlePaddle Authors. All Rights Reserved.
 * Copyright (c) 2021 RapidOCR Authors. All rights reserved.
 * MamoKeep modifications: Kotlin/Android port, bounded lifecycle, cancellation,
 * explicit model contracts, and analytic rectangle offset in place of Pyclipper.
 * Full license: assets/licenses/Apache-2.0.txt; provenance: docs/qa/ocr-2026-10-22/engine.md.
 */
package io.github.snowdrit.mamokeep.data.scanner

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvException
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Offline PP-OCRv5 v1 CPU pipeline. The caller holds the verified model-store lease for
 * the entire call. No model downloads or database writes happen here. Bitmap ownership
 * stays with the caller. Sessions and OpenCV memory are released before returning.
 */
class OcrEngine {
    private val onRecognitionInput: ((Int, Int, Int, FloatArray) -> Unit)?

    constructor() { onRecognitionInput = null }

    /** Local instrumentation may read the borrowed NCHW input; it must not modify it. */
    internal constructor(observer: (firstLineIndex: Int, batchSize: Int, width: Int, input: FloatArray) -> Unit) {
        onRecognitionInput = observer
    }

    suspend fun recognize(image: Bitmap, modelDirectory: File): OcrDocument = recognize(image, modelDirectory, null)

    /** Local instrumentation can inspect each borrowed BGR crop before resize; it must not modify it. */
    internal suspend fun recognize(
        image: Bitmap, modelDirectory: File, onCrop: ((Int, Mat) -> Unit)?,
    ): OcrDocument = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        if (image.isRecycled || image.width <= 0 || image.height <= 0 ||
            maxOf(image.width, image.height) > OCR_MAX_IMAGE_SIDE || image.config == Bitmap.Config.HARDWARE) {
            throw OcrException(OcrFailure.INPUT, "Expected an upright software bitmap with longest side <= $OCR_MAX_IMAGE_SIDE")
        }
        val characters = withContext(Dispatchers.IO) {
            ocrCharacters(File(modelDirectory, "rec-characters.txt").readLines(Charsets.UTF_8))
        }
        if (!OpenCVLoader.initLocal()) throw OcrException(OcrFailure.ENGINE, "OpenCV initialization failed")
        try {
            val environment = OrtEnvironment.getEnvironment()
            environment.setTelemetry(false)
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(CPU_THREADS)
                options.setInterOpNumThreads(1)
                options.setCPUArenaAllocator(false)
                Mat().useMat { bgr ->
                    Mat().useMat { rgba ->
                        Utils.bitmapToMat(image, rgba)
                        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
                    }
                    val detection = environment.createSession(File(modelDirectory, "det.onnx").absolutePath, options).use { detector ->
                        validateInput(detector, recognition = false)
                        detect(environment, detector, bgr)
                    }
                    currentCoroutineContext().ensureActive()
                    val boxes = detection.regions.original.map { it.box }
                    val grid = OcrGrid.analyze(bgr, boxes)
                    val rescues = grid.rescues.filter { detection.evidence.supports(it.box) }
                    // A contour can describe a line already recovered by a printed cell.
                    // Keep one recognition region when most of either region is shared.
                    // Crop padding may cover a neighboring row; ownership uses
                    // the detector core, before unclip adds recognition context.
                    val occupied = (detection.regions.original.map { it.core } + rescues.map { it.box }).toMutableList()
                    val contourBoxes = mutableListOf<DetectorRegion>()
                    for (region in detection.regions.contours) {
                        val duplicate = occupied.any { other ->
                            ocrIntersectionArea(region.core, other) > minOf(ocrPolygonArea(region.core), ocrPolygonArea(other)) / 2
                        }
                        if (!duplicate) { contourBoxes.add(region); occupied.add(region.core) }
                    }
                    currentCoroutineContext().ensureActive()
                    val lines = if (boxes.isEmpty() && rescues.isEmpty() && contourBoxes.isEmpty()) emptyList() else {
                        environment.createSession(File(modelDirectory, "rec.onnx").absolutePath, options).use { recognizer ->
                            validateInput(recognizer, recognition = true)
                            val regions = boxes.map { box -> RecognitionRegion(box, cropSize(box)) { crop(it, box) } }
                            val original = recognizeLines(environment, recognizer, bgr, regions, characters, onCrop)
                            // Keep original batch widths/context: a rescued cell must not change
                            // the tensors or text of already detected lines.
                            val rescued = rescues.map { rescue ->
                                RecognitionRegion(rescue.box, rescue.width to rescue.height, rescue::crop)
                            }
                            val previous = original + recognizeLines(environment, recognizer, bgr, rescued, characters, onCrop,
                                firstIndex = original.size, initialTextCharacters = original.sumOf { it.text.length + 1 })
                            // Extra contours must not change either existing batch context.
                            val contours = contourBoxes.map { region ->
                                RecognitionRegion(region.core, cropSize(region.box)) { crop(it, region.box) }
                            }
                            previous + recognizeLines(environment, recognizer, bgr, contours, characters, onCrop,
                                firstIndex = previous.size, initialTextCharacters = previous.sumOf { it.text.length + 1 })
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    OcrDocument(image.width, image.height, lines, groupOcrRows(lines, grid = grid.tables))
                }
            }
        } catch (error: OrtException) {
            currentCoroutineContext().ensureActive()
            throw OcrException(OcrFailure.ENGINE, "ONNX inference failed", error)
        } catch (error: CvException) {
            currentCoroutineContext().ensureActive()
            throw OcrException(OcrFailure.ENGINE, "Image processing failed", error)
        }
    }

    private fun validateInput(session: OrtSession, recognition: Boolean) {
        val info = session.inputInfo[INPUT_NAME]?.info as? TensorInfo
        val shape = info?.shape
        if (session.inputNames != setOf(INPUT_NAME) || session.outputNames != setOf(OUTPUT_NAME) ||
            info?.type != OnnxJavaType.FLOAT || shape == null || shape.size != 4 || shape[1] != 3L ||
            (recognition && shape[2] != REC_HEIGHT.toLong())) {
            throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Unexpected PP-OCRv5 input/output contract")
        }
    }

    private data class DetectorRegion(val box: List<OcrPoint>, val core: List<OcrPoint>)
    private data class DetectorBoxes(val original: List<DetectorRegion>, val contours: List<DetectorRegion>)
    private data class Detection(val regions: DetectorBoxes, val evidence: OcrDetectorEvidence)

    private suspend fun detect(environment: OrtEnvironment, session: OrtSession, source: Mat): Detection {
        val ratio = minOf(1.0, DET_SIDE.toDouble() / maxOf(source.rows(), source.cols()))
        val height = (round((source.rows() * ratio).toInt() / 32.0) * 32).toInt()
        val width = (round((source.cols() * ratio).toInt() / 32.0) * 32).toInt()
        if (height <= 0 || width <= 0) throw OcrException(OcrFailure.INPUT, "Image is too narrow for text detection")
        return Mat().useMat { resized ->
            Imgproc.resize(source, resized, Size(width.toDouble(), height.toDouble()))
            val input = FloatArray(3 * width * height)
            normalizeInto(resized, width, DET_MEAN, DET_STD, input, 0)
            currentCoroutineContext().ensureActive()
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, height.toLong(), width.toLong())).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).use { output ->
                    currentCoroutineContext().ensureActive()
                    val probabilities = tensorOutput(output, longArrayOf(1, 1, height.toLong(), width.toLong()))
                    if (probabilities.any { !it.isFinite() || it < 0f || it > 1f }) {
                        throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Invalid OCR output probabilities")
                    }
                    Mat(height, width, CvType.CV_32FC1).useMat { map ->
                        map.put(0, 0, probabilities)
                        val boxes = detectorBoxes(map, source.cols(), source.rows())
                        Detection(boxes, OcrDetectorEvidence.from(probabilities, width, height,
                            source.cols(), source.rows(), DET_THRESHOLD, DET_BOX_THRESHOLD))
                    }
                }
            }
        }
    }

    private suspend fun detectorBoxes(map: Mat, originalWidth: Int, originalHeight: Int): DetectorBoxes {
        val contours = mutableListOf<MatOfPoint>()
        try {
            Mat().useMat { mask ->
                Imgproc.threshold(map, mask, DET_THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
                mask.convertTo(mask, CvType.CV_8UC1)
                Mat.ones(2, 2, CvType.CV_8UC1).useMat { kernel -> Imgproc.dilate(mask, mask, kernel) }
                Mat().useMat { hierarchy -> Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE) }
            }
            val boxes = mutableListOf<DetectorRegion>()
            val contourBoxes = mutableListOf<DetectorRegion>()
            for (contour in contours.take(MAX_CANDIDATES)) {
                currentCoroutineContext().ensureActive()
                val box = MatOfPoint2f(*contour.toArray()).useMat { points ->
                    val rectangle = Imgproc.minAreaRect(points)
                    if (minOf(rectangle.size.width, rectangle.size.height) < 3.0) null else {
                        val corners = Array(4) { Point() }
                        rectangle.points(corners)
                        ordered(corners.map { OcrPoint(it.x, it.y) })
                    }
                } ?: continue // DB's declared minimum candidate size.
                val original = boxScore(map, box) >= DET_BOX_THRESHOLD
                val expanded = expandOcrRectangle(box, DET_UNCLIP_RATIO)
                if (minOf(distance(expanded[0], expanded[1]), distance(expanded[0], expanded[3])) < 5.0) continue
                val scaled = expanded.map { point ->
                    OcrPoint(
                        round(point.x / map.cols() * originalWidth).coerceIn(0.0, originalWidth - 1.0),
                        round(point.y / map.rows() * originalHeight).coerceIn(0.0, originalHeight - 1.0),
                    )
                }
                if (distance(scaled[0], scaled[1]).toInt() <= 3 || distance(scaled[0], scaled[3]).toInt() <= 3) continue
                if (!original) {
                    // Only long text bands qualify for this recovery. Applying
                    // contour scoring to short regions also admits QR fragments.
                    val size = cropSize(scaled)
                    if (size.first.toDouble() / size.second < MIN_CONTOUR_LINE_ASPECT) continue
                    if (boxScore(map, contour.toArray().map { OcrPoint(it.x, it.y) }) < DET_BOX_THRESHOLD) continue
                }
                val core = box.map { point ->
                    OcrPoint(round(point.x / map.cols() * originalWidth).coerceIn(0.0, originalWidth - 1.0),
                        round(point.y / map.rows() * originalHeight).coerceIn(0.0, originalHeight - 1.0))
                }
                val region = DetectorRegion(scaled, core)
                if (original) boxes.add(region) else contourBoxes.add(region)
            }
            return DetectorBoxes(boxes, contourBoxes)
        } finally { contours.forEach { it.release() } }
    }

    private fun boxScore(map: Mat, box: List<OcrPoint>): Double {
        val xmin = floor(box.minOf { it.x }).toInt().coerceIn(0, map.cols() - 1)
        val xmax = ceil(box.maxOf { it.x }).toInt().coerceIn(0, map.cols() - 1)
        val ymin = floor(box.minOf { it.y }).toInt().coerceIn(0, map.rows() - 1)
        val ymax = ceil(box.maxOf { it.y }).toInt().coerceIn(0, map.rows() - 1)
        return Mat.zeros(ymax - ymin + 1, xmax - xmin + 1, CvType.CV_8UC1).useMat { mask ->
            MatOfPoint(*box.map { Point((it.x - xmin).toInt().toDouble(), (it.y - ymin).toInt().toDouble()) }.toTypedArray()).useMat { polygon ->
                Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0))
            }
            map.submat(ymin, ymax + 1, xmin, xmax + 1).useMat { area -> Core.mean(area, mask).`val`[0] }
        }
    }

    private data class RecognitionRegion(
        val box: List<OcrPoint>, val size: Pair<Int, Int>, val read: (Mat) -> Mat,
    )

    private suspend fun recognizeLines(
        environment: OrtEnvironment, session: OrtSession, source: Mat,
        regions: List<RecognitionRegion>, characters: List<String>, onCrop: ((Int, Mat) -> Unit)?,
        firstIndex: Int = 0, initialTextCharacters: Int = 0,
    ): List<OcrLine> {
        // As in RapidOCR, equal-aspect crops share a batch to bound unnecessary padding.
        val sorted = regions.sortedBy { it.size.let { size -> size.first.toDouble() / size.second } }
        val result = mutableListOf<OcrLine>()
        var textCharacters = initialTextCharacters
        for (batch in sorted.chunked(REC_BATCH)) {
            currentCoroutineContext().ensureActive()
            val width = (REC_HEIGHT * maxOf(REC_MIN_WIDTH.toDouble() / REC_HEIGHT,
                batch.maxOf { it.size.let { size -> size.first.toDouble() / size.second } })).toInt()
            if (width > REC_MAX_WIDTH) throw OcrException(OcrFailure.LIMIT, "Text line exceeds maximum recognizer width")
            val plane = REC_HEIGHT * width
            val input = FloatArray(batch.size * 3 * plane)
            for ((index, region) in batch.withIndex()) {
                currentCoroutineContext().ensureActive()
                region.read(source).useMat { crop ->
                    onCrop?.invoke(firstIndex + result.size + index, crop)
                    val resizedWidth = minOf(width, ceil(REC_HEIGHT * crop.cols().toDouble() / crop.rows()).toInt())
                    Mat().useMat { resized ->
                        Imgproc.resize(crop, resized, Size(resizedWidth.toDouble(), REC_HEIGHT.toDouble()))
                        normalizeInto(resized, width, REC_MEAN, REC_STD, input, index * 3 * plane)
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            onRecognitionInput?.invoke(firstIndex + result.size, batch.size, width, input)
            OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(batch.size.toLong(), 3, REC_HEIGHT.toLong(), width.toLong())).use { tensor ->
                session.run(mapOf(INPUT_NAME to tensor)).use { output ->
                    currentCoroutineContext().ensureActive()
                    // PP-OCRv5's dynamic time dimension is not ceil(width / 8):
                    // for example, widths 321..324 produce 40 rather than 41 steps.
                    val steps = ocrRecognitionSteps(outputTensor(output).info.shape, batch.size, characters.size)
                    val values = tensorOutput(output, longArrayOf(batch.size.toLong(), steps.toLong(), characters.size.toLong()))
                    batch.forEachIndexed { index, region ->
                        val decoded = decodeOcr(values, steps, characters, index * steps * characters.size)
                        textCharacters += decoded.text.length + 1
                        if (textCharacters > MAX_TEXT_CHARACTERS) {
                            throw OcrException(OcrFailure.LIMIT, "Document exceeds maximum recognized text length")
                        }
                        result.add(OcrLine(decoded.text, decoded.confidence, region.box))
                    }
                }
            }
        }
        return result
    }

    private fun cropSize(box: List<OcrPoint>): Pair<Int, Int> {
        val width = maxOf(distance(box[0], box[1]), distance(box[2], box[3])).toInt()
        val height = maxOf(distance(box[0], box[3]), distance(box[1], box[2])).toInt()
        return if (height.toDouble() / width >= 1.5) height to width else width to height
    }

    private fun crop(source: Mat, box: List<OcrPoint>): Mat {
        val width = maxOf(distance(box[0], box[1]), distance(box[2], box[3])).toInt()
        val height = maxOf(distance(box[0], box[3]), distance(box[1], box[2])).toInt()
        val target = Mat()
        try {
            MatOfPoint2f(*box.map { Point(it.x, it.y) }.toTypedArray()).useMat { from ->
                MatOfPoint2f(Point(0.0, 0.0), Point(width.toDouble(), 0.0), Point(width.toDouble(), height.toDouble()), Point(0.0, height.toDouble())).useMat { to ->
                    Imgproc.getPerspectiveTransform(from, to).useMat { matrix ->
                        Imgproc.warpPerspective(source, target, matrix, Size(width.toDouble(), height.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
                    }
                }
            }
            if (height.toDouble() / width >= 1.5) Core.rotate(target, target, Core.ROTATE_90_COUNTERCLOCKWISE)
            return target
        } catch (error: Throwable) { target.release(); throw error }
    }

    private suspend fun normalizeInto(image: Mat, paddedWidth: Int, mean: DoubleArray, std: DoubleArray, output: FloatArray, offset: Int) {
        val width = image.cols()
        val height = image.rows()
        val bytes = ByteArray(width * height * 3)
        image.get(0, 0, bytes)
        val plane = paddedWidth * height
        for (y in 0 until height) {
            currentCoroutineContext().ensureActive()
            for (x in 0 until width) for (channel in 0 until 3) {
                val pixel = bytes[(y * width + x) * 3 + channel].toInt() and 255
                output[offset + channel * plane + y * paddedWidth + x] = ((pixel / 255.0 - mean[channel]) / std[channel]).toFloat()
            }
        }
    }

    private fun tensorOutput(result: OrtSession.Result, expected: LongArray): FloatArray {
        val tensor = outputTensor(result)
        if (tensor.info.type != OnnxJavaType.FLOAT || !tensor.info.shape.contentEquals(expected)) {
            throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Unexpected OCR output shape or type")
        }
        val count = expected.fold(1L) { total, value -> total * value }.toInt()
        val values = FloatArray(count)
        tensor.floatBuffer.get(values)
        return values
    }

    private fun outputTensor(result: OrtSession.Result): OnnxTensor =
        result.get(OUTPUT_NAME).orElse(null) as? OnnxTensor
            ?: throw OcrException(OcrFailure.INCOMPATIBLE_MODEL, "Missing OCR output tensor")

    private fun ordered(points: List<OcrPoint>): List<OcrPoint> {
        val xs = points.sortedBy { it.x }
        val left = xs.take(2).sortedBy { it.y }
        val right = xs.drop(2).sortedBy { it.y }
        return listOf(left[0], right[0], right[1], left[1])
    }

    private fun distance(a: OcrPoint, b: OcrPoint) = Math.hypot(a.x - b.x, a.y - b.y)

    companion object {
        private const val INPUT_NAME = "x"
        private const val OUTPUT_NAME = "fetch_name_0"
        private const val DET_SIDE = 1600
        private const val DET_THRESHOLD = 0.3
        private const val DET_BOX_THRESHOLD = 0.5
        private const val DET_UNCLIP_RATIO = 1.5
        private const val MIN_CONTOUR_LINE_ASPECT = 5.0
        private const val MAX_CANDIDATES = 1000
        private const val MAX_TEXT_CHARACTERS = 100000
        private const val REC_HEIGHT = 48
        private const val REC_MIN_WIDTH = 320
        private const val REC_MAX_WIDTH = 3200
        private const val REC_BATCH = 6
        private const val CPU_THREADS = 2
        private val DET_MEAN = doubleArrayOf(0.485, 0.456, 0.406)
        private val DET_STD = doubleArrayOf(0.229, 0.224, 0.225)
        private val REC_MEAN = doubleArrayOf(0.5, 0.5, 0.5)
        private val REC_STD = doubleArrayOf(0.5, 0.5, 0.5)
    }
}

private inline fun <T : Mat, R> T.useMat(block: (T) -> R): R = try { block(this) } finally { release() }
