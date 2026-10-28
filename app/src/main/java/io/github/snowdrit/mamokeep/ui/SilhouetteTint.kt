package io.github.snowdrit.mamokeep.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** A saved line colour is shared by the garage, screen titles and car switcher. */
@Composable
fun silhouetteTint(argb: Int?): Color = argb?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface
