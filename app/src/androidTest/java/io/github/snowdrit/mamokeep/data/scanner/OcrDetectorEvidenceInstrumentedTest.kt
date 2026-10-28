package io.github.snowdrit.mamokeep.data.scanner

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.opencv.android.OpenCVLoader

/** Model-free DB probability maps: text ownership, blank space and clipped neighboring glyphs. */
class OcrDetectorEvidenceInstrumentedTest {
    @Before fun initializeOpenCv() { assertTrue(OpenCVLoader.initLocal()) }

    @Test fun aWholeOrMajorityTextComponentSupportsTheGridLine() = runBlocking {
        val evidence = evidence()
        assertTrue(evidence.supports(box(2.0, 2.0, 8.0, 8.0)))
        assertTrue(evidence.supports(box(2.0, 2.0, 5.0, 8.0)))
        // A rotated source quad is rasterized, rather than using only its bounding rectangle.
        assertTrue(evidence.supports(listOf(OcrPoint(5.0, 1.0), OcrPoint(9.0, 5.0),
            OcrPoint(5.0, 9.0), OcrPoint(1.0, 5.0))))
    }

    @Test fun aFragmentOrHalfOfANeighboringComponentDoesNotSupplyAnotherLine() = runBlocking {
        val evidence = evidence()
        assertFalse(evidence.supports(box(2.0, 2.0, 3.0, 8.0)))
        assertFalse(evidence.supports(box(2.0, 2.0, 4.0, 8.0)))
        assertFalse(evidence.supports(box(10.0, 10.0, 18.0, 18.0)))
    }

    @Test fun sourceCoordinatesAndDisconnectedComponentsRetainTheirOwners() = runBlocking {
        val probabilities = FloatArray(20 * 20)
        for (y in 3..6) for (x in 3..6) probabilities[y * 20 + x] = 0.9f
        for (y in 12..15) for (x in 12..15) probabilities[y * 20 + x] = 0.9f
        val evidence = OcrDetectorEvidence.from(probabilities, 20, 20, 40, 40, 0.3, 0.5)
        assertTrue(evidence.supports(box(4.0, 4.0, 16.0, 16.0)))
        assertTrue(evidence.supports(box(22.0, 22.0, 34.0, 34.0)))
        assertFalse(evidence.supports(box(4.0, 4.0, 6.0, 16.0)))
        assertFalse(evidence.supports(box(16.0, 16.0, 20.0, 20.0)))
    }

    @Test fun weakOrAbsentDetectorEvidenceCannotBeReplacedByGridInk() = runBlocking {
        for (probability in listOf(0f, 0.3f, 0.4f)) {
            assertFalse(evidence(probability).supports(box(2.0, 2.0, 8.0, 8.0)))
        }
        assertTrue(evidence(0.5f).supports(box(2.0, 2.0, 8.0, 8.0)))
    }

    private suspend fun evidence(probability: Float = 0.9f): OcrDetectorEvidence {
        val probabilities = FloatArray(20 * 20)
        for (y in 3..6) for (x in 3..6) probabilities[y * 20 + x] = probability
        return OcrDetectorEvidence.from(probabilities, 20, 20, 20, 20, 0.3, 0.5)
    }

    private fun box(left: Double, top: Double, right: Double, bottom: Double) = listOf(
        OcrPoint(left, top), OcrPoint(right, top), OcrPoint(right, bottom), OcrPoint(left, bottom),
    )
}
