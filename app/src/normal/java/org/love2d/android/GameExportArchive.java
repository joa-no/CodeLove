package org.love2d.android;

import android.content.ContentResolver;
import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a standard .love archive directly from saved SAF documents.
 *
 * The archive is an intermediate build input, NOT an installable APK.
 * Call off the main thread. The caller owns the OutputStream and must discard
 * its contents if an IOException is thrown (a ZIP may then be incomplete).
 * Does not change SAF files, the selected root or the editor's save state.
 */
final class GameExportArchive {
    private static final int MAX_DEPTH = 32;
    private static final int MAX_ENTRIES = 10000;
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int BUFFER_BYTES = 32 * 1024;

    static final class Summary {
        final int files;
        final long bytes;

        Summary(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    private final DirectSafStorage storage;
    private final ContentResolver resolver;
    private final Set<String> names = new HashSet<>();
    private int fileCount;
    private int entryCount;
    private long totalBytes;
    private boolean hasMainLua;

    private GameExportArchive(Context context, DirectSafStorage storage) {
        this.storage = storage;
        this.resolver = context.getContentResolver();
    }

    static Summary write(Context context, DirectSafStorage storage, OutputStream target)
            throws IOException {
        if (context == null || storage == null || target == null) {
            throw new IllegalArgumentException("Missing archive input.");
        }
        GameExportArchive writer = new GameExportArchive(context, storage);
        // Validate the root before writing any bytes. Do not trust the synthetic
        // File as a real filesystem directory; read exclusively through SAF.
        List<DirectSafStorage.Entry> root = storage.list(storage.getRoot());
        for (DirectSafStorage.Entry entry : root) {
            validateName(entry.name);
            if (!entry.directory && "main.lua".equals(entry.name)) {
                writer.hasMainLua = true;
            }
        }
        if (!writer.hasMainLua) {
            throw new IOException("O projeto precisa conter main.lua na raiz.");
        }
        ZipOutputStream zip = new ZipOutputStream(target);
        // Do not close the caller's output stream; closeEntry / finish writes
        // the central directory while retaining ownership with the caller.
        writer.copyEntries(zip, root, "", 0);
        zip.finish();
        zip.flush();
        return new Summary(writer.fileCount, writer.totalBytes);
    }

    private void copyEntries(ZipOutputStream zip, List<DirectSafStorage.Entry> entries,
            String prefix, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Projeto profundo demais.");
        for (DirectSafStorage.Entry entry : entries) {
            validateName(entry.name);
            if (++entryCount > MAX_ENTRIES) {
                throw new IOException("O projeto excede o limite de 10.000 entradas.");
            }
            String path = prefix + entry.name;
            if (entry.directory) {
                String directory = path + "/";
                reserve(directory);
                zip.putNextEntry(new ZipEntry(directory));
                zip.closeEntry();
                copyEntries(zip, storage.list(entry.file), directory, depth + 1);
                continue;
            }
            reserve(path);
            if (++fileCount > MAX_ENTRIES) {
                throw new IOException("O projeto excede o limite de 10.000 arquivos.");
            }
            zip.putNextEntry(new ZipEntry(path));
            try (InputStream input = resolver.openInputStream(entry.uri)) {
                if (input == null) throw new IOException("Nao foi possivel ler " + path);
                byte[] buffer = new byte[BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    totalBytes += read;
                    if (totalBytes > MAX_BYTES) {
                        throw new IOException("Projeto maior que 512 MiB.");
                    }
                    zip.write(buffer, 0, read);
                }
            }
            zip.closeEntry();
        }
    }

    private void reserve(String path) throws IOException {
        if (!names.add(path)) {
            throw new IOException("Nome de arquivo duplicado: " + path);
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.indexOf('\u0000') >= 0) {
            throw new IOException("Nome de arquivo nao suportado na exportacao.");
        }
    }
}
