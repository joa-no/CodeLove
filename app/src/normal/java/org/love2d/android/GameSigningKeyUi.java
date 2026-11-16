package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
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
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (20 * density + 0.5f);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Workspace.COLOR_PANEL);
        background.setCornerRadius(18 * density);
        panel.setBackground(background);

        TextView heading = new TextView(activity);
        heading.setText("Chaves de assinatura");
        heading.setTextSize(20);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(Workspace.COLOR_TEXT);
        panel.addView(heading);

        TextView explanation = new TextView(activity);
        explanation.setText("A chave é associada ao identificador " + applicationId
            + ". Guarde o backup .p12 e sua senha: sem eles não será possível"
            + " atualizar um jogo já publicado após perder os dados do CodeLove.");
        explanation.setTextSize(13);
        explanation.setTextColor(Workspace.COLOR_MUTED);
        explanation.setPadding(0, (int)(12 * density), 0, (int)(14 * density));
        panel.addView(explanation);

        final AlertDialog dialog = new AlertDialog.Builder(activity).create();
        String[] labels = {"Criar chave", "Salvar backup .p12", "Restaurar backup .p12"};
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            TextView option = new TextView(activity);
            option.setText(labels[i]);
            option.setTextSize(16);
            option.setTextColor(Workspace.COLOR_TEXT);
            option.setGravity(android.view.Gravity.CENTER_VERTICAL);
            option.setPadding((int)(14 * density), 0, (int)(14 * density), 0);
            GradientDrawable optionBackground = new GradientDrawable();
            optionBackground.setColor(Workspace.COLOR_PANEL);
            optionBackground.setStroke(Math.max(1, (int)density), Workspace.COLOR_MUTED);
            optionBackground.setCornerRadius(12 * density);
            option.setBackground(optionBackground);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(52 * density));
            params.bottomMargin = (int)(9 * density);
            panel.addView(option, params);
            option.setOnClickListener(v -> {
                dialog.dismiss();
                requestPassword(activity, projectId, applicationId, action);
            });
        }

        TextView cancel = new TextView(activity);
        cancel.setText("Cancelar");
        cancel.setTextSize(15);
        cancel.setTextColor(0xFFA78BFA);
        cancel.setGravity(android.view.Gravity.CENTER);
        cancel.setPadding(0, (int)(9 * density), 0, 0);
        panel.addView(cancel);
        cancel.setOnClickListener(v -> dialog.dismiss());

        dialog.setView(panel);
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
                dialog.getWindow().setLayout(
                    activity.getResources().getDisplayMetrics().widthPixels
                        - (int)(36 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
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
