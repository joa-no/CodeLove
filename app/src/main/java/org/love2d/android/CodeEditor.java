/*
 * CodeLove - editor de codigo.
 *
 * Abre arquivos de texto, mostra numeros de linha e cores (Lua).
 * Usa o motor Sora; as gravacoes sao controladas pelo Workspace.
 *
 * Baseado no LOVE para Android (licenca zlib). Versao alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.graphics.drawable.RippleDrawable;
import android.widget.ImageButton;

import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    private static int COLOR_KEYWORD = 0xFFC4A5FF;
    private static int COLOR_NUMBER = 0xFFF2A65A;
    private static int COLOR_STRING = 0xFF7EE0B5;
    private static int COLOR_COMMENT = 0xFF6B7189;

    /** Mantem realce Lua legivel tanto no escuro quanto no claro. */
    static void configureSyntaxTheme(boolean light) {
        COLOR_KEYWORD = light ? 0xFF6E47B9 : 0xFFC4A5FF;
        COLOR_NUMBER = light ? 0xFFA45112 : 0xFFF2A65A;
        COLOR_STRING = light ? 0xFF16805A : 0xFF7EE0B5;
        COLOR_COMMENT = light ? 0xFF66738B : 0xFF6B7189;
    }

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
    private boolean emptyWorkspaceEnabled;
    private final TextView emptyHint;
    private final TextView dirtyMark;
    private final io.github.rosemoe.sora.widget.CodeEditor soraInput;
    private final CodeLoveParameterHints soraParameterHints;
    private final CodeLoveLuaOutdent soraOutdent;
    private final CodeLovePairedDeletion soraPairedDeletion;
    private final CodeLoveClosingOvertype soraClosingOvertype;
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

    /** Copia temporaria dos textos/cursores, sem referencia a Activity ou SAF antigo. */
    static final class ThemeTab {
        final File file;
        final String text;
        final String original;
        final int selection;
        final int scrollY;
        ThemeTab(TabState tab) {
            file = tab.file;
            text = tab.text;
            original = tab.original;
            selection = tab.selection;
            scrollY = tab.scrollY;
        }
    }

    static final class ThemeTabs {
        final ArrayList<ThemeTab> tabs;
        final File activeFile;
        ThemeTabs(ArrayList<ThemeTab> tabs, File activeFile) {
            this.tabs = tabs;
            this.activeFile = activeFile;
        }
    }

    private File file;           // arquivo aberto (nulo se nenhum)
    private String originalText; // conteudo lido na abertura, usado para detectar conflito externo
    private boolean dirty;       // tem alteracao ainda nao gravada no arquivo original
    private boolean saveFailureNotified; // evita repetir o mesmo aviso a cada tentativa
    private boolean loading;     // true enquanto trocamos o texto por codigo
    private boolean highlightLua; // arquivo .lua usa linguagem e ajudas especificas
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
        filesMenuButton.setContentDescription(activity.getString(R.string.cl_open_files));
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
        // Faixa de abas integrada ao painel, sem o preto quase absoluto.
        tabsScroller.setBackgroundColor(Workspace.isLightTheme()
            ? Workspace.COLOR_FIELD : 0xFF1B1E2A);
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

        // Sora gerencia a edicao, os numeros de linha, a selecao e a rolagem.
        soraInput = new io.github.rosemoe.sora.widget.CodeEditor(activity);
        soraInput.setTypefaceText(Typeface.MONOSPACE);
        soraInput.setTextSize(13f);
        soraInput.setTabWidth(4);
        // Mantem o recuo em quatro espacos e permite apagar um nivel
        // por Backspace no inicio da linha. Sem TextWatcher legado.
        soraInput.getProps().autoIndent = true;
        soraInput.getProps().symbolPairAutoCompletion = true;
        soraInput.getProps().deleteMultiSpaces = 4;
        soraInput.getProps().deleteEmptyLineFast = false;
        soraInput.setWordwrap(false);
        soraInput.setLineNumberEnabled(true);
        // O Sora desenha um divisor vertical padrao ao lado da numeracao.
        // Remove somente essa linha, mantendo os numeros e sua coluna.
        soraInput.setDividerWidth(0f);
        soraInput.setInputType(InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        io.github.rosemoe.sora.widget.schemes.EditorColorScheme soraColors =
            new io.github.rosemoe.sora.widget.schemes.EditorColorScheme();
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.WHOLE_BACKGROUND,
            Workspace.COLOR_PANEL);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_BACKGROUND,
            Workspace.COLOR_PANEL);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_NORMAL,
            Workspace.COLOR_TEXT);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER,
            Workspace.COLOR_MUTED);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.LINE_NUMBER_CURRENT,
            Workspace.COLOR_FOLDER);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTION_INSERT,
            Workspace.COLOR_FOLDER);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTED_TEXT_BACKGROUND,
            Workspace.isLightTheme() ? 0x447751BC : 0x66A78BFA);
        // Janela de autocomplete: reutiliza a paleta real do CodeLove,
        // inclusive quando a aparencia estiver no tema claro/automatico.
        // O layout padrao do Sora ja suporta bordas arredondadas (8dp).
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_BACKGROUND,
            Workspace.COLOR_FIELD);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_CORNER,
            Workspace.COLOR_BORDER);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY,
            Workspace.COLOR_TEXT);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY,
            Workspace.COLOR_MUTED);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_TEXT_MATCHED,
            Workspace.COLOR_FOLDER);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.COMPLETION_WND_ITEM_CURRENT,
            Workspace.isLightTheme() ? 0xFFECE4FA : 0xFF33274D);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SELECTION_HANDLE,
            Workspace.COLOR_FOLDER);
        soraColors.setColor(CodeLoveLuaLanguage.KEYWORD_COLOR_ID, COLOR_KEYWORD);
        soraColors.setColor(CodeLoveLuaLanguage.NUMBER_COLOR_ID, COLOR_NUMBER);
        soraColors.setColor(CodeLoveLuaLanguage.STRING_COLOR_ID, COLOR_STRING);
        soraColors.setColor(CodeLoveLuaLanguage.COMMENT_COLOR_ID, COLOR_COMMENT);
        // Paleta neutra para controles utilitarios; roxo reservado a destaques.
        // Cores compartilhadas entre o tema Sora e os indicadores arredondados.
        final int scrollNormalColor = Workspace.isLightTheme() ? 0xFF747B8E : 0xFF6F6985;
        final int scrollPressedColor = Workspace.isLightTheme() ? 0xFF5F677D : 0xFF8A7CB0;
        final int actionIconColor = Workspace.isLightTheme() ? 0xFF596173 : 0xFFC9CEDA;
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SCROLL_BAR_THUMB,
            scrollNormalColor);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SCROLL_BAR_THUMB_PRESSED,
            scrollPressedColor);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.SCROLL_BAR_TRACK,
            Workspace.isLightTheme() ? 0xFFE5E0EF : 0xFF252738);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND,
            Workspace.COLOR_FIELD);
        soraColors.setColor(
            io.github.rosemoe.sora.widget.schemes.EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR,
            actionIconColor);
        soraInput.setColorScheme(soraColors);
        // Mantem o scroll arrastavel do Sora, com indicador neutro arredondado.
        StateListDrawable scrollbarThumb = new StateListDrawable();
        scrollbarThumb.addState(new int[]{android.R.attr.state_pressed},
            roundedScrollThumb(scrollPressedColor));
        scrollbarThumb.addState(new int[]{},
            roundedScrollThumb(scrollNormalColor));
        soraInput.setVerticalScrollbarThumbDrawable(scrollbarThumb);
        StateListDrawable horizontalThumb = new StateListDrawable();
        horizontalThumb.addState(new int[]{android.R.attr.state_pressed},
            roundedScrollThumb(scrollPressedColor));
        horizontalThumb.addState(new int[]{},
            roundedScrollThumb(scrollNormalColor));
        soraInput.setHorizontalScrollbarThumbDrawable(horizontalThumb);
        // Reutiliza o painel de selecao do Sora: mesmos comandos e gestos,
        // somente a superficie e os botoes sao tematizados.
        io.github.rosemoe.sora.widget.component.EditorTextActionWindow selectionWindow =
            soraInput.getComponent(
                io.github.rosemoe.sora.widget.component.EditorTextActionWindow.class);
        GradientDrawable selectionBackground = box(Workspace.COLOR_FIELD, 14, true);
        selectionWindow.getView().setBackground(selectionBackground);
        selectionWindow.getView().setElevation(dp(7));
        styleSelectionActions(selectionWindow.getView());
        // Restaura o design compacto do autocomplete do CodeLove sobre
        // o mecanismo de sugestoes do Sora, sem acionar o EditText legado.
        soraInput.replaceComponent(
            io.github.rosemoe.sora.widget.component.EditorAutoCompletion.class,
            new CodeLoveCompletionWindow(soraInput));
        soraParameterHints = new CodeLoveParameterHints(activity, soraInput);
        soraOutdent = new CodeLoveLuaOutdent(soraInput);
        soraPairedDeletion = new CodeLovePairedDeletion(soraInput);
        soraClosingOvertype = new CodeLoveClosingOvertype(soraInput);
        soraInput.subscribeAlways(io.github.rosemoe.sora.event.ContentChangeEvent.class,
            event -> {
                if (loading || file == null || event.getAction()
                    == io.github.rosemoe.sora.event.ContentChangeEvent.ACTION_SET_NEW_TEXT) {
                    return;
                }
                boolean firstDirty = !dirty;
                dirty = true;
                saveFailureNotified = false;
                if (firstDirty && portraitTabs) renderTabStrip();
                refreshDirtyMark();
                listener.onEdited();
            });
        view.addView(soraInput, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Tela inicial do modo vertical, sem criar nenhum arquivo ficticio.
        emptyState = new LinearLayout(activity);
        emptyState.setOrientation(LinearLayout.VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(dp(24), dp(24), dp(24), dp(24));
        TextView emptyTitle = new TextView(activity);
        emptyTitle.setText(activity.getString(R.string.cl_empty_open));
        emptyTitle.setTextColor(Workspace.COLOR_TEXT);
        emptyTitle.setTextSize(18);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyState.addView(emptyTitle, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        emptyHint = new TextView(activity);
        emptyHint.setText(activity.getString(R.string.cl_empty_hint));
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

    /** Preserva o texto JA VISIVEL em cada aba, sem reler arquivos na troca de tema. */
    ThemeTabs captureThemeTabs() {
        snapshotActiveTab();
        ArrayList<ThemeTab> tabs = new ArrayList<>();
        for (TabState tab : openTabs.values()) {
            tabs.add(new ThemeTab(tab));
        }
        return new ThemeTabs(tabs, file);
    }

    /** Restaura as abas limpas na interface nova, mantendo foco e rolagem. */
    void restoreThemeTabs(ThemeTabs state, Storage newStorage) {
        if (state == null) return;
        openTabs.clear();
        for (ThemeTab saved : state.tabs) {
            TabState tab = new TabState(saved.file, newStorage, saved.text);
            tab.original = saved.original;
            tab.selection = saved.selection;
            tab.scrollY = saved.scrollY;
            openTabs.put(saved.file.getAbsolutePath(), tab);
        }
        TabState active = state.activeFile == null ? null
            : openTabs.get(state.activeFile.getAbsolutePath());
        if (active == null && !openTabs.isEmpty()) {
            for (TabState tab : openTabs.values()) active = tab;
        }
        if (active != null) showTab(active);
        else renderTabStrip();
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

    /** A EditorActivity mostra a area vazia mesmo em horizontal, sem criar arquivo. */
    void setEmptyWorkspaceEnabled(boolean enabled) {
        if (emptyWorkspaceEnabled == enabled) return;
        emptyWorkspaceEnabled = enabled;
        updateEmptyState();
    }

    /** Somente a apresentacao muda; o editor real continua fechado sem arquivo. */
    private void updateEmptyState() {
        boolean noFile = emptyWorkspaceEnabled && file == null;
        emptyHint.setText(activity.getString(portraitTabs
            ? R.string.cl_empty_hint : R.string.cl_empty_hint_horizontal));
        emptyState.setVisibility(noFile ? View.VISIBLE : View.GONE);
        soraInput.setVisibility(noFile ? View.GONE : View.VISIBLE);
        footerDivider.setVisibility(noFile ? View.GONE : View.VISIBLE);
        footer.setVisibility(noFile ? View.GONE : View.VISIBLE);
        if (noFile) title.setText("CodeLove");
    }

    private void snapshotActiveTab() {
        if (file == null) return;
        TabState tab = openTabs.get(file.getAbsolutePath());
        if (tab == null) return;
        tab.text = soraInput.getText().toString();
        tab.original = originalText;
        tab.dirty = dirty;
        tab.selection = Math.max(0, soraInput.getCursor().getLeft());
        tab.scrollY = soraInput.getOffsetY();
    }

    private void showTab(TabState tab) {
        soraParameterHints.setEnabled(false);
        soraOutdent.setEnabled(false);
        soraPairedDeletion.setEnabled(false);
        soraClosingOvertype.setEnabled(false);
        file = tab.file;
        storage = tab.storage;
        originalText = tab.original;
        highlightLua = file.getName().toLowerCase(Locale.ROOT).endsWith(".lua");
        // O novo analisador Sora substitui a recoloracao de spans do EditText.
        // Arquivos de outras extensoes usam linguagem vazia.
        soraInput.setEditorLanguage(highlightLua ? new CodeLoveLuaLanguage() : null);
        loading = true;
        try {
            soraInput.setText(tab.text);
            int position = Math.min(Math.max(0, tab.selection), soraInput.getText().length());
            io.github.rosemoe.sora.text.CharPosition cursor =
                soraInput.getText().getIndexer().getCharPosition(position);
            soraInput.setSelection(cursor.line, cursor.column, false);
        } finally {
            loading = false;
        }
        dirty = tab.dirty;
        saveFailureNotified = false;
        refreshDirtyMark();
        title.setText(file.getName());
        updateEmptyState();
        soraParameterHints.setEnabled(highlightLua);
        soraOutdent.setEnabled(highlightLua);
        soraPairedDeletion.setEnabled(highlightLua);
        soraClosingOvertype.setEnabled(highlightLua);
        // Preserva a rolagem de cada aba independentemente da posicao do cursor.
        // Adia o reposicionamento ate o Sora concluir o novo layout, inclusive
        // quando o tema reconstroi toda a interface do editor.
        final var displayedContent = soraInput.getText();
        final int savedY = Math.max(0, tab.scrollY);
        renderTabStrip();
        listener.onActiveTabChanged();
        soraInput.post(() -> {
            // Evita uma restauracao antiga se o usuario ja mudou de aba.
            if (file == null || !file.equals(tab.file)
                || soraInput.getText() != displayedContent) return;
            int y = Math.min(savedY, soraInput.getScrollMaxY());
            var scroller = soraInput.getScroller();
            scroller.forceFinished(true);
            scroller.startScroll(0, y, 0, 0, 0);
            scroller.abortAnimation();
            soraInput.invalidate();
        });
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
            close.setContentDescription(activity.getString(R.string.cl_close_file, tab.file.getName()));
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
        soraParameterHints.setEnabled(false);
        soraOutdent.setEnabled(false);
        soraPairedDeletion.setEnabled(false);
        soraClosingOvertype.setEnabled(false);
        file = null;
        originalText = null;
        dirty = false;
        loading = true;
        try { soraInput.setText(""); }
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

        final String text = soraInput.getText().toString();
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
                .setTitle(activity.getString(R.string.cl_file_changed_title))
                .setMessage(activity.getString(R.string.cl_file_changed_message))
                .setNegativeButton(activity.getString(R.string.cl_cancel), (dialog, which) -> { })
                .setPositiveButton(activity.getString(R.string.cl_overwrite), (dialog, which) -> writeTextNow(text))
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
            activity.getString(R.string.cl_save_before_switch),
            Toast.LENGTH_LONG).show();
    }

    private void notifySaveFailure() {
        if (saveFailureNotified) {
            return;
        }
        saveFailureNotified = true;
        Toast.makeText(activity,
            activity.getString(R.string.cl_save_failure),
            Toast.LENGTH_LONG).show();
    }

    private void refreshDirtyMark() {
        dirtyMark.setVisibility(dirty ? View.VISIBLE : View.GONE);
        String hint = dirty ? activity.getString(R.string.cl_unsaved_mark) : "";
        dirtyMark.setContentDescription(hint);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            dirtyMark.setTooltipText(dirty ? hint : null);
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

    /** Indicadores arredondados, sem alterar o gesto de arrastar do Sora. */
    private GradientDrawable roundedScrollThumb(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        return drawable;
    }

    /** Tematiza os botoes existentes; nao recria suas acoes de edicao. */
    private void styleSelectionActions(View root) {
        if (!(root instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) root;
        for (int i = 0; i < group.getChildCount(); i++) {
            View item = group.getChildAt(i);
            if (item instanceof ImageButton) {
                GradientDrawable pressed = box(
                    Workspace.isLightTheme() ? 0xFFE4D8FA : 0xFF332848, 10, false);
                RippleDrawable ripple = new RippleDrawable(
                    ColorStateList.valueOf(Workspace.isLightTheme()
                        ? 0x446E47B9 : 0x66A78BFA),
                    box(Workspace.COLOR_FIELD, 10, false), pressed);
                item.setBackground(ripple);
            } else if (item instanceof ViewGroup) {
                styleSelectionActions(item);
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
