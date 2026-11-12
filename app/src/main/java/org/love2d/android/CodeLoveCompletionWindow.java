/*
 * CodeLove - visual de autocomplete do Sora com a identidade do CodeLove.
 *
 * Usa as sugestoes e a selecao do Sora. Apenas a apresentacao e alterada:
 * tipografia monoespacada, prefixo roxo, restante discreto e largura compacta.
 */
package org.love2d.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.Unsubscribe;
import io.github.rosemoe.sora.lang.completion.CompletionItem;
import io.github.rosemoe.sora.widget.component.EditorAutoCompletion;
import io.github.rosemoe.sora.widget.component.EditorCompletionAdapter;

final class CodeLoveCompletionWindow extends EditorAutoCompletion {
    private final CodeLoveCompletionAdapter rows;
    private int completionEditRevision;

    CodeLoveCompletionWindow(io.github.rosemoe.sora.widget.CodeEditor editor) {
        super(editor);
        rows = new CodeLoveCompletionAdapter();
        setAdapter(rows);
        // Nao aplicar o destaque generico do Sora por cima do prefixo proprio.
        setHighlightMatchedLabel(false);
        // No celular, manter o menu junto do cursor, nao centralizar quase
        // na largura inteira do editor.
        setCompletionWndPositionMode(WINDOW_POS_MODE_FOLLOW_CURSOR_ALWAYS);
    }

    /**
     * O Sora so atualiza sugestoes ao apagar quando a janela ainda esta
     * visivel. Se um erro de digitacao zerar a lista (Randomk), a exclusao
     * seguinte deve restaurar as sugestoes do prefixo valido (Random).
     * Nao substitui o mecanismo de busca do Sora nem interfere na insercao.
     */
    @Override
    protected void onContentChange(ContentChangeEvent event, Unsubscribe unsubscribe) {
        completionEditRevision++;
        boolean popupWasShowing = isShowing();
        super.onContentChange(event, unsubscribe);
        if (event.getAction() != ContentChangeEvent.ACTION_DELETE
                || event.isCausedByUndoManager() || !isEnabled()
                || popupWasShowing || getEditor().hasComposingText()) {
            return;
        }
        // Apenas Backspace de um caractere na mesma linha.
        var start = event.getChangeStart();
        var end = event.getChangeEnd();
        if (start.line != end.line || end.column != start.column + 1) return;

        final int revision = completionEditRevision;
        final int expectedLine = start.line;
        final int expectedColumn = start.column;
        // O Sora possui um intervalo minimo entre buscas (cancelCompletionNs).
        // Executar depois dele tambem deixa o teclado atualizar a posicao.
        long delayMs = Math.max(90L,
            getEditor().getProps().cancelCompletionNs / 1000000L + 20L);
        getEditor().postDelayedInLifecycle(() -> {
            if (revision != completionEditRevision || !isEnabled()
                    || isShowing() || getEditor().hasComposingText()
                    || getEditor().getCursor().isSelected()) {
                return;
            }
            var caret = getEditor().getCursor().left();
            if (caret.line != expectedLine || caret.column != expectedColumn
                    || caret.column == 0) return;

            String line = getEditor().getText().getLineString(caret.line);
            int begin = Math.min(caret.column, line.length());
            int endOfPrefix = begin;
            while (begin > 0) {
                char ch = line.charAt(begin - 1);
                if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != ':') break;
                begin--;
            }
            if (begin == endOfPrefix) return;

            String prefix = line.substring(begin, endOfPrefix);
            // Evita criar buscas em background quando nao ha sugestoes.
            for (String candidate : LoveApiCatalog.SUGGESTIONS) {
                if (candidate.startsWith(prefix) && !candidate.equals(prefix)) {
                    requireCompletion();
                    break;
                }
            }
        }, delayMs);
    }

    @Override
    public void updateCompletionWindowPosition(boolean scrollEditor) {
        super.updateCompletionWindowPosition(scrollEditor);
        float dp = getEditor().getDpUnit();
        if (dp <= 0) return;

        int longest = 8;
        for (int i = 0; i < Math.min(rows.getCount(), 24); i++) {
            CompletionItem item = rows.getItem(i);
            if (item.label != null) {
                longest = Math.max(longest, item.label.length());
            }
        }
        // Mesmo para uma unica sugestao, nao usa os 300dp padroes do Sora.
        int width = Math.round(Math.max(84f, Math.min(260f, 20f + longest * 7.3f)) * dp);
        setSize(width, getHeight());
    }

    private static final class CodeLoveCompletionAdapter extends EditorCompletionAdapter {
        @Override
        public int getItemHeight() {
            float dp = getContext().getResources().getDisplayMetrics().density;
            return Math.round(34f * dp);
        }

        @Override
        protected View getView(int position, View oldView, ViewGroup parent, boolean selected) {
            Context context = getContext();
            float density = context.getResources().getDisplayMetrics().density;
            TextView text;
            if (oldView instanceof TextView) {
                text = (TextView) oldView;
            } else {
                text = new TextView(context);
                text.setTypeface(Typeface.MONOSPACE);
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                text.setSingleLine(true);
                text.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                text.setPadding(Math.round(10 * density), Math.round(6 * density),
                    Math.round(10 * density), Math.round(6 * density));
                text.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, getItemHeight()));
            }

            CompletionItem item = getItem(position);
            String candidate = item.label == null ? "" : item.label.toString();
            SpannableString styled = new SpannableString(candidate);
            int typedLength = Math.min(candidate.length(), Math.max(0, item.prefixLength));
            int purple = Workspace.isLightTheme() ? 0xFF6E47B9 : 0xFFC4A5FF;
            if (typedLength > 0) {
                styled.setSpan(new ForegroundColorSpan(purple),
                    0, typedLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (typedLength < candidate.length()) {
                styled.setSpan(new ForegroundColorSpan(Workspace.COLOR_MUTED),
                    typedLength, candidate.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            text.setText(styled);

            if (selected) {
                GradientDrawable selectedBackground = new GradientDrawable();
                selectedBackground.setColor(Workspace.isLightTheme()
                    ? 0xFFECE4FA : 0xFF33274D);
                selectedBackground.setCornerRadius(6 * density);
                text.setBackground(selectedBackground);
            } else {
                text.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            }
            return text;
        }
    }
}
