package org.love2d.android;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Acesso direto a um projeto escolhido pelo SAF.
 *
 * Os File retornados por esta classe sao apenas identificadores estaveis para a
 * interface do CodeLove. Eles NAO representam uma copia do projeto no disco.
 * O conteudo real permanece no DocumentsProvider e e acessado pelo ContentResolver.
 */
final class DirectSafStorage implements CodeEditor.Storage {
    static final class Entry {
        final File file;
        final Uri uri;
        final String documentId;
        final boolean directory;
        final String name;
        final String mimeType;

        Entry(File file, Uri uri, String documentId, boolean directory,
              String name, String mimeType) {
            this.file = file;
            this.uri = uri;
            this.documentId = documentId;
            this.directory = directory;
            this.name = name;
            this.mimeType = mimeType;
        }
    }

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ENTRIES = 10000;

    private final Context context;
    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String rootDocumentId;
    private final String displayName;
    private final File syntheticRoot;
    private final Map<String, Uri> uriByPath = new HashMap<String, Uri>();
    private final Map<String, String> idByPath = new HashMap<String, String>();
    private final Map<String, Boolean> dirByPath = new HashMap<String, Boolean>();

    DirectSafStorage(Context context, Uri treeUri, String rootDocumentId, String displayName) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.treeUri = treeUri;
        this.rootDocumentId = rootDocumentId;
        this.displayName = displayName == null || displayName.trim().isEmpty()
            ? "Projeto" : displayName.trim();

        String key = sha1(treeUri.toString() + "#" + rootDocumentId);
        String safeName = this.displayName.replaceAll("[\\/:*?\"<>|]", "_");
        if (safeName.isEmpty()) safeName = "Projeto";
        this.syntheticRoot = new File(new File(this.context.getCacheDir(),
            ".codelove-direct"), key + File.separator + safeName);

