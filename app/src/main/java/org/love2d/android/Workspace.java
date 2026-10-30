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
    private String lastStatus = "";

    private CodeEditor editor;
    private View editorView;
    private LinearLayout panel;
    private LinearLayout placeholder;
    private LinearLayout playButton;
    private IconView playIcon;
    private TextView toggle;
    private TextView projectTitle;
    private TextView statusView;
    private LinearLayout treeBox;
    private EditText search;

    private boolean open = true;
    private boolean onLeft = true;
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

    /**
     * Cria um projeto de exemplo na pasta de jogos, se ela estiver vazia.
     * Chamado pela lista de projetos.
     */
    public static void ensureSampleProject(File gamesDir) {
        if (gamesDir == null || !gamesDir.isDirectory()) {
            return;
        }
        String[] existing = gamesDir.list();
        if (existing != null && existing.length > 0) {
            return;
        }
        File project = new File(gamesDir, "meu_jogo");
        writeFile(new File(project, "main.lua"),
            "-- Projeto de exemplo do CodeLove.\n"
            + "-- Edite este arquivo para mudar o jogo.\n"
            + "\n"
            + "local x = 100\n"
            + "\n"
            + "function love.update(dt)\n"
            + "  x = x + 60 * dt\n"
            + "end\n"
            + "\n"
            + "function love.draw()\n"
            + "  love.graphics.print(\"Ola, CodeLove!\", x, 100)\n"
            + "end\n");
        writeFile(new File(project, "conf.lua"),
            "function love.conf(t)\n"
            + "  t.window.title = \"meu_jogo\"\n"
            + "end\n");
        writeFile(new File(new File(project, "scripts"), "util.lua"),
            "local util = {}\n"
            + "\n"
            + "function util.clamp(v, a, b)\n"
            + "  return math.max(a, math.min(b, v))\n"
            + "end\n"
            + "\n"
            + "return util\n");
    }

    private static void writeFile(File file, String text) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "Nao consegui criar a pasta " + parent);
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "Falha ao criar " + file, e);
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
            drawable.setStroke(Math.max(1, dp(1)), COLOR_BORDER);
        }
        return drawable;
    }

    // ------------------------------------------------------------------
    // Montagem da tela
    // ------------------------------------------------------------------

    private void build() {
        root.setBackgroundColor(COLOR_OUTER);

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
            }
        });
        editorView = editor.getView();
        editorView.setVisibility(View.GONE);

        root.addView(placeholder);
        root.addView(editorView);
        root.addView(panel);
        root.addView(toggle);
        root.addView(playButton);

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

        header.addView(headerButton("\u21BB", v -> renderTree()), headerButtonParams());
        header.addView(headerButton("\u21C4", v -> {
            onLeft = !onLeft;
            applyLayout();
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

        // Editor de codigo (tambem acompanha o teclado).
        RelativeLayout.LayoutParams editorLp = new RelativeLayout.LayoutParams(
            editorWidth, ViewGroup.LayoutParams.MATCH_PARENT);
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

        // A seta aponta para onde o painel vai se mover ao tocar.
        boolean pointLeft = (onLeft == open);
        toggle.setText(pointLeft ? "\u2039" : "\u203A");
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
            if (!row.dir) {
                openInEditor(row.file);
            }
            return;
        }

        if (row.dir) {
            renderTree();
        } else {
            openInEditor(row.file);
        }
    }

    private void openInEditor(File file) {
        if (editor.open(file)) {
            applyLayout();
            renderTree();
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
        expanded.add(project.getAbsolutePath());
        playing = false;
        sendCommand("open", project.getAbsolutePath());
        projectTitle.setText(project.getName());
        placeholder.setVisibility(View.GONE);
        updatePlayButton();
        renderTree();
    }

    private void updatePlayButton() {
        playIcon.setKind(playing ? IconView.PAUSE : IconView.PLAY);
        playButton.setVisibility(activeProject != null ? View.VISIBLE : View.GONE);
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

    private static final class IconView extends View {
        static final int FOLDER = 0;
        static final int FILE = 1;
        static final int CHEVRON_RIGHT = 2;
        static final int CHEVRON_DOWN = 3;
        static final int PLAY = 4;
        static final int PAUSE = 5;

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
                case PLAY:
                    path.moveTo(8f, 5f);
                    path.lineTo(19f, 12f);
                    path.lineTo(8f, 19f);
                    path.close();
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
