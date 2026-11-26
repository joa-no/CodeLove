package org.love2d.android;

import android.app.Activity;
import android.content.ContentResolver;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports a LOVE archive into a newly created SAF project, never an existing project. */
final class LoveProjectImport {
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 10000;
    private static final int MAX_DEPTH = 32;

    static final class Result {
        final String documentId;
        final String name;
        final int error;

        Result(String documentId, String name, int error) {
            this.documentId = documentId;
            this.name = name;
            this.error = error;
        }
    }

    private static final class Node {
        final String path;
        final boolean directory;
        ZipEntry entry;
        Node(String path, boolean directory, ZipEntry entry) {
            this.path = path;
            this.directory = directory;
            this.entry = entry;
        }
    }

    private LoveProjectImport() {}

    private static boolean validName(String name) {
        if (name == null || name.trim().isEmpty() || name.length() > 255
                || name.equals(".") || name.equals("..")
                || name.endsWith(".") || name.endsWith(" ")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0) return false;
        }
        return true;
    }

    static String projectName(String filename) {
        if (!LoveArchivePlayback.isLoveName(filename)) return null;
        String name = filename.substring(0, filename.length() - 5);
        return validName(name) ? name : null;
    }

    private static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String leaf(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static void addNode(LinkedHashMap<String, Node> nodes, Map<String, String> folded,
            String path, boolean directory, ZipEntry entry) throws IOException {
        String key = path.toLowerCase(Locale.ROOT);
        String oldPath = folded.get(key);
        if (oldPath != null && !oldPath.equals(path)) throw new IOException("Case collision");
        Node old = nodes.get(path);
        if (old != null) {
            if (!old.directory || !directory || (entry != null && old.entry != null)) {
                throw new IOException("Duplicate or conflicting archive path");
            }
            if (entry != null) old.entry = entry;
            return;
        }
        if (nodes.size() >= MAX_ENTRIES) throw new IOException("Too many archive paths");
        folded.put(key, path);
        nodes.put(path, new Node(path, directory, entry));
    }

    private static LinkedHashMap<String, Node> inspect(ZipFile zip) throws IOException {
        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        Map<String, String> folded = new HashMap<>();
        long total = 0;
        int count = 0;
        java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (++count > MAX_ENTRIES) throw new IOException("Too many ZIP entries");
            String path = entry.getName();
            boolean directory = entry.isDirectory();
            if (directory) path = path.substring(0, path.length() - 1);
            String[] parts = path.split("/", -1);
            if (parts.length > MAX_DEPTH) throw new IOException("Archive path too deep");
            String prefix = "";
            for (int i = 0; i < parts.length; i++) {
                if (!validName(parts[i])) throw new IOException("Unsafe archive path");
                prefix = prefix.isEmpty() ? parts[i] : prefix + "/" + parts[i];
                boolean last = i == parts.length - 1;
                addNode(nodes, folded, prefix, !last || directory, last ? entry : null);
            }
            if (!directory) {
                long size = entry.getSize();
                if (size < 0 || size > MAX_BYTES - total || entry.getCrc() < 0) {
                    throw new IOException("Invalid archive size");
                }
                total += size;
            }
        }
        Node main = nodes.get("main.lua");
        if (main == null || main.directory) throw new IOException("Missing main.lua");
        return nodes;
    }

    private static void stage(ContentResolver resolver, Uri source, File staged) throws IOException {
        try (InputStream in = resolver.openInputStream(source);
                OutputStream out = new FileOutputStream(staged)) {
            if (in == null) throw new IOException("Cannot read archive");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (count > MAX_BYTES - total) throw new IOException("Archive too large");
                total += count;
                out.write(buffer, 0, count);
            }
        }
    }

    /** Only background work here; registration/selection happens after successful extraction. */
    static Result extract(Activity host, Uri source, Uri treeUri, String rootId,
            String destinationName, String name) {
        File staged = null;
        DirectSafStorage destination = null;
        DirectSafStorage.Entry project = null;
        boolean ownsProject = false;
        boolean complete = false;
        int error = R.string.cl_love_import_failed;
        try {
            if (!validName(name)) throw new IOException("Invalid project name");
            ContentResolver resolver = host.getContentResolver();
            staged = File.createTempFile("import-", ".love", host.getCacheDir());
            stage(resolver, source, staged);
            try (ZipFile zip = new ZipFile(staged)) {
                LinkedHashMap<String, Node> nodes = inspect(zip);
                destination = new DirectSafStorage(host, treeUri, rootId, destinationName);
                Set<String> ids = new HashSet<>();
                ids.add(rootId);
                for (DirectSafStorage.Entry item : destination.list(destination.getRoot())) {
                    ids.add(item.documentId);
                    if (item.name.equalsIgnoreCase(name)) {
                        return new Result(null, null, R.string.cl_love_import_exists);
                    }
                }
                project = destination.createDirectory(destination.getRoot(), name);
                if (!ids.add(project.documentId)) throw new IOException("Existing directory returned");
                ownsProject = true;
                if (!name.equals(LoveArchivePlayback.displayName(host, project.uri))) {
                    throw new IOException("Destination provider changed project name");
                }
                if (!destination.list(project.file).isEmpty()) throw new IOException("Project not empty");
                Map<String, File> dirs = new HashMap<>();
                dirs.put("", project.file);
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                for (Node node : nodes.values()) {
                    File parent = dirs.get(parent(node.path));
                    if (parent == null) throw new IOException("Missing parent");
                    String leaf = leaf(node.path);
                    DirectSafStorage.Entry item = node.directory
                        ? destination.createDirectory(parent, leaf)
                        : destination.createFile(parent, leaf);
                    if (!ids.add(item.documentId)) throw new IOException("Existing document returned");
                    if (!leaf.equals(LoveArchivePlayback.displayName(host, item.uri))) {
                        throw new IOException("Destination provider changed archive name");
                    }
                    if (node.directory) {
                        dirs.put(node.path, item.file);
                        continue;
                    }
                    CRC32 crc = new CRC32();
                    long written = 0;
                    try (InputStream in = zip.getInputStream(node.entry);
                            OutputStream out = resolver.openOutputStream(item.uri, "w")) {
                        if (out == null) throw new IOException("Cannot write project");
                        int count;
                        while ((count = in.read(buffer)) != -1) {
                            if (count > MAX_BYTES - total || count > node.entry.getSize() - written) {
                                throw new IOException("Expanded archive too large");
                            }
                            out.write(buffer, 0, count);
                            crc.update(buffer, 0, count);
                            total += count;
                            written += count;
                        }
                    }
                    if (written != node.entry.getSize() || crc.getValue() != node.entry.getCrc()) {
                        throw new IOException("Corrupt archive entry");
                    }
                }
                complete = true;
                return new Result(project.documentId, name, 0);
            }
        } catch (IOException | RuntimeException ignored) {
            // Never log private filenames, paths or provider identifiers.
            if (ownsProject && !complete && destination != null && project != null
                    && !destination.delete(project.file)) {
                error = R.string.cl_love_import_cleanup_failed;
            }
            return new Result(null, null, error);
        } finally {
            if (staged != null) staged.delete();
        }
    }
}
