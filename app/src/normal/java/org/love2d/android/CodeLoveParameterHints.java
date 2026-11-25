/*
 * CodeLove - ajuda de parametros para o motor Sora.
 *
 * Reutiliza LoveApiParameters sem usar TextWatcher, spans ou EditText.
 * O trabalho e limitado a 2048 caracteres anteriores ao cursor e e
 * agendado apos uma pausa, para preservar a fluidez do Sora.
 */
package org.love2d.android;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EditorReleaseEvent;
import io.github.rosemoe.sora.event.ScrollEvent;
import io.github.rosemoe.sora.event.SelectionChangeEvent;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;

final class CodeLoveParameterHints {
    private static final long IDLE_DELAY_MS = 170L;
    private static final int MAX_CONTEXT_CHARS = 2048;

    private final Activity activity;
    private final io.github.rosemoe.sora.widget.CodeEditor editor;
    private final Runnable pendingUpdate = this::update;
    private final TextView label;
    private final PopupWindow popup;
    private boolean enabled;

    CodeLoveParameterHints(Activity activity,
                           io.github.rosemoe.sora.widget.CodeEditor editor) {
        this.activity = activity;
        this.editor = editor;

        label = new TextView(activity);
        label.setTypeface(Typeface.MONOSPACE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        label.setTextColor(Workspace.COLOR_TEXT);
        label.setPadding(dp(10), dp(7), dp(10), dp(7));
        label.setMaxWidth(dp(370));

        GradientDrawable panel = new GradientDrawable();
        panel.setColor(Workspace.COLOR_FIELD);
        panel.setCornerRadius(dp(8));
        panel.setStroke(Math.max(1, dp(1)), Workspace.COLOR_BORDER);
        label.setBackground(panel);
        label.setElevation(dp(6));

        popup = new PopupWindow(label, ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, false);
        popup.setBackgroundDrawable(panel);
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);

        editor.subscribeAlways(ContentChangeEvent.class, event -> {
            if (event.getAction() == ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                clear();
            } else {
                schedule();
            }
        });
        editor.subscribeAlways(SelectionChangeEvent.class, event -> schedule());
        editor.subscribeAlways(ScrollEvent.class, event -> {
            // Nao prender a janela sobre o codigo durante a rolagem.
            editor.removeCallbacks(pendingUpdate);
            hide();
        });
        editor.subscribeAlways(EditorReleaseEvent.class, event -> {
            enabled = false;
            clear();
        });
    }

    void setEnabled(boolean value) {
        if (enabled == value) return;
        enabled = value;
        if (enabled) schedule();
        else clear();
    }

    private void schedule() {
        editor.removeCallbacks(pendingUpdate);
        if (!enabled) return;
        editor.postDelayed(pendingUpdate, IDLE_DELAY_MS);
    }

    private void clear() {
        editor.removeCallbacks(pendingUpdate);
        hide();
    }

    private void hide() {
        if (popup.isShowing()) popup.dismiss();
    }

    private void update() {
        if (!enabled || !editor.isAttachedToWindow()
            || editor.getVisibility() != View.VISIBLE || editor.getCursor() == null
            || editor.getCursor().isSelected()) {
            hide();
            return;
        }

        // A sugestao de palavras do Sora tem prioridade visual.
        EditorAutoCompletion completion = editor.getComponent(EditorAutoCompletion.class);
        if (completion.isShowing()) {
            hide();
            return;
        }

        CharPosition caret = editor.getCursor().left();
        int index = caret.index;
        if (index <= 0 || index > editor.getText().length()) {
            hide();
            return;
        }

        // Evita copiar o arquivo inteiro ou procurar a partir do comeco.
        String preceding = editor.getText().substring(
            Math.max(0, index - MAX_CONTEXT_CHARS), index);
        int depth = 0, open = -1;
        for (int i = preceding.length() - 1; i >= 0; i--) {
            char c = preceding.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            }
        }
        if (open < 0) {
            hide();
            return;
        }

        int nameEnd = open;
        while (nameEnd > 0 && Character.isWhitespace(preceding.charAt(nameEnd - 1))) {
            nameEnd--;
        }
        int nameStart = nameEnd;
        while (nameStart > 0) {
            char c = preceding.charAt(nameStart - 1);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '.' && c != ':') {
                break;
            }
            nameStart--;
        }
        if (nameStart == nameEnd) {
            hide();
            return;
        }

        String name = preceding.substring(nameStart, nameEnd);
        String signature = LoveApiParameters.signature(name);
        if (signature == null) {
            hide();
            return;
        }

        int argument = 0;
        int nested = 0;
        for (int i = open + 1; i < preceding.length(); i++) {
            char c = preceding.charAt(i);
            if (c == '(' || c == '[' || c == '{') nested++;
            else if (c == ')' || c == ']' || c == '}') nested = Math.max(0, nested - 1);
            else if (c == ',' && nested == 0) argument++;
        }

        String firstLine = name + signature;
        String full = firstLine + "\n"
            + activity.getString(R.string.cl_current_arg, argument + 1);
        SpannableString styled = new SpannableString(full);
        styled.setSpan(new ForegroundColorSpan(Workspace.COLOR_FOLDER),
            0, name.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new ForegroundColorSpan(Workspace.COLOR_MUTED),
            firstLine.length() + 1, full.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        label.setText(styled);

        int[] origin = new int[2];
        editor.getLocationOnScreen(origin);
        int x = origin[0] + Math.round(editor.getCharOffsetX(caret.line, caret.column));
        int caretBottom = origin[1] + Math.round(
            editor.getCharOffsetY(caret.line, caret.column));
        int y = caretBottom + dp(4);

        Rect visible = new Rect();
        editor.getWindowVisibleDisplayFrame(visible);
        int availableWidth = Math.max(dp(100), visible.width() - dp(12));
        label.measure(
            View.MeasureSpec.makeMeasureSpec(availableWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = label.getMeasuredWidth();
        int height = label.getMeasuredHeight();

        x = Math.max(visible.left + dp(4), Math.min(x,
            visible.right - width - dp(4)));
        if (y + height + dp(4) > visible.bottom) {
            y = caretBottom - editor.getRowHeight() - height - dp(4);
        }
        if (y < visible.top + dp(4) || y + height > visible.bottom) {
            hide();
            return;
        }

        if (popup.isShowing()) {
            popup.update(x, y, -1, -1);
        } else {
            popup.showAtLocation(editor, Gravity.TOP | Gravity.LEFT, x, y);
        }
    }

    private int dp(float value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
