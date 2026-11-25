/*
 * CodeLove - recuo automatico de terminadores Lua no Sora.
 *
 * Trabalha somente na linha alterada e nunca modifica Content no
 * callback ContentChangeEvent. As alteracoes sao postadas para a UI.
 */
package org.love2d.android;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.event.ContentChangeEvent;
import io.github.rosemoe.sora.event.EditorReleaseEvent;
import io.github.rosemoe.sora.text.Content;

final class CodeLoveLuaOutdent {
    private static final Pattern TERMINATOR =
        Pattern.compile("^([ \t]*)(end|else|elseif|until)$");

    private final io.github.rosemoe.sora.widget.CodeEditor editor;
    private boolean enabled;
    private int adjustedLine = -1;
    private String adjustedKeyword;

    CodeLoveLuaOutdent(io.github.rosemoe.sora.widget.CodeEditor editor) {
        this.editor = editor;
        editor.subscribeAlways(ContentChangeEvent.class, this::onChanged);
        editor.subscribeAlways(EditorReleaseEvent.class, event -> setEnabled(false));
    }

    void setEnabled(boolean value) {
        enabled = value;
        adjustedLine = -1;
        adjustedKeyword = null;
    }

    private void onChanged(ContentChangeEvent event) {
        if (!enabled || event.getAction() != ContentChangeEvent.ACTION_INSERT
            || event.isCausedByUndoManager() || event.getChangedText().length() != 1) {
            return;
        }

        int line = event.getChangeStart().line;
        if (line != event.getChangeEnd().line) return;
        Content content = editor.getText();
        if (line < 0 || line >= content.getLineCount()) return;

        // Nao varrer o arquivo: apenas a linha que acaba de ser editada.
        String snapshot = content.getLineString(line);
        Matcher matcher = TERMINATOR.matcher(snapshot);
        if (!matcher.matches()) return;
        String indent = matcher.group(1);
        String keyword = matcher.group(2);
        if (indent.isEmpty()) return;

        // "elseif" comeca por "else": somente um recuo por linha.
        if (line == adjustedLine && "else".equals(adjustedKeyword)
            && "elseif".equals(keyword)) return;

        String reduced = removeIndentLevel(indent);
        if (reduced.equals(indent)) return;

        // O cursor so fica estavel ao final do evento. Validar todo o
        // contexto antes de aplicar a edicao, para evitar alteracoes atrasadas.
        editor.post(() -> {
            if (!enabled || editor.getCursor() == null
                || editor.getCursor().isSelected()
                || editor.getCursor().getLeftLine() != line
                || editor.getCursor().getLeftColumn() != snapshot.length()) return;
            Content now = editor.getText();
            if (line < 0 || line >= now.getLineCount()
                || !snapshot.equals(now.getLineString(line))) return;
            if (line == adjustedLine && "else".equals(adjustedKeyword)
                && "elseif".equals(keyword)) return;

            now.beginBatchEdit();
            try {
                now.delete(line, reduced.length(), line, indent.length());
            } finally {
                now.endBatchEdit();
            }
            editor.setSelection(line, snapshot.length()
                - (indent.length() - reduced.length()), false);
            adjustedLine = line;
            adjustedKeyword = keyword;
        });
    }

    private static String removeIndentLevel(String indent) {
        if (indent.endsWith("\t")) {
            return indent.substring(0, indent.length() - 1);
        }
        if (indent.endsWith("    ")) {
            return indent.substring(0, indent.length() - 4);
        }
        int count = 0;
        for (int i = indent.length() - 1; i >= 0 && indent.charAt(i) == ' '; i--) {
            count++;
        }
        return count == 0 ? indent
            : indent.substring(0, indent.length() - Math.min(4, count));
    }
}
