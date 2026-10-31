/*
 * CodeLove - menus e janelinhas (nome, confirmacao) desenhados DENTRO da tela
 * do app, por cima de tudo. Assim nao abrem outra janela do Android e o jogo
 * nao e pausado enquanto voce usa o menu.
 *
 * Baseado no LOVE para Android (licenca zlib). Versao alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.util.List;

final class OverlayUi {
    /** Confere o texto digitado. Devolve a mensagem de erro, ou nulo se estiver ok. */
    interface Validator {
        String check(String text);
    }

    interface TextDone {
        void accept(String text);
    }

    private static final int COLOR_DANGER = 0xFFFF7B8A;

    private final Activity activity;
    private final RelativeLayout root;
    private FrameLayout current;
    private EditText currentField;

    /** Altura da barra de status; o Workspace atualiza. */
    int topInset;

    OverlayUi(Activity activity, RelativeLayout root) {
        this.activity = activity;
        this.root = root;
    }

    boolean isShowing() {
        return current != null;
    }

    void dismiss() {
        if (current == null) {
            return;
        }
        if (currentField != null) {
            InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentField.getWindowToken(), 0);
            }
            currentField = null;
        }
        root.removeView(current);
        current = null;
    }

    // ------------------------------------------------------------------
    // Menu
    // ------------------------------------------------------------------

    /** Menu de opcoes junto de um elemento da tela. */
    void showMenu(View anchor, List<String> labels, List<Runnable> actions) {
        FrameLayout scrim = openScrim(0x00000000);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(Workspace.COLOR_PANEL, 14, true));
        card.setPadding(0, dp(6), 0, dp(6));
        card.setClickable(true);

        for (int i = 0; i < labels.size(); i++) {
            final Runnable action = actions.get(i);
            TextView item = new TextView(activity);
            item.setText(labels.get(i));
            item.setTextSize(15);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(16), 0, dp(16), 0);
            item.setSingleLine(true);
            item.setTextColor(labels.get(i).startsWith("Excluir") ? COLOR_DANGER : Workspace.COLOR_TEXT);
            item.setOnClickListener(v -> {
                dismiss();
                action.run();
            });
            card.addView(item, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        }

        int cardWidth = dp(240);
        int cardHeight = labels.size() * dp(44) + dp(12);
        int margin = dp(8);

        int[] anchorPos = new int[2];
        int[] rootPos = new int[2];
        anchor.getLocationInWindow(anchorPos);
        root.getLocationInWindow(rootPos);
        int anchorLeft = anchorPos[0] - rootPos[0];
        int anchorTop = anchorPos[1] - rootPos[1];

        // alinha pela borda direita do elemento e mantem dentro da tela
        int left = anchorLeft + anchor.getWidth() - cardWidth;
        left = Math.max(margin, Math.min(left, root.getWidth() - cardWidth - margin));
        int top = anchorTop + anchor.getHeight();
        if (top + cardHeight > root.getHeight() - margin) {
            top = anchorTop - cardHeight; // sem espaco embaixo: abre para cima
        }
        top = Math.max(topInset + margin, top);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        lp.topMargin = top;
        scrim.addView(card, lp);
    }

    // ------------------------------------------------------------------
    // Janela para digitar um nome
    // ------------------------------------------------------------------

    void askText(String title, String initial, String confirmLabel,
                 final Validator validator, final TextDone done) {
        FrameLayout scrim = openScrim(0x99000000);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(Workspace.COLOR_PANEL, 16, true));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.setClickable(true);

        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Workspace.COLOR_TEXT);
        titleView.setTextSize(17);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleView);

        final EditText field = new EditText(activity);
        field.setText(initial);
        field.setSelection(initial.length());
        field.setTextColor(Workspace.COLOR_TEXT);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setBackground(box(Workspace.COLOR_FIELD, 12, true));
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fieldLp.setMargins(0, dp(12), 0, 0);
        card.addView(field, fieldLp);

        final TextView error = new TextView(activity);
        error.setTextColor(COLOR_DANGER);
        error.setTextSize(12);
        error.setPadding(dp(4), dp(6), dp(4), 0);
        error.setVisibility(View.GONE);
        card.addView(error);

        final Runnable confirm = new Runnable() {
            @Override
            public void run() {
                String text = field.getText().toString().trim();
                String problem = validator == null ? null : validator.check(text);
                if (problem != null) {
                    error.setText(problem);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                dismiss();
                done.accept(text);
            }
        };
        field.setOnEditorActionListener((v, actionId, event) -> {
            confirm.run();
            return true;
        });

        card.addView(buttons("Cancelar", confirmLabel, Workspace.COLOR_FOLDER, 0xFF0D0F16,
            confirm), buttonsParams());

        int width = Math.min(dp(360), root.getWidth() - dp(32));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = topInset + dp(72);
        scrim.addView(card, lp);

        currentField = field;
        field.requestFocus();
        field.post(() -> {
            InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    // ------------------------------------------------------------------
    // Janela de confirmacao
    // ------------------------------------------------------------------

    void confirm(String message, String confirmLabel, final Runnable onYes) {
        FrameLayout scrim = openScrim(0x99000000);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(Workspace.COLOR_PANEL, 16, true));
        card.setPadding(dp(18), dp(18), dp(18), dp(14));
        card.setClickable(true);

        TextView text = new TextView(activity);
        text.setText(message);
        text.setTextColor(Workspace.COLOR_TEXT);
        text.setTextSize(15);
        card.addView(text);

        card.addView(buttons("Cancelar", confirmLabel, COLOR_DANGER, 0xFF0D0F16, new Runnable() {
            @Override
            public void run() {
                dismiss();
                onYes.run();
            }
        }), buttonsParams());

        int width = Math.min(dp(360), root.getWidth() - dp(32));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
            width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = topInset + dp(72);
        scrim.addView(card, lp);
    }

    // ------------------------------------------------------------------

    private LinearLayout.LayoutParams buttonsParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(16), 0, 0);
        return lp;
    }

    /** Linha com os botoes "cancelar" e "confirmar", alinhados a direita. */
    private LinearLayout buttons(String cancelLabel, String confirmLabel, int confirmColor,
                                 int confirmTextColor, final Runnable onConfirm) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.END);

        TextView cancel = button(cancelLabel, Workspace.COLOR_FIELD, Workspace.COLOR_TEXT);
        cancel.setOnClickListener(v -> dismiss());
        row.addView(cancel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)));

        TextView ok = button(confirmLabel, confirmColor, confirmTextColor);
        ok.setTypeface(Typeface.DEFAULT_BOLD);
        ok.setOnClickListener(v -> onConfirm.run());
        LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        okLp.setMargins(dp(10), 0, 0, 0);
        row.addView(ok, okLp);
        return row;
    }

    private TextView button(String label, int background, int textColor) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(textColor);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(box(background, 12, false));
        return button;
    }

    /** Fundo que cobre a tela toda: tocar fora fecha. */
    private FrameLayout openScrim(int color) {
        dismiss();
        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(color);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismiss());
        root.addView(scrim, new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        current = scrim;
        return scrim;
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private GradientDrawable box(int color, int radiusDp, boolean border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (border) {
            drawable.setStroke(Math.max(1, dp(1)), Workspace.COLOR_BORDER);
        }
        return drawable;
    }
}
