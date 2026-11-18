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
        byte[] badHeader = valid.clone();
        badHeader[0] = 0;
        reject(badHeader);
        byte[] badLength = valid.clone();
        badLength[40] = 5; // UTF-16 length must be four, not five.
        reject(badLength);
        byte[] badTerminator = valid.clone();
        badTerminator[46] = 1;
        reject(badTerminator);
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

    private static void put16(byte[] b, int at, int value) {
        b[at] = (byte)value;
        b[at + 1] = (byte)(value >>> 8);
    }

    private static void put32(byte[] b, int at, int value) {
        put16(b, at, value);
        put16(b, at + 2, value >>> 16);
    }
}
