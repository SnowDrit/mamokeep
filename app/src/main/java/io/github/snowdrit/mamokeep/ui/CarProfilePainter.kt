package io.github.snowdrit.mamokeep.ui

import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import io.github.snowdrit.mamokeep.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Compact profiles do not need the full resolution used by the garage hero. */
@Composable
fun rememberCarProfilePainter(bodyType: String?, width: Dp, height: Dp): Painter {
    val resource = BodyTypes.resFor(bodyType)
    if (resource == R.drawable.ic_car_side) return painterResource(resource)

    val resources = LocalResources.current
    val density = LocalDensity.current
    val targetWidth = with(density) { width.roundToPx() }
    val targetHeight = with(density) { height.roundToPx() }
    // A changed selection starts with its own loading state, never the previous car.
    return key(resources, resource, targetWidth, targetHeight) {
        val bitmap by produceState<ImageBitmap?>(initialValue = null) {
            value = withContext(Dispatchers.IO) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeResource(resources, resource, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                    "Invalid car profile bitmap: $resource"
                }
                // Icon uses Fit: one axis may leave empty space. The larger source /
                // slot ratio determines the scale, including very wide body types.
                val scale = max(
                    bounds.outWidth.toDouble() / targetWidth,
                    bounds.outHeight.toDouble() / targetHeight,
                )
                var sampleSize = 1
                while (sampleSize * 2 <= scale) sampleSize *= 2
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inScaled = false
                }
                checkNotNull(BitmapFactory.decodeResource(resources, resource, options)) {
                    "Cannot decode car profile bitmap: $resource"
                }.asImageBitmap()
            }
        }
        val loaded = bitmap
        if (loaded == null) painterResource(R.drawable.ic_car_side)
        else remember(loaded) { BitmapPainter(loaded) }
    }
}
