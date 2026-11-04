/*
 * CodeLove - area de trabalho (painel lateral + area do jogo).
 *
 * Mostra, na MESMA tela do app, um painel lateral (abre/fecha e pode ficar
 * a esquerda ou a direita) com os arquivos do projeto em arvore, e deixa a
 * area do jogo do LOVE ocupar o espaco que sobra.
 *
 * Baseado no LOVE para Android (licenca zlib). Versao alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class Workspace {
    private static final String TAG = "CodeLove";
    private static final int REQUEST_ADD_PROJECT_TREE = 4102;
    private static Workspace currentInstance;

    // Cores inspiradas no desenho de referencia (tema escuro).
    static final int COLOR_OUTER = 0xFF0D0F16;
    static final int COLOR_PANEL = 0xFF151822;
    static final int COLOR_FIELD = 0xFF1B1F2B;
    static final int COLOR_BORDER = 0xFF272C3B;
    static final int COLOR_TEXT = 0xFFE6E8EE;
    static final int COLOR_MUTED = 0xFF8A8FA3;
    static final int COLOR_FOLDER = 0xFFA78BFA;
    static final int COLOR_LUA = 0xFF7AA2F7;
    static final int COLOR_IMAGE = 0xFF5EEAD4;
    static final int COLOR_OTHER = 0xFF9CA3AF;

    private static final int PANEL_WIDTH_DP = 272;
    private static final int MARGIN_DP = 8;
    private static final int CONTROL_BUTTON_DP = 44;
    private static final int BAR_PAD_DP = 6;
    private static final int BAR_GAP_DP = 8;
    private static final int ROW_HEIGHT_DP = 36;
    private static final int LEVEL_WIDTH_DP = 16;
    private static final int MAX_DEPTH = 12;

    private final Activity activity;
    private final RelativeLayout root;
    private final View surface;
    private final File gamesDir;   // pasta onde ficam os projetos (pode ser nula)
    private final File channelDir; // pasta do canal de comandos com o executor (Lua)
    private long commandSequence = 0L; // sequencia da fila Java -> Lua nesta execucao
    private final int panelWidth;
    private final int margin;
    private final Set<String> expanded = new HashSet<String>();

    private File activeProject = null; // projeto aberto agora (nulo se nenhum)
    private boolean playing = false;
    private String lastStatus = "";

    private OverlayUi ui;
    private SharedPreferences prefs;
    private LinearLayout consolePanel;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private LinearLayout consoleButton;
    private boolean consoleOpen = false;
    private String lastConsole = "";
    private File lastFolder = null; // ultima pasta tocada no projeto aberto
    private CodeEditor editor;
    private View editorView;
    private LinearLayout panel;
    private LinearLayout placeholder;
    private LinearLayout playButton;
    private TextView resetButton;
    private IconView playIcon;
    private IconView footerPlayIcon;
    private LinearLayout controlsBar;
    private IconView fullscreenIcon;
    private TextView projectTitle;
    private TextView statusView;
    private LinearLayout projectNotice;
    private LinearLayout treeBox;
    private EditText search;

    private boolean fullscreen = false;
    private boolean onLeft = true; // restaurado das preferencias no construtor
    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;     // barras do sistema (sem o teclado)
    private int insetBottomIme;  // barras do sistema + teclado

    private Workspace(Activity activity, RelativeLayout root, View surface, File gamesDir) {
        this.activity = activity;
        this.root = root;
        this.surface = surface;
        this.gamesDir = gamesDir;
        this.channelDir = channelDir(activity);
        this.prefs = activity.getSharedPreferences("codelove", Context.MODE_PRIVATE);
        this.onLeft = prefs.getBoolean("panel_left", true);
        this.panelWidth = dp(PANEL_WIDTH_DP);
        this.margin = dp(MARGIN_DP);
    }

    /**
     * Liga a area de trabalho ao layout do SDL.
     * "layout" e o layout criado pelo SDL; "surface" e onde o LOVE desenha.
     */
    public static void attach(Activity activity, ViewGroup layout, View surface) {
        if (activity == null || surface == null || !(layout instanceof RelativeLayout)) {
            return;
        }
        File games = activity.getExternalFilesDir("games");
        if (games != null) {
            if (!games.isDirectory()) {
                games.mkdirs();
            }
            ensureSampleProject(games);
        }
        Workspace workspace = new Workspace(activity, (RelativeLayout) layout, surface, games);
        currentInstance = workspace;
        workspace.build();
        workspace.showPreviousCrashReport();
    }

    private void showPreviousCrashReport() {
        String report = CrashDiagnostics.consumePreviousExitReport(activity);
        if (report != null) {
            showNotice("Diagnostico do encerramento anterior", report);
        }
    }

    /** Sincroniza o polling da interface com o ciclo de vida da Activity SDL. */
    public static void onHostResume(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity && !workspace.disposed) {
            workspace.hostResumed = true;
            workspace.startStatusPolling();
        }
    }

    public static void onHostPause(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity) {
            workspace.hostResumed = false;
            workspace.stopStatusPolling();
        }
    }

    public static void onHostDestroy(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity) {
            workspace.hostResumed = false;
            workspace.disposed = true;
            workspace.stopStatusPolling();
            currentInstance = null;
        }
    }

    /** Pasta do canal de comandos (Java -> executor em Lua). */
    public static File channelDir(Context context) {
        return new File(context.getFilesDir(), "codelove");
    }

    /**
     * Prepara um canal de comandos limpo. Precisa ser chamado ANTES de o LOVE
     * iniciar. Devolve o caminho da pasta, que o executor recebe como argumento.
     */
    public static String prepareChannel(Context context) {
        File dir = channelDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IllegalStateException("Nao foi possivel preparar o canal do CodeLove.");
        }

        // O executor comeca com a sequencia 1 em cada inicializacao. Limpa a fila
        // anterior antes de iniciar o LOVE, sem tocar nos arquivos de status/console.
        File queue = new File(dir, "commands");
        if (queue.exists()) {
            File[] pending = queue.listFiles();
            if (pending == null) {
                throw new IllegalStateException("Nao foi possivel ler a fila de comandos.");
            }
            for (File file : pending) {
                if (!file.isFile() || !file.delete()) {
                    throw new IllegalStateException("Nao foi possivel limpar a fila de comandos.");
                }
            }
            if (!queue.delete()) {
                throw new IllegalStateException("Nao foi possivel reiniciar a fila de comandos.");
            }
        }
        if (!queue.mkdirs()) {
            throw new IllegalStateException("Nao foi possivel criar a fila de comandos.");
        }

        new File(dir, "commands.txt").delete(); // remove o protocolo antigo, se existir
        new File(dir, "status.txt").delete();
        return dir.getAbsolutePath();
    }

    /** Cria o projeto de exemplo na primeira vez que o app roda (e so nela). */
    public static void ensureSampleProject(File gamesDir) {
        FileOps.ensureSampleProject(gamesDir);
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
            drawable.setStroke(Math.max(1, dp(1)), COLOR_BORDER);
        }
        return drawable;
    }

    // ------------------------------------------------------------------
    // Montagem da tela
    // ------------------------------------------------------------------

    private void build() {
        root.setBackgroundColor(COLOR_OUTER);
        ui = new OverlayUi(activity, root);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(COLOR_PANEL, 16, true));
        panel.setPadding(dp(12), dp(12), dp(12), dp(12));

        panel.addView(buildHeader(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerLp.setMargins(0, dp(10), 0, dp(10));
        panel.addView(divider, dividerLp);

        panel.addView(buildSearch(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(activity);
        treeBox = new LinearLayout(activity);
        treeBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(treeBox, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollLp.setMargins(0, dp(10), 0, 0);
        panel.addView(scroll, scrollLp);

        // Mensagem do executor (Lua) no pe do painel.
        statusView = new TextView(activity);
        statusView.setText("Aguardando o executor...");
        statusView.setTextColor(COLOR_MUTED);
        statusView.setTextSize(12);
        statusView.setMaxLines(3);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        statusView.setPadding(dp(4), dp(8), dp(4), 0);
        panel.addView(statusView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Rodape do painel: botoes de acao (perto dos dedos).
        View footerDivider = new View(activity);
        footerDivider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams footerDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        footerDividerLp.setMargins(0, dp(8), 0, dp(10));
        panel.addView(footerDivider, footerDividerLp);

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.addView(footerIconButton(IconView.PLUS, v -> showAddMenu(v)), footerButtonParams(true));
        footer.addView(footerGlyphButton("\u21BB", v -> renderTree()), footerButtonParams(false));
        footer.addView(footerGlyphButton("\u21C4", v -> {
            onLeft = !onLeft;
            applyLayout();
            saveState();
        }), footerButtonParams(false));
        panel.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // Controles do jogo: a versao da barra flutuante continua disponivel
        // quando o editor esta fechado ou em tela cheia.
        playIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        playButton = new LinearLayout(activity);
        playButton.setGravity(Gravity.CENTER);
        playButton.setBackground(box(COLOR_FIELD, 22, false));
        playButton.addView(playIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        playButton.setOnClickListener(v -> togglePlayback());

        // Console: o botao sera inserido no rodape do editor.
        consoleButton = new LinearLayout(activity);
        consoleButton.setGravity(Gravity.CENTER);
        consoleButton.setBackground(box(COLOR_FIELD, 22, false));
        consoleButton.addView(new IconView(activity, IconView.CONSOLE, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        consoleButton.setOnClickListener(v -> {
            consoleOpen = !consoleOpen;
            applyLayout();
            if (consoleOpen) {
                updateConsole();
            }
        });
        consolePanel = buildConsolePanel();

        // Reset: mantem a acao tanto na barra flutuante quanto no rodape do editor.
        resetButton = new TextView(activity);
        resetButton.setText("\u21BA");
        resetButton.setGravity(Gravity.CENTER);
        resetButton.setTextColor(COLOR_TEXT);
        resetButton.setTextSize(20);
        resetButton.setBackground(box(COLOR_FIELD, 22, false));
        resetButton.setOnClickListener(v -> resetScene());

        // Tela cheia continua sendo um controle separado da barra do editor.
        fullscreenIcon = new IconView(activity, IconView.FULLSCREEN, COLOR_TEXT);
        LinearLayout fullscreenButton = new LinearLayout(activity);
        fullscreenButton.setGravity(Gravity.CENTER);
        fullscreenButton.setBackground(box(COLOR_FIELD, 22, false));
        fullscreenButton.addView(fullscreenIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        fullscreenButton.setOnClickListener(v -> setFullscreen(!fullscreen));

        // Barra flutuante usada quando o editor esta fechado ou em tela cheia.
        controlsBar = new LinearLayout(activity);
        controlsBar.setOrientation(LinearLayout.HORIZONTAL);
        controlsBar.setGravity(Gravity.CENTER_VERTICAL);
        controlsBar.setBackground(box(COLOR_PANEL, 30, true));
        controlsBar.setPadding(dp(BAR_PAD_DP), dp(BAR_PAD_DP), dp(BAR_PAD_DP), dp(BAR_PAD_DP));
        controlsBar.setVisibility(View.GONE);
        controlsBar.addView(playButton, barButtonParams(true));
        controlsBar.addView(resetButton, barButtonParams(false));
        controlsBar.addView(fullscreenButton, barButtonParams(false));

        // Controles equivalentes na barra inferior do editor.
        footerPlayIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        LinearLayout footerPlayButton = new LinearLayout(activity);
        footerPlayButton.setGravity(Gravity.CENTER);
        footerPlayButton.setBackground(box(COLOR_FIELD, 22, false));
        footerPlayButton.addView(footerPlayIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        footerPlayButton.setOnClickListener(v -> togglePlayback());

        TextView footerResetButton = new TextView(activity);
        footerResetButton.setText("\u21BA");
        footerResetButton.setGravity(Gravity.CENTER);
        footerResetButton.setTextColor(COLOR_TEXT);
        footerResetButton.setTextSize(20);
        footerResetButton.setBackground(box(COLOR_FIELD, 22, false));
        footerResetButton.setOnClickListener(v -> resetScene());

        placeholder = buildPlaceholder();

        editor = new CodeEditor(activity, new CodeEditor.Listener() {
            @Override
            public void onSaved(File file) {
                onFileSaved(file);
            }

            @Override
            public void onClosed() {
                applyLayout();
                renderTree();
                saveState();
            }
        });
        editor.addFooterControl(footerPlayButton, CONTROL_BUTTON_DP, 0);
        editor.addFooterControl(footerResetButton, CONTROL_BUTTON_DP, BAR_GAP_DP);
        editor.addFooterControl(consoleButton, CONTROL_BUTTON_DP, BAR_GAP_DP);
        editorView = editor.getView();
        editorView.setVisibility(View.GONE);

        root.addView(placeholder);
        root.addView(editorView);
        root.addView(panel);
        root.addView(consolePanel);
        root.addView(controlsBar);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                startStatusPolling();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                stopStatusPolling();
                editor.saveNow();
            }
        });

        // Acompanha as barras do sistema (relogio no topo, gestos embaixo).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getStableInsetBottom();
            insetBottomIme = insets.getSystemWindowInsetBottom();
            applyLayout();
            return insets;
        });

        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                root.post(this::applyLayout);
            }
        });

        renderTree();
        applyLayout();
        root.requestApplyInsets();
        restoreState();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        header.addView(new IconView(activity, IconView.FOLDER, COLOR_FOLDER),
            new LinearLayout.LayoutParams(dp(22), dp(22)));

        TextView name = new TextView(activity);
        projectTitle = name;
        name.setText(activeProject != null ? activeProject.getName() : "CodeLove");
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameLp.setMargins(dp(10), 0, dp(8), 0);
        header.addView(name, nameLp);

        LinearLayout info = new LinearLayout(activity);
        info.setGravity(Gravity.CENTER);
        info.setBackground(box(COLOR_FIELD, 10, false));
        info.addView(new IconView(activity, IconView.INFO, COLOR_MUTED),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        info.setOnClickListener(v -> showAbout());
        header.addView(info, new LinearLayout.LayoutParams(dp(32), dp(32)));

        return header;
    }

    private LinearLayout footerIconButton(int kind, View.OnClickListener listener) {
        LinearLayout button = new LinearLayout(activity);
        button.setGravity(Gravity.CENTER);
        button.setBackground(box(COLOR_FIELD, 12, false));
        button.addView(new IconView(activity, kind, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        button.setOnClickListener(listener);
        return button;
    }

    private TextView footerGlyphButton(String glyph, View.OnClickListener listener) {
        TextView button = new TextView(activity);
        button.setText(glyph);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(20);
        button.setBackground(box(COLOR_FIELD, 12, false));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams footerButtonParams(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        if (!first) {
            lp.setMargins(dp(8), 0, 0, 0);
        }
        return lp;
    }

    private LinearLayout.LayoutParams barButtonParams(boolean first) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            dp(CONTROL_BUTTON_DP), dp(CONTROL_BUTTON_DP));
        if (!first) {
            lp.setMargins(dp(BAR_GAP_DP), 0, 0, 0);
        }
        return lp;
    }

    private static final String CREDITS =
        "O CodeLove \u00E9 um projeto derivado (fork) do L\u00D6VE para Android, modificado para "
        + "trazer um ambiente de trabalho completo dentro do pr\u00F3prio app: pastas e arquivos, "
        + "editor de c\u00F3digo, jogo ao vivo e console.\n\n"
        + "Todo o motor que executa os jogos \u00E9 o L\u00D6VE (love2d.org), um framework livre para "
        + "jogos 2D feito e mantido pela equipe LOVE Development Team e por muitos colaboradores. "
        + "O CodeLove s\u00F3 existe gra\u00E7as a esse trabalho. Muito obrigado!\n\n"
        + "O CodeLove \u00E9 uma vers\u00E3o modificada e n\u00E3o \u00E9 o L\u00D6VE original. "
        + "Ele n\u00E3o \u00E9 afiliado nem endossado pelo projeto L\u00D6VE.\n\n"
        + "O L\u00D6VE \u00E9 distribu\u00EDdo sob a licen\u00E7a zlib, e o app usa outras "
        + "bibliotecas livres, cada uma com a sua licen\u00E7a. Toque em \"Licen\u00E7as\" para ler "
        + "todos os avisos.";

    private void showAbout() {
        ui.showAbout("joa-no", CREDITS, readAssetText("codelove-licenses.txt"));
    }

    /** Le um arquivo de texto de dentro do app (assets). */
    private String readAssetText(String name) {
        try (java.io.InputStream in = activity.getAssets().open(name)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "O arquivo de licencas (" + name + ") nao foi encontrado neste app.\n\n"
                + "Licenca do LOVE: zlib. Copyright (c) 2006-2024 LOVE Development Team. "
                + "Veja love2d.org.";
        }
    }

    /** Tela cheia: some tudo (pastas, editor, console) e o jogo ocupa a area toda. */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        if (ui != null) {
            ui.dismiss();
        }
        fullscreenIcon.setKind(on ? IconView.FULLSCREEN_EXIT : IconView.FULLSCREEN);
        applyLayout();
    }

    private View buildSearch() {
        search = new EditText(activity);
        search.setHint("Buscar arquivos...");
        search.setHintTextColor(COLOR_MUTED);
        search.setTextColor(COLOR_TEXT);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(box(COLOR_FIELD, 12, true));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        // Sem a tela cheia de edicao do teclado na horizontal.
        search.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderTree();
            }
        });
        search.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager imm =
                (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
            }
            surface.requestFocus();
            return true;
        });
        return search;
    }

    // ------------------------------------------------------------------
    // Posicao do painel, do botao e da area do jogo
    // ------------------------------------------------------------------

    private void applyLayout() {
        int sideInset = onLeft ? insetLeft : insetRight;
        boolean panelShown = !fullscreen;
        boolean editorOpen = editor != null && editor.isOpen() && !fullscreen;
        if (ui != null) {
            ui.topInset = insetTop;
        }

        // Tamanho da area de jogo cheia: a tela sem as barras do sistema.
        float baseW = root.getWidth() - insetLeft - insetRight;
        float baseH = root.getHeight() - insetTop - insetBottom;

        // Da borda da tela para dentro: [painel] [editor].
        // O editor ocupa sempre todo o espaco restante, sem modo expandido.
        int cursor = sideInset;
        int panelOffset = 0;
        int editorOffset = 0;
        if (panelShown) {
            panelOffset = cursor + margin;
            cursor += margin + panelWidth + margin;
        }
        int editorWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        if (editorOpen) {
            editorOffset = panelShown ? cursor : cursor + margin;
            if (baseW > 0f) {
                editorWidth = Math.max(0, (int) baseW - (editorOffset - sideInset) - margin);
                cursor = editorOffset + editorWidth + margin;
            } else {
                cursor = editorOffset + margin;
            }
        }
        int reserved = cursor - sideInset;

        // O console fica dentro da area visivel do editor, acima do rodape.
        // Usa a altura REAL disponivel (incluindo o teclado), para nunca subir
        // para fora da tela quando o teclado virtual estiver aberto.
        int barHeight = dp(CONTROL_BUTTON_DP) + 2 * dp(BAR_PAD_DP);
        int editorVisibleHeight = Math.max(0, root.getHeight()
            - insetTop - margin - insetBottomIme - margin);
        int consoleFooterSpace = dp(71); // padding inferior + divisor/margens + barra
        int consoleAvailableHeight = Math.max(0, editorVisibleHeight - consoleFooterSpace);
        int consoleHeight = Math.min(dp(220),
            Math.min((int) (editorVisibleHeight * 0.38f), consoleAvailableHeight));

        // A area do jogo SEMPRE tem o mesmo tamanho: a tela cheia, sem as barras do
        // sistema. Assim o jogo nao muda de resolucao quando o painel ou o teclado mudam.
        RelativeLayout.LayoutParams surfaceLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceLp.leftMargin = insetLeft;
        surfaceLp.topMargin = insetTop;
        surfaceLp.rightMargin = insetRight;
        surfaceLp.bottomMargin = insetBottom;
        surface.setLayoutParams(surfaceLp);

        // O jogo so encolhe (mesma proporcao) e vai para o espaco que sobrou,
        // centralizado na vertical. Nao e esticado nem espremido.
        float scale = 1f;
        float shiftX = 0f;
        float shiftY = 0f;
        if (baseW > 0f && baseH > 0f && reserved > 0) {
            float availW = Math.max(1f, baseW - reserved);
            float availH = baseH;
            scale = Math.min(1f, Math.min(availW / baseW, availH / baseH));
            shiftX = (onLeft ? reserved : 0f) + (availW - baseW * scale) / 2f;
            shiftY = (availH - baseH * scale) / 2f;
        }
        surface.setPivotX(0f);
        surface.setPivotY(0f);
        surface.setScaleX(scale);
        surface.setScaleY(scale);
        surface.setTranslationX(shiftX);
        surface.setTranslationY(shiftY);

        // Aviso "nenhum arquivo selecionado": ocupa a mesma area do jogo.
        RelativeLayout.LayoutParams placeholderLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        placeholderLp.topMargin = insetTop;
        placeholderLp.bottomMargin = insetBottom;
        placeholderLp.leftMargin = insetLeft + (onLeft ? reserved : 0);
        placeholderLp.rightMargin = insetRight + (onLeft ? 0 : reserved);
        placeholder.setLayoutParams(placeholderLp);

        // Painel de arquivos (acompanha o teclado: sobe junto com ele).
        RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        panelLp.topMargin = insetTop + margin;
        panelLp.bottomMargin = insetBottomIme + margin;
        if (onLeft) {
            panelLp.leftMargin = panelOffset;
        } else {
            panelLp.rightMargin = panelOffset;
        }
        panel.setLayoutParams(panelLp);
        panel.setVisibility(panelShown ? View.VISIBLE : View.GONE);

        // Editor de codigo: ocupa todo o espaco restante e acompanha o teclado.
        int editorLayoutWidth = editorWidth;
        RelativeLayout.LayoutParams editorLp = new RelativeLayout.LayoutParams(
            editorLayoutWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        editorLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        editorLp.topMargin = insetTop + margin;
        editorLp.bottomMargin = insetBottomIme + margin;
        if (onLeft) {
            editorLp.leftMargin = editorOffset;
        } else {
            editorLp.rightMargin = editorOffset;
        }
        editorView.setLayoutParams(editorLp);
        editorView.setVisibility(editorOpen ? View.VISIBLE : View.GONE);

        // Barra de controles do jogo: embaixo. Centralizada na area do jogo; em tela
        // cheia vira uma barrinha translucida no canto, para nao atrapalhar.
        int buttonCount = 3;
        int barWidth = buttonCount * dp(CONTROL_BUTTON_DP) + (buttonCount - 1) * dp(BAR_GAP_DP)
            + 2 * dp(BAR_PAD_DP);
        int regionStart = insetLeft + (onLeft ? reserved : 0);
        int regionWidth = root.getWidth() - insetLeft - insetRight - reserved;
        int barX;
        if (root.getWidth() <= 0) {
            barX = 0;
        } else if (fullscreen) {
            barX = onLeft ? insetLeft + margin : root.getWidth() - insetRight - margin - barWidth;
        } else {
            barX = regionStart + Math.max(0, (regionWidth - barWidth) / 2);
        }
        RelativeLayout.LayoutParams barLp = new RelativeLayout.LayoutParams(barWidth, barHeight);
        barLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        barLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        barLp.leftMargin = barX;
        barLp.bottomMargin = insetBottom + margin;
        controlsBar.setLayoutParams(barLp);
        controlsBar.setAlpha(fullscreen ? 0.55f : 1f);

        // Console integrado ao editor: ocupa a largura interna do editor e
        // termina imediatamente antes do divisor/rodape de controles.
        RelativeLayout.LayoutParams consoleLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, consoleHeight);
        consoleLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        consoleLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        int consoleInset = dp(12);
        if (onLeft) {
            consoleLp.leftMargin = editorOffset + consoleInset;
            consoleLp.rightMargin = insetRight + margin + consoleInset;
        } else {
            consoleLp.rightMargin = editorOffset + consoleInset;
            consoleLp.leftMargin = insetLeft + margin + consoleInset;
        }
        consoleLp.bottomMargin = insetBottomIme + margin + consoleFooterSpace;
        consolePanel.setLayoutParams(consoleLp);
        consolePanel.setVisibility((consoleOpen && editorOpen && consoleHeight > 0)
            ? View.VISIBLE : View.GONE);

        updateControlsVisibility();
    }

    // ------------------------------------------------------------------
    // Arvore de arquivos
    // ------------------------------------------------------------------

    private static final class Row {
        final File file;
        final int depth;
        final boolean dir;
        final boolean expanded;

        Row(File file, int depth, boolean dir, boolean expanded) {
            this.file = file;
            this.depth = depth;
            this.dir = dir;
            this.expanded = expanded;
        }
    }

    private void renderTree() {
        treeBox.removeAllViews();

        File treeRoot = (gamesDir != null && gamesDir.isDirectory()) ? gamesDir : activeProject;
        if (treeRoot == null) {
            TextView note = new TextView(activity);
            note.setText("Pasta de projetos indisponivel.");
            note.setTextColor(COLOR_MUTED);
            note.setTextSize(13);
            note.setPadding(dp(4), dp(4), dp(4), dp(4));
            treeBox.addView(note);
            return;
        }

        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<Row> rows = new ArrayList<Row>();
        collect(treeRoot, 0, query, rows);

        if (rows.isEmpty()) {
            TextView note = new TextView(activity);
            note.setText(query.isEmpty() ? "Nenhum projeto ainda." : "Nada encontrado.");
            note.setTextColor(COLOR_MUTED);
            note.setTextSize(13);
            note.setPadding(dp(4), dp(4), dp(4), dp(4));
            treeBox.addView(note);
            return;
        }

        for (Row row : rows) {
            treeBox.addView(buildRow(row), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_HEIGHT_DP)));
        }
    }

    /** Monta a lista de linhas visiveis. Devolve true se achou algo nesta pasta. */
    private boolean collect(File dir, int depth, String query, ArrayList<Row> out) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        File[] kids = dir.listFiles();
        if (kids == null) {
            return false;
        }
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) {
                    return da ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });

        boolean any = false;
        for (File f : kids) {
            boolean isDir = f.isDirectory();
            boolean nameMatch = query.isEmpty()
                || f.getName().toLowerCase(Locale.ROOT).contains(query);

            if (isDir) {
                if (query.isEmpty()) {
                    boolean isOpen = expanded.contains(f.getAbsolutePath());
                    out.add(new Row(f, depth, true, isOpen));
                    if (isOpen) {
                        collect(f, depth + 1, query, out);
                    }
                    any = true;
                } else {
                    int mark = out.size();
                    out.add(new Row(f, depth, true, true));
                    boolean childMatch = collect(f, depth + 1, query, out);
                    if (childMatch || nameMatch) {
                        any = true;
                    } else {
                        while (out.size() > mark) {
                            out.remove(out.size() - 1);
                        }
                    }
                }
            } else if (nameMatch) {
                out.add(new Row(f, depth, false, false));
                any = true;
            }
        }
        return any;
    }

    private View buildRow(final Row row) {
        LinearLayout line = new LinearLayout(activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(4), 0, dp(4), 0);
        boolean selected = (activeProject != null
                && row.file.getAbsolutePath().equals(activeProject.getAbsolutePath()))
            || (editor != null && editor.isOpen()
                && row.file.getAbsolutePath().equals(editor.getFile().getAbsolutePath()));
        if (selected) {
            line.setBackground(box(COLOR_FIELD, 10, false));
        }

        // Linhas guia verticais, uma por nivel de profundidade.
        for (int i = 0; i < row.depth; i++) {
            line.addView(new GuideView(activity), new LinearLayout.LayoutParams(
                dp(LEVEL_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // Seta de abrir/fechar (so para pastas).
        if (row.dir) {
            line.addView(new IconView(activity,
                    row.expanded ? IconView.CHEVRON_DOWN : IconView.CHEVRON_RIGHT, COLOR_MUTED),
                new LinearLayout.LayoutParams(dp(14), dp(14)));
        } else {
            line.addView(new View(activity), new LinearLayout.LayoutParams(dp(14), dp(14)));
        }

        // Icone da pasta ou do arquivo.
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.setMargins(dp(6), 0, dp(8), 0);
        line.addView(new IconView(activity,
            row.dir ? IconView.FOLDER : IconView.FILE, colorFor(row)), iconLp);

        TextView label = new TextView(activity);
        label.setText(row.file.getName());
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        line.addView(label, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        line.setOnClickListener(v -> onRowClicked(row));
        line.setOnLongClickListener(v -> {
            showRowMenu(row, v);
            return true;
        });
        return line;
    }

    /** Pasta de projeto (filho direto da pasta de jogos) que contem este arquivo. */
    private File projectOf(File file) {
        if (gamesDir == null) {
            return null;
        }
        File current = file;
        while (current != null) {
            File parent = current.getParentFile();
            if (parent != null && parent.getAbsolutePath().equals(gamesDir.getAbsolutePath())) {
                return current;
            }
            current = parent;
        }
        return null;
    }

    private void onRowClicked(Row row) {
        if (row.dir) {
            String path = row.file.getAbsolutePath();
            if (!expanded.remove(path)) {
                expanded.add(path);
            }
        }

        // Tocar em qualquer pasta ou arquivo de outro projeto abre esse projeto.
        File project = projectOf(row.file);
        if (project != null
            && (activeProject == null
                || !project.getAbsolutePath().equals(activeProject.getAbsolutePath()))) {
            selectProject(project);
            rememberFolder(row);
            if (!row.dir) {
                openInEditor(row.file);
            }
            return;
        }

        rememberFolder(row);
        if (row.dir) {
            renderTree();
        } else {
            openInEditor(row.file);
        }
    }

    // ------------------------------------------------------------------
    // Criar, renomear e apagar
    // ------------------------------------------------------------------

    private static boolean sameFile(File a, File b) {
        return a.getAbsolutePath().equals(b.getAbsolutePath());
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Aviso persistente do CodeLove para erros de selecao de projeto.
     * O texto pode ter qualquer tamanho pratico: a area central rola quando
     * ultrapassa a altura disponivel do painel.
     */
    private void showProjectNotice(String message) {
        CrashDiagnostics.log(activity, "Project notice shown");
        showNotice("Nao foi possivel adicionar o projeto", message);
    }

    private void showNotice(String titleText, String message) {
        if (projectNotice != null) {
            root.removeView(projectNotice);
            projectNotice = null;
        }

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(box(COLOR_PANEL, 18, true));
        sheet.setPadding(dp(16), dp(14), dp(16), dp(12));
        sheet.setElevation(dp(12));

        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sheet.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView messageScroll = new ScrollView(activity);
        messageScroll.setFillViewport(false);
        messageScroll.setVerticalScrollBarEnabled(true);
        TextView body = new TextView(activity);
        body.setText(message == null ? "Ocorreu um erro inesperado." : message);
        body.setTextColor(COLOR_MUTED);
        body.setTextSize(13);
        body.setLineSpacing(dp(2), 1.0f);
        body.setTextIsSelectable(true);
        body.setPadding(0, dp(9), dp(4), dp(9));
        messageScroll.addView(body, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams messageLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        messageLp.setMargins(0, dp(2), 0, dp(10));
        sheet.addView(messageScroll, messageLp);

        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_BORDER);
        sheet.addView(divider, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(10), 0, 0);

        TextView close = new TextView(activity);
        close.setText("Fechar");
        close.setTextColor(COLOR_MUTED);
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(COLOR_FIELD, 10, true));
        close.setPadding(dp(12), 0, dp(12), 0);
        actions.addView(close, new LinearLayout.LayoutParams(0, dp(42), 1f));
        close.setOnClickListener(v -> dismissProjectNotice());

        View gap = new View(activity);
        actions.addView(gap, new LinearLayout.LayoutParams(dp(10), 1));

        TextView retry = new TextView(activity);
        retry.setText("Selecionar novamente");
        retry.setTextColor(0xFF1B1F2B);
        retry.setTextSize(13);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(box(0xFFA78BFA, 10, false));
        retry.setPadding(dp(12), 0, dp(12), 0);
        actions.addView(retry, new LinearLayout.LayoutParams(0, dp(42), 1.5f));
        retry.setOnClickListener(v -> {
            dismissProjectNotice();
            addExistingProject();
        });
        sheet.addView(actions);

        int availableWidth = Math.max(dp(280), root.getWidth() - dp(32));
        int sheetWidth = Math.min((int) (availableWidth * 0.82f), dp(540));
        RelativeLayout.LayoutParams sheetLp = new RelativeLayout.LayoutParams(
            sheetWidth, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        sheetLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        sheetLp.setMargins(0, 0, 0, Math.max(dp(12), insetBottom));
        projectNotice = sheet;
        root.addView(sheet, sheetLp);
        sheet.bringToFront();
    }

    private void dismissProjectNotice() {
        if (projectNotice != null) {
            root.removeView(projectNotice);
            projectNotice = null;
        }
    }

    private void rememberFolder(Row row) {
        if (activeProject != null && isInside(row.file, activeProject)) {
            lastFolder = row.dir ? row.file : row.file.getParentFile();
        }
    }

    /** Pasta onde um arquivo novo cai pelo botao "+": a ultima tocada, ou a raiz do projeto. */
    private File targetFolder() {
        if (activeProject == null) {
            return null;
        }
        if (lastFolder != null && lastFolder.isDirectory() && isInside(lastFolder, activeProject)) {
            return lastFolder;
        }
        return activeProject;
    }

    private void showAddMenu(View anchor) {
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        labels.add("Novo projeto");
        actions.add(() -> newProject());
        labels.add("Adicionar projeto existente");
        actions.add(() -> addExistingProject());
        final File folder = targetFolder();
        if (folder != null) {
            labels.add("Novo arquivo em " + folder.getName());
            actions.add(() -> newFile(folder));
            labels.add("Nova pasta em " + folder.getName());
            actions.add(() -> newFolder(folder));
        }
        ui.showMenu(anchor, labels, actions);
    }

    private void addExistingProject() {
        CrashDiagnostics.log(activity, "SAF picker launch requested");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_ADD_PROJECT_TREE);
        } catch (Exception e) {
            toast("Nao foi possivel abrir o seletor de pastas do Android.");
            Log.e(TAG, "Falha ao abrir o seletor SAF", e);
        }
    }

    static void handleActivityResult(Activity activity, int requestCode,
                                     int resultCode, Intent data) {
        if (requestCode != REQUEST_ADD_PROJECT_TREE) {
            return;
        }
        Workspace workspace = currentInstance;
        if (workspace == null || workspace.activity != activity
            || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        try {
            CrashDiagnostics.log(activity, "SAF result received; resultCode=RESULT_OK");
            Uri treeUri = data.getData();
            int grantedFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            CrashDiagnostics.log(activity, "SAF inspectProject started");
            SafProjectManager.ProjectSelection selection =
                SafProjectManager.inspectProject(activity, treeUri);
            CrashDiagnostics.log(activity, "SAF inspectProject finished; valid=" + selection.isValid());
            if (!selection.isValid()) {
                workspace.showProjectNotice(selection.error);
                return;
            }

            CrashDiagnostics.log(activity, "SAF register started");
            String error = SafProjectManager.register(activity, treeUri, grantedFlags,
                selection.documentId, selection.displayName);
            CrashDiagnostics.log(activity, "SAF register finished; success=" + (error == null));
            if (error != null) {
                workspace.showProjectNotice(error);
            } else {
                workspace.toast("Importando projeto para o armazenamento local...");
                final Workspace targetWorkspace = workspace;
                final Uri selectedTreeUri = treeUri;
                final SafProjectManager.ProjectSelection selectedProject = selection;
                new Thread(() -> {
                    SafProjectManager.ProjectCopy copy = SafProjectManager.ensureWorkingCopy(
                        activity, selectedTreeUri, selectedProject.documentId,
                        selectedProject.displayName, targetWorkspace.gamesDir);
                    activity.runOnUiThread(() -> {
                        if (currentInstance != targetWorkspace || targetWorkspace.disposed
                            || activity.isFinishing()) {
                            return;
                        }
                        if (!copy.isSuccess()) {
                            targetWorkspace.showProjectNotice(copy.error);
                        } else {
                            targetWorkspace.expanded.add(copy.directory.getAbsolutePath());
                            targetWorkspace.renderTree();
                            targetWorkspace.toast("Projeto importado como copia local. As alteracoes ainda nao sao sincronizadas com a pasta original.");
                        }
                    });
                }, "CodeLove-SAF-Import").start();
            }
        } catch (RuntimeException e) {
            CrashDiagnostics.log(activity, "SAF result handler caught RuntimeException", e);
            Log.e(TAG, "Falha inesperada ao processar a pasta selecionada pelo SAF", e);
            workspace.showProjectNotice(
                "O Android encontrou um erro inesperado ao processar essa pasta. "
                + "O erro foi registrado para diagnostico. Tente novamente; se continuar, envie o registro do aplicativo.");
        }
    }

    private void showRowMenu(Row row, View anchor) {
        final File file = row.file;
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        if (row.dir) {
            labels.add("Novo arquivo aqui");
            actions.add(() -> newFile(file));
            labels.add("Nova pasta aqui");
            actions.add(() -> newFolder(file));
        }
        labels.add("Renomear");
        actions.add(() -> rename(file));
        labels.add("Excluir");
        actions.add(() -> delete(file));
        ui.showMenu(anchor, labels, actions);
    }

    private void newProject() {
        if (gamesDir == null) {
            toast("Pasta de projetos indisponivel.");
            return;
        }
        ui.askText("Novo projeto", "", "Criar",
            text -> FileOps.checkName(gamesDir, text),
            text -> {
                File project = FileOps.createProject(gamesDir, text);
                if (project == null) {
                    toast("Nao consegui criar o projeto.");
                    return;
                }
                selectProject(project);
                openInEditor(new File(project, "main.lua"));
            });
    }

    private void newFile(final File folder) {
        ui.askText("Novo arquivo em " + folder.getName(), "", "Criar",
            text -> FileOps.checkName(folder, FileOps.withDefaultExtension(text)),
            text -> {
                File file = FileOps.createFile(folder, FileOps.withDefaultExtension(text));
                if (file == null) {
                    toast("Nao consegui criar o arquivo.");
                    return;
                }
                enterProjectOf(file);
                expanded.add(folder.getAbsolutePath());
                lastFolder = folder;
                renderTree();
                openInEditor(file);
            });
    }

    private void newFolder(final File parent) {
        ui.askText("Nova pasta em " + parent.getName(), "", "Criar",
            text -> FileOps.checkName(parent, text),
            text -> {
                File folder = FileOps.createFolder(parent, text);
                if (folder == null) {
                    toast("Nao consegui criar a pasta.");
                    return;
                }
                enterProjectOf(folder);
                expanded.add(parent.getAbsolutePath());
                expanded.add(folder.getAbsolutePath());
                lastFolder = folder;
                renderTree();
            });
    }

    /** Se o item novo e de outro projeto, passa a trabalhar nesse projeto. */
    private void enterProjectOf(File file) {
        File project = projectOf(file);
        if (project != null && (activeProject == null || !sameFile(project, activeProject))) {
            selectProject(project);
        }
    }

    private void rename(final File target) {
        final File parent = target.getParentFile();
        ui.askText("Renomear", target.getName(), "Renomear",
            text -> text.equals(target.getName()) ? null : FileOps.checkName(parent, text),
            text -> {
                if (!text.equals(target.getName())) {
                    doRename(target, new File(parent, text));
                }
            });
    }

    private void doRename(File target, File dest) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        File editedFile = editor.isOpen() ? editor.getFile() : null;

        // grava e fecha o editor se o arquivo aberto esta no que vai mudar de nome
        if (editedFile != null && isInside(editedFile, target) && !editor.close()) {
            return;
        }
        if (isActiveProject) {
            sendCommand("close", "");
        }

        if (!target.renameTo(dest)) {
            toast("Nao consegui renomear.");
            if (isActiveProject) {
                sendCommand("open", target.getAbsolutePath());
            }
            renderTree();
            return;
        }

        // acompanha o novo caminho nas pastas abertas
        String oldPath = target.getAbsolutePath();
        String newPath = dest.getAbsolutePath();
        java.util.Set<String> next = new HashSet<String>();
        for (String path : expanded) {
            if (path.equals(oldPath)) {
                next.add(newPath);
            } else if (path.startsWith(oldPath + File.separator)) {
                next.add(newPath + path.substring(oldPath.length()));
            } else {
                next.add(path);
            }
        }
        expanded.clear();
        expanded.addAll(next);
        lastFolder = null;

        if (isActiveProject) {
            activeProject = dest;
            projectTitle.setText(dest.getName());
            playing = false;
            sendCommand("open", dest.getAbsolutePath());
            updatePlayButton();
        } else if (activeProject != null && isInside(target, activeProject)) {
            playing = false;
            sendCommand("open", activeProject.getAbsolutePath());
            updatePlayButton();
        }

        renderTree();
        if (editedFile != null && sameFile(editedFile, target)) {
            openInEditor(dest); // era este arquivo que estava aberto
        }
        saveState();
    }

    private void delete(final File target) {
        String what = "\"" + target.getName() + "\"";
        String message = target.isDirectory()
            ? "Excluir a pasta " + what + " e tudo que esta dentro dela? Isso nao pode ser desfeito."
            : "Excluir o arquivo " + what + "? Isso nao pode ser desfeito.";
        ui.confirm(message, "Excluir", () -> doDelete(target));
    }

    private void doDelete(File target) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);

        if (editor.isOpen() && isInside(editor.getFile(), target) && !editor.close()) {
            return;
        }
        if (isActiveProject) {
            sendCommand("close", "");
        }

        if (!FileOps.deleteRecursive(target)) {
            toast("Nao consegui apagar tudo.");
        }
        expanded.remove(target.getAbsolutePath());
        lastFolder = null;

        if (isActiveProject) {
            activeProject = null;
            playing = false;
            projectTitle.setText("CodeLove");
            placeholder.setVisibility(View.VISIBLE);
            updatePlayButton();
        } else if (activeProject != null && isInside(target, activeProject)) {
            playing = false;
            sendCommand("open", activeProject.getAbsolutePath());
            updatePlayButton();
        }
        renderTree();
        saveState();
    }

    private void openInEditor(File file) {
        if (editor.open(file)) {
            applyLayout();
            renderTree();
            saveState();
        } else if (!editor.hasUnsavedChanges()) {
            Toast.makeText(activity, "Esse arquivo nao abre no editor (binario ou grande demais).",
                Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isInside(File file, File dir) {
        String f = file.getAbsolutePath();
        String d = dir.getAbsolutePath();
        return f.equals(d) || f.startsWith(d + File.separator);
    }

    /** Depois de salvar: recarrega o projeto no executor, e o 1o quadro mostra o codigo novo. */
    private void onFileSaved(File file) {
        if (activeProject != null && isInside(file, activeProject)) {
            playing = false;
            sendCommand("open", activeProject.getAbsolutePath());
            updatePlayButton();
        }
    }

    // ------------------------------------------------------------------
    // Projeto aberto, play/pause e canal com o executor (Lua)
    // ------------------------------------------------------------------

    /** Abre o projeto no executor SEM sair desta tela. O jogo comeca pausado. */
    private void selectProject(File project) {
        if (editor != null && editor.isOpen() && !isInside(editor.getFile(), project)
            && !editor.close()) {
            return;
        }
        activeProject = project;
        lastFolder = null;
        expanded.add(project.getAbsolutePath());
        playing = false;
        sendCommand("open", project.getAbsolutePath());
        projectTitle.setText(project.getName());
        placeholder.setVisibility(View.GONE);
        updatePlayButton();
        renderTree();
        saveState();
    }

    private void togglePlayback() {
        playing = !playing;
        sendCommand(playing ? "play" : "pause", "");
        updatePlayButton();
    }

    private void updatePlayButton() {
        int kind = playing ? IconView.PAUSE : IconView.PLAY;
        playIcon.setKind(kind);
        footerPlayIcon.setKind(kind);
        updateControlsVisibility();
    }

    /**
     * Com o editor aberto, os controles ficam no rodape dele. A barra flutuante
     * permanece para quando o editor esta fechado ou em tela cheia.
     */
    private void updateControlsVisibility() {
        boolean editorVisible = editor != null && editor.isOpen() && !fullscreen;
        boolean showBar = activeProject != null && !editorVisible;
        controlsBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        consolePanel.setVisibility((consoleOpen && editorVisible) ? View.VISIBLE : View.GONE);
    }

    /** Volta a cena ao inicio: recarrega o projeto, pausado, no primeiro quadro. */
    private void resetScene() {
        if (activeProject == null) {
            return;
        }
        if (!editor.saveNow()) {
            return;
        }
        playing = false;
        sendCommand("open", activeProject.getAbsolutePath());
        updatePlayButton();
    }

    /**
     * Publica um comando como arquivo independente. O rename do temporario
     * torna a publicacao atomica para o leitor Lua: ele nunca ve um comando parcial.
     */
    private synchronized void sendCommand(String command, String argument) {
        File queue = new File(channelDir, "commands");
        if (!queue.isDirectory() && !queue.mkdirs()) {
            Log.w(TAG, "Sem pasta da fila de comandos: " + queue);
            return;
        }

        long next = commandSequence + 1L;
        String name = String.format(Locale.ROOT, "command-%010d.txt", next);
        File target = new File(queue, name);
        File temp = new File(queue, name + ".tmp");
        byte[] data = (command + "\t" + argument).getBytes(StandardCharsets.UTF_8);

        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } catch (IOException e) {
            temp.delete();
            Log.w(TAG, "Falha ao preparar o comando " + command, e);
            return;
        }

        if (!temp.renameTo(target)) {
            temp.delete();
            Log.w(TAG, "Falha ao publicar o comando " + command);
            return;
        }
        commandSequence = next;
    }

    /** Painel do console: titulo, botao de limpar, botao de fechar e o texto. */
    private LinearLayout buildConsolePanel() {
        LinearLayout panelBox = new LinearLayout(activity);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        // Os cantos superiores arredondados identificam o console; a base reta
        // faz a transicao visual com o rodape do editor, sem parecer uma janela solta.
        GradientDrawable consoleBackground = new GradientDrawable();
        consoleBackground.setColor(COLOR_PANEL);
        consoleBackground.setCornerRadii(new float[] {
            dp(14), dp(14), dp(14), dp(14), 0, 0, 0, 0
        });
        consoleBackground.setStroke(Math.max(1, dp(1)), COLOR_BORDER);
        panelBox.setBackground(consoleBackground);
        panelBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        panelBox.setClickable(true); // nao deixa o toque passar para o jogo
        panelBox.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(activity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText("Console");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clear = new TextView(activity);
        clear.setText("Limpar");
        clear.setTextColor(COLOR_TEXT);
        clear.setTextSize(13);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(12), 0, dp(12), 0);
        clear.setBackground(box(COLOR_FIELD, 10, false));
        clear.setOnClickListener(v -> {
            sendCommand("clearlog", "");
            lastConsole = "";
            consoleText.setText("");
        });
        header.addView(clear, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));

        TextView close = new TextView(activity);
        close.setText("\u00D7");
        close.setTextColor(COLOR_MUTED);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(COLOR_FIELD, 10, false));
        close.setOnClickListener(v -> {
            consoleOpen = false;
            applyLayout();
        });
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        closeLp.setMargins(dp(6), 0, 0, 0);
        header.addView(close, closeLp);

        panelBox.addView(header, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerLp.setMargins(0, dp(8), 0, dp(6));
        panelBox.addView(divider, dividerLp);

        consoleText = new TextView(activity);
        consoleText.setTypeface(Typeface.MONOSPACE);
        consoleText.setTextSize(12);
        consoleText.setTextColor(COLOR_TEXT);
        consoleText.setTextIsSelectable(true); // segure para copiar uma mensagem
        consoleText.setPadding(dp(2), dp(2), dp(2), dp(2));

        consoleScroll = new ScrollView(activity);
        consoleScroll.addView(consoleText, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        panelBox.addView(consoleScroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panelBox;
    }

    /** Le o console.txt (escrito pelo executor) e rola para o fim se mudou. */
    private void updateConsole() {
        String text = readTail(new File(channelDir, "console.txt"), 32768);
        if (text == null) {
            text = "O console ainda nao recebeu nada do executor.\n"
                + "Se isto continuar depois de abrir um projeto, o arquivo\n"
                + "app/src/normal/assets/main.lua nao e a versao 2.";
        }
        if (!text.equals(lastConsole)) {
            lastConsole = text;
            consoleText.setText(text);
            // Rola ate o fim SEM usar fullScroll: ele tambem muda o foco e tirava o
            // cursor (e o teclado) do editor sempre que chegava uma linha nova.
            consoleScroll.post(() -> consoleScroll.scrollTo(0,
                Math.max(0, consoleText.getHeight() - consoleScroll.getHeight())));
        }
    }

    /** Le so o final do arquivo (ate maxBytes), sem a primeira linha se foi cortada. */
    private static String readTail(File file, int maxBytes) {
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            long start = Math.max(0L, length - maxBytes);
            if (start > 0) {
                long skipped = 0;
                while (skipped < start) {
                    long n = in.skip(start - skipped);
                    if (n <= 0) {
                        break;
                    }
                    skipped += n;
                }
            }
            byte[] data = new byte[(int) (length - start)];
            int total = 0;
            while (total < data.length) {
                int read = in.read(data, total, data.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            String text = new String(data, 0, total, StandardCharsets.UTF_8);
            if (start > 0) {
                int cut = text.indexOf('\n');
                if (cut >= 0) {
                    text = text.substring(cut + 1);
                }
            }
            return text;
        } catch (IOException e) {
            return null;
        }
    }

    /** Guarda onde voce parou: projeto aberto, arquivo no editor e lado do painel. */
    private void saveState() {
        prefs.edit()
            .putString("project", activeProject == null ? "" : activeProject.getAbsolutePath())
            .putString("file", (editor != null && editor.isOpen()) ? editor.getFile().getAbsolutePath() : "")
            .putBoolean("panel_left", onLeft)
            .apply();
    }

    /** Ao abrir o app, volta para o projeto e o arquivo em que voce estava. */
    private void restoreState() {
        String projectPath = prefs.getString("project", "");
        String filePath = prefs.getString("file", "");
        if (projectPath.isEmpty() || gamesDir == null) {
            return;
        }
        File project = new File(projectPath);
        if (!project.isDirectory() || sameFile(project, gamesDir) || !isInside(project, gamesDir)) {
            return;
        }
        selectProject(project);
        if (!filePath.isEmpty()) {
            File file = new File(filePath);
            if (file.isFile() && isInside(file, project)) {
                openInEditor(file);
            }
        }
    }

    private static String readText(File file) {
        if (!file.isFile()) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 4096L)];
            int read = in.read(data);
            return read <= 0 ? "" : new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** Mostra, no pe do painel, a ultima mensagem do executor. */
    private boolean hostResumed = false;
    private boolean disposed = false;

    private boolean canPollStatus() {
        return hostResumed && !disposed && currentInstance == this
            && root.isAttachedToWindow() && !activity.isFinishing()
            && (android.os.Build.VERSION.SDK_INT < 17 || !activity.isDestroyed());
    }

    private void startStatusPolling() {
        if (!canPollStatus()) return;
        root.removeCallbacks(statusPoller);
        root.postDelayed(statusPoller, 700);
    }

    private void stopStatusPolling() {
        root.removeCallbacks(statusPoller);
    }

    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
            if (!canPollStatus()) return;

            try {
                String text = readText(new File(channelDir, "status.txt"));
                if (text != null) {
                    text = text.trim();
                    if (!text.isEmpty() && !text.equals(lastStatus)) {
                        lastStatus = text;
                        statusView.setText(text);
                    }
                }
                if (consoleOpen) {
                    updateConsole();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "Falha ao atualizar o status/console do CodeLove", e);
            }

            if (canPollStatus()) {
                root.postDelayed(this, 700);
            }
        }
    };

    /** Aviso que cobre a area do jogo enquanto nenhum projeto estiver aberto. */
    private LinearLayout buildPlaceholder() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(COLOR_OUTER);
        box.setClickable(true); // nao deixa o toque passar para o jogo escondido
        box.setVisibility(activeProject == null ? View.VISIBLE : View.GONE);

        TextView title = new TextView(activity);
        title.setText("Nenhum arquivo selecionado");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView hint = new TextView(activity);
        hint.setText("Escolha um projeto no painel para ver o jogo aqui.");
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, 0);
        box.addView(hint);
        return box;
    }

    private int colorFor(Row row) {
        if (row.dir) {
            return COLOR_FOLDER;
        }
        String name = row.file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".lua")) {
            return COLOR_LUA;
        }
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".gif") || name.endsWith(".webp")) {
            return COLOR_IMAGE;
        }
        return COLOR_OTHER;
    }

    // ------------------------------------------------------------------
    // Icones desenhados por codigo (sem arquivos de imagem)
    // ------------------------------------------------------------------

    static final class IconView extends View {
        static final int FOLDER = 0;
        static final int FILE = 1;
        static final int CHEVRON_RIGHT = 2;
        static final int CHEVRON_DOWN = 3;
        static final int PLAY = 4;
        static final int PAUSE = 5;
        static final int EXPAND = 6;
        static final int COLLAPSE = 7;
        static final int PLUS = 8;
        static final int CONSOLE = 9;
        static final int FULLSCREEN = 10;
        static final int FULLSCREEN_EXIT = 11;
        static final int INFO = 12;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context context, int kind, int color) {
            super(context);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.8f); // em unidades da grade 24x24
            paint.setColor(color);
            setKind(kind);
        }

        /** Troca o desenho do icone. */
        void setKind(int kind) {
            path.reset();
            paint.setStyle((kind == PLAY || kind == PAUSE) ? Paint.Style.FILL : Paint.Style.STROKE);

            // Desenho em uma grade de 24 x 24.
            switch (kind) {
                case FOLDER:
                    path.moveTo(3f, 6.5f);
                    path.lineTo(9f, 6.5f);
                    path.lineTo(11f, 8.5f);
                    path.lineTo(21f, 8.5f);
                    path.lineTo(21f, 18f);
                    path.lineTo(3f, 18f);
                    path.close();
                    break;
                case FILE:
                    path.moveTo(6f, 3f);
                    path.lineTo(14f, 3f);
                    path.lineTo(19f, 8f);
                    path.lineTo(19f, 21f);
                    path.lineTo(6f, 21f);
                    path.close();
                    path.moveTo(14f, 3f);
                    path.lineTo(14f, 8f);
                    path.lineTo(19f, 8f);
                    break;
                case CHEVRON_RIGHT:
                    path.moveTo(9f, 6f);
                    path.lineTo(15f, 12f);
                    path.lineTo(9f, 18f);
                    break;
                case INFO:
                    path.addCircle(12f, 12f, 9f, Path.Direction.CW);
                    path.moveTo(12f, 11f);
                    path.lineTo(12f, 17f);
                    path.moveTo(12f, 7.4f);
                    path.lineTo(12f, 7.6f);
                    break;
                case FULLSCREEN:
                    path.moveTo(4f, 9f);
                    path.lineTo(4f, 4f);
                    path.lineTo(9f, 4f);
                    path.moveTo(15f, 4f);
                    path.lineTo(20f, 4f);
                    path.lineTo(20f, 9f);
                    path.moveTo(20f, 15f);
                    path.lineTo(20f, 20f);
                    path.lineTo(15f, 20f);
                    path.moveTo(9f, 20f);
                    path.lineTo(4f, 20f);
                    path.lineTo(4f, 15f);
                    break;
                case FULLSCREEN_EXIT:
                    path.moveTo(4f, 9f);
                    path.lineTo(9f, 9f);
                    path.lineTo(9f, 4f);
                    path.moveTo(15f, 4f);
                    path.lineTo(15f, 9f);
                    path.lineTo(20f, 9f);
                    path.moveTo(20f, 15f);
                    path.lineTo(15f, 15f);
                    path.lineTo(15f, 20f);
                    path.moveTo(9f, 20f);
                    path.lineTo(9f, 15f);
                    path.lineTo(4f, 15f);
                    break;
                case CONSOLE:
                    path.moveTo(5f, 7f);
                    path.lineTo(11f, 12f);
                    path.lineTo(5f, 17f);
                    path.moveTo(13f, 17f);
                    path.lineTo(19f, 17f);
                    break;
                case PLUS:
                    path.moveTo(12f, 5f);
                    path.lineTo(12f, 19f);
                    path.moveTo(5f, 12f);
                    path.lineTo(19f, 12f);
                    break;
                case PLAY:
                    path.moveTo(8f, 5f);
                    path.lineTo(19f, 12f);
                    path.lineTo(8f, 19f);
                    path.close();
                    break;
                case EXPAND:
                    path.moveTo(14f, 4f);
                    path.lineTo(20f, 4f);
                    path.lineTo(20f, 10f);
                    path.moveTo(20f, 4f);
                    path.lineTo(13f, 11f);
                    path.moveTo(10f, 20f);
                    path.lineTo(4f, 20f);
                    path.lineTo(4f, 14f);
                    path.moveTo(4f, 20f);
                    path.lineTo(11f, 13f);
                    break;
                case COLLAPSE:
                    path.moveTo(20f, 10f);
                    path.lineTo(14f, 10f);
                    path.lineTo(14f, 4f);
                    path.moveTo(14f, 10f);
                    path.lineTo(21f, 3f);
                    path.moveTo(4f, 14f);
                    path.lineTo(10f, 14f);
                    path.lineTo(10f, 20f);
                    path.moveTo(10f, 14f);
                    path.lineTo(3f, 21f);
                    break;
                case PAUSE:
                    path.moveTo(7f, 5f);
                    path.lineTo(11f, 5f);
                    path.lineTo(11f, 19f);
                    path.lineTo(7f, 19f);
                    path.close();
                    path.moveTo(13f, 5f);
                    path.lineTo(17f, 5f);
                    path.lineTo(17f, 19f);
                    path.lineTo(13f, 19f);
                    path.close();
                    break;
                default:
                    path.moveTo(6f, 9f);
                    path.lineTo(12f, 15f);
                    path.lineTo(18f, 9f);
                    break;
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float scale = Math.min(getWidth(), getHeight()) / 24f;
            canvas.save();
            canvas.scale(scale, scale);
            canvas.drawPath(path, paint);
            canvas.restore();
        }
    }

    /** Linha vertical fina que liga pais e filhos na arvore. */
    private static final class GuideView extends View {
        private final Paint paint = new Paint();

        GuideView(Context context) {
            super(context);
            paint.setColor(COLOR_BORDER);
            paint.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float x = getWidth() / 2f;
            canvas.drawLine(x, 0f, x, (float) getHeight(), paint);
        }
    }
}
