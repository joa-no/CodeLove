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
            ? context.getString(R.string.cl_fallback_project) : fallback;
    }

    static ProjectSelection inspectProject(Context context, Uri treeUri) {
        if (treeUri == null || !"content".equals(treeUri.getScheme())) {
            return ProjectSelection.invalid(context.getString(R.string.cl_saf_invalid));
        }

        try {
            // A pasta escolhida pelo usuario e sempre a raiz do workspace.
            // Nunca substituimos silenciosamente essa escolha por uma subpasta
            // que contenha main.lua: isso esconderia niveis da arvore SAF.
            String rootId = DocumentsContract.getTreeDocumentId(treeUri);
            if (rootId == null || rootId.isEmpty()) {
                return ProjectSelection.invalid(context.getString(R.string.cl_saf_identify));
            }

            String name = queryDisplayName(context, treeUri, rootId);
            return ProjectSelection.valid(rootId,
                name == null ? context.getString(R.string.cl_fallback_project) : name);
        } catch (SecurityException e) {
            return ProjectSelection.invalid(
                context.getString(R.string.cl_saf_access));
        } catch (Exception e) {
            return ProjectSelection.invalid(
                context.getString(R.string.cl_saf_identify_retry));
        }
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
            return activity.getString(R.string.cl_saf_invalid_project);
        }

        int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        boolean hadPermission = hasPersistedReadWritePermission(activity, treeUri);
        int persistableFlags = hadPermission
            ? requiredFlags : (grantedFlags & requiredFlags);

        if (!hadPermission) {
            if (persistableFlags != requiredFlags) {
                return activity.getString(R.string.cl_saf_permission);
            }
            try {
                activity.getContentResolver().takePersistableUriPermission(
                    treeUri, persistableFlags);
            } catch (SecurityException e) {
                return activity.getString(R.string.cl_saf_persist);
            }
            if (!hasPersistedReadWritePermission(activity, treeUri)) {
                return activity.getString(R.string.cl_saf_persist_verify);
            }
        }

        SharedPreferencesHelper helper =
            new SharedPreferencesHelper(activity.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE));

        try {
            JSONArray stored = new JSONArray(helper.getProjects());
            JSONArray projects = new JSONArray();

            JSONObject entry = new JSONObject();
            entry.put("uri", treeUri.toString());
            entry.put("projectDocumentId", projectDocumentId);
            entry.put("name", displayName == null ? activity.getString(R.string.cl_fallback_project) : displayName);
            entry.put("flags", persistableFlags);
            projects.put(entry);

            if (!helper.saveProjects(projects.toString())) {
                if (!hadPermission) releasePermission(activity, treeUri, persistableFlags);
                return activity.getString(R.string.cl_saf_register_save);
            }

            // O CodeLove trabalha com um projeto externo ativo por vez.
            // Depois que a nova selecao foi persistida, descarta autorizacoes
            // antigas para evitar restaurar uma URI obsoleta na proxima abertura.
            for (int i = 0; i < stored.length(); i++) {
                JSONObject previous = stored.optJSONObject(i);
                if (previous == null) continue;
                String previousUri = previous.optString("uri", "");
                if (previousUri.isEmpty() || treeUri.toString().equals(previousUri)) continue;
                try {
                    Uri oldTreeUri = Uri.parse(previousUri);
                    int oldFlags = previous.optInt("flags", requiredFlags) & requiredFlags;
                    if (oldFlags == 0) oldFlags = requiredFlags;
                    if (hasPersistedReadWritePermission(activity, oldTreeUri)) {
                        releasePermission(activity, oldTreeUri, oldFlags);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            if (!hadPermission) releasePermission(activity, treeUri, persistableFlags);
            return activity.getString(R.string.cl_saf_register_failure);
        }

        return null;
    }

    static List<RegisteredProject> getRegisteredProjects(Context context) {
        ArrayList<RegisteredProject> result = new ArrayList<RegisteredProject>();
        try {
            JSONArray projects = new JSONArray(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_PROJECTS, "[]"));

            // Existe somente um projeto externo ativo: a selecao mais recente.
            JSONObject item = projects.optJSONObject(projects.length() - 1);
            if (item != null) {
                String uri = item.optString("uri", "");
                String documentId = item.optString("projectDocumentId", "");
                String name = item.optString("name", context.getString(R.string.cl_fallback_project));
                if (!uri.isEmpty() && !documentId.isEmpty()) {
                    Uri treeUri = Uri.parse(uri);
                    if (hasPersistedReadWritePermission(context, treeUri)) {
                        result.add(new RegisteredProject(treeUri, documentId, name));
                    }
                }
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
