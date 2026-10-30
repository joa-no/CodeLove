/*
 * CodeLove - reconhecimento simples de codigo Lua (para colorir no editor).
 * Nao depende do Android, so de Java comum.
 */

package org.love2d.android;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LuaSyntax {
    static final int KEYWORD = 0;
    static final int NUMBER = 1;
    static final int STRING = 2;
    static final int COMMENT = 3;

    private static final Set<String> KEYWORDS = new HashSet<String>(Arrays.asList(
        "and", "break", "do", "else", "elseif", "end", "false", "for", "function", "goto",
        "if", "in", "local", "nil", "not", "or", "repeat", "return", "then", "true",
        "until", "while"));

    private LuaSyntax() {
    }

    /** Devolve trechos {inicio, fim, tipo} em ordem, sem sobreposicao. */
    static List<int[]> scan(String text) {
        List<int[]> out = new ArrayList<int[]>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);

            if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
                // comentario: de bloco --[[ ... ]] ou de linha
                int end;
                int level = longBracketLevel(text, i + 2);
                if (level >= 0) {
                    end = closeLongBracket(text, i + 2, level);
                } else {
                    end = text.indexOf('\n', i);
                    if (end < 0) {
                        end = n;
                    }
                }
                out.add(new int[]{i, end, COMMENT});
                i = end;
                continue;
            }

            if (c == '"' || c == '\'') {
                int end = scanQuoted(text, i, c);
                out.add(new int[]{i, end, STRING});
                i = end;
                continue;
            }

            if (c == '[') {
                int level = longBracketLevel(text, i);
                if (level >= 0) {
                    int end = closeLongBracket(text, i, level);
                    out.add(new int[]{i, end, STRING});
                    i = end;
                    continue;
                }
            }

            if (isDigit(c) && (i == 0 || !isIdentChar(text.charAt(i - 1)))) {
                int end = scanNumber(text, i);
                out.add(new int[]{i, end, NUMBER});
                i = end;
                continue;
            }

            if (isIdentStart(c)) {
                int end = i + 1;
                while (end < n && isIdentChar(text.charAt(end))) {
                    end++;
                }
                if (KEYWORDS.contains(text.substring(i, end))) {
                    out.add(new int[]{i, end, KEYWORD});
                }
                i = end;
                continue;
            }

            i++;
        }
        return out;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
    }

    private static boolean isIdentChar(char c) {
        return isIdentStart(c) || isDigit(c);
    }

    /** Se em pos comeca "[", "[=[", "[==["..., devolve o numero de "="; senao -1. */
    private static int longBracketLevel(String text, int pos) {
        int n = text.length();
        if (pos >= n || text.charAt(pos) != '[') {
            return -1;
        }
        int j = pos + 1;
        int level = 0;
        while (j < n && text.charAt(j) == '=') {
            level++;
            j++;
        }
        return (j < n && text.charAt(j) == '[') ? level : -1;
    }

    /** Devolve a posicao logo depois do fecho "]=*]", ou o fim do texto se faltar. */
    private static int closeLongBracket(String text, int openPos, int level) {
        StringBuilder closer = new StringBuilder("]");
        for (int k = 0; k < level; k++) {
            closer.append('=');
        }
        closer.append(']');
        int start = openPos + level + 2;
        int idx = text.indexOf(closer.toString(), start);
        return idx < 0 ? text.length() : idx + closer.length();
    }

    private static int scanQuoted(String text, int start, char quote) {
        int n = text.length();
        int j = start + 1;
        while (j < n) {
            char c = text.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                return j + 1;
            }
            if (c == '\n') {
                return j; // texto sem fechar: acaba na quebra de linha
            }
            j++;
        }
        return n;
    }

    private static int scanNumber(String text, int start) {
        int n = text.length();
        int j = start;
        if (text.charAt(j) == '0' && j + 1 < n && (text.charAt(j + 1) == 'x' || text.charAt(j + 1) == 'X')) {
            j += 2;
            while (j < n && Character.digit(text.charAt(j), 16) >= 0) {
                j++;
            }
            return j;
        }
        while (j < n && isDigit(text.charAt(j))) {
            j++;
        }
        if (j + 1 < n && text.charAt(j) == '.' && isDigit(text.charAt(j + 1))) {
            j++;
            while (j < n && isDigit(text.charAt(j))) {
                j++;
            }
        }
        if (j < n && (text.charAt(j) == 'e' || text.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (text.charAt(k) == '+' || text.charAt(k) == '-')) {
                k++;
            }
            if (k < n && isDigit(text.charAt(k))) {
                while (k < n && isDigit(text.charAt(k))) {
                    k++;
                }
                j = k;
            }
        }
        return j;
    }
}
