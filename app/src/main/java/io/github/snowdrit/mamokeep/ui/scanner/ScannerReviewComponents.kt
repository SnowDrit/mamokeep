package io.github.snowdrit.mamokeep.ui.scanner

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.snowdrit.mamokeep.R

@Composable
internal fun OcrConfirmation(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
            value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange,
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ScannerSource(image: Bitmap?, text: String, initiallyExpanded: Boolean = false) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.ocr_source)) }
        if (expanded) {
            image?.let {
                Image(
                    it.asImageBitmap(), contentDescription = stringResource(R.string.ocr_photo),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp), contentScale = ContentScale.Fit,
                )
            }
            if (text.isNotBlank()) SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
