package org.love2d.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ScrollView;
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

        final Dialog dialog = new Dialog(activity);
        String[] labels = {"Criar chave", "Salvar backup .p12", "Restaurar backup .p12"};
        String[] descriptions = {
            "Gerar uma nova identidade de assinatura",
            "Guardar uma cópia de segurança da chave",
            "Recuperar uma chave de um backup existente"
        };
        for (int i = 0; i < labels.length; i++) {
            final int action = i;
            LinearLayout option = new LinearLayout(activity);
            option.setOrientation(LinearLayout.HORIZONTAL);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding((int)(14*density), 0, (int)(14*density), 0);
            GradientDrawable optionBackground = new GradientDrawable();
            optionBackground.setColor(Workspace.COLOR_PANEL);
            optionBackground.setStroke(Math.max(1, (int)density), Workspace.COLOR_BORDER);
            optionBackground.setCornerRadius(12*density);
            option.setBackground(optionBackground);
            option.setClickable(true);
            option.setFocusable(true);
            option.setContentDescription(labels[i]);
            View icon;
            if (i == 1) {
                // File/archive symbol instead of a download arrow.
                icon = new View(activity) {
                    final android.graphics.Paint paint = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        super.onDraw(canvas);
                        canvas.save();
                        canvas.translate((getWidth() - 24*density)/2f,
                            (getHeight() - 24*density)/2f);
                        canvas.scale(density, density);
                        paint.setColor(0xFFA78BFA);
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        paint.setStrokeWidth(1.9f);
                        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                        canvas.drawRoundRect(4, 5, 20, 20, 2, 2, paint);
                        canvas.drawLine(4, 9, 20, 9, paint);
                        canvas.drawLine(12, 9, 12, 15, paint);
                        canvas.drawLine(9, 15, 15, 15, paint);
                        canvas.restore();
                    }
                };
            } else if (i == 2) {
                // Vetor de restauracao centralizado, independente das metricas da fonte.
                icon = new View(activity) {
                    final android.graphics.Paint paint = new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG);
                    @Override protected void onDraw(android.graphics.Canvas canvas) {
                        super.onDraw(canvas);
                        canvas.save();
                        canvas.translate((getWidth() - 24*density)/2f,
                            (getHeight() - 24*density)/2f);
                        canvas.scale(density, density);
                        paint.setColor(Workspace.COLOR_FOLDER);
                        paint.setStyle(android.graphics.Paint.Style.STROKE);
                        paint.setStrokeWidth(1.9f);
                        paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                        paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
                        android.graphics.RectF arc = new android.graphics.RectF(4, 4, 20, 20);
                        canvas.drawArc(arc, -130, 290, false, paint);
                        canvas.drawLine(3.8f, 5.2f, 4.3f, 11.0f, paint);
                        canvas.drawLine(3.8f, 5.2f, 9.4f, 6.2f, paint);
                        canvas.restore();
                    }
                };
            } else {
                TextView glyph = text(activity, "⚿", 23, 0xFFA78BFA);
                glyph.setGravity(Gravity.CENTER);
                icon = glyph;
            }
            option.addView(icon, new LinearLayout.LayoutParams(
                (int)(24*density), (int)(28*density)));
            LinearLayout labelsGroup = new LinearLayout(activity);
            labelsGroup.setOrientation(LinearLayout.VERTICAL);
            TextView name = text(activity, labels[i], 15, Workspace.COLOR_TEXT);
            labelsGroup.addView(name);
            TextView description = text(activity, descriptions[i], 12, Workspace.COLOR_MUTED);
            description.setPadding(0, (int)(3*density), 0, 0);
            labelsGroup.addView(description);
            LinearLayout.LayoutParams groupParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            groupParams.leftMargin = (int)(10*density);
            option.addView(labelsGroup, groupParams);
            TextView chevron = text(activity, "›", 25, Workspace.COLOR_MUTED);
            option.addView(chevron);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(66*density));
            params.bottomMargin = (int)(9*density);
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

        showRoundedDialog(activity, dialog, panel, density, false);
    }

    private static void showRoundedDialog(Activity activity, Dialog dialog,
                                          View panel, float density, boolean longForm) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(activity);
        frame.setPadding((int)(3*density), (int)(3*density),
            (int)(3*density), (int)(3*density));
        frame.addView(panel);
        dialog.setContentView(frame);
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(0.65f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout(metrics.widthPixels - (int)(30*density),
                longForm ? (int)(metrics.heightPixels * 0.84f) :
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT);
        }
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
        background.setColor(Workspace.COLOR_FIELD);
        background.setStroke(Math.max(1, (int)density), Workspace.COLOR_BORDER);
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
        // O arredondamento pertence apenas ao dialogo externo.
        // Arredondar o conteudo rolavel cria uma emenda visivel sobre o rodape.
        content.setBackgroundColor(Workspace.COLOR_PANEL);

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
            warningBg.setColor(0xFF30291D);
            warningBg.setStroke(Math.max(1, (int)density), 0xFFB78B42);
            warningBg.setCornerRadius(12 * density);
            warningPanel.setBackground(warningBg);
            TextView warningTitle = text(activity, "⚠  Antes de continuar", 15, 0xFFFFCB78);
            warningTitle.setTypeface(Typeface.DEFAULT_BOLD);
            warningPanel.addView(warningTitle);
            TextView warningBody = text(activity,
                "Depois que um jogo for distribuído, a chave utilizada para assiná-lo deverá ser preservada. "
                + "Perder essa chave pode impedir a atualização do mesmo aplicativo.",
                // The warning stays dark in both themes: always use a readable
                // light foreground instead of the theme-dependent COLOR_TEXT.
                14, 0xFFFFEED7);
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

        final EditText[] certificateFields = new EditText[6];
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

            TextView optional = text(activity, "Informações do certificado (opcional)  ▾",
                15, Workspace.COLOR_TEXT);
            optional.setPadding(0, (int)(18*density), 0, (int)(12*density));
            optional.setTypeface(Typeface.DEFAULT_BOLD);
            content.addView(optional);
            LinearLayout details = new LinearLayout(activity);
            details.setOrientation(LinearLayout.VERTICAL);
            details.setVisibility(View.GONE);
            content.addView(details);
            TextView privacy = text(activity,
                "Não é necessário preencher estes campos. Os dados informados serão públicos no certificado do APK. Evite informações pessoais sensíveis.",
                13, Workspace.COLOR_MUTED);
            details.addView(privacy);
            String[] hints = {"Nome do desenvolvedor (CN)", "Organização (O)",
                "Unidade organizacional (OU)", "Cidade (L)", "Estado (ST)", "País (C, ex.: BR)"};
            for (int i = 0; i < hints.length; i++) {
                EditText entry = passwordInput(activity, hints[i]);
                entry.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
                entry.setFilters(new android.text.InputFilter[]{
                    new android.text.InputFilter.LengthFilter(i == 5 ? 2 : 128)});
                entry.setContentDescription(hints[i]);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int)(52*density));
                p.topMargin = (int)(8*density);
                details.addView(entry, p);
                certificateFields[i] = entry;
            }
            optional.setOnClickListener(v -> {
                boolean open = details.getVisibility() != View.VISIBLE;
                details.setVisibility(open ? View.VISIBLE : View.GONE);
                optional.setText("Informações do certificado (opcional)  " + (open ? "▴" : "▾"));
            });

            TextView backupNotice = text(activity,
                "ⓘ  Após criar a chave, salve uma cópia de segurança .p12 fora do CodeLove "
                + "e guarde a senha separadamente. Desinstalar o aplicativo apaga a chave local.",
                13, Workspace.COLOR_MUTED);
            backupNotice.setPadding(0, (int)(18*density), 0, (int)(18*density));
            content.addView(backupNotice);

            View separator = new View(activity);
            separator.setBackgroundColor(Workspace.COLOR_BORDER);
            content.addView(separator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, (int)density)));

            acknowledged = new CheckBox(activity);
            acknowledged.setButtonDrawable(android.R.color.transparent);
            acknowledged.setText("");
            acknowledged.setPadding(0, 0, 0, 0);
            acknowledged.setMinWidth(0);
            acknowledged.setMinHeight(0);
            GradientDrawable checkBg = new GradientDrawable();
            checkBg.setCornerRadius(4 * density);
            checkBg.setStroke(Math.max(1, (int)(2*density)), Workspace.COLOR_FOLDER);
            acknowledged.setBackground(checkBg);
            final CheckBox checkbox = acknowledged;
            acknowledged.setOnCheckedChangeListener((button, checked) -> {
                checkBg.setColor(checked ? Workspace.COLOR_FOLDER : 0x00000000);
                checkbox.setText(checked ? "✓" : "");
                checkbox.setTextSize(12);
                checkbox.setGravity(Gravity.CENTER);
                checkbox.setTextColor(Workspace.COLOR_ACCENT_TEXT);
            });
            LinearLayout checkRow = new LinearLayout(activity);
            checkRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(
                (int)(20*density), (int)(20*density));
            checkParams.rightMargin = (int)(10*density);
            checkRow.addView(checkbox, checkParams);
            TextView checkLabel = text(activity,
                "Li e compreendi os riscos de perder minha chave.", 13, Workspace.COLOR_TEXT);
            checkRow.addView(checkLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            checkLabel.setOnClickListener(v -> checkbox.setChecked(!checkbox.isChecked()));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = (int)(14*density);
            content.addView(checkRow, rowParams);
        }

        TextView error = text(activity, "", 12, 0xFFFFA6A6);
        TextView confirm = text(activity, creating ? "Criar chave de assinatura" : "Continuar",
            15, Workspace.COLOR_ACCENT_TEXT);
        confirm.setTypeface(Typeface.DEFAULT_BOLD);
        confirm.setGravity(Gravity.CENTER);
        GradientDrawable buttonBg = new GradientDrawable();
        buttonBg.setColor(Workspace.COLOR_FOLDER);
        buttonBg.setCornerRadius(12 * density);
        confirm.setBackground(buttonBg);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(48 * density));
        buttonParams.topMargin = (int)(12 * density);
        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setPadding(pad, (int)(6*density), pad, (int)(12*density));
        footer.setBackgroundColor(Workspace.COLOR_PANEL);
        footer.addView(error);
        footer.addView(confirm, buttonParams);
        TextView cancel = text(activity, "Cancelar", 15, Workspace.COLOR_FOLDER);
        cancel.setGravity(Gravity.CENTER);
        footer.addView(cancel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(44*density)));

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(bg);
        layout.setClipToOutline(true);
        if (creating) {
            // Formulario longo: campos rolaveis e botoes sempre acessiveis.
            layout.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            layout.addView(footer);
        } else {
            // Formularios curtos: conteudo e botoes no mesmo fluxo, sem area vazia.
            content.addView(footer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            layout.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        Dialog dialog = new Dialog(activity);
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
                final String[] details = new String[6];
                for (int i = 0; i < details.length; i++) {
                    details[i] = certificateFields[i].getText().toString().trim();
                }
                try {
                    GameSigningKeyStore.certificateSubject(details);
                } catch (java.security.GeneralSecurityException e) {
                    error.setText("Verifique os dados do certificado. O país deve ter duas letras (ex.: BR).");
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
                        GameSigningKeyManager.generate(activity, projectId, password, details);
                        activity.runOnUiThread(() ->
                            CodeLoveModal.message(activity, "Chave criada",
                                "A chave foi criada. Faça agora o backup .p12 e guarde "
                                + "sua senha em local seguro. Sem ambos, futuras "
                                + "atualizações podem ficar impossíveis.", "Entendi"));
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
            if (action == 1) {
                // ACTION_CREATE_DOCUMENT creates a document before any bytes are
                // written. A bad password must be rejected BEFORE the picker.
                new Thread(() -> {
                    try {
                        GameSigningKeyManager.inspect(activity, projectId, password);
                        activity.runOnUiThread(() -> {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                clearPending();
                                return;
                            }
                            try {
                                activity.startActivityForResult(
                                    GameSigningKeySaf.createBackupDocumentIntent(applicationId),
                                    REQUEST_BACKUP);
                            } catch (RuntimeException e) {
                                clearPending();
                                toast(activity, "Não foi possível abrir o seletor de arquivos");
                            }
                        });
                    } catch (Exception e) {
                        clearPending();
                        activity.runOnUiThread(() ->
                            toast(activity, "Senha incorreta ou chave inválida. "
                                + "Nenhum arquivo de backup foi criado."));
                    }
                }, "CodeLove-KeyBackupPreflight").start();
            } else {
                try {
                    activity.startActivityForResult(
                        GameSigningKeySaf.openBackupDocumentIntent(), REQUEST_RESTORE);
                } catch (RuntimeException e) {
                    clearPending();
                    toast(activity, "Não foi possível abrir o seletor de arquivos");
                }
            }
        });
        showRoundedDialog(activity, dialog, layout, density, creating);
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
                if (request == REQUEST_BACKUP) {
                    // The picker has already created the document at this point.
                    // Remove incomplete backups, including zero-byte placeholders.
                    try {
                        if (DocumentsContract.isDocumentUri(activity, uri)) {
                            DocumentsContract.deleteDocument(
                                activity.getContentResolver(), uri);
                        }
                    } catch (Exception cleanup) {
                        Log.w("CodeLoveKeyBackup",
                            "Unable to delete incomplete backup from provider", cleanup);
                    }
                }
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
