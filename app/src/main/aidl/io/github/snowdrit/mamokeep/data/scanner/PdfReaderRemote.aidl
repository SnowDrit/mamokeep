package io.github.snowdrit.mamokeep.data.scanner;

import android.os.ParcelFileDescriptor;

interface PdfReaderRemote {
    int open(in ParcelFileDescriptor input);
    // A null result means that the page was rendered as PNG into output.
    @nullable String readPage(int index, in ParcelFileDescriptor output, boolean forceRaster);
    void close();
}
