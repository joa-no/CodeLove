package org.love2d.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Minimal valid UTF-8 Android binary XML string-pool fixture. */
public final class OfflineBinaryManifestCheck {
    public static void main(String[] args) throws Exception {
        byte[] valid = fixture("game");
        String[] strings = OfflineBinaryManifest.readStrings(valid);
        if (strings.length != 1 || !"game".equals(strings[0])) {
            throw new AssertionError("Manifest string not decoded");
        }
        byte[] utf16 = utf16Fixture("Jogo");
        if (!"Jogo".equals(OfflineBinaryManifest.readStrings(utf16)[0])) {
            throw new AssertionError("UTF-16 manifest string not decoded");
        }
        byte[] brokenUtf16 = utf16.clone();
        brokenUtf16[50] = 1;
        reject(brokenUtf16);
        byte[] badHeader = valid.clone();
        badHeader[0] = 0;
        reject(badHeader);
        byte[] badLength = valid.clone();
        badLength[40] = 5; // UTF-16 length must be four, not five.
        reject(badLength);
        byte[] badTerminator = valid.clone();
        badTerminator[46] = 1;
        reject(badTerminator);
        if (args.length == 1) {
            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(args[0])) {
                java.util.zip.ZipEntry entry = apk.getEntry("AndroidManifest.xml");
                if (entry == null || entry.getSize() > 4 * 1024 * 1024) {
                    throw new AssertionError("Missing or oversized real Android manifest");
                }
                byte[] real;
                try (java.io.InputStream input = apk.getInputStream(entry);
                     java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        output.write(buffer, 0, n);
                        if (output.size() > 4 * 1024 * 1024) {
                            throw new AssertionError("Manifest exceeds limit");
                        }
                    }
                    real = output.toByteArray();
                }
                String[] decoded = OfflineBinaryManifest.readStrings(real);
                if (decoded.length == 0) {
                    throw new AssertionError("Real Android manifest has no strings");
                }
                System.out.println("Real template manifest strings decoded: " + decoded.length);
            }
        }
        System.out.println("Binary manifest string pool checks passed");
    }

    private static void reject(byte[] bytes) throws Exception {
        try {
            OfflineBinaryManifest.readStrings(bytes);
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Malformed binary XML accepted");
    }

    private static byte[] fixture(String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        int poolSize = 28 + 4 + 2 + text.length + 1;
        int padded = (poolSize + 3) & ~3;
        byte[] bytes = new byte[8 + padded];
        put16(bytes, 0, 3);
        put16(bytes, 2, 8);
        put32(bytes, 4, bytes.length);
        put16(bytes, 8, 1);
        put16(bytes, 10, 28);
        put32(bytes, 12, padded);
        put32(bytes, 16, 1);
        put32(bytes, 24, 0x100);
        put32(bytes, 28, 32);
        bytes[40] = (byte)value.length();
        bytes[41] = (byte)text.length;
        System.arraycopy(text, 0, bytes, 42, text.length);
        return bytes;
    }

    private static byte[] utf16Fixture(String value) {
        int padded = (28 + 4 + 2 + value.length() * 2 + 2 + 3) & ~3;
        byte[] bytes = new byte[8 + padded];
        put16(bytes, 0, 3);
        put16(bytes, 2, 8);
        put32(bytes, 4, bytes.length);
        put16(bytes, 8, 1);
        put16(bytes, 10, 28);
        put32(bytes, 12, padded);
        put32(bytes, 16, 1);
        put32(bytes, 28, 32);
        put16(bytes, 40, value.length());
        for (int i = 0; i < value.length(); i++) {
            put16(bytes, 42 + i * 2, value.charAt(i));
        }
        return bytes;
    }

    private static void put16(byte[] b, int at, int value) {
        b[at] = (byte)value;
        b[at + 1] = (byte)(value >>> 8);
    }

    private static void put32(byte[] b, int at, int value) {
        put16(b, at, value);
        put16(b, at + 2, value >>> 16);
    }
}
