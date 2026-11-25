package org.love2d.android;

import java.io.File;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Preflight for an offline LÖVE Android APK template.
 *
 * This is NOT an APK builder. A template must be a standalone embedded-game
 * runtime, not CodeLove's editor APK. Manifest/resource patching, native
 * alignment and signing are separate steps and must not be bypassed.
 */
final class OfflineApkTemplateValidator {
    static final class Result {
        final int nativeLibraries;
        final Set<String> abis;
        final boolean alreadyContainsGame;

        Result(int nativeLibraries, Set<String> abis, boolean alreadyContainsGame) {
            this.nativeLibraries = nativeLibraries;
            this.abis = java.util.Collections.unmodifiableSet(new HashSet<>(abis));
            this.alreadyContainsGame = alreadyContainsGame;
        }
    }

    private OfflineApkTemplateValidator() {}

    static Result inspect(File template) throws IOException {
        if (template == null || !template.isFile() || template.length() == 0) {
            throw new IOException("APK template missing");
        }
        boolean manifest = false;
        boolean dex = false;
        boolean hasLove = false;
        boolean hasSdl = false;
        boolean hasGame = false;
        int libraries = 0;
        Set<String> abis = new HashSet<>();
        try (ZipFile zip = new ZipFile(template)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String name = entry.getName();
                if ("AndroidManifest.xml".equals(name)) manifest = true;
                if ("classes.dex".equals(name)) dex = true;
                if ("assets/game.love".equals(name) || "assets/main.lua".equals(name)) {
                    hasGame = true;
                }
                if (name.startsWith("lib/") && name.endsWith(".so")) {
                    String[] parts = name.split("/");
                    if (parts.length == 3 && !parts[1].isEmpty()) {
                        abis.add(parts[1]);
                        libraries++;
                        if ("liblove.so".equals(parts[2]) || "libliblove.so".equals(parts[2])) {
                            hasLove = true;
                        }
                        if ("libSDL3.so".equals(parts[2])) hasSdl = true;
                    }
                }
            }
        }
        if (!manifest || !dex || !hasLove || !hasSdl || abis.isEmpty()) {
            throw new IOException("APK template lacks Android manifest, dex or LÖVE runtime");
        }
        return new Result(libraries, abis, hasGame);
    }
}
