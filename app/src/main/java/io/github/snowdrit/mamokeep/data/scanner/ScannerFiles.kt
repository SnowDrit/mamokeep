package io.github.snowdrit.mamokeep.data.scanner

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal fun atomicWrite(file: File, bytes: ByteArray) {
    if (!file.parentFile!!.isDirectory && !file.parentFile!!.mkdirs()) throw java.io.IOException("Cannot create scanner storage")
    val temporary = File(file.parentFile, ".${file.name}.tmp")
    try {
        FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { temporary.delete() }
}

internal fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > maxBytes - output.size()) throw CatalogException(CatalogError.INVALID)
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
