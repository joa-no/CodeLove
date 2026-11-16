package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.text.InputType;
import android.view.Gravity;
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

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static EditText passwordInput(Activity activity, String hint) {
        float density = activity.getResources().getDisplayMetrics().density;
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(Workspace.COLOR_TEXT);
        field.setHintTextColor(Workspace.COLOR_MUTED);
        field.setPadding((int)(14*density), 0, (int)(14*density), 0);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setHint(hint);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF20232D);
        background.setStroke(Math.max(1, (int)density), 0xFF666477);
        background.setCornerRadius(12*density);
        field.setBackground(background);
        return field;
    }

    private static void requestPassword(Activity activity, String projectId,
                                        String applicationId, int action) {
        final float density = activity.getResources().getDisplayMetrics().density;
        final int pad = (int) (20 * density + 0.5f);
        final boolean creating = action == 0;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Workspace.COLOR_PANEL);
        bg.setCornerRadius(18 * density);
        content.setBackground(bg);

        TextView title = text(activity, creating ? "Criar chave de assinatura" :
            action == 1 ? "Salvar backup .p12" : "Restaurar backup .p12",
            20, Workspace.COLOR_TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView info = text(activity, creating
            ? "Esta chave será a identidade digital de " + applicationId
                + " e será necessária para publicar atualizações do jogo."
            : action == 1
                ? "Salve uma cópia da chave fora do CodeLove. Você precisará deste arquivo e da senha para recuperar a identidade do jogo."
                : "A restauração só é permitida quando este jogo ainda não possui uma chave. O backup deve corresponder à identidade usada nas versões anteriores.",
            13, Workspace.COLOR_MUTED);
        info.setPadding(0, (int)(12 * density), 0, (int)(12 * density));
        content.addView(info);

        if (creating) {
            LinearLayout warningPanel = new LinearLayout(activity);
            warningPanel.setOrientation(LinearLayout.VERTICAL);
            warningPanel.setPadding((int)(14*density), (int)(14*density),
                (int)(14*density), (int)(14*density));
            GradientDrawable warningBg = new GradientDrawable();
            warningBg.setColor(0xFF25212C);
            warningBg.setCornerRadius(12 * density);
            warningPanel.setBackground(warningBg);
            TextView warningTitle = text(activity, "⚠  Antes de continuar", 15, Workspace.COLOR_TEXT);
            warningTitle.setTypeface(Typeface.DEFAULT_BOLD);
            warningPanel.addView(warningTitle);
            TextView warningBody = text(activity,
                "Depois que um jogo for distribuído, a chave utilizada para assiná-lo deverá ser preservada. "
                + "Perder essa chave pode impedir a atualização do mesmo aplicativo.",
                14, Workspace.COLOR_TEXT);
            warningBody.setPadding(0, (int)(10*density), 0, 0);
            warningPanel.addView(warningBody);
            LinearLayout.LayoutParams warningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            warningParams.bottomMargin = (int)(18*density);
            content.addView(warningPanel, warningParams);
        }

        TextView passwordLabel = text(activity, "Senha da chave", 15, Workspace.COLOR_TEXT);
        passwordLabel.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(passwordLabel);
        EditText field = passwordInput(activity, creating ? "Mínimo 12 caracteres" : "Digite a senha");
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(52*density));
        inputParams.topMargin = (int)(10*density);
        content.addView(field, inputParams);

        TextView guidance = text(activity, creating
            ? "Use uma senha forte e exclusiva, preferencialmente com 16 caracteres ou mais."
            : "A senha não é armazenada pelo CodeLove.",
            13, Workspace.COLOR_MUTED);
        guidance.setPadding(0, (int)(10*density), 0, (int)(18*density));
        content.addView(guidance);

        EditText confirmation = null;
        CheckBox acknowledged = null;
        if (creating) {
            TextView confirmationLabel = text(activity, "Confirmar senha", 15, Workspace.COLOR_TEXT);
            confirmationLabel.setTypeface(Typeface.DEFAULT_BOLD);
            content.addView(confirmationLabel);
            confirmation = passwordInput(activity, "Digite a senha novamente");
            LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(52*density));
            confirmParams.topMargin = (int)(10*density);
            content.addView(confirmation, confirmParams);

            TextView backupNotice = text(activity,
                "ⓘ  Após criar a chave, salve uma cópia de segurança .p12 fora do CodeLove "
                + "e guarde a senha separadamente. Desinstalar o aplicativo apaga a chave local.",
                13, Workspace.COLOR_MUTED);
            backupNotice.setPadding(0, (int)(18*density), 0, (int)(18*density));
            content.addView(backupNotice);

            View separator = new View(activity);
            separator.setBackgroundColor(0xFF363744);
            content.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int)density)));

            acknowledged = new CheckBox(activity);
            acknowledged.setText("Li e compreendi os riscos de perder minha chave.");
            acknowledged.setTextSize(14);
            acknowledged.setTextColor(Workspace.COLOR_TEXT);
            acknowledged.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFA78BFA));
            LinearLayout.LayoutParams checkboxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            checkboxParams.topMargin = (int)(14*density);
            content.addView(acknowledged, checkboxParams);
        }

        TextView error = text(activity, "", 12, 0xFFFFA6A6);
        content.addView(error);
        TextView confirm = text(activity, creating ? "Criar chave de assinatura" : "Continuar",
            15, 0xFF171322);
        confirm.setTypeface(Typeface.DEFAULT_BOLD);
        confirm.setGravity(Gravity.CENTER);
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(0xFFA78BFA);
        buttonBg.setCornerRadius(12 * density);
        confirm.setBackground(buttonBg);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density));
        buttonParams.topMargin = (int)(12 * density);
        content.addView(confirm, buttonParams);

        TextView cancel = text(activity, "Cancelar", 15, 0xFFA78BFA);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(0, (int)(14 * density), 0, 0);
        content.addView(cancel);

        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        Dialog dialog = new Dialog(activity);
        dialog.getWindow();
        dialog.setContentView(scroll);
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setDimAmount(0.65f);
        cancel.setOnClickListener(v -> dialog.dismiss());
        final EditText confirmField = confirmation;
        final CheckBox check = acknowledged;
        confirm.setOnClickListener(v -> {
            char[] password = field.getText().toString().toCharArray();
            if (password.length == 0 || (creating && password.length < 12)) {
                error.setText(creating ? "A senha deve ter pelo menos 12 caracteres." : "Informe a senha.");
                Arrays.fill(password, '\0');
                return;
            }
            if (creating) {
                char[] repeated = confirmField.getText().toString().toCharArray();
                boolean matches = Arrays.equals(password, repeated);
                Arrays.fill(repeated, '\0');
                if (!matches) {
                    error.setText("As senhas não coincidem.");
                    Arrays.fill(password, '\0');
                    return;
                }
                if (!check.isChecked()) {
                    error.setText("Confirme que leu os avisos antes de continuar.");
                    Arrays.fill(password, '\0');
                    return;
                }
                field.setText("");
                confirmField.setText("");
                dialog.dismiss();
                new Thread(() -> {
                    try {
                        if (GameSigningKeyManager.hasKey(activity, projectId)) {
                            throw new java.io.IOException("Já existe uma chave para este jogo");
                        }
                        GameSigningKeyManager.generate(activity, projectId, password);
                        activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
                            .setTitle("Chave criada")
                            .setMessage("A chave foi criada. Faça agora o backup .p12 e guarde sua senha em local seguro. Sem ambos, futuras atualizações podem ficar impossíveis.")
                            .setPositiveButton("Entendi", null).show());
                    } catch (Exception e) {
                        activity.runOnUiThread(() -> toast(activity,
                            "Não foi possível criar a chave: " + e.getMessage()));
                    } finally {
                        Arrays.fill(password, '\0');
                    }
                }, "CodeLove-KeyCreate").start();
                return;
            }
            if (action == 1) {
                try {
                    if (!GameSigningKeyManager.hasKey(activity, projectId)) {
                        error.setText("Crie uma chave antes de salvar o backup.");
                        Arrays.fill(password, '\0');
                        return;
                    }
                } catch (Exception e) {
                    error.setText("Não foi possível localizar a chave.");
                    Arrays.fill(password, '\0');
                    return;
                }
            }
            synchronized (GameSigningKeyUi.class) {
                if (busy) {
                    Arrays.fill(password, '\0');
                    error.setText("Já existe uma operação em andamento.");
                    return;
                }
                busy = true;
                pendingProject = projectId;
                pendingPassword = password;
            }
            field.setText("");
            dialog.dismiss();
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
        });
        dialog.show();
        dialog.getWindow().setLayout(
            activity.getResources().getDisplayMetrics().widthPixels - (int)(36*density),
            ViewGroup.LayoutParams.WRAP_CONTENT);
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
