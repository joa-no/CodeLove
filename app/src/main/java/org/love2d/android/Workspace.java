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
import android.content.SharedPreferences;
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
    private static final int EDITOR_WIDTH_DP = 360;
    private static final int ROW_HEIGHT_DP = 36;
    private static final int LEVEL_WIDTH_DP = 16;
    private static final int MAX_DEPTH = 12;

    private final Activity activity;
    private final RelativeLayout root;
    private final View surface;
    private final File gamesDir;   // pasta onde ficam os projetos (pode ser nula)
    private final File channelDir; // pasta do canal de comandos com o executor (Lua)
    private final int panelWidth;
    private final int margin;
    private final Set<String> expanded = new HashSet<String>();

    private File activeProject = null; // projeto aberto agora (nulo se nenhum)
    private boolean playing = false;
    private boolean editorExpanded = false;
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
    private TextView toggle;
    private TextView projectTitle;
    private TextView statusView;
    private LinearLayout treeBox;
    private EditText search;

    private boolean open = true;
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
        new Workspace(activity, (RelativeLayout) layout, surface, games).build();
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
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        new File(dir, "commands.txt").delete();
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

        toggle = new TextView(activity);
        toggle.setGravity(Gravity.CENTER);
        toggle.setTextColor(COLOR_TEXT);
        toggle.setTextSize(18);
        toggle.setBackground(box(COLOR_PANEL, 20, true));
        toggle.setOnClickListener(v -> {
            open = !open;
            applyLayout();
        });

        // Botao de play/pause (so aparece com um projeto aberto).
        playIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        playButton = new LinearLayout(activity);
        playButton.setGravity(Gravity.CENTER);
        playButton.setBackground(box(COLOR_PANEL, 20, true));
        playButton.addView(playIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        playButton.setVisibility(View.GONE);
        playButton.setOnClickListener(v -> {
            playing = !playing;
            sendCommand(playing ? "play" : "pause", "");
            updatePlayButton();
        });

        // Console: mostra o que o jogo escreve com print() e os erros.
        consoleButton = new LinearLayout(activity);
        consoleButton.setGravity(Gravity.CENTER);
        consoleButton.setBackground(box(COLOR_PANEL, 20, true));
        consoleButton.addView(new IconView(activity, IconView.CONSOLE, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        consoleButton.setVisibility(View.GONE);
        consoleButton.setOnClickListener(v -> {
            consoleOpen = !consoleOpen;
            applyLayout();
            if (consoleOpen) {
                updateConsole();
            }
        });
        consolePanel = buildConsolePanel();

        // Botao de reset: volta a cena ao estado inicial (pausada).
        resetButton = new TextView(activity);
        resetButton.setText("\u21BA");
        resetButton.setGravity(Gravity.CENTER);
        resetButton.setTextColor(COLOR_TEXT);
        resetButton.setTextSize(20);
        resetButton.setBackground(box(COLOR_PANEL, 20, true));
        resetButton.setVisibility(View.GONE);
        resetButton.setOnClickListener(v -> resetScene());

        placeholder = buildPlaceholder();

        editor = new CodeEditor(activity, new CodeEditor.Listener() {
            @Override
            public void onSaved(File file) {
                onFileSaved(file);
            }

            @Override
            public void onClosed() {
                editorExpanded = false;
                editor.setExpanded(false);
                applyLayout();
                renderTree();
                saveState();
            }

            @Override
            public void onExpandToggle() {
                editorExpanded = !editorExpanded;
                editor.setExpanded(editorExpanded);
                applyLayout();
            }
        });
        editorView = editor.getView();
        editorView.setVisibility(View.GONE);

        root.addView(placeholder);
        root.addView(editorView);
        root.addView(panel);
        root.addView(toggle);
        root.addView(playButton);
        root.addView(resetButton);
        root.addView(consoleButton);
        root.addView(consolePanel);

        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                root.removeCallbacks(statusPoller);
                editor.saveNow();
            }
        });
        root.postDelayed(statusPoller, 700);

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

        header.addView(iconButton(IconView.PLUS, v -> showAddMenu(v)), headerButtonParams());
        header.addView(headerButton("\u21BB", v -> renderTree()), headerButtonParams());
        header.addView(headerButton("\u21C4", v -> {
            onLeft = !onLeft;
            applyLayout();
            saveState();
        }), headerButtonParams());
        return header;
    }

    private TextView headerButton(String glyph, View.OnClickListener listener) {
        TextView button = new TextView(activity);
        button.setText(glyph);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(COLOR_MUTED);
        button.setTextSize(16);
        button.setBackground(box(COLOR_FIELD, 10, false));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout iconButton(int kind, View.OnClickListener listener) {
        LinearLayout button = new LinearLayout(activity);
        button.setGravity(Gravity.CENTER);
        button.setBackground(box(COLOR_FIELD, 10, false));
        button.addView(new IconView(activity, kind, COLOR_MUTED),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams headerButtonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(32), dp(32));
        lp.setMargins(dp(6), 0, 0, 0);
        return lp;
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
        boolean editorOpen = editor != null && editor.isOpen();
        if (ui != null) {
            ui.topInset = insetTop;
        }

        // Tamanho da area de jogo cheia: a tela sem as barras do sistema.
        float baseW = root.getWidth() - insetLeft - insetRight;
        float baseH = root.getHeight() - insetTop - insetBottom;

        int editorWidth = dp(EDITOR_WIDTH_DP);
        if (baseW > 0f) {
            editorWidth = Math.min(editorWidth, (int) (baseW * 0.45f));
        }

        // Da borda da tela para dentro: [painel] [editor] e, por fim, a area do jogo.
        int cursor = sideInset;
        int panelOffset = 0;
        int editorOffset = 0;
        if (open) {
            panelOffset = cursor + margin;
            cursor += margin + panelWidth + margin;
        }
        if (editorOpen) {
            editorOffset = open ? cursor : cursor + margin;
            cursor = editorOffset + editorWidth + margin;
        }
        int reserved = cursor - sideInset;

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
        if (reserved > 0 && baseW > reserved && baseH > 0f) {
            scale = (baseW - reserved) / baseW;
            shiftX = onLeft ? reserved : 0f;
            shiftY = (baseH - baseH * scale) / 2f;
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
        panel.setVisibility(open ? View.VISIBLE : View.GONE);

        // Editor de codigo (tambem acompanha o teclado). Expandido, ele vai ate a
        // outra borda e cobre a area do jogo.
        int editorLayoutWidth = editorWidth;
        if (editorOpen && editorExpanded && baseW > 0f) {
            editorLayoutWidth = (int) baseW - (editorOffset - sideInset) - margin;
        }
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

        // Botao de abrir/fechar: canto de cima da area do jogo, junto ao que vem antes.
        RelativeLayout.LayoutParams toggleLp = new RelativeLayout.LayoutParams(dp(40), dp(40));
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        toggleLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        toggleLp.topMargin = insetTop + margin;
        if (onLeft) {
            toggleLp.leftMargin = sideInset + reserved + margin;
        } else {
            toggleLp.rightMargin = sideInset + reserved + margin;
        }
        toggle.setLayoutParams(toggleLp);

        // Botao de play/pause: logo ao lado do botao de abrir/fechar.
        RelativeLayout.LayoutParams playLp = new RelativeLayout.LayoutParams(dp(40), dp(40));
        playLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        playLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        playLp.topMargin = insetTop + margin;
        int playEdge = sideInset + reserved + margin + dp(40) + margin;
        if (onLeft) {
            playLp.leftMargin = playEdge;
        } else {
            playLp.rightMargin = playEdge;
        }
        playButton.setLayoutParams(playLp);

        // Botao de reset: ao lado do play.
        RelativeLayout.LayoutParams resetLp = new RelativeLayout.LayoutParams(dp(40), dp(40));
        resetLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        resetLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        resetLp.topMargin = insetTop + margin;
        int resetEdge = playEdge + dp(40) + margin;
        if (onLeft) {
            resetLp.leftMargin = resetEdge;
        } else {
            resetLp.rightMargin = resetEdge;
        }
        resetButton.setLayoutParams(resetLp);

        // Botao do console: ao lado do reset.
        RelativeLayout.LayoutParams consoleBtnLp = new RelativeLayout.LayoutParams(dp(40), dp(40));
        consoleBtnLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        consoleBtnLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        consoleBtnLp.topMargin = insetTop + margin;
        int consoleEdge = resetEdge + dp(40) + margin;
        if (onLeft) {
            consoleBtnLp.leftMargin = consoleEdge;
        } else {
            consoleBtnLp.rightMargin = consoleEdge;
        }
        consoleButton.setLayoutParams(consoleBtnLp);

        // Painel do console: na parte de baixo da area do jogo.
        int consoleHeight = dp(260);
        if (baseH > 0f) {
            consoleHeight = Math.min(consoleHeight, (int) (baseH * 0.4f));
        }
        RelativeLayout.LayoutParams consoleLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, consoleHeight);
        consoleLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        consoleLp.leftMargin = insetLeft + (onLeft ? reserved : 0) + margin;
        consoleLp.rightMargin = insetRight + (onLeft ? 0 : reserved) + margin;
        consoleLp.bottomMargin = insetBottom + margin;
        consolePanel.setLayoutParams(consoleLp);

        // A seta aponta para onde o painel vai se mover ao tocar.
        boolean pointLeft = (onLeft == open);
        toggle.setText(pointLeft ? "\u2039" : "\u203A");

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
        final File folder = targetFolder();
        if (folder != null) {
            labels.add("Novo arquivo em " + folder.getName());
            actions.add(() -> newFile(folder));
            labels.add("Nova pasta em " + folder.getName());
            actions.add(() -> newFolder(folder));
        }
        ui.showMenu(anchor, labels, actions);
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
        if (editedFile != null && isInside(editedFile, target)) {
            editor.close();
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

        if (editor.isOpen() && isInside(editor.getFile(), target)) {
            editor.close();
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
        } else {
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
        if (editor != null && editor.isOpen() && !isInside(editor.getFile(), project)) {
            editor.close();
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

    private void updatePlayButton() {
        playIcon.setKind(playing ? IconView.PAUSE : IconView.PLAY);
        updateControlsVisibility();
    }

    /** Com o editor expandido, os botoes da area do jogo somem (ela fica coberta). */
    private void updateControlsVisibility() {
        boolean show = !(editor != null && editor.isOpen() && editorExpanded);
        toggle.setVisibility(show ? View.VISIBLE : View.GONE);
        int projectVisibility = (activeProject != null && show) ? View.VISIBLE : View.GONE;
        playButton.setVisibility(projectVisibility);
        resetButton.setVisibility(projectVisibility);
        consoleButton.setVisibility(projectVisibility);
        consolePanel.setVisibility((consoleOpen && show && activeProject != null) ? View.VISIBLE : View.GONE);
    }

    /** Volta a cena ao inicio: recarrega o projeto, pausado, no primeiro quadro. */
    private void resetScene() {
        if (activeProject == null) {
            return;
        }
        editor.saveNow();
        playing = false;
        sendCommand("open", activeProject.getAbsolutePath());
        updatePlayButton();
    }

    /** Escreve uma linha no arquivo de comandos que o executor (Lua) le. */
    private void sendCommand(String command, String argument) {
        if (!channelDir.isDirectory() && !channelDir.mkdirs()) {
            Log.w(TAG, "Sem pasta do canal: " + channelDir);
            return;
        }
        File file = new File(channelDir, "commands.txt");
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write((command + "\t" + argument + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao enviar o comando " + command, e);
        }
    }

    /** Painel do console: titulo, botao de limpar, botao de fechar e o texto. */
    private LinearLayout buildConsolePanel() {
        LinearLayout panelBox = new LinearLayout(activity);
        panelBox.setOrientation(LinearLayout.VERTICAL);
        panelBox.setBackground(box(COLOR_PANEL, 16, true));
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
            text = "";
        }
        if (!text.equals(lastConsole)) {
            lastConsole = text;
            consoleText.setText(text);
            consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
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
    private final Runnable statusPoller = new Runnable() {
        @Override
        public void run() {
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
            root.postDelayed(this, 700);
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
