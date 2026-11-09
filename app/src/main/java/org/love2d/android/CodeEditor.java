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
import android.text.SpannableString;
import android.text.Spanned;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
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
        void onFilesRequested();
        void onActiveTabChanged();

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
    private final LinearLayout header;
    private final TextView title;
    private final LinearLayout emptyState;
    private final View footerDivider;
    private final TextView filesMenuButton;
    private final HorizontalScrollView tabsScroller;
    private final LinearLayout tabsRow;
    private final LinkedHashMap<String, TabState> openTabs = new LinkedHashMap<>();
    private boolean portraitTabs;
    private final TextView dirtyMark;
    private final TextView gutter;
    private final EditText input;
    private PopupWindow autocompletePopup;
    private TextView autocompleteText;
    private String autocompleteCandidate;
    private PopupWindow parameterHintPopup;
    private TextView parameterHintText;
    private final ScrollView scroll;
    private final LinearLayout footer;
    private final View footerSpacer;

    /** Estado de uma aba enquanto outro arquivo e editado. */
    private static final class TabState {
        final File file;
        final Storage storage;
        String text;
        String original;
        boolean dirty;
        int selection;
        int scrollY;
        TabState(File file, Storage storage, String text) {
            this.file = file;
            this.storage = storage;
            this.text = text;
            this.original = text;
        }
    }

    private File file;           // arquivo aberto (nulo se nenhum)
    private String originalText; // conteudo lido na abertura, usado para detectar conflito externo
    private boolean dirty;       // tem alteracao ainda nao gravada no arquivo original
    private boolean saveFailureNotified; // evita repetir o mesmo aviso a cada tentativa
    private boolean loading;     // true enquanto trocamos o texto por codigo
    private boolean applyingAutoIndent;
    private int changeStart;
    private int changeBefore;
    private int changeAfter;
    private String changeRemoved = "";
    private boolean highlightLua;
    private static final long DELETION_ASSIST_IDLE_DELAY_MS = 220L;
    private final Runnable pendingDeletionAssistance = this::runPendingDeletionAssistance;
    private int lineCount;
    private long followUntil;    // ate quando o editor pode seguir o cursor

    // Evita recalcular todo o destaque Lua dentro de cada evento do teclado.
    // Uma pausa curta permite agrupar insercoes, exclusoes e pares automaticos.
    private static final long HIGHLIGHT_IDLE_DELAY_MS = 180L;
    private final Runnable pendingHighlight = this::applyPendingHighlight;

    private void scheduleHighlight() {
        input.removeCallbacks(pendingHighlight);
        if (file != null && highlightLua) {
            input.postDelayed(pendingHighlight, HIGHLIGHT_IDLE_DELAY_MS);
        }
    }

    private void applyPendingHighlight() {
        if (file != null && highlightLua) {
            applyHighlight(input.getText());
        }
    }

    // Durante Backspace continuo, evita recalcular e movimentar popups
    // a cada caractere. Os auxilios voltam apos uma pausa curta.
    private void runPendingDeletionAssistance() {
        if (file != null && highlightLua) {
            updateAutocompleteSuggestion(input.getText());
            updateParameterHint(input.getText());
        }
    }

    private void scheduleDeletionAssistance() {
        dismissAutocompleteSuggestion();
        dismissParameterHint();
        input.removeCallbacks(pendingDeletionAssistance);
        input.postDelayed(pendingDeletionAssistance, DELETION_ASSIST_IDLE_DELAY_MS);
    }

    private void cancelDeletionAssistance() {
        input.removeCallbacks(pendingDeletionAssistance);
    }

    CodeEditor(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;

        view = new LinearLayout(activity);
        view.setOrientation(LinearLayout.VERTICAL);
        // O editor acompanha os paineis: superficie retangular, sem moldura
        // arredondada ou borda externa. O espacamento interno do conteudo permanece.
        view.setBackground(box(Workspace.COLOR_PANEL, 0, false));
        view.setPadding(dp(12), dp(12), dp(12), dp(12));

        // ---- cabecalho: nome do arquivo, marca de "nao salvo" e botao de fechar ----
        header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        dirtyMark = new TextView(activity);
        dirtyMark.setText("\u25CF");
        dirtyMark.setTextColor(Workspace.COLOR_FOLDER);
        dirtyMark.setTextSize(12);
        dirtyMark.setPadding(0, 0, dp(6), 0);
        dirtyMark.setVisibility(View.GONE);
        header.addView(dirtyMark);

        filesMenuButton = new TextView(activity);
        filesMenuButton.setText("\u2630");
        filesMenuButton.setTextColor(Workspace.COLOR_TEXT);
        filesMenuButton.setTextSize(24);
        filesMenuButton.setGravity(Gravity.CENTER);
        filesMenuButton.setContentDescription("Abrir pastas e arquivos");
        filesMenuButton.setBackground(box(Workspace.COLOR_FIELD, 10, false));
        filesMenuButton.setVisibility(View.GONE);
        filesMenuButton.setOnClickListener(v -> listener.onFilesRequested());
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(42), dp(40));
        menuLp.setMargins(0, 0, dp(10), 0);
        header.addView(filesMenuButton, menuLp);

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

        // Abas representam apenas os arquivos abertos, nao todos de uma pasta.
        tabsRow = new LinearLayout(activity);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsScroller = new HorizontalScrollView(activity);
        tabsScroller.setHorizontalScrollBarEnabled(false);
        tabsScroller.setBackgroundColor(Workspace.COLOR_OUTER);
        tabsScroller.addView(tabsRow, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tabsScroller.setVisibility(View.GONE);
        LinearLayout.LayoutParams tabsLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        tabsLp.setMargins(0, dp(8), 0, 0);
        view.addView(tabsScroller, tabsLp);
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
        input = new EditText(activity) {
            @Override
            public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
                InputConnection target = super.onCreateInputConnection(outAttrs);
                if (target == null) return null;
                return new InputConnectionWrapper(target, true) {
                    @Override
                    public boolean commitText(CharSequence text, int newCursorPosition) {
                        if (text != null && text.length() == 1 && text.charAt(0) == '\n'
                            && acceptAutocompleteSuggestion()) {
                            return true;
                        }
                        return super.commitText(text, newCursorPosition);
                    }

                    @Override
                    public boolean sendKeyEvent(KeyEvent event) {
                        if (event != null && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                            && acceptAutocompleteSuggestion()) {
                            return true;
                        }
                        return super.sendKeyEvent(event);
                    }
                };
            }
        };
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
                changeRemoved = count > 0 ? s.subSequence(start, start + count).toString() : "";
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
                    final int newlinePosition = changeStart;
                    if (isEmptyPairAt(s, newlinePosition)) {
                        scheduleExpandEmptyPair(s, newlinePosition);
                    } else {
                        final String indent = indentationForNewLine(s, newlinePosition);
                        if (!indent.isEmpty()) {
                        // Altera o texto somente depois que o Android terminar
                        // a notificacao atual do TextWatcher. Isso evita uma
                        // modificacao recursiva durante afterTextChanged().
                        input.post(() -> {
                            Editable current = input.getText();
                            int expectedCursor = newlinePosition + 1;
                            if (current.length() <= newlinePosition
                                || current.charAt(newlinePosition) != '\n'
                                || input.getSelectionStart() != expectedCursor
                                || input.getSelectionEnd() != expectedCursor) {
                                return;
                            }

                            applyingAutoIndent = true;
                            try {
                                current.insert(expectedCursor, indent);
                                input.setSelection(expectedCursor + indent.length());
                            } finally {
                                applyingAutoIndent = false;
                            }
                        });
                        }
                    }
                }

                scheduleClosingKeywordOutdent(s);
                scheduleIndentBackspaceCollapse(s);
                scheduleAutoPair(s);
                schedulePairedDeletion(s);

                boolean firstDirty = !dirty;
                dirty = true;
                if (firstDirty && portraitTabs) renderTabStrip();
                listener.onEdited();
                saveFailureNotified = false;
                followUntil = SystemClock.uptimeMillis() + 500; // digitando: pode seguir o cursor
                refreshDirtyMark();
                scheduleHighlight();
                refreshGutter(s, false);
                // Popups de ajuda nao sao reposicionados por Backspace continuo.
                boolean continuousDelete = highlightLua
                    && changeBefore > 0 && changeAfter == 0;
                if (continuousDelete) {
                    scheduleDeletionAssistance();
                } else {
                    cancelDeletionAssistance();
                    updateAutocompleteSuggestion(s);
                    updateParameterHint(s);
                }
            }
        });
        input.setOnEditorActionListener((textView, actionId, event) -> {
            if (acceptAutocompleteSuggestion()) return true;
            return false;
        });
        input.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }
            if (keyCode == KeyEvent.KEYCODE_ENTER && acceptAutocompleteSuggestion()) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_TAB) {
                int start = input.getSelectionStart();
                int end = input.getSelectionEnd();
                if (start != end || start < 0) {
                    return false;
                }
                input.getText().insert(start, "    ");
                input.setSelection(start + 4);
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DEL) {
                return deleteIndentLevel();
            }
            return false;
        });

        HorizontalScrollView horizontal = new CaretGuardHorizontalScrollView(activity);
        horizontal.setFillViewport(true);

        LinearLayout horizontalContent = new LinearLayout(activity);
        horizontalContent.setOrientation(LinearLayout.VERTICAL);
        horizontalContent.addView(input, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        horizontal.addView(horizontalContent, new ViewGroup.LayoutParams(
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

        // Tela inicial do modo vertical, sem criar nenhum arquivo ficticio.
        emptyState = new LinearLayout(activity);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView emptyTitle = new TextView(activity);
        emptyTitle.setText("Nenhum arquivo aberto");
        emptyTitle.setTextColor(Workspace.COLOR_TEXT);
        emptyTitle.setTextSize(18);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView emptyHint = new TextView(activity);
        emptyHint.setText("Toque em \u2630 para acessar suas pastas e abrir um arquivo.");
        emptyHint.setTextColor(Workspace.COLOR_MUTED);
        emptyHint.setTextSize(13);
        emptyHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emptyHintLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyHintLp.topMargin = dp(10);
        emptyState.addView(emptyHint, emptyHintLp);
        emptyState.setVisibility(View.GONE);
        view.addView(emptyState, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Rodape (expandir / fechar), logo acima do teclado quando ele esta aberto.
        footerDivider = new View(activity);
        footerDivider.setBackgroundColor(Workspace.COLOR_BORDER);
        LinearLayout.LayoutParams footerDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        footerDividerLp.setMargins(0, dp(6), 0, dp(8));
        view.addView(footerDivider, footerDividerLp);
        view.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // O texto ocupa pelo menos a altura visivel (para tocar na parte vazia).
        // Evita solicitar novo layout a cada callback quando a altura nao mudou.
        scroll.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int height = b - t;
            if (height <= 0) return;
            if (input.getMinHeight() != height) input.setMinHeight(height);
            if (gutter.getMinHeight() != height) gutter.setMinHeight(height);
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

    /** Exibe as abas e o menu somente no layout vertical sem SDL. */
    void setPortraitTabs(boolean enabled) {
        if (portraitTabs == enabled) return;
        portraitTabs = enabled;
        filesMenuButton.setVisibility(enabled ? View.VISIBLE : View.GONE);

        // Ordem no vertical: menu, titulo do arquivo, indicador de alteracao.
        // No horizontal, mantem a posicao e as medidas anteriores.
        header.removeView(dirtyMark);
        if (enabled) {
            title.setMaxWidth(dp(210));
            title.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
            header.addView(dirtyMark, header.indexOfChild(title) + 1);
        } else {
            title.setMaxWidth(Integer.MAX_VALUE);
            title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            header.addView(dirtyMark, 0);
        }
        updateEmptyState();
        renderTabStrip();
    }

    /** Somente a apresentacao muda; o editor real continua fechado sem arquivo. */
    private void updateEmptyState() {
        boolean noFile = portraitTabs && file == null;
        emptyState.setVisibility(noFile ? View.VISIBLE : View.GONE);
        scroll.setVisibility(noFile ? View.GONE : View.VISIBLE);
        footerDivider.setVisibility(noFile ? View.GONE : View.VISIBLE);
        footer.setVisibility(noFile ? View.GONE : View.VISIBLE);
        if (noFile) title.setText("CodeLove");
    }

    private void snapshotActiveTab() {
        if (file == null) return;
        TabState tab = openTabs.get(file.getAbsolutePath());
        if (tab == null) return;
        tab.text = input.getText().toString();
        tab.original = originalText;
        tab.dirty = dirty;
        tab.selection = Math.max(0, input.getSelectionStart());
        tab.scrollY = scroll.getScrollY();
    }

    private void showTab(TabState tab) {
        dismissAutocompleteSuggestion();
        dismissParameterHint();
        input.removeCallbacks(pendingHighlight);
        cancelDeletionAssistance();
        file = tab.file;
        storage = tab.storage;
        originalText = tab.original;
        highlightLua = file.getName().toLowerCase(Locale.ROOT).endsWith(".lua");
        loading = true;
        try {
            input.setText(tab.text);
            input.setSelection(Math.min(Math.max(0, tab.selection), input.length()));
        } finally {
            loading = false;
        }
        dirty = tab.dirty;
        saveFailureNotified = false;
        refreshDirtyMark();
        title.setText(file.getName());
        updateEmptyState();
        applyHighlight(input.getText());
        refreshGutter(input.getText(), true);
        followUntil = 0;
        int restoreY = tab.scrollY;
        scroll.scrollTo(0, restoreY);
        scroll.post(() -> {
            if (file != null && file.equals(tab.file)) scroll.scrollTo(0, restoreY);
        });
        renderTabStrip();
        listener.onActiveTabChanged();
    }

    private void renderTabStrip() {
        // No vertical, remove a faixa escura quando nao ha abas abertas.
        // O editor vazio e o botao do explorador continuam visiveis.
        tabsScroller.setVisibility(portraitTabs && !openTabs.isEmpty()
            ? View.VISIBLE : View.GONE);
        if (!portraitTabs) return;
        tabsRow.removeAllViews();
        for (TabState tab : openTabs.values()) {
            String key = tab.file.getAbsolutePath();
            boolean active = file != null && file.getAbsolutePath().equals(key);
            boolean unsaved = active ? dirty : tab.dirty;
            LinearLayout cell = new LinearLayout(activity);
            cell.setGravity(Gravity.CENTER_VERTICAL);
            cell.setOrientation(LinearLayout.HORIZONTAL);
            cell.setPadding(dp(9), 0, dp(7), 0);
            cell.setBackground(box(active ? Workspace.COLOR_FIELD : Workspace.COLOR_PANEL,
                8, active));

            // Icone da linguagem antes do nome, independente da pasta aberta.
            LanguageTabIcon icon = new LanguageTabIcon(activity, tab.file.getName());
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(
                dp(18), dp(18));
            iconLp.rightMargin = dp(7);
            cell.addView(icon, iconLp);
            cell.setOnClickListener(v -> open(tab.file));

            TextView name = new TextView(activity);
            name.setText(tab.file.getName() + (unsaved ? " \u2022" : ""));
            name.setTextSize(12);
            name.setTextColor(active ? Workspace.COLOR_TEXT : Workspace.COLOR_MUTED);
            name.setSingleLine(true);
            name.setMaxWidth(dp(130));
            name.setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(name);
            name.setOnClickListener(v -> open(tab.file));
            TextView close = new TextView(activity);
            close.setText("\u00D7");
            close.setTextSize(21);
            close.setGravity(Gravity.CENTER);
            close.setTextColor(Workspace.COLOR_MUTED);
            close.setContentDescription("Fechar " + tab.file.getName());
            cell.addView(close, new LinearLayout.LayoutParams(dp(30), dp(36)));
            close.setOnClickListener(v -> closeTab(key));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            lp.setMargins(0, 0, dp(6), 0);
            tabsRow.addView(cell, lp);
        }
    }

    /** Selecionar uma aba preserva texto nao salvo nas demais. */
    boolean open(File target) {
        if (target == null) return false;
        String key = target.getAbsolutePath();
        if (file != null && file.getAbsolutePath().equals(key)) return true;
        // Preserva a regra anterior no horizontal, sem abas visiveis.
        if (!portraitTabs && dirty) {
            notifyUnsavedChanges();
            return false;
        }
        TabState next = openTabs.get(key);
        if (next == null) {
            String text;
            try {
                text = storage != null ? storage.readText(target, MAX_BYTES) : readText(target);
            } catch (IOException error) {
                Log.w(TAG, "Falha ao ler " + target, error);
                return false;
            }
            if (text == null) return false;
            next = new TabState(target, storage, text);
        }
        snapshotActiveTab();
        openTabs.put(key, next);
        showTab(next);
        return true;
    }

    private void emptyEditor() {
        dismissAutocompleteSuggestion();
        dismissParameterHint();
        input.removeCallbacks(pendingHighlight);
        cancelDeletionAssistance();
        file = null;
        originalText = null;
        dirty = false;
        loading = true;
        try { input.setText(""); }
        finally { loading = false; }
        title.setText("");
        updateEmptyState();
        renderTabStrip();
        listener.onClosed();
    }

    private boolean closeTab(String key) {
        snapshotActiveTab();
        TabState target = openTabs.get(key);
        if (target == null) return true;
        if (target.dirty) {
            notifyUnsavedChanges();
            return false;
        }
        boolean activeRemoved = file != null && file.getAbsolutePath().equals(key);
        openTabs.remove(key);
        if (activeRemoved) {
            if (openTabs.isEmpty()) emptyEditor();
            else {
                TabState last = null;
                for (TabState tab : openTabs.values()) last = tab;
                showTab(last);
            }
        } else renderTabStrip();
        return true;
    }

    /** O X do rodape fecha a aba atual no vertical. */
    boolean close() {
        if (file == null) return true;
        return portraitTabs ? closeTab(file.getAbsolutePath()) : closeAllTabs();
    }

    boolean hasUnsavedTabs() {
        if (dirty) return true;
        for (TabState tab : openTabs.values()) {
            if (tab.dirty) return true;
        }
        return false;
    }

    boolean closeAllTabs() {
        snapshotActiveTab();
        if (hasUnsavedTabs()) {
            notifyUnsavedChanges();
            return false;
        }
        openTabs.clear();
        emptyEditor();
        return true;
    }

    /** Mantem exclusao, movimento e renomeacao seguros para todas as abas. */
    boolean closeTabsInside(File target) {
        if (target == null) return true;
        snapshotActiveTab();
        ArrayList<String> affected = new ArrayList<>();
        String prefix = target.getAbsolutePath();
        for (Map.Entry<String, TabState> entry : openTabs.entrySet()) {
            String path = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + File.separator)) {
                if (entry.getValue().dirty) {
                    notifyUnsavedChanges();
                    return false;
                }
                affected.add(path);
            }
        }
        if (affected.isEmpty()) return true;
        boolean activeRemoved = file != null && affected.contains(file.getAbsolutePath());
        for (String path : affected) openTabs.remove(path);
        if (activeRemoved) {
            if (openTabs.isEmpty()) emptyEditor();
            else {
                TabState last = null;
                for (TabState tab : openTabs.values()) last = tab;
                showTab(last);
            }
        } else renderTabStrip();
        return true;
    }

    boolean hasTabInside(File target) {
        if (target == null) return false;
        String prefix = target.getAbsolutePath();
        for (String path : openTabs.keySet()) {
            if (path.equals(prefix) || path.startsWith(prefix + File.separator)) return true;
        }
        return false;
    }

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
        TabState tab = openTabs.get(file.getAbsolutePath());
        if (tab != null) {
            tab.text = text;
            tab.original = text;
            tab.dirty = false;
        }
        if (portraitTabs) renderTabStrip();
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
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                followUntil = 0;
            }
            return super.onInterceptTouchEvent(event);
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
        public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                followUntil = 0;
            }
            return super.onInterceptTouchEvent(event);
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
            || code.matches("(?s).*\\bfunction\\s*(?:[\\w_.:]*\\s*)?\\([^)]*\\)\\s*$")
            || code.endsWith("{");

        return opensBlock ? indent + "    " : indent;
    }

    /**
     * Recuo automatico para palavras que fecham ou mudam um bloco Lua.
     * A alteracao e postada para nao modificar o Editable dentro do TextWatcher.
     */
    private void scheduleClosingKeywordOutdent(CharSequence text) {
        int cursor = input.getSelectionStart();
        if (cursor <= 0 || cursor > text.length() || changeAfter <= 0
            || changeStart + changeAfter != cursor) {
            return;
        }

        int lineStart = cursor - 1;
        while (lineStart >= 0 && text.charAt(lineStart) != '\n') {
            lineStart--;
        }
        lineStart++;

        String linePrefix = text.subSequence(lineStart, cursor).toString();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("^(\\s*)(end|else|elseif|until)$")
            .matcher(linePrefix);
        if (!matcher.matches()) {
            return;
        }

        String indent = matcher.group(1);
        String reducedIndent = removeOneIndentLevel(indent);
        if (reducedIndent.equals(indent)) {
            return;
        }

        final int expectedCursor = cursor;
        final int expectedLineStart = lineStart;
        input.post(() -> {
            Editable current = input.getText();
            if (input.getSelectionStart() != expectedCursor
                || input.getSelectionEnd() != expectedCursor
                || current.length() < expectedCursor
                || !current.subSequence(expectedLineStart, expectedCursor).toString().equals(linePrefix)) {
                return;
            }
            applyingAutoIndent = true;
            try {
                current.replace(expectedLineStart, expectedLineStart + indent.length(), reducedIndent);
                input.setSelection(expectedCursor - (indent.length() - reducedIndent.length()));

            } finally {
                applyingAutoIndent = false;
            }
        });
    }

    /**
     * Alguns teclados Android nao enviam KEYCODE_DEL ao OnKeyListener.
     * Detecta a exclusao de um espaco no recuo e completa o nivel inteiro.
     */
    private void scheduleIndentBackspaceCollapse(CharSequence text) {
        if (changeBefore != 1 || changeAfter != 0 || !" ".equals(changeRemoved)) {
            return;
        }
        int cursor = input.getSelectionStart();
        if (cursor != changeStart || cursor < 0) {
            return;
        }
        int lineStart = cursor - 1;
        while (lineStart >= 0 && text.charAt(lineStart) != '\n') {
            lineStart--;
        }
        lineStart++;
        String remaining = text.subSequence(lineStart, cursor).toString();
        for (int i = 0; i < remaining.length(); i++) {
            if (remaining.charAt(i) != ' ' && remaining.charAt(i) != '\t') {
                return;
            }
        }
        String originalIndent = remaining + " ";
        String reduced = removeOneIndentLevel(originalIndent);
        int targetLength = reduced.length();
        if (targetLength >= remaining.length()) {
            return;
        }
        final int expectedCursor = cursor;
        final int expectedLineStart = lineStart;
        final String expectedRemaining = remaining;
        input.post(() -> {
            Editable current = input.getText();
            if (input.getSelectionStart() != expectedCursor
                || input.getSelectionEnd() != expectedCursor
                || current.length() < expectedCursor
                || !current.subSequence(expectedLineStart, expectedCursor).toString().equals(expectedRemaining)) {
                return;
            }
            applyingAutoIndent = true;
            try {
                current.delete(expectedLineStart + targetLength, expectedCursor);
                input.setSelection(expectedLineStart + targetLength);

            } finally {
                applyingAutoIndent = false;
            }
        });
    }

    private static char matchingCloser(char opener) {
        switch (opener) {
            case '(': return ')';
            case '[': return ']';
            case '{': return '}';
            case '"': return '"';
            case '\'': return '\'';
            default: return 0;
        }
    }

    /** Insere o fechamento do par e permite pular um fechamento ja existente. */
    private void scheduleAutoPair(CharSequence text) {
        if (changeBefore != 0 || changeAfter != 1) return;
        int cursor = input.getSelectionStart();
        if (cursor != changeStart + 1 || cursor > text.length()) return;
        char typed = text.charAt(changeStart);
        char closer = matchingCloser(typed);
        if (closer == 0) {
            if (typed == ')' || typed == ']' || typed == '}') {
                if (cursor < text.length() && text.charAt(cursor) == typed) {
                    final int position = changeStart;
                    input.post(() -> {
                        Editable current = input.getText();
                        if (input.getSelectionStart() == position + 1
                            && position + 1 < current.length()
                            && current.charAt(position) == typed
                            && current.charAt(position + 1) == typed) {
                            applyingAutoIndent = true;
                            try {
                                current.delete(position, position + 1);
                                input.setSelection(position + 1);
                                scheduleHighlight();
                            } finally {
                                applyingAutoIndent = false;
                            }
                        }
                    });
                }
            }
            return;
        }
        if (cursor < text.length() && text.charAt(cursor) == closer) return;
        final int position = cursor;
        final char expectedOpener = typed;
        input.post(() -> {
            Editable current = input.getText();
            if (input.getSelectionStart() != position || input.getSelectionEnd() != position
                || position <= 0 || current.charAt(position - 1) != expectedOpener) return;
            applyingAutoIndent = true;
            try {
                current.insert(position, String.valueOf(closer));
                input.setSelection(position);
                scheduleHighlight();
            } finally {
                applyingAutoIndent = false;
            }
        });
    }

    /** Apagar o caractere de abertura remove tambem seu fechamento correspondente. */
    private void schedulePairedDeletion(CharSequence text) {
        if (changeBefore != 1 || changeAfter != 0 || changeRemoved.length() != 1) return;
        char closer = matchingCloser(changeRemoved.charAt(0));
        if (closer == 0) return;
        int cursor = input.getSelectionStart();
        if (cursor != changeStart || cursor >= text.length() || text.charAt(cursor) != closer) return;
        final int position = cursor;
        input.post(() -> {
            Editable current = input.getText();
            if (input.getSelectionStart() != position || input.getSelectionEnd() != position
                || position >= current.length() || current.charAt(position) != closer) return;
            applyingAutoIndent = true;
            try {
                current.delete(position, position + 1);
                scheduleHighlight();
            } finally {
                applyingAutoIndent = false;
            }
        });
    }

    private static boolean isEmptyPairAt(CharSequence text, int newlinePosition) {
        if (newlinePosition <= 0 || newlinePosition + 1 >= text.length()) return false;
        char opener = text.charAt(newlinePosition - 1);
        return opener == '{' && text.charAt(newlinePosition + 1) == '}';
    }

    /** Enter entre um par vazio cria linha interna e preserva o fechamento alinhado. */
    private void scheduleExpandEmptyPair(CharSequence text, int newlinePosition) {
        int lineStart = newlinePosition - 1;
        while (lineStart >= 0 && text.charAt(lineStart) != '\n') lineStart--;
        lineStart++;
        int indentEnd = lineStart;
        while (indentEnd < newlinePosition && (text.charAt(indentEnd) == ' ' || text.charAt(indentEnd) == '\t')) indentEnd++;
        final String baseIndent = text.subSequence(lineStart, indentEnd).toString();
        final String innerIndent = baseIndent + "    ";
        final int expectedCursor = newlinePosition + 1;
        input.post(() -> {
            Editable current = input.getText();
            if (input.getSelectionStart() != expectedCursor || input.getSelectionEnd() != expectedCursor
                || current.length() <= newlinePosition + 1 || current.charAt(newlinePosition) != '\n'
                || current.charAt(newlinePosition + 1) != matchingCloser(current.charAt(newlinePosition - 1))) return;
            applyingAutoIndent = true;
            try {
                current.insert(expectedCursor, innerIndent + "\n" + baseIndent);
                input.setSelection(expectedCursor + innerIndent.length());
                scheduleHighlight();
                refreshGutter(current, false);
            } finally {
                applyingAutoIndent = false;
            }
        });
    }

    private static String removeOneIndentLevel(String indent) {
        if (indent.endsWith("\t")) {
            return indent.substring(0, indent.length() - 1);
        }
        if (indent.endsWith("    ")) {
            return indent.substring(0, indent.length() - 4);
        }
        int spaces = 0;
        for (int i = indent.length() - 1; i >= 0 && indent.charAt(i) == ' '; i--) {
            spaces++;
        }
        return spaces == 0 ? indent : indent.substring(0, indent.length() - Math.min(4, spaces));
    }

    /** Backspace remove um nivel de indentacao de uma vez no inicio da linha. */
    private boolean deleteIndentLevel() {
        int start = input.getSelectionStart();
        int end = input.getSelectionEnd();
        if (start != end || start <= 0) {
            return false;
        }
        Editable text = input.getText();
        int lineStart = start - 1;
        while (lineStart >= 0 && text.charAt(lineStart) != '\n') {
            lineStart--;
        }
        lineStart++;
        for (int i = lineStart; i < start; i++) {
            if (text.charAt(i) != ' ' && text.charAt(i) != '\t') {
                return false;
            }
        }
        String indent = text.subSequence(lineStart, start).toString();
        String reduced = removeOneIndentLevel(indent);
        if (reduced.equals(indent)) {
            return false;
        }
        text.delete(lineStart + reduced.length(), start);
        input.setSelection(lineStart + reduced.length());
        return true;
    }

    /**
     * Sugestao pequena de palavras Lua. Nao altera o texto ate o usuario aceitar.
     */
    private void updateAutocompleteSuggestion(CharSequence text) {
        int cursor = input.getSelectionStart();
        if (cursor <= 0 || cursor > text.length() || !highlightLua
            || input.getVisibility() != View.VISIBLE) {
            dismissAutocompleteSuggestion();
            return;
        }

        int start = cursor;
        while (start > 0) {
            char ch = text.charAt(start - 1);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') break;
            start--;
        }
        if (start == cursor) {
            dismissAutocompleteSuggestion();
            return;
        }

        String prefix = text.subSequence(start, cursor).toString();
        String[] words = LoveApiCatalog.SUGGESTIONS;
        String match = null;
        for (String word : words) {
            if (word.startsWith(prefix) && !word.equals(prefix)) {
                match = word;
                break;
            }
        }
        if (match == null) {
            dismissAutocompleteSuggestion();
            return;
        }

        autocompleteCandidate = match;
        if (autocompleteText == null) {
            autocompleteText = new TextView(activity);
            autocompleteText.setTextColor(Workspace.COLOR_TEXT);
            autocompleteText.setTextSize(12);
            autocompleteText.setTypeface(Typeface.MONOSPACE);
            autocompleteText.setPadding(dp(10), dp(6), dp(10), dp(6));
            autocompleteText.setBackground(box(Workspace.COLOR_FIELD, 8, true));
            autocompleteText.setElevation(dp(6));
            autocompleteText.setOnClickListener(v -> acceptAutocompleteSuggestion());
            autocompletePopup = new PopupWindow(
                autocompleteText,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
            autocompletePopup.setBackgroundDrawable(box(Workspace.COLOR_FIELD, 8, false));
            autocompletePopup.setOutsideTouchable(true);
        }
        SpannableString styledSuggestion = new SpannableString(match);
        int typedLength = Math.min(prefix.length(), match.length());
        if (typedLength > 0) {
            styledSuggestion.setSpan(new ForegroundColorSpan(COLOR_KEYWORD),
                0, typedLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (typedLength < match.length()) {
            styledSuggestion.setSpan(new ForegroundColorSpan(Workspace.COLOR_MUTED),
                typedLength, match.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        autocompleteText.setText(styledSuggestion);
        if (!input.isAttachedToWindow() || input.getLayout() == null) return;

        android.text.Layout layout = input.getLayout();
        int line = layout.getLineForOffset(cursor);
        float horizontal = layout.getPrimaryHorizontal(cursor);
        int[] location = new int[2];
        input.getLocationOnScreen(location);
        int x = Math.max(0, location[0] + Math.round(horizontal) - input.getScrollX());
        int y = Math.max(0, location[1] + layout.getLineBottom(line)
            + input.getPaddingTop() - input.getScrollY());
        if (autocompletePopup.isShowing()) {
            autocompletePopup.update(x, y, -1, -1);
        } else {
            autocompletePopup.showAtLocation(input, Gravity.TOP | Gravity.LEFT, x, y);
        }
    }

    /**
     * Mostra a assinatura da funcao quando o cursor esta dentro de seus argumentos.
     * A ajuda e contextual e nao interfere na digitacao nem no console do LÖVE.
     */
    private void updateParameterHint(CharSequence text) {
        int cursor = input.getSelectionStart();
        if (cursor < 0 || cursor > text.length() || !highlightLua
            || input.getVisibility() != View.VISIBLE) {
            dismissParameterHint();
            return;
        }

        int depth = 0;
        int open = -1;
        for (int i = cursor - 1; i >= 0; i--) {
            char ch = text.charAt(i);
            if (ch == ')') {
                depth++;
            } else if (ch == '(') {
                if (depth == 0) {
                    open = i;
                    break;
                }
                depth--;
            }
        }
        if (open < 0) {
            dismissParameterHint();
            return;
        }

        int nameEnd = open;
        while (nameEnd > 0 && Character.isWhitespace(text.charAt(nameEnd - 1))) nameEnd--;
        int nameStart = nameEnd;
        while (nameStart > 0) {
            char ch = text.charAt(nameStart - 1);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.' && ch != ':') break;
            nameStart--;
        }
        if (nameStart == nameEnd) {
            dismissParameterHint();
            return;
        }

        String function = text.subSequence(nameStart, nameEnd).toString();
        String signature = parameterSignature(function);
        if (signature == null) {
            dismissParameterHint();
            return;
        }

        int argument = 0;
        depth = 0;
        for (int i = open + 1; i < cursor; i++) {
            char ch = text.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') depth++;
            else if (ch == ')' || ch == ']' || ch == '}') depth = Math.max(0, depth - 1);
            else if (ch == ',' && depth == 0) argument++;
        }

        if (parameterHintText == null) {
            parameterHintText = new TextView(activity);
            parameterHintText.setTextColor(Workspace.COLOR_TEXT);
            parameterHintText.setTextSize(11);
            parameterHintText.setTypeface(Typeface.MONOSPACE);
            parameterHintText.setPadding(dp(10), dp(7), dp(10), dp(7));
            parameterHintText.setBackground(box(Workspace.COLOR_FIELD, 8, true));
            parameterHintText.setElevation(dp(6));
            parameterHintPopup = new PopupWindow(
                parameterHintText,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                false);
            parameterHintPopup.setBackgroundDrawable(box(Workspace.COLOR_FIELD, 8, false));
            parameterHintPopup.setOutsideTouchable(true);
        }
        parameterHintText.setText(function + signature + "\nArgumento atual: " + (argument + 1));
        if (!input.isAttachedToWindow() || input.getLayout() == null) return;

        android.text.Layout layout = input.getLayout();
        int line = layout.getLineForOffset(cursor);
        float horizontal = layout.getPrimaryHorizontal(cursor);
        int[] location = new int[2];
        input.getLocationOnScreen(location);
        int x = Math.max(0, location[0] + Math.round(horizontal) - input.getScrollX());
        int y = Math.max(0, location[1] + layout.getLineBottom(line)
            + input.getPaddingTop() - input.getScrollY());
        if (parameterHintPopup.isShowing()) {
            parameterHintPopup.update(x, y, -1, -1);
        } else {
            parameterHintPopup.showAtLocation(input, Gravity.TOP | Gravity.LEFT, x, y);
        }
    }

    private String parameterSignature(String function) {
        return LoveApiParameters.signature(function);
    }

    private void dismissParameterHint() {
        if (parameterHintPopup != null && parameterHintPopup.isShowing()) {
            parameterHintPopup.dismiss();
        }
    }

    private boolean acceptAutocompleteSuggestion() {
        if (autocompleteCandidate == null || autocompleteCandidate.isEmpty()) return false;
        int cursor = input.getSelectionStart();
        if (cursor < 0 || cursor > input.length()) {
            dismissAutocompleteSuggestion();
            return false;
        }
        int start = cursor;
        while (start > 0) {
            char ch = input.getText().charAt(start - 1);
            if (!Character.isLetterOrDigit(ch) && ch != '_' && ch != '.') break;
            start--;
        }
        String prefix = input.getText().subSequence(start, cursor).toString();
        String candidate = autocompleteCandidate;
        if (prefix.isEmpty() || !candidate.startsWith(prefix)) {
            dismissAutocompleteSuggestion();
            return false;
        }

        dismissAutocompleteSuggestion();
        input.getText().replace(start, cursor, candidate);
        input.setSelection(start + candidate.length());
        return true;
    }

    private void dismissAutocompleteSuggestion() {
        autocompleteCandidate = null;
        if (autocompletePopup != null && autocompletePopup.isShowing()) {
            autocompletePopup.dismiss();
        }
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
            if (text.charAt(i) == '\n') lines++;
        }
        if (!force && lines == lineCount) return;
        lineCount = lines;

        StringBuilder numbers = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            if (i > 0) numbers.append("\n");
            numbers.append(i + 1);
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
