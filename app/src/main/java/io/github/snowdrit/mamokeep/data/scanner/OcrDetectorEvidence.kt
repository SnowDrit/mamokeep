package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.floor

/** Undilated DB text components. Grid ink alone cannot establish another text line. */
internal class OcrDetectorEvidence private constructor(
    private val width: Int,
    private val height: Int,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val labels: IntArray,
    private val areas: IntArray,
    private val strong: BooleanArray,
) {
    suspend fun supports(box: List<OcrPoint>): Boolean {
        val scaled = box.map { OcrPoint(it.x / sourceWidth * width, it.y / sourceHeight * height) }
        val left = floor(scaled.minOf { it.x }).toInt().coerceIn(0, width - 1)
        val right = ceil(scaled.maxOf { it.x }).toInt().coerceIn(0, width - 1)
        val top = floor(scaled.minOf { it.y }).toInt().coerceIn(0, height - 1)
        val bottom = ceil(scaled.maxOf { it.y }).toInt().coerceIn(0, height - 1)
        val cropWidth = right - left + 1
        val cropHeight = bottom - top + 1
        return Mat.zeros(cropHeight, cropWidth, CvType.CV_8UC1).useEvidenceMat { mask ->
            MatOfPoint(*scaled.map { Point((it.x - left).toInt().toDouble(), (it.y - top).toInt().toDouble()) }
                .toTypedArray()).useEvidenceMat { polygon -> Imgproc.fillPoly(mask, listOf(polygon), Scalar(1.0)) }
            val pixels = ByteArray(cropWidth * cropHeight)
            mask.get(0, 0, pixels)
            val owned = IntArray(areas.size)
            for (y in 0 until cropHeight) {
                currentCoroutineContext().ensureActive()
                for (x in 0 until cropWidth) {
                    if (pixels[y * cropWidth + x].toInt() == 0) continue
                    val component = labels[(top + y) * width + left + x]
                    if (strong[component]) owned[component]++
                }
            }
            // A small tail from a neighboring printed glyph is not this line's evidence.
            // Use the same strict majority principle as physical row ownership.
            areas.indices.any { strong[it] && owned[it].toLong() * 2 > areas[it] }
        }
    }

    companion object {
        suspend fun from(
            probabilities: FloatArray, width: Int, height: Int, sourceWidth: Int, sourceHeight: Int,
            foregroundThreshold: Double, componentThreshold: Double,
        ): OcrDetectorEvidence {
            val threshold = foregroundThreshold.toFloat() // Match OpenCV threshold on a CV_32F map.
            val binary = ByteArray(probabilities.size) { if (probabilities[it] > threshold) 1 else 0 }
            return Mat(height, width, CvType.CV_8UC1).useEvidenceMat { ink ->
                ink.put(0, 0, binary)
                Mat().useEvidenceMat { labels ->
                    Mat().useEvidenceMat { stats ->
                        Mat().useEvidenceMat { centroids ->
                            val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8)
                            val pixels = IntArray(probabilities.size)
                            labels.get(0, 0, pixels)
                            val components = IntArray(count * 5)
                            stats.get(0, 0, components)
                            val areas = IntArray(count) { components[it * 5 + Imgproc.CC_STAT_AREA] }
                            val sums = DoubleArray(count)
                            for (y in 0 until height) {
                                currentCoroutineContext().ensureActive()
                                for (x in 0 until width) {
                                    val offset = y * width + x
                                    if (pixels[offset] != 0) sums[pixels[offset]] += probabilities[offset]
                                }
                            }
                            val strong = BooleanArray(count) { it != 0 && sums[it] / areas[it] >= componentThreshold }
                            OcrDetectorEvidence(width, height, sourceWidth, sourceHeight, pixels, areas, strong)
                        }
                    }
                }
            }
        }
    }
}

private inline fun <T : Mat, R> T.useEvidenceMat(block: (T) -> R): R = try { block(this) } finally { release() }
