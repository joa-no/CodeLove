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
 * stable game-specific key before installation. The identity-aware direct-assets
 * overload rewrites the binary manifest; app label and icon remain unchanged.
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
    private static final long MAX_MANIFEST_BYTES = 4L * 1024L * 1024L;
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


    /**
     * Recommended LÖVE 11.4+ embedding format: unpack the saved .love into
     * the APK's assets/ directory (assets/main.lua, scripts/, images/, ...).
     *
     * Accepts an *embed* template containing exactly one placeholder game
     * slot, either assets/game.love (legacy) or assets/main.lua (new form).
     * The slot is removed before writing the new game assets. Unrelated APK
     * contents are preserved, and collisions are rejected rather than
     * silently overwriting runtime resources.
     *
     * Output is aligned but UNSIGNED. This overload keeps the template
     * identity for backward compatibility with earlier offline tests.
     */
    static void writeUnsignedAssets(File templateApk, File gameLove,
                                    OutputStream output) throws IOException {
        writeUnsignedAssets(templateApk, gameLove, output, null);
    }

    /**
     * Changes package, versions and AndroidX Startup authority. Game label,
     * icon and resources.arsc remain unchanged until separately implemented.
     */
    static void writeUnsignedAssets(File templateApk, File gameLove,
                                    OutputStream output, OfflineApkIdentity identity)
            throws IOException {
        if (templateApk == null || gameLove == null || output == null
                || !templateApk.isFile() || !gameLove.isFile()
                || templateApk.getCanonicalFile().equals(gameLove.getCanonicalFile())) {
            throw new IOException("Template APK, game.love and output must be different");
        }
        if (gameLove.length() <= 0 || gameLove.length() > MAX_GAME_ARCHIVE) {
            throw new IOException("Game LOVE archive exceeds allowed size");
        }
        validateGame(gameLove);
        try (ZipFile template = new ZipFile(templateApk);
             ZipFile game = new ZipFile(gameLove)) {
            Set<String> templateNames = new HashSet<>();
            Set<String> reservedAssets = new HashSet<>();
            String slot = null;
            int count = 0;
            Enumeration<? extends ZipEntry> templates = template.entries();
            while (templates.hasMoreElements()) {
                ZipEntry item = templates.nextElement();
                String name = item.getName();
                validateZipName(name);
                if (!templateNames.add(name) || ++count > MAX_APK_ENTRIES
                        || (item.getMethod() != ZipEntry.STORED
                            && item.getMethod() != ZipEntry.DEFLATED)
                        || item.getSize() < 0) {
                    throw new IOException("Invalid APK template entry: " + name);
                }
                if (GAME_ENTRY.equals(name) || "assets/main.lua".equals(name)) {
                    if (slot != null) {
                        throw new IOException("Template contains multiple game slots");
                    }
                    slot = name;
                } else if (name.startsWith("assets/") && !isOldJarSignature(name)) {
                    reservedAssets.add(name);
                }
            }
            if (slot == null || !templateNames.contains("AndroidManifest.xml")
                    || !templateNames.contains("classes.dex")) {
                throw new IOException("Missing embedded game slot or Android manifest");
            }

            // No game file may overwrite or shadow an asset supplied by the
            // engine. Also reject file-versus-directory prefix conflicts.
            Enumeration<? extends ZipEntry> gameEntries = game.entries();
            while (gameEntries.hasMoreElements()) {
                ZipEntry item = gameEntries.nextElement();
                String target = "assets/" + item.getName();
                String logical = item.isDirectory()
                        ? target.substring(0, target.length() - 1) : target;
                for (String existing : reservedAssets) {
                    String base = existing.endsWith("/")
                            ? existing.substring(0, existing.length() - 1)
                            : existing;
                    if (base.equals(logical)
                            || (!item.isDirectory()
                                && base.startsWith(logical + "/"))
                            || (!existing.endsWith("/")
                                && logical.startsWith(base + "/"))) {
                        throw new IOException("Game asset conflicts with APK: " + target);
                    }
                }
            }

            // Preflight the rewritten manifest before writing any output bytes.
            byte[] rewrittenManifest = identity == null ? null : rewriteManifest(template, identity);
            CountingStream counter = new CountingStream(output);
            try (ZipOutputStream apk = new ZipOutputStream(counter)) {
                templates = template.entries();
                while (templates.hasMoreElements()) {
                    ZipEntry entry = templates.nextElement();
                    String name = entry.getName();
                    if (slot.equals(name) || isOldJarSignature(name)) continue;
                    if (rewrittenManifest != null && "AndroidManifest.xml".equals(name)) {
                        writeManifestEntry(entry, rewrittenManifest, apk, counter);
                    } else {
                        writeCopiedEntry(template, entry, name, apk, counter);
                    }
                }
                gameEntries = game.entries();
                while (gameEntries.hasMoreElements()) {
                    ZipEntry entry = gameEntries.nextElement();
                    writeCopiedEntry(game, entry, "assets/" + entry.getName(),
                                     apk, counter);
                }
            }
        }
    }

    private static byte[] rewriteManifest(ZipFile template, OfflineApkIdentity identity)
            throws IOException {
        ZipEntry entry = template.getEntry("AndroidManifest.xml");
        if (entry == null || entry.isDirectory() || entry.getSize() <= 0
                || entry.getSize() > MAX_MANIFEST_BYTES) {
            throw new IOException("Missing or oversized Android binary manifest");
        }
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (InputStream input = template.getInputStream(entry)) {
            byte[] chunk = new byte[BUFFER];
            int n;
            while ((n = input.read(chunk)) != -1) {
                if (n == 0) continue;
                if (buffer.size() > MAX_MANIFEST_BYTES - n) {
                    throw new IOException("Android manifest exceeds 4 MiB");
                }
                buffer.write(chunk, 0, n);
            }
        }
        if (buffer.size() != entry.getSize()) throw new IOException("Truncated Android manifest");
        // Rewrite authority first: it is derived from the original package.
        // Preserve all hardcoded LÖVE and AndroidX component class names.
        byte[] xml = OfflineBinaryManifest.withStartupAuthority(
                buffer.toByteArray(), identity.packageName);
        xml = OfflineBinaryManifest.withPackageName(xml, identity.packageName);
        xml = OfflineBinaryManifest.withVersionName(xml, identity.versionName);
        return OfflineBinaryManifest.withVersionCode(xml, identity.versionCode);
    }

    private static void writeManifestEntry(ZipEntry original, byte[] xml,
                                           ZipOutputStream out, CountingStream counter)
            throws IOException {
        ZipEntry entry = copyEntry("AndroidManifest.xml", original);
        if (entry.getMethod() == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(xml);
            entry.setSize(xml.length);
            entry.setCrc(crc.getValue());
            entry.setExtra(alignmentExtra(counter.count, "AndroidManifest.xml",
                    OTHER_ALIGNMENT));
        }
        out.putNextEntry(entry);
        out.write(xml);
        out.closeEntry();
    }

    private static void writeCopiedEntry(ZipFile source, ZipEntry entry,
                                         String target, ZipOutputStream result,
                                         CountingStream counter)
            throws IOException {
        ZipEntry copy = copyEntry(target, entry);
        if (entry.getMethod() == ZipEntry.STORED) {
            int alignment = target.startsWith("lib/") && target.endsWith(".so")
                    ? NATIVE_ALIGNMENT : OTHER_ALIGNMENT;
            copy.setExtra(alignmentExtra(counter.count, target, alignment));
        }
        result.putNextEntry(copy);
        if (!entry.isDirectory()) {
            try (InputStream in = source.getInputStream(entry)) {
                transfer(in, result);
            }
        }
        result.closeEntry();
    }

    private static void validateGame(File gameLove) throws IOException {
        try (ZipFile game = new ZipFile(gameLove)) {
            Set<String> names = new HashSet<>();
            Set<String> files = new HashSet<>();
            Set<String> directories = new HashSet<>();
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
                String logical = entry.isDirectory()
                        ? name.substring(0, name.length() - 1) : name;
                String[] parts = logical.split("/");
                StringBuilder parent = new StringBuilder();
                for (int i = 0; i < parts.length - 1; i++) {
                    if (i > 0) parent.append('/');
                    parent.append(parts[i]);
                    String path = parent.toString();
                    if (files.contains(path)) {
                        throw new IOException("LOVE file/directory collision: " + path);
                    }
                    directories.add(path);
                }
                if (entry.isDirectory()) {
                    if (files.contains(logical)) {
                        throw new IOException("LOVE file/directory collision: " + logical);
                    }
                    directories.add(logical);
                } else {
                    if (directories.contains(logical)) {
                        throw new IOException("LOVE file/directory collision: " + logical);
                    }
                    files.add(logical);
                }
                if ("main.lua".equals(name) && !entry.isDirectory()) foundMain = true;
                if (!entry.isDirectory()) {
                    if (entry.getSize() > MAX_GAME_CONTENT - size) {
                        throw new IOException("Uncompressed LOVE game exceeds 512 MiB");
                    }
                    size += entry.getSize();
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
        String[] parts = name.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if ("..".equals(part) || ".".equals(part)
                    || (part.isEmpty()
                        && !(i == parts.length - 1 && name.endsWith("/")))) {
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
