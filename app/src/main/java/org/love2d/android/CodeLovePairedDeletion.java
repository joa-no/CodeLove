/*
 * CodeLove - exclusao de pares vazios no motor Sora.
 *
 * Ao pressionar Backspace entre dois delimitadores adjacentes, o Sora
 * apaga o caractere de abertura e este observador remove o de fechamento.
 * Nenhum processamento ocorre durante digitacao ou rolagem normais.
 */
package org.love2d.android;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EditorReleaseEvent;
import io.github.rosemoe.sora.text.Content;

final class CodeLovePairedDeletion {
    private final io.github.rosemoe.sora.widget.CodeEditor editor;
    private boolean enabled;
    private long generation;

    CodeLovePairedDeletion(io.github.rosemoe.sora.widget.CodeEditor editor) {
        this.editor = editor;
        editor.subscribeAlways(ContentChangeEvent.class, this::onChanged);
        editor.subscribeAlways(EditorReleaseEvent.class, event -> setEnabled(false));
    }

    void setEnabled(boolean value) {
        enabled = value;
        generation++;
    }

    private static char closerFor(char opener) {
        switch (opener) {
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            case '"': return '"';
            case '\'': return '\'';
            default: return 0;
        }
    }

    private void onChanged(ContentChangeEvent event) {
        if (!enabled || event.isCausedByUndoManager()
            || event.getAction() != ContentChangeEvent.ACTION_DELETE
            || event.getChangedText().length() != 1
            || event.getChangeStart().line != event.getChangeEnd().line) {
            return;
        }

        final char closer = closerFor(event.getChangedText().charAt(0));
        if (closer == 0) return;
        final int line = event.getChangeStart().line;
        final int column = event.getChangeStart().column;
        final Content originalDocument = editor.getText();
        if (line < 0 || line >= originalDocument.getLineCount()) return;

        final String lineText = originalDocument.getLineString(line);
        if (column < 0 || column >= lineText.length()
            || lineText.charAt(column) != closer) {
            return;
        }
        final long version = generation;

        // O Sora alerta para nao modificar Content dentro do evento.
        // A validacao impede apagar texto se o cursor/arquivo mudar antes
        // da execucao deste callback.
        editor.post(() -> {
            if (!enabled || version != generation
                || editor.getText() != originalDocument
                || editor.getCursor() == null || editor.getCursor().isSelected()
                || editor.getCursor().getLeftLine() != line
                || editor.getCursor().getLeftColumn() != column
                || line >= originalDocument.getLineCount()) {
                return;
            }
            String currentLine = originalDocument.getLineString(line);
            if (!currentLine.equals(lineText) || column >= currentLine.length()
                || currentLine.charAt(column) != closer) return;

            originalDocument.beginBatchEdit();
            try {
                originalDocument.delete(line, column, line, column + 1);
            } finally {
                originalDocument.endBatchEdit();
            }
            editor.setSelection(line, column, false);
        });
    }
}
