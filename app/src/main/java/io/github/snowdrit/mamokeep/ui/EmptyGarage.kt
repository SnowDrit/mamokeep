package io.github.snowdrit.mamokeep.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R

/** A valid empty backup leaves the choice to create a car with the user. */
@Composable
internal fun EmptyGarage(onOpenCarPicker: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_car_multiple), contentDescription = null,
                modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.garage_empty), textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = onOpenCarPicker) { Text(stringResource(R.string.car_add)) }
        }
    }
}
