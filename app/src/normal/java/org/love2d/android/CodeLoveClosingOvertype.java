/*
 * CodeLove - pula fechamento ja presente no cursor em arquivos Lua.
 *
 * Exemplo: (|) + ")" -> ()|, em vez de ()).
 * Apenas ), ] e } (comportamento do CodeLove anterior). Aspas
 * continuam sob controle do pareamento nativo do Sora.
 *
 * Nao modifica o documento dentro de ContentChangeEvent; usa post()
 * com verificacoes de arquivo, linha, cursor e conteudo.
 */
package org.love2d.android;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EditorReleaseEvent;
import io.github.rosemoe.sora.text.Content;

final class CodeLoveClosingOvertype {
    private final io.github.rosemoe.sora.widget.CodeEditor editor;
    private boolean enabled;
    private long generation;

    CodeLoveClosingOvertype(io.github.rosemoe.sora.widget.CodeEditor editor) {
        this.editor = editor;
        editor.subscribeAlways(ContentChangeEvent.class, this::onChanged);
        editor.subscribeAlways(EditorReleaseEvent.class, event -> setEnabled(false));
    }

    void setEnabled(boolean value) {
        enabled = value;
        generation++;
    }

    private static boolean isCloser(char ch) {
        return ch == ')' || ch == ']' || ch == '}';
    }

    private void onChanged(ContentChangeEvent event) {
        if (!enabled || event.isCausedByUndoManager()
            || event.getAction() != ContentChangeEvent.ACTION_INSERT
            || event.getChangedText().length() != 1
            || event.getChangeStart().line != event.getChangeEnd().line) return;

        final char typed = event.getChangedText().charAt(0);
        if (!isCloser(typed)) return;

        final int line = event.getChangeStart().line;
        final int column = event.getChangeStart().column;
        final Content doc = editor.getText();
        if (line < 0 || line >= doc.getLineCount()) return;
        final String snapshot = doc.getLineString(line);

        // Uma copia do fechamento foi inserida imediatamente antes de outra.
        if (column < 0 || column + 1 >= snapshot.length()
            || snapshot.charAt(column) != typed
            || snapshot.charAt(column + 1) != typed) return;
        final long version = generation;

        editor.post(() -> {
            if (!enabled || version != generation || editor.getText() != doc
                || editor.getCursor() == null || editor.getCursor().isSelected()
                || editor.getCursor().getLeftLine() != line
                || editor.getCursor().getLeftColumn() != column + 1
                || line >= doc.getLineCount()
                || !snapshot.equals(doc.getLineString(line))) return;

            // Descarta o caractere digitado e avanca sobre o existente.
            doc.beginBatchEdit();
            try {
                doc.delete(line, column, line, column + 1);
            } finally {
                doc.endBatchEdit();
            }
            editor.setSelection(line, column + 1, false);
        });
    }
}
