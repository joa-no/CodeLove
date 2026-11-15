package org.love2d.android;

import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Offline APK-base experiment: replace assets/game.love without Gradle.
 *
 * No Android framework dependencies: suitable for running on the phone and in
 * an ordinary JVM test. The result is UNSIGNED. It must be signed with a
 * stable game-specific key before installation; changing the Android manifest,
 * app label, version or icon is NOT implemented here.
 *
 * The caller owns the temporary output location and must discard partial
 * output after an exception. writeUnsigned closes the output stream.
 */
final class OfflineGameApk {
    static final String GAME_ENTRY = "assets/game.love";
    private static final long MAX_GAME_ARCHIVE = 512L * 1024L * 1024L;
    private static final long MAX_GAME_CONTENT = 512L * 1024L * 1024L;
    private static final int MAX_GAME_ENTRIES = 10000;
    private static final int MAX_APK_ENTRIES = 20000;
    private static final int BUFFER = 32768;
    private static final int NATIVE_ALIGNMENT = 16 * 1024;
    private static final int OTHER_ALIGNMENT = 4;
    private static final int EXTRA_ID = 0xCAFE;

    private OfflineGameApk() {}

    static void writeUnsigned(File templateApk, File gameLove, OutputStream output)
            throws IOException {
        if (templateApk == null || gameLove == null || output == null
                || !templateApk.isFile() || !gameLove.isFile()
                || templateApk.getCanonicalFile().equals(gameLove.getCanonicalFile())) {
            throw new IOException("Template APK, game.love and output must be different");
        }
        if (gameLove.length() <= 0 || gameLove.length() > MAX_GAME_ARCHIVE) {
            throw new IOException("Game LOVE archive exceeds allowed size");
        }

        // Preflight all game paths and CRCs before writing a byte of the APK.
        validateGame(gameLove);
        try (ZipFile source = new ZipFile(templateApk)) {
            validateTemplate(source);
            CountingStream counter = new CountingStream(output);
            try (ZipOutputStream apk = new ZipOutputStream(counter)) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (GAME_ENTRY.equals(name) || isOldJarSignature(name)) {
                        continue;
                    }
                    ZipEntry copy = copyEntry(name, entry);
                    if (entry.getMethod() == ZipEntry.STORED) {
                        int alignment = name.startsWith("lib/") && name.endsWith(".so")
                                ? NATIVE_ALIGNMENT : OTHER_ALIGNMENT;
                        copy.setExtra(alignmentExtra(counter.count, name, alignment));
                    }
                    apk.putNextEntry(copy);
                    if (!entry.isDirectory()) {
                        try (InputStream in = source.getInputStream(entry)) {
                            transfer(in, apk);
                        }
                    }
                    apk.closeEntry();
                }

                // Keep the embedded .love as STORED. LÖVE gets the exact
                // original ZIP bytes and the APK entry is four-byte aligned.
                ZipEntry game = new ZipEntry(GAME_ENTRY);
                game.setMethod(ZipEntry.STORED);
                game.setSize(gameLove.length());
                game.setCrc(fileCrc(gameLove));
                game.setExtra(alignmentExtra(counter.count, GAME_ENTRY, OTHER_ALIGNMENT));
                apk.putNextEntry(game);
                try (InputStream in = new java.io.FileInputStream(gameLove)) {
                    transfer(in, apk);
                }
                apk.closeEntry();
            }
        }
    }

    private static void validateGame(File gameLove) throws IOException {
        try (ZipFile game = new ZipFile(gameLove)) {
            Set<String> names = new HashSet<>();
            long size = 0;
            int count = 0;
            boolean foundMain = false;
            Enumeration<? extends ZipEntry> entries = game.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                validateZipName(name);
                if (!names.add(name) || ++count > MAX_GAME_ENTRIES
                        || entry.getMethod() == -1
                        || (entry.getMethod() != ZipEntry.STORED
                            && entry.getMethod() != ZipEntry.DEFLATED)
                        || entry.getSize() < 0) {
                    throw new IOException("Unsupported or duplicate LOVE entry: " + name);
                }
                if ("main.lua".equals(name) && !entry.isDirectory()) foundMain = true;
                if (!entry.isDirectory()) {
                    size += entry.getSize();
                    if (size > MAX_GAME_CONTENT) {
                        throw new IOException("Uncompressed LOVE game exceeds 512 MiB");
                    }
                    // Verify that the archive can be read and its CRC matches.
                    CRC32 crc = new CRC32();
                    long copied = 0;
                    try (InputStream input = game.getInputStream(entry)) {
                        byte[] chunk = new byte[BUFFER];
                        int read;
                        while ((read = input.read(chunk)) != -1) {
                            if (read == 0) continue;
                            copied += read;
                            if (copied > entry.getSize()) {
                                throw new IOException("LOVE entry grew beyond declared size");
                            }
                            crc.update(chunk, 0, read);
                        }
                    }
                    if (copied != entry.getSize() || crc.getValue() != entry.getCrc()) {
                        throw new IOException("LOVE entry length or CRC mismatch: " + name);
                    }
                }
            }
            if (!foundMain) throw new IOException("LOVE archive lacks root main.lua");
        }
    }

    private static void validateTemplate(ZipFile apk) throws IOException {
        Set<String> names = new HashSet<>();
        int count = 0;
        int gameCount = 0;
        Enumeration<? extends ZipEntry> entries = apk.entries();
        while (entries.hasMoreElements()) {
            ZipEntry e = entries.nextElement();
            String name = e.getName();
            validateZipName(name);
            if (!names.add(name) || ++count > MAX_APK_ENTRIES
                    || (e.getMethod() != ZipEntry.STORED
                        && e.getMethod() != ZipEntry.DEFLATED)
                    || e.getSize() < 0) {
                throw new IOException("Invalid APK template ZIP entry: " + name);
            }
            if (GAME_ENTRY.equals(name)) gameCount++;
        }
        if (gameCount != 1 || names.contains("assets/main.lua")
                || !names.contains("AndroidManifest.xml")
                || !names.contains("classes.dex")) {
            throw new IOException("Invalid or non-embed APK template");
        }
    }

    private static void validateZipName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/")
                || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Unsafe archive entry path");
        }
        for (String part : name.split("/", -1)) {
            if ("..".equals(part) || ".".equals(part) ||
                    (part.isEmpty() && !name.endsWith("/"))) {
                throw new IOException("Unsafe archive entry path: " + name);
            }
        }
    }

    private static boolean isOldJarSignature(String name) {
        if (!name.startsWith("META-INF/")) return false;
        String leaf = name.substring("META-INF/".length());
        if (leaf.isEmpty() || leaf.indexOf('/') >= 0) return false;
        String upper = leaf.toUpperCase(Locale.ROOT);
        return upper.equals("MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    private static ZipEntry copyEntry(String name, ZipEntry input) {
        ZipEntry result = new ZipEntry(name);
        result.setMethod(input.getMethod());
        if (input.getTime() >= 0) result.setTime(input.getTime());
        if (input.getComment() != null) result.setComment(input.getComment());
        if (input.getMethod() == ZipEntry.STORED) {
            result.setSize(input.getSize());
            result.setCrc(input.getCrc());
        }
        return result;
    }

    /**
     * ZIP local header = 30 bytes + UTF-8 filename + extra fields.
     * A custom extra field makes STORED data mmap-friendly (16 KiB .so,
     * 4 bytes other stored entries). zipalign -c -P 16 4 checks this later.
     */
    private static byte[] alignmentExtra(long position, String name, int alignment) {
        int nameBytes = name.getBytes(StandardCharsets.UTF_8).length;
        int padding = (int) ((alignment
                - ((position + 30 + nameBytes + 4) % alignment)) % alignment);
        byte[] field = new byte[4 + padding];
        field[0] = (byte) (EXTRA_ID & 0xff);
        field[1] = (byte) ((EXTRA_ID >>> 8) & 0xff);
        field[2] = (byte) (padding & 0xff);
        field[3] = (byte) ((padding >>> 8) & 0xff);
        return field;
    }

    private static long fileCrc(File game) throws IOException {
        CRC32 crc = new CRC32();
        try (InputStream input = new java.io.FileInputStream(game)) {
            byte[] chunk = new byte[BUFFER];
            int read;
            while ((read = input.read(chunk)) != -1) {
                if (read > 0) crc.update(chunk, 0, read);
            }
        }
        return crc.getValue();
    }

    private static void transfer(InputStream input, OutputStream output)
            throws IOException {
        byte[] chunk = new byte[BUFFER];
        int read;
        while ((read = input.read(chunk)) != -1) {
            if (read > 0) output.write(chunk, 0, read);
        }
    }

    private static final class CountingStream extends FilterOutputStream {
        long count;
        CountingStream(OutputStream out) { super(out); }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            out.write(bytes, offset, length);
            count += length;
        }
    }
}
