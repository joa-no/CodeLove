package org.love2d.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Read-only lookup of the compiled LÖVE launcher icon paths in resources.arsc.
 * AGP may shorten ZIP names (e.g. res/IP.png) while resource IDs and names
 * remain drawable/love. Never guess the file paths or rewrite resources.arsc.
 */
final class OfflineApkIconResources {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_STRINGS = 100000;
    private static final int TABLE = 0x0002, POOL = 0x0001;
    private static final int PACKAGE = 0x0200, TYPE = 0x0201;
    private static final int STRING = 0x03;
    private static final Map<Integer, String> DENSITIES;
    static {
        Map<Integer, String> densities = new HashMap<>();
        densities.put(160, "mdpi");
        densities.put(240, "hdpi");
        densities.put(320, "xhdpi");
        densities.put(480, "xxhdpi");
        densities.put(640, "xxxhdpi");
        DENSITIES = Collections.unmodifiableMap(densities);
    }

    private OfflineApkIconResources() {}

    static Map<String, String> find(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 20 || bytes.length > MAX_BYTES
                || u16(bytes, 0) != TABLE || u16(bytes, 2) < 12
                || u32(bytes, 4) != bytes.length) {
            throw new IOException("Invalid Android resource table");
        }
        int cursor = u16(bytes, 2);
        String[] values = null;
        int packages = 0;
        Map<String, String> icons = new LinkedHashMap<>();
        Set<String> usedPaths = new HashSet<>();
        while (cursor < bytes.length) {
            int size = chunk(bytes, cursor, bytes.length);
            int type = u16(bytes, cursor);
            if (type == POOL) {
                if (values != null || packages > 0) {
                    throw new IOException("Unexpected resource string pool");
                }
                values = strings(bytes, cursor, size);
            } else if (type == PACKAGE) {
                if (values == null || ++packages != 1) {
                    throw new IOException("Unsupported Android resource packages");
                }
                scanPackage(bytes, cursor, size, values, icons, usedPaths);
            } else {
                throw new IOException("Unexpected resource table chunk");
            }
            cursor += size;
        }
        if (packages != 1 || icons.size() != DENSITIES.size()) {
            throw new IOException("Incomplete LÖVE launcher icon mapping");
        }
        return Collections.unmodifiableMap(icons);
    }

    private static void scanPackage(byte[] bytes, int base, int length,
                                    String[] values, Map<String, String> icons,
                                    Set<String> usedPaths) throws IOException {
        int header = u16(bytes, base + 2);
        if (header < 288 || header > length || u32(bytes, base + 284) != 0) {
            throw new IOException("Unsupported Android resource package header");
        }
        int typePool = boundedOffset(bytes, base, length, base + 268);
        int keyPool = boundedOffset(bytes, base, length, base + 276);
        String[] types = strings(bytes, typePool,
                chunk(bytes, typePool, base + length));
        String[] keys = strings(bytes, keyPool,
                chunk(bytes, keyPool, base + length));
        int cursor = base + header;
        while (cursor < base + length) {
            int size = chunk(bytes, cursor, base + length);
            if (u16(bytes, cursor) == TYPE) {
                scanType(bytes, cursor, size, types, keys, values, icons, usedPaths);
            }
            cursor += size;
        }
    }

    private static void scanType(byte[] bytes, int base, int size,
                                 String[] types, String[] keys, String[] values,
                                 Map<String, String> icons, Set<String> usedPaths)
            throws IOException {
        int header = u16(bytes, base + 2);
        if (header < 36 || header > size) {
            throw new IOException("Invalid resource type header");
        }
        int typeId = bytes[base + 8] & 255;
        if (typeId < 1 || typeId > types.length
                || !"drawable".equals(types[typeId - 1])) {
            return;
        }
        long configSize = u32(bytes, base + 20);
        if (configSize < 16 || header < 20L + configSize) {
            throw new IOException("Invalid resource configuration");
        }
        String density = DENSITIES.get(u16(bytes, base + 34));
        if (density == null) return;
        long count = u32(bytes, base + 12);
        long entriesStart = u32(bytes, base + 16);
        if (count > 100000 || count * 4 > size - header || entriesStart > size
                || entriesStart < header + count * 4) {
            throw new IOException("Invalid resource entry table");
        }
        for (int i = 0; i < (int)count; i++) {
            long relative = u32(bytes, base + header + i * 4);
            if (relative == 0xffffffffL) continue;
            if (relative > size - entriesStart - 16) {
                throw new IOException("Resource entry outside type chunk");
            }
            int at = base + (int)entriesStart + (int)relative;
            if (u16(bytes, at) < 8) {
                throw new IOException("Invalid resource entry");
            }
            long key = u32(bytes, at + 4);
            if (key >= keys.length) throw new IOException("Invalid resource key");
            if (!"love".equals(keys[(int)key])) continue;
            if (u16(bytes, at + 2) != 0 || u16(bytes, at + 8) != 8
                    || (bytes[at + 11] & 255) != STRING) {
                throw new IOException("Launcher icon is not a PNG file resource");
            }
            long index = u32(bytes, at + 12);
            if (index >= values.length) throw new IOException("Invalid launcher icon path");
            String path = values[(int)index];
            if (!path.startsWith("res/") || !path.endsWith(".png")
                    || path.contains("..") || path.indexOf('\\') >= 0
                    || icons.containsKey(density) || !usedPaths.add(path)) {
                throw new IOException("Ambiguous launcher icon resource");
            }
            icons.put(density, path);
        }
    }

    private static int boundedOffset(byte[] bytes, int base, int size, int field)
            throws IOException {
        long offset = u32(bytes, field);
        if (offset < u16(bytes, base + 2) || offset > size - 8) {
            throw new IOException("Invalid resource string pool offset");
        }
        return base + (int)offset;
    }

    private static String[] strings(byte[] bytes, int base, int length) throws IOException {
        if (u16(bytes, base) != POOL || u16(bytes, base + 2) < 28
                || length < u16(bytes, base + 2)) {
            throw new IOException("Unsupported Android resource string pool");
        }
        int header = u16(bytes, base + 2);
        long count = u32(bytes, base + 8), start = u32(bytes, base + 20);
        int flags = (int)u32(bytes, base + 16);
        if (count > MAX_STRINGS || start > length || header + count * 4 > start) {
            throw new IOException("Invalid Android resource string pool entries");
        }
        String[] result = new String[(int)count];
        for (int i = 0; i < result.length; i++) {
            long offset = u32(bytes, base + header + i * 4);
            if (offset > length - start) throw new IOException("Resource string outside pool");
            int at = base + (int)(start + offset), end = base + length;
            if ((flags & 0x100) != 0) {
                int[] charLength = utf8Length(bytes, at, end);
                int[] byteLength = utf8Length(bytes, charLength[1], end);
                at = byteLength[1];
                if (byteLength[0] >= end - at || bytes[at + byteLength[0]] != 0) {
                    throw new IOException("Malformed resource UTF-8 string");
                }
                result[i] = new String(bytes, at, byteLength[0], StandardCharsets.UTF_8);
            } else {
                if (at > end - 2) throw new IOException("Invalid UTF-16 resource");
                int n = u16(bytes, at);
                at += 2;
                if ((n & 0x8000) != 0) {
                    if (at > end - 2) throw new IOException("Truncated UTF-16 length");
                    n = ((n & 0x7fff) << 16) | u16(bytes, at);
                    at += 2;
                }
                if (n < 0 || n > (end - at - 2) / 2) {
                    throw new IOException("Invalid UTF-16 resource length");
                }
                result[i] = new String(bytes, at, n * 2, StandardCharsets.UTF_16LE);
                if (u16(bytes, at + n * 2) != 0) {
                    throw new IOException("Unterminated UTF-16 resource string");
                }
            }
        }
        return result;
    }

    private static int[] utf8Length(byte[] bytes, int at, int end) throws IOException {
        if (at >= end) throw new IOException("Missing UTF-8 length");
        int first = bytes[at++] & 255;
        if ((first & 0x80) == 0) return new int[]{first, at};
        if (at >= end) throw new IOException("Truncated UTF-8 length");
        return new int[]{((first & 0x7f) << 8) | (bytes[at++] & 255), at};
    }

    private static int chunk(byte[] bytes, int at, int limit) throws IOException {
        if (at < 0 || at > limit - 8) throw new IOException("Truncated resource chunk");
        int header = u16(bytes, at + 2);
        long size = u32(bytes, at + 4);
        if (header < 8 || size < header || size > limit - at) {
            throw new IOException("Invalid resource chunk bounds");
        }
        return (int)size;
    }

    private static int u16(byte[] data, int pos) throws IOException {
        if (pos < 0 || pos > data.length - 2) {
            throw new IOException("Truncated resource word");
        }
        return (data[pos] & 255) | (data[pos + 1] & 255) << 8;
    }
    private static long u32(byte[] data, int pos) throws IOException {
        return ((long)u16(data, pos) | (long)u16(data, pos + 2) << 16) & 0xffffffffL;
    }
}
