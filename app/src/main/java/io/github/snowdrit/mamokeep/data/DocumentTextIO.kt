package io.github.snowdrit.mamokeep.data

import android.content.ContentResolver
import android.net.Uri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Document providers may perform disk or remote I/O, even while opening a stream. */
internal suspend fun ContentResolver.readDocumentText(uri: Uri): String = withContext(Dispatchers.IO) {
    val stream = openInputStream(uri) ?: throw IOException("Document provider returned no input stream")
    stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
}

internal suspend fun ContentResolver.writeDocumentText(uri: Uri, text: String) = withContext(Dispatchers.IO) {
    val stream = openOutputStream(uri, "wt") ?: throw IOException("Document provider returned no output stream")
    stream.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
}
