/*
 * CodeLove - highlight Lua / LOVE2D no motor Sora.
 *
 * Leitura e analise acontecem na thread do SimpleAnalyzeManager; nenhuma
 * coloracao e aplicada por spans do Android EditText durante a digitacao.
 */
package org.love2d.android;

import android.os.Bundle;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.completion.SimpleCompletionItem;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import io.github.rosemoe.sora.lang.styling.MappedSpans;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;

final class CodeLoveLuaLanguage extends EmptyLanguage {
    static final int KEYWORD_COLOR_ID = 201;
    static final int NUMBER_COLOR_ID = 202;
    static final int STRING_COLOR_ID = 203;
    static final int COMMENT_COLOR_ID = 204;

    private static final Pattern BLOCK_KEYWORD = Pattern.compile(
        "(?s).*\\b(then|do|else|repeat)\\s*$");
    private static final Pattern FUNCTION_HEADER = Pattern.compile(
        "(?s).*\\bfunction\\s*(?:[\\w_.:]*\\s*)?\\([^)]*\\)\\s*$");

    private final AnalyzeManager analyzer = new LuaAnalyzer();
    private final SymbolPairMatch symbolPairs = new SymbolPairMatch.DefaultSymbolPairs();
    private final NewlineHandler[] newlineHandlers =
        new NewlineHandler[] { new EmptyTableNewlineHandler() };

    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
    }

    /** Usa a insercao nativa do Sora para (), [], {}, "", ''. */
    @Override
    public SymbolPairMatch getSymbolPairs() {
        return symbolPairs;
    }

    /**
     * O Sora ja preserva o recuo anterior: devolver somente o incremento.
     * O calculo ocorre apenas quando o usuario insere Enter.
     */
    @Override
    public int getIndentAdvance(ContentReference content, int line, int column) {
        String currentLine = content.getLine(line);
        String beforeCaret = currentLine.substring(
            0, Math.max(0, Math.min(column, currentLine.length())));
        int commentStart = beforeCaret.indexOf("--");
        if (commentStart >= 0) {
            beforeCaret = beforeCaret.substring(0, commentStart);
        }
        String code = beforeCaret.trim();
        if (code.isEmpty()) return 0;
        return (BLOCK_KEYWORD.matcher(code).matches()
            || FUNCTION_HEADER.matcher(code).matches()
            || code.endsWith("{")) ? 4 : 0;
    }

    @Override
    public NewlineHandler[] getNewlineHandlers() {
        return newlineHandlers;
    }

    /**
     * Enter entre chaves vazias:
     * {│} -> {
     *          │
     *         }
     * Mantem a chave de fechamento alinhada ao recuo inicial.
     */
    private static final class EmptyTableNewlineHandler implements NewlineHandler {
        @Override
        public boolean matchesRequirement(Content content, CharPosition position,
                                          Styles style) {
            int cursor = position.index;
            return cursor > 0 && cursor < content.length()
                && content.charAt(cursor - 1) == '{'
                && content.charAt(cursor) == '}';
        }

        @Override
        public NewlineHandleResult handleNewline(Content content, CharPosition position,
                                                 Styles style, int tabSize) {
            String line = content.getLineString(position.line);
            int count = 0;
            while (count < position.column && count < line.length()) {
                char ch = line.charAt(count);
                if (ch != ' ' && ch != '\t') break;
                count++;
            }
            String baseIndent = line.substring(0, count);
            String insert = "\n" + baseIndent + "    " + "\n" + baseIndent;
            // Desloca o cursor para a linha vazia, antes de "\n}".
            return new NewlineHandleResult(insert, 1 + baseIndent.length());
        }
    }

    /**
     * O autocomplete usa a thread de completions propria do Sora, sem
     * percorrer o documento ou criar popups no TextWatcher da UI.
     * Preserva o catalogo Lua + LOVE usado anteriormente no CodeLove.
     */
    @Override
    public void requireAutoComplete(ContentReference content, CharPosition position,
                                    CompletionPublisher publisher, Bundle extraArguments) {
        if (position.column <= 0) return;
        String prefix = CompletionHelper.computePrefix(content, position,
            ch -> Character.isLetterOrDigit(ch) || ch == '_' || ch == '.');
        if (prefix.isEmpty()) return;

        final int maxResults = 24;
        int count = 0;
        for (String candidate : LoveApiCatalog.SUGGESTIONS) {
            publisher.checkCancelled();
            if (!candidate.startsWith(prefix) || candidate.equals(prefix)) continue;
            publisher.addItem(new SimpleCompletionItem(candidate, prefix.length(), candidate));
            if (++count >= maxResults) break;
        }
    }

    private static int colorIdFor(int kind) {
        switch (kind) {
            case LuaSyntax.KEYWORD: return KEYWORD_COLOR_ID;
            case LuaSyntax.NUMBER: return NUMBER_COLOR_ID;
            case LuaSyntax.STRING: return STRING_COLOR_ID;
            case LuaSyntax.COMMENT: return COMMENT_COLOR_ID;
            default: return EditorColorScheme.TEXT_NORMAL;
        }
    }

    /** Mapeia trechos absolutos do lexer para spans por linha do Sora. */
    private static final class LuaAnalyzer extends SimpleAnalyzeManager<Void> {
        @Override
        protected Styles analyze(StringBuilder content, Delegate<Void> delegate) {
            final int length = content.length();
            final List<int[]> tokens = LuaSyntax.scan(content.toString());
            final MappedSpans.Builder builder = new MappedSpans.Builder();
            final int defaultColor = EditorColorScheme.TEXT_NORMAL;
            int line = 0;
            int lineStart = 0;
            int tokenIndex = 0;

            while (true) {
                if (delegate.isCancelled()) return null;

                int lineEnd = lineStart;
                while (lineEnd < length && content.charAt(lineEnd) != '\n') lineEnd++;

                while (tokenIndex < tokens.size()
                    && tokens.get(tokenIndex)[1] <= lineStart) {
                    tokenIndex++;
                }

                // Em cada linha, o ultimo estilo de uma coluna prevalece.
                // Isso evita dois spans na mesma coluna quando tokens sao adjacentes.
                TreeMap<Integer, Integer> transitions = new TreeMap<>();
                transitions.put(0, defaultColor);

                for (int j = tokenIndex; j < tokens.size(); j++) {
                    int[] token = tokens.get(j);
                    if (token[0] >= lineEnd) break;
                    int start = Math.max(lineStart, token[0]);
                    int end = Math.min(lineEnd, token[1]);
                    if (end <= start) continue;

                    transitions.put(start - lineStart, colorIdFor(token[2]));
                    if (end < lineEnd) transitions.put(end - lineStart, defaultColor);
                }

                int previousColor = -1;
                for (Map.Entry<Integer, Integer> transition : transitions.entrySet()) {
                    int colorId = transition.getValue();
                    if (colorId == previousColor) continue;
                    builder.add(line, Span.obtain(transition.getKey(),
                        TextStyle.makeStyle(colorId)));
                    previousColor = colorId;
                }

                if (lineEnd >= length) break;
                lineStart = lineEnd + 1;
                line++;
            }

            builder.determine(line);
            return new Styles(builder.build());
        }
    }
}
