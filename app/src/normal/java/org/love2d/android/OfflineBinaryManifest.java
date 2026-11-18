package org.love2d.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Strict read-only inspection of Android's binary XML string pool.
 * A future manifest rewriter must validate this structure before editing.
 * Does not edit the manifest or claim to personalize an APK.
 */
final class OfflineBinaryManifest {
    private static final int XML = 0x0003;
    private static final int STRING_POOL = 0x0001;
    private static final int UTF8 = 0x100;
    private static final int MAX_STRINGS = 200000;

    private OfflineBinaryManifest() {}

    static String[] readStrings(byte[] xml) throws IOException {
        if (xml == null || xml.length < 36 || u16(xml, 0) != XML
                || u16(xml, 2) != 8 || u32(xml, 4) != xml.length) {
            throw new IOException("Invalid binary Android manifest header");
        }
        int offset = 8;
        if (u16(xml, offset) != STRING_POOL) {
            throw new IOException("Manifest does not start with a string pool");
        }
        int headerSize = u16(xml, offset + 2);
        long chunkSize = u32(xml, offset + 4);
        long count = u32(xml, offset + 8);
        long styles = u32(xml, offset + 12);
        long flags = u32(xml, offset + 16);
        long stringsStart = u32(xml, offset + 20);
        long stylesStart = u32(xml, offset + 24);
        if (headerSize != 28 || chunkSize < headerSize || count > MAX_STRINGS
                || styles != 0 || stylesStart != 0
                || (flags & ~UTF8) != 0 || stringsStart < 28 + count * 4
                || chunkSize > xml.length - offset) {
            throw new IOException("Unsupported binary manifest string pool"
                    + " (header=" + headerSize + ", count=" + count
                    + ", styles=" + styles + ", flags=0x"
                    + Long.toHexString(flags) + ", stringsStart=" + stringsStart
                    + ", stylesStart=" + stylesStart + ", chunk=" + chunkSize + ")");
        }
        int end = offset + (int) chunkSize;
        int dataStart = offset + (int) stringsStart;
        if (dataStart > end) throw new IOException("Invalid string data offset");
        String[] values = new String[(int) count];
        for (int i = 0; i < values.length; i++) {
            long relative = u32(xml, offset + headerSize + i * 4);
            if (relative > end - dataStart) {
                throw new IOException("String offset outside pool");
            }
            int at = dataStart + (int) relative;
            String value;
            if ((flags & UTF8) != 0) {
                int[] utf16Length = length(xml, at, end);
                int[] byteLength = length(xml, utf16Length[1], end);
                int size = byteLength[0];
                at = byteLength[1];
                if (size < 0 || at >= end || size >= end - at
                        || xml[at + size] != 0) {
                    throw new IOException("Malformed UTF-8 manifest string");
                }
                value = new String(xml, at, size, StandardCharsets.UTF_8);
                if (value.length() != utf16Length[0]
                        || !java.util.Arrays.equals(value.getBytes(StandardCharsets.UTF_8),
                                java.util.Arrays.copyOfRange(xml, at, at + size))) {
                    throw new IOException("Malformed or noncanonical manifest string");
                }
            } else {
                int[] decodedLength = utf16Length(xml, at, end);
                int chars = decodedLength[0];
                at = decodedLength[1];
                if (chars < 0 || chars > (end - at - 2) / 2) {
                    throw new IOException("Invalid UTF-16 manifest string length");
                }
                int terminator = at + chars * 2;
                if (u16(xml, terminator) != 0) {
                    throw new IOException("Missing UTF-16 manifest string terminator");
                }
                char[] characters = new char[chars];
                for (int j = 0; j < chars; j++) {
                    characters[j] = (char) u16(xml, at + j * 2);
                }
                value = new String(characters);
                for (int j = 0; j < value.length(); j++) {
                    char c = value.charAt(j);
                    if (Character.isHighSurrogate(c)) {
                        if (++j >= value.length() || !Character.isLowSurrogate(value.charAt(j))) {
                            throw new IOException("Malformed UTF-16 surrogate pair");
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IOException("Unpaired UTF-16 low surrogate");
                    }
                }
            }
            values[i] = value;
        }
        return values;
    }

    /**
     * Read-only inspection of the binary XML chunk stream. This establishes
     * safe chunk boundaries before any manifest attribute can be rewritten.
     */
    static int[] chunkTypes(byte[] xml) throws IOException {
        readStrings(xml);
        int[] types = new int[xml.length / 8];
        int count = 0;
        int at = 8;
        while (at < xml.length) {
            if (at > xml.length - 8) {
                throw new IOException("Truncated binary XML chunk header");
            }
            int type = u16(xml, at);
            int header = u16(xml, at + 2);
            long size = u32(xml, at + 4);
            if (header < 8 || size < header || size > xml.length - at
                    || (size & 3) != 0) {
                throw new IOException("Invalid binary XML chunk at offset " + at);
            }
            types[count++] = type;
            at += (int) size;
        }
        return java.util.Arrays.copyOf(types, count);
    }

    private static int[] utf16Length(byte[] data, int at, int end) throws IOException {
        if (at > end - 2) throw new IOException("Truncated UTF-16 length");
        int first = u16(data, at);
        if ((first & 0x8000) == 0) return new int[] {first, at + 2};
        if (at > end - 4) throw new IOException("Truncated extended UTF-16 length");
        return new int[] {((first & 0x7fff) << 16) | u16(data, at + 2), at + 4};
    }

    // UTF-8 Android string pools encode both UTF-16 character count and
    // UTF-8 byte count using the same one/two-byte length format.
    private static int[] length(byte[] data, int at, int end) throws IOException {
        if (at >= end) throw new IOException("Truncated manifest string length");
        int first = data[at++] & 255;
        if ((first & 128) == 0) return new int[] { first, at };
        if (at >= end) throw new IOException("Truncated manifest string length");
        return new int[] { ((first & 127) << 8) | (data[at++] & 255), at };
    }

    private static int u16(byte[] b, int p) throws IOException {
        if (p < 0 || p + 2 > b.length) throw new IOException("Truncated binary XML");
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }

    private static long u32(byte[] b, int p) throws IOException {
        if (p < 0 || p + 4 > b.length) throw new IOException("Truncated binary XML");
        return (long)(b[p] & 255) | ((long)(b[p + 1] & 255) << 8)
                | ((long)(b[p + 2] & 255) << 16) | ((long)(b[p + 3] & 255) << 24);
    }
}
