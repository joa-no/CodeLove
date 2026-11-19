package org.love2d.android;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.LinkedHashMap;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** JVM-executable tests; no JUnit, Android SDK or network required. */
public final class OfflineGameApkTest {
    private OfflineGameApkTest() {}

    private static byte[] love(String main) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry("main.lua"));
            zip.write(main.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("scripts/data.lua"));
            zip.write("return 42".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return buffer.toByteArray();
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes,
                            boolean stored) throws Exception {
        ZipEntry item = new ZipEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            item.setMethod(ZipEntry.STORED);
            item.setSize(bytes.length);
            item.setCrc(crc.getValue());
        }
        zip.putNextEntry(item);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static void makeTemplate(File file, boolean gameSlot, boolean loose)
            throws Exception {
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file))) {
            add(zip, "AndroidManifest.xml", new byte[]{3, 0, 5, 1}, false);
            add(zip, "classes.dex", new byte[]{100, 101, 120}, false);
            add(zip, "resources.arsc", new byte[]{1, 2, 3}, true);
            add(zip, "lib/arm64-v8a/liblove.so", new byte[]{1, 2, 3, 4}, true);
            add(zip, "lib/armeabi-v7a/liblove.so", new byte[]{9, 8, 7}, true);
            add(zip, "META-INF/MANIFEST.MF", new byte[]{1}, false);
            add(zip, "META-INF/OLD.SF", new byte[]{1}, false);
            add(zip, "META-INF/OLD.RSA", new byte[]{2}, false);
            add(zip, "META-INF/androidx.core_core.version", new byte[]{3}, false);
            if (gameSlot) add(zip, "assets/game.love", love("error('placeholder')"), false);
            if (loose) add(zip, "assets/main.lua", new byte[]{5}, false);
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    private static void expectReject(File template, File game, File output)
            throws Exception {
        try (FileOutputStream out = new FileOutputStream(output)) {
            OfflineGameApk.writeUnsigned(template, game, out);
            throw new AssertionError("Expected malformed input to be rejected");
        } catch (java.io.IOException expected) {
            // Partial output is discarded by the caller.
            Files.deleteIfExists(output.toPath());
        }
    }


    private static void testDirectAssets() throws Exception {
        File directory = Files.createTempDirectory("offline-assets-jvm-").toFile();
        try {
            File template = new File(directory, "template.apk");
            File game = new File(directory, "direct.love");
            File output = new File(directory, "direct-unsigned.apk");
            makeTemplate(template, true, false);
            byte[] originalTemplate = Files.readAllBytes(template.toPath());
            Files.write(game.toPath(), love("print('direct embed')"));

            try (FileOutputStream stream = new FileOutputStream(output)) {
                OfflineGameApk.writeUnsignedAssets(template, game, stream);
            }
            check(Arrays.equals(originalTemplate, Files.readAllBytes(template.toPath())),
                  "Direct export modified the original APK base");
            try (ZipFile apk = new ZipFile(output)) {
                check(apk.getEntry("assets/game.love") == null,
                      "Legacy ZIP should not remain in direct-asset APK");
                ZipEntry main = apk.getEntry("assets/main.lua");
                ZipEntry nested = apk.getEntry("assets/scripts/data.lua");
                check(main != null && nested != null,
                      "Direct game Lua assets are missing");
                try (java.io.InputStream stream = apk.getInputStream(main)) {
                    check(Arrays.equals(stream.readAllBytes(),
                          "print('direct embed')".getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)),
                          "Direct main.lua differs from original");
                }
                check(apk.getEntry("lib/arm64-v8a/liblove.so") != null,
                      "Native engine missing from direct asset output");
                check(apk.getEntry("META-INF/OLD.RSA") == null,
                      "Stale signature remained in direct asset output");
            }

            // The recommended template has assets/main.lua instead of
            // assets/game.love. Replacing that placeholder must work too.
            makeTemplate(template, false, true);
            File recommended = new File(directory, "direct-from-main.apk");
            try (FileOutputStream stream = new FileOutputStream(recommended)) {
                OfflineGameApk.writeUnsignedAssets(template, game, stream);
            }
            try (ZipFile apk = new ZipFile(recommended)) {
                check(apk.getEntry("assets/game.love") == null,
                      "The recommended template should not add a legacy slot");
                ZipEntry main = apk.getEntry("assets/main.lua");
                check(main != null, "Recommended template lost main.lua");
                try (java.io.InputStream input = apk.getInputStream(main)) {
                    check(Arrays.equals(input.readAllBytes(),
                          "print('direct embed')".getBytes(
                              java.nio.charset.StandardCharsets.UTF_8)),
                          "Failed to replace recommended template placeholder");
                }
            }
            makeTemplate(template, true, true);
            try (FileOutputStream stream = new FileOutputStream(
                    new File(directory, "invalid-duplicate-slot.apk"))) {
                try {
                    OfflineGameApk.writeUnsignedAssets(template, game, stream);
                    throw new AssertionError("Template with both slots was accepted");
                } catch (java.io.IOException expected) {
                    // A template containing game.love AND main.lua is invalid.
                }
            }
        } finally {
            File[] entries = directory.listFiles();
            if (entries != null) for (File item : entries) item.delete();
            directory.delete();
        }
    }

    private static void test() throws Exception {
        File directory = Files.createTempDirectory("offline-apk-java-").toFile();
        try {
            File template = new File(directory, "base.apk");
            File game = new File(directory, "game.love");
            File output = new File(directory, "unsigned.apk");
            makeTemplate(template, true, false);
            byte[] originalGame = love("print('offline java')");
            Files.write(game.toPath(), originalGame);
            byte[] originalApk = Files.readAllBytes(template.toPath());
            try (FileOutputStream stream = new FileOutputStream(output)) {
                OfflineGameApk.writeUnsigned(template, game, stream);
            }
            check(Arrays.equals(Files.readAllBytes(template.toPath()), originalApk),
                  "Must not change template");
            try (ZipFile apk = new ZipFile(output)) {
                ZipEntry entry = apk.getEntry("assets/game.love");
                check(entry != null && entry.getMethod() == ZipEntry.STORED,
                      "Must have stored embedded LOVE");
                try (java.io.InputStream in = apk.getInputStream(entry)) {
                    check(Arrays.equals(in.readAllBytes(), originalGame),
                          "Embedded LOVE bytes changed");
                }
                check(apk.getEntry("AndroidManifest.xml") != null, "Lost manifest");
                check(apk.getEntry("lib/arm64-v8a/liblove.so") != null, "Lost native library");
                check(apk.getEntry("META-INF/OLD.RSA") == null, "Stale signature remained");
                check(apk.getEntry("META-INF/OLD.SF") == null, "Stale signature remained");
                check(apk.getEntry("META-INF/androidx.core_core.version") != null,
                      "Removed non-signature metadata");
            }
            makeTemplate(template, false, false);
            expectReject(template, game, new File(directory, "bad1.apk"));
            makeTemplate(template, true, true);
            expectReject(template, game, new File(directory, "bad2.apk"));
            makeTemplate(template, true, false);
            try (ZipOutputStream malformed = new ZipOutputStream(new FileOutputStream(game))) {
                add(malformed, "../main.lua", new byte[]{1}, false);
            }
            expectReject(template, game, new File(directory, "bad3.apk"));
            testDirectAssets();
            System.out.println("OfflineGameApk JVM tests passed");
        } finally {
            File[] entries = directory.listFiles();
            if (entries != null) for (File item : entries) item.delete();
            directory.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 4 && "--repack".equals(args[0])) {
            try (FileOutputStream out = new FileOutputStream(args[3])) {
                OfflineGameApk.writeUnsigned(
                    new File(args[1]), new File(args[2]), out);
            }
            System.out.println("Java offline APK created: unsigned");
        } else if (args.length == 4 && "--repack-assets".equals(args[0])) {
            try (FileOutputStream out = new FileOutputStream(args[3])) {
                OfflineGameApk.writeUnsignedAssets(
                    new File(args[1]), new File(args[2]), out);
            }
            System.out.println("Direct asset APK created: unsigned");
        } else if (args.length == 7 && ("--repack-assets-identity".equals(args[0])
                || "--repack-assets-identity-icon".equals(args[0]))) {
            OfflineApkIdentity identity = OfflineApkIdentity.create(
                    args[4], "Prototype Test", args[5], Integer.parseInt(args[6]));
            boolean withIcon = "--repack-assets-identity-icon".equals(args[0]);
            Map<String, byte[]> icons = null;
            if (withIcon) {
                icons = new LinkedHashMap<>();
                String[] densities = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
                int[] sizes = {48, 72, 96, 144, 192};
                for (int i = 0; i < densities.length; i++) {
                    BufferedImage image = new BufferedImage(
                            sizes[i], sizes[i], BufferedImage.TYPE_INT_ARGB);
                    image.setRGB(0, 0, 0xff4488ff);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    check(ImageIO.write(image, "png", bytes), "Cannot encode test PNG");
                    icons.put(densities[i], bytes.toByteArray());
                }
            }
            try (FileOutputStream out = new FileOutputStream(args[3])) {
                OfflineGameApk.writeUnsignedAssets(
                        new File(args[1]), new File(args[2]), out, identity, icons);
            }
            try (ZipFile apk = new ZipFile(args[3])) {
                ZipEntry manifest = apk.getEntry("AndroidManifest.xml");
                check(manifest != null, "Personalized APK lost manifest");
                byte[] xml;
                try (java.io.InputStream input = apk.getInputStream(manifest)) {
                    xml = input.readAllBytes();
                }
                check(Arrays.asList(OfflineBinaryManifest.rootAttributes(xml))
                        .contains("package=" + identity.packageName), "Wrong package");
                check(Arrays.asList(OfflineBinaryManifest.rootAttributes(xml))
                        .contains("versionName=" + identity.versionName), "Wrong versionName");
                check(Arrays.asList(OfflineBinaryManifest.rootAttributes(xml))
                        .contains("versionCode=type=0x10,data=" + identity.versionCode),
                        "Wrong versionCode");
                check(Arrays.asList(OfflineBinaryManifest.componentReferences(xml))
                        .contains("provider.authorities=" + identity.packageName
                                + ".androidx-startup"), "Wrong Startup authority");
                check(apk.getEntry("assets/main.lua") != null, "Embedded game missing");
                check("Prototype Test".equals(OfflineBinaryManifest.applicationLabel(xml)),
                        "Application label was not rewritten");
                if (withIcon) {
                    for (Map.Entry<String, byte[]> iconEntry : icons.entrySet()) {
                        boolean found = false;
                        java.util.Enumeration<? extends ZipEntry> items = apk.entries();
                        while (items.hasMoreElements()) {
                            ZipEntry item = items.nextElement();
                            if (item.getName().startsWith("res/drawable-" + iconEntry.getKey())
                                    && item.getName().endsWith("/love.png")) {
                                try (java.io.InputStream input = apk.getInputStream(item)) {
                                    check(Arrays.equals(input.readAllBytes(), iconEntry.getValue()),
                                            "Launcher PNG was not replaced");
                                }
                                found = true;
                            }
                        }
                        check(found, "Missing launcher icon density " + iconEntry.getKey());
                    }
                }
            }
            System.out.println("Manifest rewrite in assembled APK verified");
        } else if (args.length == 0) {
            test();
        } else {
            throw new IllegalArgumentException(
                "Usage: OfflineGameApkTest [--repack template.apk game.love output.apk]");
        }
    }
}
