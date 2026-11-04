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

import java.util.List;

/**
 * Registro das pastas de projeto escolhidas pelo seletor SAF do Android.
 *
 * Nesta primeira etapa, guarda a autorizacao persistente e os metadados.
 * A copia de trabalho e a sincronizacao com o Workspace serao integradas
 * nas etapas seguintes.
 */
final class SafProjectManager {
    private static final String PREFS_NAME = "codelove_saf";
    private static final String KEY_PROJECTS = "projects";

    private SafProjectManager() {
    }

    static String getDisplayName(Context context, Uri treeUri) {
        Cursor cursor = null;
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
            cursor = context.getContentResolver().query(documentUri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String name = cursor.getString(column);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        String fallback = treeUri.getLastPathSegment();
        return fallback == null || fallback.trim().isEmpty()
            ? "Projeto externo" : fallback;
    }

    /**
     * Registra uma pasta apenas quando o provedor concedeu leitura e escrita.
     * Retorna null em caso de sucesso ou uma mensagem adequada para o usuario.
     */
    static String register(Activity activity, Uri treeUri, int grantedFlags, String displayName) {
        if (treeUri == null || !"content".equals(treeUri.getScheme())) {
            return "O Android retornou uma pasta invalida.";
        }

        int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        int persistableFlags = grantedFlags & requiredFlags;
        if (persistableFlags != requiredFlags) {
            return "Essa pasta nao concedeu permissao de leitura e escrita.";
        }

        try {
            activity.getContentResolver().takePersistableUriPermission(treeUri, persistableFlags);
        } catch (SecurityException e) {
            return "O Android nao permitiu guardar o acesso permanente a essa pasta.";
        }

        android.content.SharedPreferences prefs =
            activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONArray projects = new JSONArray();
        try {
            JSONArray stored = new JSONArray(prefs.getString(KEY_PROJECTS, "[]"));
            for (int i = 0; i < stored.length(); i++) {
                JSONObject item = stored.optJSONObject(i);
                if (item != null && !treeUri.toString().equals(item.optString("uri"))) {
                    projects.put(item);
                }
            }

            JSONObject entry = new JSONObject();
            entry.put("uri", treeUri.toString());
            entry.put("name", displayName == null ? "Projeto externo" : displayName);
            entry.put("flags", persistableFlags);
            projects.put(entry);

            if (!prefs.edit().putString(KEY_PROJECTS, projects.toString()).commit()) {
                return "O acesso foi concedido, mas nao consegui salvar o registro do projeto.";
            }
        } catch (Exception e) {
            return "Nao consegui registrar essa pasta no CodeLove.";
        }

        return null;
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
