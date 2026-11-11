/*
 * CodeLove - highlight Lua / LOVE2D no motor Sora.
 *
 * Leitura e analise acontecem na thread do SimpleAnalyzeManager; nenhuma
 * coloracao e aplicada por spans do Android EditText durante a digitacao.
 */
package org.love2d.android;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.rosemoe.sora.lang.EmptyLanguage;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.analysis.SimpleAnalyzeManager;
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

    private final AnalyzeManager analyzer = new LuaAnalyzer();

    @Override
    public AnalyzeManager getAnalyzeManager() {
        return analyzer;
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
