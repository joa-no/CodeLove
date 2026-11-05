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
 * Registra as pastas escolhidas pelo usuario e valida a raiz do projeto.
 *
 * Este componente nao cria copia de trabalho. A URI SAF persistida e a fonte
 * de verdade do projeto externo; o acesso aos documentos e feito por
 * DirectSafStorage.
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

    static final class RegisteredProject {
        final Uri treeUri;
        final String documentId;
        final String displayName;

        RegisteredProject(Uri treeUri, String documentId, String displayName) {
            this.treeUri = treeUri;
            this.documentId = documentId;
            this.displayName = displayName;
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
                return ProjectSelection.valid(rootId,
                    name == null ? "Projeto externo" : name);
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
                if ("main.lua".equals(cursor.getString(nameColumn))) {
                    if (mimeColumn < 0) return true;
                    return !DocumentsContract.Document.MIME_TYPE_DIR.equals(
                        cursor.getString(mimeColumn));
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

        if (!hadPermission) {
            if (persistableFlags != requiredFlags) {
                return "Essa pasta nao concedeu permissao de leitura e escrita.";
            }
            try {
                activity.getContentResolver().takePersistableUriPermission(
                    treeUri, persistableFlags);
            } catch (SecurityException e) {
                return "O Android nao permitiu guardar o acesso permanente a essa pasta.";
            }
            if (!hasPersistedReadWritePermission(activity, treeUri)) {
                return "O Android nao confirmou a autorizacao persistente dessa pasta.";
            }
        }

        SharedPreferencesHelper helper =
            new SharedPreferencesHelper(activity.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE));

        try {
            JSONArray stored = new JSONArray(helper.getProjects());
            JSONArray projects = new JSONArray();
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null) continue;
                if (!treeUri.toString().equals(item.optString("uri"))) {
                    projects.put(item);
                }
            }

            JSONObject entry = new JSONObject();
            entry.put("uri", treeUri.toString());
            entry.put("projectDocumentId", projectDocumentId);
            entry.put("name", displayName == null ? "Projeto externo" : displayName);
            entry.put("flags", persistableFlags);
            projects.put(entry);

            if (!helper.saveProjects(projects.toString())) {
                if (!hadPermission) releasePermission(activity, treeUri, persistableFlags);
                return "O acesso foi concedido, mas nao consegui salvar o registro do projeto.";
            }
        } catch (Exception e) {
            if (!hadPermission) releasePermission(activity, treeUri, persistableFlags);
            return "Nao consegui registrar essa pasta no CodeLove.";
        }

        return null;
    }

    static List<RegisteredProject> getRegisteredProjects(Context context) {
        ArrayList<RegisteredProject> result = new ArrayList<RegisteredProject>();
        try {
            JSONArray projects = new JSONArray(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_PROJECTS, "[]"));

            for (int i = 0; i < projects.length(); i++) {
                JSONObject item = projects.optJSONObject(i);
                if (item == null) continue;

                String uri = item.optString("uri", "");
                String documentId = item.optString("projectDocumentId", "");
                String name = item.optString("name", "Projeto externo");
                if (uri.isEmpty() || documentId.isEmpty()) continue;

                Uri treeUri = Uri.parse(uri);
                if (!hasPersistedReadWritePermission(context, treeUri)) continue;
                result.add(new RegisteredProject(treeUri, documentId, name));
            }
        } catch (Exception e) {
            android.util.Log.e("CodeLove",
                "Nao foi possivel ler os projetos SAF registrados", e);
        }
        return result;
    }

    private static void releasePermission(Context context, Uri treeUri, int flags) {
        try {
            context.getContentResolver().releasePersistableUriPermission(treeUri, flags);
        } catch (SecurityException ignored) {
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

    private static final class SharedPreferencesHelper {
        private final android.content.SharedPreferences prefs;

        SharedPreferencesHelper(android.content.SharedPreferences prefs) {
            this.prefs = prefs;
        }

        String getProjects() {
            return prefs.getString(KEY_PROJECTS, "[]");
        }

        boolean saveProjects(String value) {
            return prefs.edit().putString(KEY_PROJECTS, value).commit();
        }
    }
}
