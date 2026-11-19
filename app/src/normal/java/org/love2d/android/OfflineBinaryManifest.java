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

    /**
     * Inspect root manifest attributes without changing the APK. Binary XML
     * attributes contain both a raw string index and a typed value.
     */
    static String[] rootAttributes(byte[] xml) throws IOException {
        String[] pool = readStrings(xml);
        int at = 8;
        while (at < xml.length) {
            int type = u16(xml, at);
            int header = u16(xml, at + 2);
            long size = u32(xml, at + 4);
            if (header < 8 || size < header || size > xml.length - at
                    || (size & 3) != 0) {
                throw new IOException("Invalid XML chunk at " + at);
            }
            if (type == 0x0102) {
                if (header != 16 || size < 36) {
                    throw new IOException("Invalid start-element header or extension");
                }
                long nameIndex = u32(xml, at + 20);
                if (nameIndex >= pool.length || !"manifest".equals(pool[(int)nameIndex])) {
                    throw new IOException("First XML element is not manifest");
                }
                int attributeStart = u16(xml, at + 24);
                int attributeSize = u16(xml, at + 26);
                int attributeCount = u16(xml, at + 28);
                long first = 16L + attributeStart;
                if (attributeSize < 20 || first < 36
                        || first + (long)attributeSize * attributeCount > size) {
                    throw new IOException("Invalid manifest attribute table");
                }
                String[] attributes = new String[attributeCount];
                for (int i = 0; i < attributeCount; i++) {
                    int pos = at + (int)(first + (long)i * attributeSize);
                    long name = u32(xml, pos + 4);
                    long raw = u32(xml, pos + 8);
                    int valueSize = u16(xml, pos + 12);
                    int valueType = xml[pos + 15] & 255;
                    long value = u32(xml, pos + 16);
                    if (name >= pool.length || valueSize != 8
                            || (raw != 0xffffffffL && raw >= pool.length)
                            || (valueType == 3 && value >= pool.length)) {
                        throw new IOException("Invalid manifest attribute at " + i);
                    }
                    String display = raw != 0xffffffffL ? pool[(int)raw]
                            : valueType == 3 ? pool[(int)value]
                            : "type=0x" + Integer.toHexString(valueType)
                              + ",data=" + value;
                    attributes[i] = pool[(int)name] + "=" + display;
                }
                return attributes;
            }
            at += (int)size;
        }
        throw new IOException("No manifest start element found");
    }

    /** Read-only inventory of component names and provider authorities. */
    static String[] componentReferences(byte[] xml) throws IOException {
        String[] pool = readStrings(xml);
        chunkTypes(xml);
        java.util.ArrayList<String> references = new java.util.ArrayList<>();
        for (int at = 8; at < xml.length; at += (int)u32(xml, at + 4)) {
            if (u16(xml, at) != 0x0102) continue;
            if (u16(xml, at + 2) != 16 || u32(xml, at + 4) < 36) {
                throw new IOException("Invalid component element header");
            }
            long elementIndex = u32(xml, at + 20);
            if (elementIndex >= pool.length) throw new IOException("Invalid component element");
            String element = pool[(int)elementIndex];
            boolean component = "activity".equals(element) || "activity-alias".equals(element)
                    || "service".equals(element) || "receiver".equals(element)
                    || "provider".equals(element) || "instrumentation".equals(element);
            if (!component) continue;
            int start = u16(xml, at + 24), stride = u16(xml, at + 26);
            int count = u16(xml, at + 28);
            long size = u32(xml, at + 4);
            if (stride < 20 || 16L + start < 36
                    || 16L + start + (long)stride * count > size) {
                throw new IOException("Invalid component attributes");
            }
            for (int i = 0; i < count; i++) {
                int p = at + 16 + start + i * stride;
                long name = u32(xml, p + 4), raw = u32(xml, p + 8);
                long typed = u32(xml, p + 16);
                if (name >= pool.length || u16(xml, p + 12) != 8) {
                    throw new IOException("Invalid component attribute");
                }
                String attribute = pool[(int)name];
                if (!"name".equals(attribute) && !"targetActivity".equals(attribute)
                        && !"authorities".equals(attribute) && !"permission".equals(attribute)
                        && !"readPermission".equals(attribute)
                        && !"writePermission".equals(attribute)) continue;
                String value;
                if (raw != 0xffffffffL) {
                    if (raw >= pool.length) throw new IOException("Invalid raw component string");
                    value = pool[(int)raw];
                } else if ((xml[p + 15] & 255) == 3) {
                    if (typed >= pool.length) throw new IOException("Invalid component string");
                    value = pool[(int)typed];
                } else {
                    value = "type=0x" + Integer.toHexString(xml[p + 15] & 255)
                            + ",data=" + typed;
                }
                references.add(element + "." + attribute + "=" + value);
            }
        }
        return references.toArray(new String[0]);
    }

    /**
     * Rewrite only the manifest's integer versionCode in a defensive copy.
     * Does not change the package, versionName, resource table or APK.
     */
    static byte[] withVersionCode(byte[] xml, int versionCode) throws IOException {
        if (versionCode <= 0) throw new IOException("Invalid versionCode");
        String[] pool = readStrings(xml);
        byte[] result = xml.clone();
        int at = 8;
        while (at < result.length) {
            int type = u16(result, at);
            int header = u16(result, at + 2);
            long size = u32(result, at + 4);
            if (header < 8 || size < header || size > result.length - at
                    || (size & 3) != 0) {
                throw new IOException("Invalid XML chunk at " + at);
            }
            if (type == 0x0102) {
                if (header != 16 || size < 36
                        || u32(result, at + 20) >= pool.length
                        || !"manifest".equals(pool[(int)u32(result, at + 20)])) {
                    throw new IOException("Invalid manifest root element");
                }
                int start = u16(result, at + 24);
                int stride = u16(result, at + 26);
                int count = u16(result, at + 28);
                long first = 16L + start;
                if (stride < 20 || first < 36 || first + (long)stride * count > size) {
                    throw new IOException("Invalid root attribute table");
                }
                int found = 0;
                for (int i = 0; i < count; i++) {
                    int p = at + (int)(first + (long)i * stride);
                    long name = u32(result, p + 4);
                    if (name >= pool.length) throw new IOException("Invalid attribute name");
                    if (!"versionCode".equals(pool[(int)name])) continue;
                    if (++found != 1 || u16(result, p + 12) != 8
                            || (result[p + 15] & 255) != 0x10
                            || u32(result, p + 8) != 0xffffffffL) {
                        throw new IOException("Unsupported versionCode attribute");
                    }
                    put32(result, p + 16, versionCode);
                }
                if (found != 1) throw new IOException("Missing unique versionCode");
                return result;
            }
            at += (int)size;
        }
        throw new IOException("Missing manifest element");
    }

    /**
     * Rewrite versionName with a rebuilt string pool, preserving existing
     * string indices and rejecting shared references.
     */
    static byte[] withVersionName(byte[] xml, String versionName)
            throws IOException {
        if (versionName == null || !versionName.matches("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}")) {
            throw new IOException("Invalid versionName");
        }
        String[] strings = readStrings(xml);
        int root = -1;
        int at = 8;
        while (at < xml.length) {
            int type = u16(xml, at);
            int header = u16(xml, at + 2);
            long size = u32(xml, at + 4);
            if (header < 8 || size < header || size > xml.length - at || (size & 3) != 0) {
                throw new IOException("Invalid XML chunk");
            }
            if (type == 0x0102) { root = at; break; }
            at += (int)size;
        }
        if (root < 0 || u16(xml, root + 2) != 16
                || u32(xml, root + 20) >= strings.length
                || !"manifest".equals(strings[(int)u32(xml, root + 20)])) {
            throw new IOException("Invalid manifest root");
        }
        int start = u16(xml, root + 24);
        int stride = u16(xml, root + 26);
        int count = u16(xml, root + 28);
        long chunkSize = u32(xml, root + 4);
        if (stride < 20 || 16L + start < 36
                || 16L + start + (long)stride * count > chunkSize) {
            throw new IOException("Invalid manifest attributes");
        }
        int index = -1;
        for (int i = 0; i < count; i++) {
            int p = root + 16 + start + i * stride;
            long name = u32(xml, p + 4);
            if (name >= strings.length) throw new IOException("Invalid attribute name");
            if (!"versionName".equals(strings[(int)name])) continue;
            if (index != -1 || u16(xml, p + 12) != 8
                    || (xml[p + 15] & 255) != 3) {
                throw new IOException("Unsupported versionName");
            }
            long typed = u32(xml, p + 16);
            long raw = u32(xml, p + 8);
            if (typed >= strings.length || (raw != 0xffffffffL && raw != typed)) {
                throw new IOException("Unsupported versionName references");
            }
            index = (int)typed;
        }
        if (index < 0) throw new IOException("Missing versionName");
        // Reject shared pool entries: a version string may be referenced
        // elsewhere in XML and modifying it would change unrelated fields.
        int references = 0;
        at = 8;
        while (at < xml.length) {
            int type = u16(xml, at);
            int header = u16(xml, at + 2);
            int size = (int)u32(xml, at + 4);
            if (type == 0x0102) {
                int astart = u16(xml, at + 24), step = u16(xml, at + 26);
                int n = u16(xml, at + 28);
                if (header != 16 || size < 36 || step < 20
                        || 16L + astart < 36 || 16L + astart + (long)step * n > size) {
                    throw new IOException("Invalid start element");
                }
                for (int i = 0; i < n; i++) {
                    int p = at + 16 + astart + i * step;
                    if (u32(xml, p + 8) == index || ((xml[p + 15] & 255) == 3
                            && u32(xml, p + 16) == index)) {
                        if (at != root || !("versionName".equals(strings[(int)u32(xml, p + 4)]))) {
                            throw new IOException("Shared versionName string");
                        }
                        references++;
                    }
                }
            }
            at += size;
        }
        if (references == 0) throw new IOException("No versionName reference");
        return replacePoolString(xml, strings, index, versionName);
    }

    /**
     * Rewrite the manifest root package string only when its pool entry is
     * not referenced by another XML attribute. Resource-table package IDs,
     * component names and applicationId-derived authorities are not changed.
     */
    static byte[] withPackageName(byte[] xml, String packageName) throws IOException {
        if (packageName == null || packageName.length() > 223
                || !packageName.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IOException("Invalid Android package name");
        }
        String[] strings = readStrings(xml);
        chunkTypes(xml);
        int root = -1;
        int index = -1;
        int references = 0;
        for (int at = 8; at < xml.length; at += (int)u32(xml, at + 4)) {
            int type = u16(xml, at);
            int header = u16(xml, at + 2);
            int size = (int)u32(xml, at + 4);
            if (type != 0x0102) continue;
            if (header != 16 || size < 36) throw new IOException("Invalid element header");
            long elementName = u32(xml, at + 20);
            if (elementName >= strings.length) throw new IOException("Invalid element name");
            if (root < 0) {
                if (!"manifest".equals(strings[(int)elementName])) {
                    throw new IOException("Invalid manifest root");
                }
                root = at;
            }
            int start = u16(xml, at + 24), stride = u16(xml, at + 26);
            int count = u16(xml, at + 28);
            if (stride < 20 || 16L + start < 36
                    || 16L + start + (long)stride * count > size) {
                throw new IOException("Invalid XML attribute table");
            }
            for (int i = 0; i < count; i++) {
                int p = at + 16 + start + i * stride;
                long name = u32(xml, p + 4);
                long raw = u32(xml, p + 8);
                long typed = u32(xml, p + 16);
                int valueType = xml[p + 15] & 255;
                if (name >= strings.length || u16(xml, p + 12) != 8
                        || (raw != 0xffffffffL && raw >= strings.length)
                        || (valueType == 3 && typed >= strings.length)) {
                    throw new IOException("Invalid XML attribute");
                }
                if (at == root && "package".equals(strings[(int)name])) {
                    if (index >= 0 || valueType != 3 || typed >= strings.length
                            || (raw != 0xffffffffL && raw != typed)) {
                        throw new IOException("Unsupported package attribute");
                    }
                    index = (int)typed;
                }
            }
        }
        if (index < 0) throw new IOException("Missing package attribute");
        // Refuse to alter a string shared by any unrelated attribute.
        for (int at = 8; at < xml.length; at += (int)u32(xml, at + 4)) {
            if (u16(xml, at) != 0x0102) continue;
            int start = u16(xml, at + 24), stride = u16(xml, at + 26);
            int count = u16(xml, at + 28);
            for (int i = 0; i < count; i++) {
                int p = at + 16 + start + i * stride;
                if (u32(xml, p + 8) == index
                        || ((xml[p + 15] & 255) == 3 && u32(xml, p + 16) == index)) {
                    long name = u32(xml, p + 4);
                    if (at != root || name >= strings.length
                            || !"package".equals(strings[(int)name])) {
                        throw new IOException("Package string is shared by another attribute");
                    }
                    references++;
                }
            }
        }
        if (references == 0) throw new IOException("Package string has no references");
        return replacePoolString(xml, strings, index, packageName);
    }

    /**
     * Rewrite only AndroidX Startup's provider authority. Component class
     * names remain unchanged; ambiguous or shared strings are rejected.
     */
    static byte[] withStartupAuthority(byte[] xml, String newPackage) throws IOException {
        if (newPackage == null || newPackage.length() > 223
                || !newPackage.matches("[A-Za-z_][A-Za-z0-9_]*([.][A-Za-z_][A-Za-z0-9_]*)+")) {
            throw new IOException("Invalid package name");
        }
        String[] strings = readStrings(xml);
        chunkTypes(xml);
        String originalPackage = null;
        for (String attribute : rootAttributes(xml)) {
            if (attribute.startsWith("package=")) {
                if (originalPackage != null) throw new IOException("Duplicate manifest package");
                originalPackage = attribute.substring(8);
            }
        }
        if (originalPackage == null) throw new IOException("Missing manifest package");
        String oldAuthority = originalPackage + ".androidx-startup";
        int index = -1;
        int providerCount = 0;
        int references = 0;
        for (int at = 8; at < xml.length; at += (int)u32(xml, at + 4)) {
            if (u16(xml, at) != 0x0102) continue;
            if (u16(xml, at + 2) != 16 || u32(xml, at + 4) < 36) {
                throw new IOException("Invalid element header");
            }
            long elementName = u32(xml, at + 20);
            if (elementName >= strings.length) throw new IOException("Invalid element name");
            int start = u16(xml, at + 24), stride = u16(xml, at + 26);
            int count = u16(xml, at + 28);
            if (stride < 20 || 16L + start < 36
                    || 16L + start + (long)stride * count > u32(xml, at + 4)) {
                throw new IOException("Invalid attribute table");
            }
            boolean provider = "provider".equals(strings[(int)elementName]);
            boolean startupProvider = false;
            int authorityIndex = -1;
            for (int i = 0; i < count; i++) {
                int p = at + 16 + start + i * stride;
                long name = u32(xml, p + 4), raw = u32(xml, p + 8);
                long typed = u32(xml, p + 16);
                if (name >= strings.length || u16(xml, p + 12) != 8) {
                    throw new IOException("Invalid attribute");
                }
                if ((xml[p + 15] & 255) != 3) continue;
                if (typed >= strings.length || (raw != 0xffffffffL && raw != typed)) {
                    throw new IOException("Unsupported string attribute");
                }
                String attribute = strings[(int)name];
                if (provider && "name".equals(attribute)
                        && "androidx.startup.InitializationProvider".equals(strings[(int)typed])) {
                    startupProvider = true;
                }
                if (provider && "authorities".equals(attribute)) {
                    if (authorityIndex >= 0) throw new IOException("Duplicate provider authorities");
                    authorityIndex = (int)typed;
                }
            }
            if (provider && startupProvider) {
                if (++providerCount != 1 || authorityIndex < 0
                        || !oldAuthority.equals(strings[authorityIndex])) {
                    throw new IOException("Unexpected AndroidX Startup authority");
                }
                index = authorityIndex;
            }
        }
        if (providerCount != 1) throw new IOException("Missing unique AndroidX Startup provider");
        // Check all XML attribute string references before rewriting a pool entry.
        for (int at = 8; at < xml.length; at += (int)u32(xml, at + 4)) {
            if (u16(xml, at) != 0x0102) continue;
            int start = u16(xml, at + 24), stride = u16(xml, at + 26);
            int count = u16(xml, at + 28);
            long elementName = u32(xml, at + 20);
            for (int i = 0; i < count; i++) {
                int p = at + 16 + start + i * stride;
                long raw = u32(xml, p + 8), typed = u32(xml, p + 16);
                if (raw != index && !((xml[p + 15] & 255) == 3 && typed == index)) continue;
                long name = u32(xml, p + 4);
                if (elementName >= strings.length || name >= strings.length
                        || !"provider".equals(strings[(int)elementName])
                        || !"authorities".equals(strings[(int)name])) {
                    throw new IOException("Shared provider authority string");
                }
                references++;
            }
        }
        if (references != 1) throw new IOException("Ambiguous provider authority references");
        return replacePoolString(xml, strings, index, newPackage + ".androidx-startup");
    }

    /**
     * Rebuild an unstyled Android binary XML string pool while keeping all
     * string indices stable. Only the pool bytes and root XML size change.
     */
    private static byte[] replacePoolString(byte[] xml, String[] strings,
                                             int index, String replacement)
            throws IOException {
        final int poolAt = 8;
        if (u16(xml, poolAt) != 1 || u16(xml, poolAt + 2) != 28
                || u32(xml, poolAt + 12) != 0 || u32(xml, poolAt + 24) != 0
                || u32(xml, poolAt + 16) != 0
                   && u32(xml, poolAt + 16) != 0x100) {
            throw new IOException("Unsupported styled or flagged string pool");
        }
        boolean utf8 = u32(xml, poolAt + 16) == 0x100;
        int oldSize = (int)u32(xml, poolAt + 4);
        int offsetsStart = 28;
        int dataStart = offsetsStart + strings.length * 4;
        java.io.ByteArrayOutputStream data = new java.io.ByteArrayOutputStream();
        int[] offsets = new int[strings.length];
        for (int i = 0; i < strings.length; i++) {
            offsets[i] = data.size();
            String value = i == index ? replacement : strings[i];
            if (utf8) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                writeLength8(data, value.length());
                writeLength8(data, bytes.length);
                data.write(bytes, 0, bytes.length);
                data.write(0);
            } else {
                writeLength16(data, value.length());
                for (int j = 0; j < value.length(); j++) {
                    int c = value.charAt(j);
                    data.write(c & 255);
                    data.write(c >>> 8);
                }
                data.write(0);
                data.write(0);
            }
        }
        int poolSize = (dataStart + data.size() + 3) & ~3;
        long total = (long)xml.length - oldSize + poolSize;
        if (total > 4L * 1024 * 1024) {
            throw new IOException("Manifest string pool exceeds size limit");
        }
        byte[] result = new byte[(int)total];
        System.arraycopy(xml, 0, result, 0, poolAt + 28);
        put32(result, poolAt + 4, poolSize);
        put32(result, poolAt + 20, dataStart);
        for (int i = 0; i < offsets.length; i++) {
            put32(result, poolAt + offsetsStart + i * 4, offsets[i]);
        }
        byte[] encoded = data.toByteArray();
        System.arraycopy(encoded, 0, result, poolAt + dataStart, encoded.length);
        System.arraycopy(xml, poolAt + oldSize, result, poolAt + poolSize,
                xml.length - poolAt - oldSize);
        put32(result, 4, result.length);
        if (!replacement.equals(readStrings(result)[index])) {
            throw new IOException("Rewritten string pool failed verification");
        }
        chunkTypes(result);
        return result;
    }

    private static void writeLength8(java.io.ByteArrayOutputStream out, int n)
            throws IOException {
        if (n < 0 || n > 32767) throw new IOException("UTF-8 string too long");
        if (n < 128) out.write(n);
        else { out.write((n >>> 8) | 128); out.write(n & 255); }
    }

    private static void writeLength16(java.io.ByteArrayOutputStream out, int n) {
        if (n < 32768) {
            out.write(n & 255); out.write(n >>> 8);
        } else {
            int first = (n >>> 16) | 0x8000;
            out.write(first & 255); out.write(first >>> 8);
            out.write(n & 255); out.write((n >>> 8) & 255);
        }
    }

    private static void put32(byte[] bytes, int at, int value) {
        bytes[at] = (byte)value;
        bytes[at + 1] = (byte)(value >>> 8);
        bytes[at + 2] = (byte)(value >>> 16);
        bytes[at + 3] = (byte)(value >>> 24);
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
