/*
 * CodeLove - editor de codigo.
 *
 * Abre arquivos de texto, mostra numeros de linha e cores (Lua), e grava
 * alteracoes somente quando o usuario aciona o botao Salvar.
 *
 * Baseado no LOVE para Android (licenca zlib). Versao alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class CodeEditor {
    interface Storage {
        String readText(File file, long maxBytes) throws IOException;
        boolean writeText(File file, String text);
    }

    interface Listener {
        /** Chamado quando o usuario altera o texto do editor. */
        void onEdited();

        /** Chamado depois que o arquivo foi gravado no disco. */
        void onSaved(File file);

        /** Chamado quando o editor foi fechado. */
        void onClosed();

    }

    private static final String TAG = "CodeLove";
    private static final long MAX_BYTES = 512L * 1024L;

    private static final int COLOR_KEYWORD = 0xFFC4A5FF;
    private static final int COLOR_NUMBER = 0xFFF2A65A;
    private static final int COLOR_STRING = 0xFF7EE0B5;
    private static final int COLOR_COMMENT = 0xFF6B7189;

    private final Activity activity;
    private final Listener listener;
    private Storage storage;
    private final LinearLayout view;
    private final TextView title;
    private final TextView dirtyMark;
    private final TextView gutter;
    private final EditText input;
    private final ScrollView scroll;
    private final LinearLayout footer;
    private final View footerSpacer;

    private File file;           // arquivo aberto (nulo se nenhum)
    private String originalText; // conteudo lido na abertura, usado para detectar conflito externo
    private boolean dirty;       // tem alteracao ainda nao gravada no arquivo original
    private boolean saveFailureNotified; // evita repetir o mesmo aviso a cada tentativa
    private boolean loading;     // true enquanto trocamos o texto por codigo
    private boolean applyingAutoIndent;
    private int changeStart;
    private int changeBefore;
    private int changeAfter;
    private boolean highlightLua;
    private int lineCount;
    private long followUntil;    // ate quando o editor pode seguir o cursor

    CodeEditor(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setBackground(box(Workspace.COLOR_PANEL, 16, true));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ---- cabecalho: nome do arquivo, marca de "nao salvo" e botao de fechar ----
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        dirtyMark = new TextView(activity);
        dirtyMark.setText("\u25CF");
        dirtyMark.setTextColor(Workspace.COLOR_FOLDER);
        dirtyMark.setTextSize(12);
        dirtyMark.setPadding(0, 0, dp(6), 0);
        dirtyMark.setVisibility(View.GONE);
        header.addView(dirtyMark);

        title = new TextView(activity);
        title.setTextColor(Workspace.COLOR_TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // ---- rodape: expandir e fechar (ficam perto dos dedos) ----
        footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        // Reserva o espaco entre os controles do projeto e o botao de fechar.
        footerSpacer = new View(activity);
        footer.addView(footerSpacer, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        TextView close = new TextView(activity);
        close.setText("\u00D7");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(Workspace.COLOR_TEXT);
        close.setTextSize(24);
        close.setBackground(box(Workspace.COLOR_FIELD, 12, false));
        close.setOnClickListener(v -> close());
        footer.addView(close, new LinearLayout.LayoutParams(dp(52), dp(44)));

        view.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(activity);
        divider.setBackgroundColor(Workspace.COLOR_BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerLp.setMargins(0, dp(10), 0, dp(6));
        view.addView(divider, dividerLp);

        // ---- area de texto: numeros de linha + codigo (rola na vertical e na horizontal) ----
        gutter = new TextView(activity);
        gutter.setTypeface(Typeface.MONOSPACE);
        gutter.setTextSize(13);
        gutter.setTextColor(Workspace.COLOR_MUTED);
        gutter.setGravity(Gravity.END | Gravity.TOP);
        gutter.setIncludeFontPadding(false);
        gutter.setPadding(dp(2), dp(4), dp(10), dp(4));
        gutter.setMinWidth(dp(34));

        input = new EditText(activity);
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(13);
        input.setTextColor(Workspace.COLOR_TEXT);
        input.setHighlightColor(0x665B6EE1);
        input.setBackground(null);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setIncludeFontPadding(false);
        input.setPadding(dp(4), dp(4), dp(24), dp(4));
        input.setHorizontallyScrolling(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        tintCursor(input);

        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                changeStart = start;
                changeBefore = count;
                changeAfter = after;
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading || applyingAutoIndent) {
                    return;
                }

                // Ao inserir uma quebra de linha, preserva a indentacao atual
                // e avanca um nivel quando a linha anterior abre um bloco Lua.
                // Colagens de varias linhas continuam intactas.
                if (changeAfter == 1 && changeStart < s.length()
                    && s.charAt(changeStart) == '\n'
                    && input.getSelectionStart() == changeStart + 1) {
                    String indent = indentationForNewLine(s, changeStart);
                    if (!indent.isEmpty()) {
                        applyingAutoIndent = true;
                        try {
                            s.insert(changeStart + 1, indent);
                            input.setSelection(changeStart + 1 + indent.length());
                        } finally {
                            applyingAutoIndent = false;
                        }
                    }
                }

                dirty = true;
                listener.onEdited();
                saveFailureNotified = false;
                followUntil = SystemClock.uptimeMillis() + 500; // digitando: pode seguir o cursor
                refreshDirtyMark();
                applyHighlight(s);
                refreshGutter(s, false);
            }
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_TAB && event.getAction() == KeyEvent.ACTION_DOWN) {
                int start = input.getSelectionStart();
                int end = input.getSelectionEnd();
                if (start != end || start < 0) {
                    return false;
                }
                input.getText().insert(start, "    ");
                input.setSelection(start + 4);
                return true;
            }
            return false;
        });

        HorizontalScrollView horizontal = new CaretGuardHorizontalScrollView(activity);
        horizontal.setFillViewport(true);
        horizontal.addView(input, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(gutter, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(horizontal, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scroll = new CaretGuardScrollView(activity);
        scroll.addView(row, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Rodape (expandir / fechar), logo acima do teclado quando ele esta aberto.
        View footerDivider = new View(activity);
        footerDivider.setBackgroundColor(Workspace.COLOR_BORDER);
        LinearLayout.LayoutParams footerDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        footerDividerLp.setMargins(0, dp(6), 0, dp(8));
        view.addView(footerDivider, footerDividerLp);
        view.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // O texto ocupa pelo menos a altura visivel (para tocar na parte vazia).
        scroll.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int height = b - t;
            input.setMinHeight(height);
            gutter.setMinHeight(height);
        });
    }

    View getView() {
        return view;
    }

    /** Adiciona um controle na barra inferior, antes do espaco e do botao de fechar. */
    void addFooterControl(View control, int widthDp, int gapDp) {
        int index = footer.indexOfChild(footerSpacer);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            dp(widthDp), dp(44));
        if (gapDp > 0) {
            params.setMargins(dp(gapDp), 0, 0, 0);
        }
        footer.addView(control, index, params);
    }

    boolean isOpen() {
        return file != null;
    }

    File getFile() {
        return file;
    }

    void setStorage(Storage storage) {
        this.storage = storage;
    }

    /** Abre outro arquivo somente depois de preservar as alteracoes atuais. */
    boolean open(File target) {
        if (dirty) {
            notifyUnsavedChanges();
            return false;
        }

        String text;
        try {
            text = storage != null ? storage.readText(target, MAX_BYTES) : readText(target);
        } catch (IOException e) {
            Log.w(TAG, "Falha ao ler " + target, e);
            return false;
        }
        if (text == null) {
            return false;
        }

        file = target;
        originalText = text;
        highlightLua = target.getName().toLowerCase(Locale.ROOT).endsWith(".lua");

        loading = true;
        input.setText(text);
        input.setSelection(0);
        loading = false;

        dirty = false;
        refreshDirtyMark();
        title.setText(target.getName());
        applyHighlight(input.getText());
        refreshGutter(input.getText(), true);
        scroll.scrollTo(0, 0);
        return true;
    }

    /** Fecha somente se todas as alteracoes puderem ser gravadas. */
    boolean close() {
        if (dirty) {
            notifyUnsavedChanges();
            return false;
        }
        file = null;
        originalText = null;
        loading = true;
        input.setText("");
        loading = false;
        dirty = false;
        refreshDirtyMark();
        listener.onClosed();
        return true;
    }

    /** Informa se ainda ha alteracoes que nao foram gravadas com sucesso. */
    boolean hasUnsavedChanges() {
        return dirty;
    }

    /** Grava agora, mas nunca sobrescreve silenciosamente uma alteracao externa. */
    boolean saveNow() {
        if (file == null || !dirty) {
            return true;
        }

        final String text = input.getText().toString();
        final String current;
        try {
            current = storage != null ? storage.readText(file, MAX_BYTES) : readText(file);
        } catch (IOException e) {
            Log.w(TAG, "Nao foi possivel verificar o arquivo antes de salvar: " + file, e);
            notifySaveFailure();
            return false;
        }

        if (current == null) {
            notifySaveFailure();
            return false;
        }

        if (originalText == null || !originalText.equals(current)) {
            new AlertDialog.Builder(activity)
                .setTitle("Arquivo alterado fora do CodeLove")
                .setMessage("Este arquivo foi modificado em outro lugar desde que voce o abriu. Se salvar agora, essas alteracoes externas serao substituidas pelo texto deste editor.")
                .setNegativeButton("Cancelar", (dialog, which) -> { })
                .setPositiveButton("Sobrescrever", (dialog, which) -> writeTextNow(text))
                .show();
            return false;
        }

        return writeTextNow(text);
    }

    private boolean writeTextNow(String text) {
        if (file == null) return false;

        if (storage != null) {
            if (!storage.writeText(file, text)) {
                notifySaveFailure();
                return false;
            }
        } else {
            File parent = file.getParentFile();
            File temp = new File(parent, file.getName() + ".tmp");
            byte[] data = text.getBytes(StandardCharsets.UTF_8);

            try {
                if (parent == null || !parent.isDirectory()) {
                    throw new IOException("A pasta do arquivo nao esta disponivel.");
                }

                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(data);
                    out.flush();
                    out.getFD().sync();
                }

                if (!temp.renameTo(file)) {
                    throw new IOException("Nao foi possivel substituir o arquivo original.");
                }
            } catch (IOException e) {
                Log.w(TAG, "Falha ao salvar " + file, e);
                temp.delete();
                notifySaveFailure();
                return false;
            }
        }

        originalText = text;
        dirty = false;
        saveFailureNotified = false;
        refreshDirtyMark();
        listener.onSaved(file);
        return true;
    }

    private void notifyUnsavedChanges() {
        Toast.makeText(activity,
            "Salve o arquivo pelo botao Salvar antes de troca-lo ou fecha-lo.",
            Toast.LENGTH_LONG).show();
    }

    private void notifySaveFailure() {
        if (saveFailureNotified) {
            return;
        }
        saveFailureNotified = true;
        Toast.makeText(activity,
            "Nao foi possivel salvar. Suas alteracoes continuam no editor.",
            Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------

    private boolean followCaret() {
        return SystemClock.uptimeMillis() < followUntil;
    }

    /**
     * O campo de texto pede para rolar ate o cursor o tempo todo, e isso "puxava"
     * a tela de volta quando voce rolava para ver o resto do codigo. Aqui so
     * deixamos ele seguir o cursor logo depois de voce digitar.
     */
    private final class CaretGuardScrollView extends ScrollView {
        CaretGuardScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            return followCaret() && super.requestChildRectangleOnScreen(child, rectangle, immediate);
        }
    }

    private final class CaretGuardHorizontalScrollView extends HorizontalScrollView {
        CaretGuardHorizontalScrollView(Context context) {
            super(context);
        }

        @Override
        public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
            return followCaret() && super.requestChildRectangleOnScreen(child, rectangle, immediate);
        }
    }

    /**
     * Calcula a indentacao da nova linha. Mantem os espacos/tabs existentes
     * e adiciona um nivel para os principais iniciadores de bloco do Lua.
     */
    private static String indentationForNewLine(CharSequence text, int newlinePosition) {
        int lineStart = newlinePosition - 1;
        while (lineStart >= 0 && text.charAt(lineStart) != '\n') {
            lineStart--;
        }
        lineStart++;

        int indentEnd = lineStart;
        while (indentEnd < newlinePosition
            && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) {
            indentEnd++;
        }

        String indent = text.subSequence(lineStart, indentEnd).toString();
        String previousLine = text.subSequence(indentEnd, newlinePosition).toString().trim();
        String code = previousLine.replaceAll("--.*$", "").trim();

        boolean opensBlock = code.matches("(?s).*\\b(then|do|else|repeat)\\s*$")
            || code.matches("(?s).*\\bfunction\\s*(?:[\\w_.:]*\\s*)?\\([^)]*\\)\\s*$");

        return opensBlock ? indent + "    " : indent;
    }

    private void refreshDirtyMark() {
        dirtyMark.setVisibility(dirty ? View.VISIBLE : View.GONE);
        String hint = dirty ? "Alterações ainda não salvas" : "";
        dirtyMark.setContentDescription(hint);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            dirtyMark.setTooltipText(dirty ? hint : null);
        }
    }

    private void refreshGutter(CharSequence text, boolean force) {
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        if (!force && lines == lineCount) {
            return;
        }
        lineCount = lines;
        StringBuilder numbers = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            if (i > 1) {
                numbers.append('\n');
            }
            numbers.append(i);
        }
        gutter.setText(numbers.toString());
    }

    private void applyHighlight(Editable text) {
        ForegroundColorSpan[] old = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : old) {
            text.removeSpan(span);
        }
        if (!highlightLua) {
            return;
        }
        List<int[]> parts = LuaSyntax.scan(text.toString());
        for (int[] part : parts) {
            text.setSpan(new ForegroundColorSpan(colorFor(part[2])),
                part[0], part[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private static int colorFor(int type) {
        switch (type) {
            case LuaSyntax.KEYWORD:
                return COLOR_KEYWORD;
            case LuaSyntax.NUMBER:
                return COLOR_NUMBER;
            case LuaSyntax.STRING:
                return COLOR_STRING;
            default:
                return COLOR_COMMENT;
        }
    }

    /** Le um arquivo de texto. Devolve nulo se for grande demais ou binario. */
    private static String readText(File target) {
        if (!target.isFile() || target.length() > MAX_BYTES) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(target)) {
            byte[] data = new byte[(int) target.length()];
            int total = 0;
            while (total < data.length) {
                int read = in.read(data, total, data.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            for (int i = 0; i < total; i++) {
                if (data[i] == 0) {
                    return null; // tem byte nulo: e binario
                }
            }
            return new String(data, 0, total, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** No tema escuro o cursor padrao some; pinta de claro (Android 10 em diante). */
    private static void tintCursor(EditText editText) {
        if (Build.VERSION.SDK_INT >= 29) {
            Drawable cursor = editText.getTextCursorDrawable();
            if (cursor != null) {
                cursor = cursor.mutate();
                cursor.setTint(Workspace.COLOR_TEXT);
                editText.setTextCursorDrawable(cursor);
            }
        }
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
