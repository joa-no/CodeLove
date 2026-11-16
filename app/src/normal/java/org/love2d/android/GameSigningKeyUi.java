package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.util.Arrays;

/** Editor-only signing key actions. No passwords are persisted. */
final class GameSigningKeyUi {
    private static final int REQUEST_BACKUP = 4105;
    private static final int REQUEST_RESTORE = 4106;
    private static String pendingProject;
    private static char[] pendingPassword;
    private static boolean busy;

    private GameSigningKeyUi() {}

    static void show(Activity activity, String applicationId) {
        final String projectId;
        try {
            projectId = GameSigningKeyManager.projectIdForPackage(applicationId);
        } catch (Exception e) {
            toast(activity, "Identificador do aplicativo inválido");
            return;
        }
        new AlertDialog.Builder(activity)
            .setTitle("Chaves de assinatura")
            .setMessage("A chave é associada ao identificador " + applicationId
                + ". Guarde o backup .p12 e sua senha: sem eles não será possível"
                + " atualizar um jogo já publicado após perder os dados do CodeLove.")
            .setItems(new String[]{"Criar chave", "Salvar backup .p12",
                "Restaurar backup .p12"}, (dialog, which) ->
                requestPassword(activity, projectId, applicationId, which))
            .setNegativeButton("Cancelar", null).show();
    }

    private static void requestPassword(Activity activity, String projectId,
                                        String applicationId, int action) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint("Senha da chave (mínimo 12 caracteres para criar)");
        new AlertDialog.Builder(activity)
            .setTitle(action == 0 ? "Criar chave" :
                action == 1 ? "Salvar backup" : "Restaurar backup")
            .setView(field)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar", (dialog, which) -> {
                char[] password = field.getText().toString().toCharArray();
                field.setText("");
                if (password.length == 0 || (action == 0 && password.length < 12)) {
                    Arrays.fill(password, '\0');
                    toast(activity, "Senha inválida");
                    return;
                }
                if (action == 0) {
                    new Thread(() -> {
                        try {
                            if (GameSigningKeyManager.hasKey(activity, projectId)) {
                                throw new java.io.IOException("Já existe uma chave para este jogo");
                            }
                            GameSigningKeyManager.generate(activity, projectId, password);
                            activity.runOnUiThread(() -> toast(activity, "Chave criada"));
                        } catch (Exception e) {
                            activity.runOnUiThread(() -> toast(activity,
                                "Não foi possível criar a chave: " + e.getMessage()));
                        } finally {
                            Arrays.fill(password, '\0');
                        }
                    }, "CodeLove-KeyCreate").start();
                    return;
                }
                synchronized (GameSigningKeyUi.class) {
                    if (busy) {
                        Arrays.fill(password, '\0');
                        toast(activity, "Já existe uma operação em andamento");
                        return;
                    }
                    busy = true;
                    pendingProject = projectId;
                    pendingPassword = password;
                }
                try {
                    Intent intent = action == 1
                        ? GameSigningKeySaf.createBackupDocumentIntent(applicationId)
                        : GameSigningKeySaf.openBackupDocumentIntent();
                    activity.startActivityForResult(intent,
                        action == 1 ? REQUEST_BACKUP : REQUEST_RESTORE);
                } catch (RuntimeException e) {
                    clearPending();
                    toast(activity, "Não foi possível abrir o seletor de arquivos");
                }
            }).show();
    }

    static boolean handleResult(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST_BACKUP && request != REQUEST_RESTORE) return false;
        final String project;
        final char[] password;
        synchronized (GameSigningKeyUi.class) {
            project = pendingProject;
            password = pendingPassword;
            pendingProject = null;
            pendingPassword = null;
        }
        if (project == null || password == null) {
            clearPending();
            return true;
        }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            Arrays.fill(password, '\0');
            clearPending();
            return true;
        }
        final Uri uri = data.getData();
        new Thread(() -> {
            try {
                if (request == REQUEST_BACKUP) {
                    GameSigningKeySaf.backupToDocument(activity, project, password, uri);
                } else {
                    GameSigningKeySaf.restoreFromDocument(activity, project, password, uri);
                }
                activity.runOnUiThread(() -> toast(activity,
                    request == REQUEST_BACKUP ? "Backup salvo" : "Chave restaurada"));
            } catch (Exception e) {
                activity.runOnUiThread(() -> toast(activity,
                    "Operação falhou: " + e.getMessage()));
            } finally {
                Arrays.fill(password, '\0');
                clearPending();
            }
        }, "CodeLove-KeyDocument").start();
        return true;
    }

    private static synchronized void clearPending() {
        if (pendingPassword != null) Arrays.fill(pendingPassword, '\0');
        pendingPassword = null;
        pendingProject = null;
        busy = false;
    }

    private static void toast(Activity activity, String message) {
        if (!activity.isFinishing() && !activity.isDestroyed()) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }
}
