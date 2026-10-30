/*
 * CodeLove - editor de codigo.
 *
 * Abre um arquivo de texto, mostra com numeros de linha e cores (Lua), salva
 * sozinho pouco depois de voce parar de digitar e avisa quando salvou.
 *
 * Baseado no LOVE para Android (licenca zlib). Versao alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class CodeEditor {
    interface Listener {
        /** Chamado depois que o arquivo foi gravado no disco. */
        void onSaved(File file);

        /** Chamado quando o editor foi fechado. */
        void onClosed();
    }

    private static final String TAG = "CodeLove";
    private static final int SAVE_DELAY_MS = 900;
    private static final long MAX_BYTES = 512L * 1024L;

    private static final int COLOR_KEYWORD = 0xFFC4A5FF;
    private static final int COLOR_NUMBER = 0xFFF2A65A;
    private static final int COLOR_STRING = 0xFF7EE0B5;
    private static final int COLOR_COMMENT = 0xFF6B7189;

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout view;
    private final TextView title;
    private final TextView dirtyMark;
    private final TextView gutter;
    private final EditText input;
    private final ScrollView scroll;

    private final Runnable saveTask = new Runnable() {
        @Override
        public void run() {
            saveNow();
        }
    };

    private File file;           // arquivo aberto (nulo se nenhum)
    private boolean dirty;       // tem alteracao ainda nao gravada
    private boolean loading;     // true enquanto trocamos o texto por codigo
    private boolean highlightLua;
    private int lineCount;

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

        title = new TextView(activity);
        title.setTextColor(Workspace.COLOR_TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dirtyMark = new TextView(activity);
        dirtyMark.setText("\u25CF");
        dirtyMark.setTextColor(Workspace.COLOR_FOLDER);
        dirtyMark.setTextSize(12);
        dirtyMark.setPadding(dp(6), 0, dp(6), 0);
        dirtyMark.setVisibility(View.GONE);
        header.addView(dirtyMark);

        TextView close = new TextView(activity);
        close.setText("\u00D7");
        close.setGravity(Gravity.CENTER);
        close.setTextColor(Workspace.COLOR_MUTED);
        close.setTextSize(22);
        close.setBackground(box(Workspace.COLOR_FIELD, 10, false));
        close.setOnClickListener(v -> close());
        header.addView(close, new LinearLayout.LayoutParams(dp(32), dp(32)));

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
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (loading) {
                    return;
                }
                dirty = true;
                refreshDirtyMark();
                applyHighlight(s);
                refreshGutter(s, false);
                input.removeCallbacks(saveTask);
                input.postDelayed(saveTask, SAVE_DELAY_MS);
            }
        });
        // Sair do editor (tocar em outra coisa) tambem salva na hora.
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                saveNow();
            }
        });

        HorizontalScrollView horizontal = new HorizontalScrollView(activity);
        horizontal.setFillViewport(true);
        horizontal.addView(input, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(gutter, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(horizontal, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        scroll = new ScrollView(activity);
        scroll.addView(row, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        view.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

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

    boolean isOpen() {
        return file != null;
    }

    File getFile() {
        return file;
    }

    /** Abre um arquivo no editor. Devolve false se nao for um texto que caiba aqui. */
    boolean open(File target) {
        saveNow();

        String text = readText(target);
        if (text == null) {
            return false;
        }

        file = target;
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

    /** Grava, fecha o arquivo e avisa. */
    void close() {
        saveNow();
        file = null;
        loading = true;
        input.setText("");
        loading = false;
        dirty = false;
        refreshDirtyMark();
        listener.onClosed();
    }

    /** Grava agora, se houver alteracao pendente. */
    void saveNow() {
        input.removeCallbacks(saveTask);
        if (file == null || !dirty) {
            return;
        }

        byte[] data = input.getText().toString().getBytes(StandardCharsets.UTF_8);
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");

        boolean written = false;
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            written = true;
        } catch (IOException e) {
            Log.w(TAG, "Falha ao gravar " + temp, e);
        }

        if (written && temp.renameTo(file)) {
            // gravado com troca atomica
        } else {
            temp.delete();
            // plano B: grava direto no arquivo
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(data);
            } catch (IOException e) {
                Log.w(TAG, "Falha ao gravar " + file, e);
                return;
            }
        }

        dirty = false;
        refreshDirtyMark();
        listener.onSaved(file);
    }

    // ------------------------------------------------------------------

    private void refreshDirtyMark() {
        dirtyMark.setVisibility(dirty ? View.VISIBLE : View.GONE);
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
