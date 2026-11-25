package org.love2d.android;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Verify the saved SAF document against the original private export. */
final class ExportDocumentVerification {
    private ExportDocumentVerification() {}

    static void verify(ContentResolver resolver, Uri destination, File original)
            throws IOException {
        byte[] expected = new byte[32768];
        byte[] actual = new byte[32768];
        try (FileInputStream source = new FileInputStream(original);
             InputStream document = resolver.openInputStream(destination)) {
            if (document == null) throw new IOException("Cannot read exported document");
            DataInputStream checked = new DataInputStream(document);
            int count;
            while ((count = source.read(expected)) != -1) {
                if (count == 0) continue;
                checked.readFully(actual, 0, count);
                for (int i = 0; i < count; i++) {
                    if (expected[i] != actual[i])
                        throw new IOException("Export document verification failed");
                }
            }
            if (checked.read() != -1)
                throw new IOException("Export document contains extra bytes");
        }
    }
}