        Uri rootUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        remember(syntheticRoot, rootUri, rootDocumentId, true);
    }

    File getRoot() {
        return syntheticRoot;
    }

    String getDisplayName() {
        return displayName;
    }

    boolean contains(File file) {
        if (file == null) return false;
        String root = syntheticRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.equals(root) || path.startsWith(root + File.separator);
    }

    boolean isKnown(File file) {
        return file != null && uriByPath.containsKey(file.getAbsolutePath());
    }

    boolean isDirectory(File file) {
        Boolean value = file == null ? null : dirByPath.get(file.getAbsolutePath());
        return value != null && value;
    }

    boolean isFile(File file) {
        Boolean value = file == null ? null : dirByPath.get(file.getAbsolutePath());
        return value != null && !value;
    }

    String readText(File file, long maxBytes) throws IOException {
        Uri uri = requireUri(file);
        Cursor cursor = resolver.query(uri,
            new String[]{DocumentsContract.Document.COLUMN_SIZE}, null, null, null);
        try {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    long size = cursor.getLong(sizeColumn);
                    if (size > maxBytes) throw new IOException("Arquivo grande demais.");
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }

        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IOException("Nao foi possivel abrir o arquivo.");
        try {
            byte[] data = readAll(in, maxBytes);
            for (byte b : data) {
                if (b == 0) return null;
            }
            return new String(data, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    boolean writeText(File file, String text) {
        try {
            Uri uri = requireUri(file);
            OutputStream out = resolver.openOutputStream(uri, "wt");
            if (out == null) return false;
            try {
                out.write(text.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } finally {
                out.close();
            }
            return true;
        } catch (Exception e) {
            android.util.Log.w("CodeLove", "Falha ao salvar arquivo SAF: " + file, e);
            return false;
        }
    }

    List<Entry> list(File directory) throws IOException {
        Uri dirUri = requireUri(directory);
        String dirId = requireDocumentId(directory);
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId);
        Cursor cursor = resolver.query(childrenUri,
            new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            }, null, null, null);
        if (cursor == null) throw new IOException("O Android nao conseguiu listar essa pasta.");

        ArrayList<Entry> result = new ArrayList<Entry>();
        try {
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                throw new IOException("O provedor nao informou os dados necessarios.");
            }

            while (cursor.moveToNext()) {
                String id = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                if (id == null || name == null || name.isEmpty()) continue;

                boolean dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                File child = new File(directory, name);
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id);
                remember(child, childUri, id, dir);
                result.add(new Entry(child, childUri, id, dir, name, mime));
            }
        } finally {
            cursor.close();
        }

        Collections.sort(result, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) return a.directory ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return result;
    }

    Entry createFile(File parent, String name) throws IOException {
        validateName(name);
        Uri parentUri = requireUri(parent);
        Uri created = DocumentsContract.createDocument(resolver, parentUri,
            mimeFor(name), name);
        if (created == null) throw new IOException("O provedor recusou a criacao do arquivo.");
        String id = DocumentsContract.getDocumentId(created);
        File file = new File(parent, name);
        remember(file, created, id, false);
        return new Entry(file, created, id, false, name, mimeFor(name));
    }

    Entry createDirectory(File parent, String name) throws IOException {
        validateName(name);
        Uri parentUri = requireUri(parent);
        Uri created = DocumentsContract.createDocument(resolver, parentUri,
            DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IOException("O provedor recusou a criacao da pasta.");
        String id = DocumentsContract.getDocumentId(created);
        File file = new File(parent, name);
        remember(file, created, id, true);
        return new Entry(file, created, id, true, name,
            DocumentsContract.Document.MIME_TYPE_DIR);
    }

    boolean delete(File file) {
        try {
            Uri uri = requireUri(file);
            boolean ok = DocumentsContract.deleteDocument(resolver, uri);
            if (ok) {
                forgetRecursive(file);
            }
            return ok;
        } catch (Exception e) {
            android.util.Log.w("CodeLove", "Falha ao apagar documento SAF: " + file, e);
            return false;
        }
    }

    File rename(File target, String newName) throws IOException {
        validateName(newName);
        Uri uri = requireUri(target);
        Uri renamed = DocumentsContract.renameDocument(resolver, uri, newName);
        if (renamed == null) throw new IOException("O provedor recusou a renomeacao.");
        File parent = target.getParentFile();
        File dest = new File(parent, newName);

        String oldPath = target.getAbsolutePath();
        String newPath = dest.getAbsolutePath();
        Boolean targetWasDirectory = dirByPath.get(oldPath);
        ArrayList<String> paths = new ArrayList<String>(uriByPath.keySet());
        for (String path : paths) {
            if (path.equals(oldPath)) {
                uriByPath.remove(path);
                idByPath.remove(path);
                dirByPath.remove(path);
            } else if (path.startsWith(oldPath + File.separator)) {
                // IDs/URIs de filhos podem mudar quando o provedor renomeia uma pasta.
                // Eles serao reconstruidos por list() quando a arvore abrir novamente.
                uriByPath.remove(path);
                idByPath.remove(path);
                dirByPath.remove(path);
            }
        }

        // O documento pode receber uma URI equivalente diferente apos rename.
        uriByPath.put(newPath, renamed);
        idByPath.put(newPath, DocumentsContract.getDocumentId(renamed));
        dirByPath.put(newPath, targetWasDirectory != null && targetWasDirectory);
        return dest;
    }

    /**
     * Cria uma copia temporaria SOMENTE para o runtime do LOVE.
     * A copia nao e usada pelo editor nem e fonte de dados do projeto.
     */
    File createRuntimeSnapshot(File destinationRoot) throws IOException {
        if (destinationRoot.exists()) deleteLocal(destinationRoot);
        if (!destinationRoot.mkdirs()) {
            throw new IOException("Nao consegui preparar a pasta temporaria de execucao.");
        }
        try {
            int[] count = new int[]{0};
            copyTree(syntheticRoot, destinationRoot, 0, count);
            if (!new File(destinationRoot, "main.lua").isFile()) {
                throw new IOException("O projeto nao contem main.lua na raiz.");
            }
            return destinationRoot;
        } catch (IOException e) {
            deleteLocal(destinationRoot);
            throw e;
        }
    }

    private void copyTree(File source, File destination, int depth, int[] count) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("Projeto profundo demais.");
        List<Entry> children = list(source);
        for (Entry entry : children) {
            if (++count[0] > MAX_ENTRIES) throw new IOException("Projeto grande demais.");
            File out = new File(destination, entry.name);
            if (entry.directory) {
                if (!out.mkdirs()) throw new IOException("Nao consegui criar " + out);
                copyTree(entry.file, out, depth + 1, count);
            } else {
                InputStream in = resolver.openInputStream(entry.uri);
                if (in == null) throw new IOException("Nao consegui ler " + entry.name);
                OutputStream outStream = new FileOutputStream(out);
                try {
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (n > 0) outStream.write(buffer, 0, n);
                    }
                    outStream.flush();
                } finally {
                    try { in.close(); } finally { outStream.close(); }
                }
            }
        }
    }

    void deleteLocalSnapshot(File directory) {
        deleteLocal(directory);
    }

    private Uri requireUri(File file) throws IOException {
        if (file == null) throw new IOException("Documento invalido.");
        Uri uri = uriByPath.get(file.getAbsolutePath());
        if (uri == null) throw new IOException("Documento SAF nao esta mais disponivel.");
        return uri;
    }

    private String requireDocumentId(File file) throws IOException {
        String id = idByPath.get(file.getAbsolutePath());
        if (id == null) throw new IOException("Documento SAF nao esta mais disponivel.");
        return id;
    }

    private void remember(File file, Uri uri, String id, boolean directory) {
        uriByPath.put(file.getAbsolutePath(), uri);
        idByPath.put(file.getAbsolutePath(), id);
        dirByPath.put(file.getAbsolutePath(), directory);
    }

    private void forgetRecursive(File file) {
        String path = file.getAbsolutePath();
        ArrayList<String> paths = new ArrayList<String>(uriByPath.keySet());
        for (String item : paths) {
            if (item.equals(path) || item.startsWith(path + File.separator)) {
                uriByPath.remove(item);
                idByPath.remove(item);
                dirByPath.remove(item);
            }
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || name.trim().isEmpty()
            || name.equals(".") || name.equals("..")
            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("Nome invalido.");
        }
    }

    private static String mimeFor(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".lua")) return "text/plain";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".txt")) return "text/plain";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream in, long maxBytes) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (n == 0) continue;
            total += n;
            if (total > maxBytes) throw new IOException("Arquivo grande demais.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void deleteLocal(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteLocal(child);
        file.delete();
    }

    private static String sha1(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
