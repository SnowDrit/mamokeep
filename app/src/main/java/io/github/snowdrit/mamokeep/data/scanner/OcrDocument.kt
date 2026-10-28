package io.github.snowdrit.mamokeep.data.scanner

internal const val OCR_MAX_IMAGE_SIDE = 2200

/** Coordinates refer to the upright, bounded input bitmap, never the camera's raw EXIF orientation. */
data class OcrPoint(val x: Double, val y: Double)

data class OcrLine(val text: String, val confidence: Double, val box: List<OcrPoint>)

/** Closed printed cell envelopes, in the same bounded-image coordinates as OCR lines. */
internal data class OcrGridCell(val table: Int, val row: Int, val column: Int, val box: List<OcrPoint>)

/** The envelope includes gaps where a printed cell could not be recovered. */
internal data class OcrGridTable(val table: Int, val box: List<OcrPoint>, val cells: List<OcrGridCell>)

data class OcrRow(val lines: List<OcrLine>) {
    val text: String = lines.joinToString(" ") { it.text }
}

data class OcrDocument(val width: Int, val height: Int, val lines: List<OcrLine>, val rows: List<OcrRow>) {
    val text: String = rows.joinToString("\n") { it.text }
}

enum class OcrFailure { INPUT, INCOMPATIBLE_MODEL, LIMIT, ENGINE }

class OcrException(val reason: OcrFailure, message: String, cause: Throwable? = null) :
    Exception(message, cause)
