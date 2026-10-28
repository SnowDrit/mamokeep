package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import io.github.snowdrit.mamokeep.R

/** The full illustration occupies its own measured space above the car's name. */
@Composable
fun CarHero(
    bodyType: String?,
    tint: Color,
    title: String,
    subtitle: String,
) {
    val painter = painterResource(BodyTypes.heroResFor(bodyType))
    val intrinsic = painter.intrinsicSize
    val fallbackRatio = LocalResources.current.getFraction(R.fraction.car_hero_fallback_ratio, 1, 1)
    // Bitmap and vector painters both report their actual aspect ratio. A painter without
    // a finite intrinsic size still receives a valid slot instead of dividing by zero.
    val ratio = if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) {
        (intrinsic.width / intrinsic.height).takeIf { it.isFinite() && it > 0f } ?: fallbackRatio
    } else fallbackRatio

    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(
                horizontal = dimensionResource(R.dimen.car_hero_inset_horizontal),
                vertical = dimensionResource(R.dimen.car_hero_inset_vertical),
            ),
        verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_hero_section_gap)),
    ) {
        Image(
            painter = painter,
            contentDescription = null,
            colorFilter = ColorFilter.tint(tint),
            contentScale = ContentScale.Fit,
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .widthIn(max = dimensionResource(R.dimen.car_hero_max_width))
                .fillMaxWidth()
                .aspectRatio(ratio),
        )
        Column(verticalArrangement = Arrangement.spacedBy(dimensionResource(R.dimen.car_hero_caption_gap))) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
