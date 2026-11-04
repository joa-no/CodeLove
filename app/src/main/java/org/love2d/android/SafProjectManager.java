package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gerencia a autorizacao SAF e identifica a raiz real de um projeto LÖVE.
 *
 * O usuario pode selecionar:
 * - a propria pasta do jogo, com main.lua na raiz; ou
 * - uma pasta-container com exatamente uma pasta de jogo diretamente dentro.
 *
 * A URI persistida continua sendo a tree URI autorizada pelo Android.
 * projectDocumentId identifica a raiz do jogo dentro dessa tree.
 */
final class SafProjectManager {
    private static final String PREFS_NAME = "codelove_saf";
    private static final String KEY_PROJECTS = "projects";

    static final class ProjectSelection {
        final String documentId;
        final String displayName;
        final String error;

        private ProjectSelection(String documentId, String displayName, String error) {
            this.documentId = documentId;
            this.displayName = displayName;
            this.error = error;
        }

        static ProjectSelection valid(String id, String name) {
            return new ProjectSelection(id, name, null);
        }

        static ProjectSelection invalid(String message) {
            return new ProjectSelection(null, null, message);
        }

        boolean isValid() {
            return error == null && documentId != null;
        }
    }

    private SafProjectManager() {
    }

    static String getDisplayName(Context context, Uri treeUri) {
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            String name = queryDisplayName(context, treeUri, documentId);
            if (name != null) return name;
        } catch (Exception ignored) {
        }
        String fallback = treeUri == null ? null : treeUri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty()
            ? "Projeto externo" : fallback;
    }

    /**
     * Verifica a estrutura antes de guardar a autorizacao.
     * Aceita a raiz do jogo ou um container com uma unica pasta de jogo.
     */
    static ProjectSelection inspectProject(Context context, Uri treeUri) {
        if (treeUri == null || !"content".equals(treeUri.getScheme())) {
            return ProjectSelection.invalid("O Android retornou uma pasta invalida.");
        }

        try {
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            if (rootId == null || rootId.isEmpty()) {
                return ProjectSelection.invalid("Nao consegui identificar a pasta selecionada.");
            }

            if (hasMainLua(context, treeUri, rootId)) {
                String name = queryDisplayName(context, treeUri, rootId);
                return ProjectSelection.valid(rootId, name == null ? "Projeto externo" : name);
            }

            ArrayList<ProjectSelection> candidates = new ArrayList<ProjectSelection>();
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId);
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(childrenUri,
                    new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    }, null, null, null);

                if (cursor == null) {
                    return ProjectSelection.invalid(
                        "O Android nao conseguiu ler o conteudo da pasta selecionada.");
                }

                int idColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(
                    DocumentsContract.Document.COLUMN_MIME_TYPE);
                if (idColumn < 0 || mimeColumn < 0) {
                    return ProjectSelection.invalid(
                        "O provedor de arquivos nao informou os dados necessarios da pasta.");
                }

                while (cursor.moveToNext()) {
                    String mime = cursor.getString(mimeColumn);
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) continue;

                    String childId = cursor.getString(idColumn);
                    if (childId == null || childId.isEmpty()) continue;
                    if (hasMainLua(context, treeUri, childId)) {
                        String name = nameColumn >= 0 ? cursor.getString(nameColumn) : null;
                        if (name == null || name.trim().isEmpty()) {
                            name = queryDisplayName(context, treeUri, childId);
                        }
                        candidates.add(ProjectSelection.valid(childId,
                            name == null ? "Projeto externo" : name));
                    }
                }
            } finally {
                if (cursor != null) cursor.close();
            }

            if (candidates.size() == 1) return candidates.get(0);
            if (candidates.size() > 1) {
                return ProjectSelection.invalid(
                    "Essa pasta contem mais de um projeto LÖVE. Selecione diretamente a pasta do jogo que deseja adicionar.");
            }
            return ProjectSelection.invalid(
                "Nao encontrei main.lua. Selecione a pasta do jogo, com main.lua diretamente dentro dela, ou uma pasta que contenha apenas uma pasta de jogo.");
        } catch (SecurityException e) {
            return ProjectSelection.invalid(
                "O Android nao permitiu ler essa pasta. Verifique a autorizacao e tente novamente.");
        } catch (Exception e) {
            return ProjectSelection.invalid(
                "Nao consegui verificar a estrutura dessa pasta. Tente selecionar a pasta do jogo diretamente.");
        }
    }

    private static boolean hasMainLua(Context context, Uri treeUri, String documentId) {
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(childrenUri,
                new String[]{
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                }, null, null, null);
            if (cursor == null) return false;

            int nameColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(
                DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (nameColumn < 0) return false;

            while (cursor.moveToNext()) {
                String name = cursor.getString(nameColumn);
                if ("main.lua".equals(name)) {
                    if (mimeColumn < 0) return true;
                    String mime = cursor.getString(mimeColumn);
                    return !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return false;
    }

    private static String queryDisplayName(Context context, Uri treeUri, String documentId) {
        Cursor cursor = null;
        try {
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            cursor = context.getContentResolver().query(documentUri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * Registra a tree URI e a raiz validada do projeto.
     * Retorna null em caso de sucesso ou uma mensagem para o usuario.
     */
    static String register(Activity activity, Uri treeUri, int grantedFlags,
                           String projectDocumentId, String displayName) {
        if (treeUri == null || !"content".equals(treeUri.getScheme())
            || projectDocumentId == null || projectDocumentId.isEmpty()) {
            return "A pasta do projeto e invalida.";
        }

        int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        boolean hadPermission = hasPersistedReadWritePermission(activity, treeUri);
        int persistableFlags = hadPermission
            ? requiredFlags : (grantedFlags & requiredFlags);

        // Uma URI ja autorizada nao precisa ser persistida novamente. Alem de
        // evitar uma operacao redundante no DocumentsProvider, isso torna a
        // selecao repetida da mesma pasta idempotente.
        if (!hadPermission) {
            if (persistableFlags != requiredFlags) {
                return "Essa pasta nao concedeu permissao de leitura e escrita.";
            }
            try {
                activity.getContentResolver().takePersistableUriPermission(treeUri, persistableFlags);
            } catch (SecurityException e) {
                return "O Android nao permitiu guardar o acesso permanente a essa pasta.";
            }
            if (!hasPersistedReadWritePermission(activity, treeUri)) {
                return "O Android nao confirmou a autorizacao persistente dessa pasta.";
            }
        }

        android.content.SharedPreferences prefs =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray projects = new JSONArray();
        String existingWorkingPath = null;
        try {
            JSONArray stored = new JSONArray(prefs.getString(KEY_PROJECTS, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null) continue;
                if (treeUri.toString().equals(item.optString("uri"))) {
                    existingWorkingPath = item.optString("workingPath", null);
                } else {
                    projects.put(item);
                }
            }

            JSONObject entry = new JSONObject();
            entry.put("uri", treeUri.toString());
            entry.put("projectDocumentId", projectDocumentId);
            entry.put("name", displayName == null ? "Projeto externo" : displayName);
            entry.put("flags", persistableFlags);
            if (existingWorkingPath != null && !existingWorkingPath.isEmpty()) {
                entry.put("workingPath", existingWorkingPath);
            }
            projects.put(entry);

            if (!prefs.edit().putString(KEY_PROJECTS, projects.toString()).commit()) {
                releaseNewPermission(activity, treeUri, persistableFlags, hadPermission);
                return "O acesso foi concedido, mas nao consegui salvar o registro do projeto.";
            }
        } catch (Exception e) {
            releaseNewPermission(activity, treeUri, persistableFlags, hadPermission);
            return "Nao consegui registrar essa pasta no CodeLove.";
        }

        return null;
    }

    private static void releaseNewPermission(Context context, Uri treeUri,
                                              int flags, boolean hadPermission) {
        if (hadPermission) return;
        try {
            context.getContentResolver().releasePersistableUriPermission(treeUri, flags);
        } catch (SecurityException ignored) {
        }
    }


    /** Cria uma copia de trabalho local sem substituir uma copia ja existente. */
    static ProjectCopy ensureWorkingCopy(Activity activity, Uri treeUri,
                                         String projectDocumentId, String displayName,
                                         File gamesDir) {
        if (gamesDir == null || (!gamesDir.isDirectory() && !gamesDir.mkdirs())) {
            return ProjectCopy.error("A pasta local de projetos do CodeLove esta indisponivel.");
        }
        if (!hasPersistedReadWritePermission(activity, treeUri)) {
            return ProjectCopy.error("A autorizacao para ler a pasta externa nao esta mais disponivel.");
        }
        String existingPath = findWorkingPath(activity, treeUri);
        if (existingPath != null) {
            File existing = new File(existingPath);
            if (existing.isDirectory() && new File(existing, "main.lua").isFile()) {
                return ProjectCopy.success(existing);
            }
            if (existing.exists()) {
                return ProjectCopy.error("A copia local registrada esta incompleta. Ela foi preservada para evitar perda de dados.");
            }
        }

        File staging = null;
        boolean published = false;
        try {
            File gamesRoot = gamesDir.getCanonicalFile();
            File stagingRoot = gamesRoot.getParentFile();
            if (stagingRoot == null || !stagingRoot.isDirectory()) {
                return ProjectCopy.error("Nao consegui preparar o armazenamento local do projeto.");
            }
            for (int attempt = 0; attempt < 10; attempt++) {
                staging = new File(stagingRoot, ".codelove-saf-" + System.nanoTime() + "-" + attempt + ".tmp");
                if (staging.mkdir()) break;
                staging = null;
            }
            if (staging == null) {
                return ProjectCopy.error("Nao consegui criar a area temporaria para importar o projeto.");
            }
            int[] copiedEntries = new int[]{0};
            copyDirectoryContents(activity, treeUri, projectDocumentId, staging, 0, copiedEntries);
            if (!new File(staging, "main.lua").isFile()) {
                throw new IOException("A copia importada nao contem main.lua na raiz.");
            }

            String baseName = safeProjectName(displayName) + " (externo)";
            File destination = null;
            for (int suffix = 1; suffix <= 100; suffix++) {
                String candidate = suffix == 1 ? baseName : baseName + " " + suffix;
                File possible = new File(gamesRoot, candidate);
                if (!possible.exists()) {
                    destination = possible;
                    break;
                }
            }
            if (destination == null) throw new IOException("Nao encontrei um nome livre para a copia local.");
            if (!gamesRoot.equals(destination.getCanonicalFile().getParentFile()) || !staging.renameTo(destination)) {
                throw new IOException("Nao consegui publicar a copia local do projeto.");
            }
            published = true;
            if (!saveWorkingPath(activity, treeUri, destination.getAbsolutePath())) {
                FileOps.deleteRecursive(destination);
                return ProjectCopy.error("A copia foi criada, mas nao consegui registrar sua localizacao. Tente adicionar o projeto novamente.");
            }
            return ProjectCopy.success(destination);
        } catch (Exception e) {
            android.util.Log.e("CodeLove", "Falha ao importar projeto SAF", e);
            return ProjectCopy.error(e.getMessage() == null
                ? "Nao consegui copiar o projeto para o armazenamento local." : e.getMessage());
        } finally {
            if (!published && staging != null && staging.exists()) FileOps.deleteRecursive(staging);
        }
    }

    static final class ProjectCopy {
        final File directory;
        final String error;
        private ProjectCopy(File directory, String error) { this.directory = directory; this.error = error; }
        static ProjectCopy success(File directory) { return new ProjectCopy(directory, null); }
        static ProjectCopy error(String message) { return new ProjectCopy(null, message); }
        boolean isSuccess() { return directory != null && error == null; }
    }

    private static String findWorkingPath(Context context, Uri treeUri) {
        try {
            JSONArray projects = new JSONArray(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PROJECTS, "[]"));
            for (int i = 0; i < projects.length(); i++) {
                JSONObject item = projects.optJSONObject(i);
                if (item != null && treeUri.toString().equals(item.optString("uri"))) {
                    String path = item.optString("workingPath", "");
                    return path.isEmpty() ? null : path;
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean saveWorkingPath(Context context, Uri treeUri, String path) {
        android.content.SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            JSONArray projects = new JSONArray(prefs.getString(KEY_PROJECTS, "[]"));
            for (int i = 0; i < projects.length(); i++) {
                JSONObject item = projects.optJSONObject(i);
                if (item != null && treeUri.toString().equals(item.optString("uri"))) {
                    item.put("workingPath", path);
                    return prefs.edit().putString(KEY_PROJECTS, projects.toString()).commit();
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String safeProjectName(String name) {
        String value = name == null ? "Projeto" : name.trim();
        if (value.isEmpty()) value = "Projeto";
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < value.length() && safe.length() < 48; i++) {
            char ch = value.charAt(i);
            safe.append(ch < 32 || "/\\\\:*?\\"<>|".indexOf(ch) >= 0 ? '_' : ch);
        }
        String result = safe.toString().trim();
        return result.isEmpty() || result.equals(".") || result.equals("..") ? "Projeto" : result;
    }

    private static void copyDirectoryContents(Context context, Uri treeUri, String documentId,
                                              File destination, int depth, int[] copiedEntries)
                                              throws IOException {
        if (depth > 32) throw new IOException("O projeto possui pastas profundas demais.");
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(childrenUri,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            if (cursor == null) throw new IOException("O Android nao conseguiu listar uma pasta do projeto.");
            int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            if (idColumn < 0 || nameColumn < 0 || mimeColumn < 0) {
                throw new IOException("O provedor de arquivos nao informou os dados de um item.");
            }
            while (cursor.moveToNext()) {
                if (++copiedEntries[0] > 10000) throw new IOException("O projeto possui itens demais para importar de uma vez.");
                String childId = cursor.getString(idColumn);
                String name = cursor.getString(nameColumn);
                String mime = cursor.getString(mimeColumn);
                if (childId == null || childId.isEmpty() || !isSafeDocumentName(name)) {
                    throw new IOException("O projeto contem um nome de arquivo ou pasta invalido.");
                }
                File target = new File(destination, name);
                if (!destination.getCanonicalFile().equals(target.getCanonicalFile().getParentFile())) {
                    throw new IOException("Um item do projeto tentou sair da pasta de destino.");
                }
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!target.mkdir()) throw new IOException("Nao consegui criar a pasta " + name + ".");
                    copyDirectoryContents(context, treeUri, childId, target, depth + 1, copiedEntries);
                } else {
                    copyDocumentFile(context, treeUri, childId, target);
                }
            }
        } catch (SecurityException e) {
            throw new IOException("O Android negou acesso a um item do projeto.", e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private static boolean isSafeDocumentName(String name) {
        return name != null && !name.trim().isEmpty() && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\\\') < 0;
    }

    private static void copyDocumentFile(Context context, Uri treeUri, String documentId, File target)
            throws IOException {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (java.io.InputStream input = context.getContentResolver().openInputStream(documentUri);
             java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
            if (input == null) throw new IOException("Nao consegui abrir um arquivo do projeto.");
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.flush();
            output.getFD().sync();
        } catch (SecurityException e) {
            throw new IOException("O Android negou acesso a um arquivo do projeto.", e);
        }
    }

    static boolean hasPersistedReadWritePermission(Context context, Uri treeUri) {
        List<android.content.UriPermission> permissions =
            context.getContentResolver().getPersistedUriPermissions();
        for (android.content.UriPermission permission : permissions) {
            if (treeUri.equals(permission.getUri())
                && permission.isReadPermission() && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
    }
}
