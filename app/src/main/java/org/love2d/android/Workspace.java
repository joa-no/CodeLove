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
    private static final int COLOR_OUTER = 0xFF0D0F16;
    private static final int COLOR_PANEL = 0xFF151822;
    private static final int COLOR_FIELD = 0xFF1B1F2B;
    private static final int COLOR_BORDER = 0xFF272C3B;
    private static final int COLOR_TEXT = 0xFFE6E8EE;
    private static final int COLOR_MUTED = 0xFF8A8FA3;
    private static final int COLOR_FOLDER = 0xFFA78BFA;
    private static final int COLOR_LUA = 0xFF7AA2F7;
    private static final int COLOR_IMAGE = 0xFF5EEAD4;
    private static final int COLOR_OTHER = 0xFF9CA3AF;

    private static final int PANEL_WIDTH_DP = 272;
    private static final int MARGIN_DP = 8;
    private static final int ROW_HEIGHT_DP = 36;
    private static final int LEVEL_WIDTH_DP = 16;
    private static final int MAX_DEPTH = 12;

    private final Activity activity;
    private final RelativeLayout root;
    private final View surface;
    private final File projectDir; // null quando nao ha projeto aberto
    private final int panelWidth;
    private final int margin;
    private final Set<String> expanded = new HashSet<String>();

    private LinearLayout panel;
    private TextView toggle;
    private LinearLayout treeBox;
    private EditText search;

    private boolean open = true;
    private boolean onLeft = true;
    private int insetLeft;
    private int insetTop;
    private int insetRight;
    private int insetBottom;

    private Workspace(Activity activity, RelativeLayout root, View surface, File projectDir) {
        this.activity = activity;
        this.root = root;
        this.surface = surface;
        this.projectDir = projectDir;
        this.panelWidth = dp(PANEL_WIDTH_DP);
        this.margin = dp(MARGIN_DP);
    }

    /**
     * Liga a area de trabalho ao layout do SDL.
     * "layout" e o layout criado pelo SDL; "surface" e onde o LOVE desenha;
     * "projectPath" e o caminho do jogo aberto (pode ser nulo).
     */
    public static void attach(Activity activity, ViewGroup layout, View surface, String projectPath) {
        if (activity == null || surface == null || !(layout instanceof RelativeLayout)) {
            return;
        }
        File dir = null;
        if (projectPath != null) {
            File candidate = new File(projectPath);
            if (candidate.isDirectory()) {
                dir = candidate;
            }
        }
        new Workspace(activity, (RelativeLayout) layout, surface, dir).build();
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

        toggle = new TextView(activity);
        toggle.setGravity(Gravity.CENTER);
        toggle.setTextColor(COLOR_TEXT);
        toggle.setTextSize(18);
        toggle.setBackground(box(COLOR_PANEL, 20, true));
        toggle.setOnClickListener(v -> {
            open = !open;
            applyLayout();
        });

        root.addView(panel);
        root.addView(toggle);

        // Acompanha as barras do sistema (relogio no topo, gestos embaixo).
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            insetLeft = insets.getSystemWindowInsetLeft();
            insetTop = insets.getSystemWindowInsetTop();
            insetRight = insets.getSystemWindowInsetRight();
            insetBottom = insets.getSystemWindowInsetBottom();
            applyLayout();
            return insets;
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
        name.setText(projectDir != null ? projectDir.getName() : "CodeLove");
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
        // Espaco que o painel toma: largura + margem de cada lado dele.
        int reserved = open ? (panelWidth + margin * 2) : 0;

        // Area do jogo: o que sobra, sem ficar embaixo das barras do sistema.
        RelativeLayout.LayoutParams surfaceLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceLp.topMargin = insetTop;
        surfaceLp.bottomMargin = insetBottom;
        if (onLeft) {
            surfaceLp.leftMargin = insetLeft + reserved;
            surfaceLp.rightMargin = insetRight;
        } else {
            surfaceLp.leftMargin = insetLeft;
            surfaceLp.rightMargin = insetRight + reserved;
        }
        surface.setLayoutParams(surfaceLp);

        // Painel lateral.
        RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        panelLp.topMargin = insetTop + margin;
        panelLp.bottomMargin = insetBottom + margin;
        if (onLeft) {
            panelLp.leftMargin = sideInset + margin;
        } else {
            panelLp.rightMargin = sideInset + margin;
        }
        panel.setLayoutParams(panelLp);
        panel.setVisibility(open ? View.VISIBLE : View.GONE);

        // Botao de abrir/fechar: canto de cima da area do jogo, junto ao painel.
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

        if (projectDir == null) {
            TextView note = new TextView(activity);
            note.setText("Nenhum projeto aberto.\nAbra um projeto na lista para ver os arquivos.");
            note.setTextColor(COLOR_MUTED);
            note.setTextSize(13);
            note.setPadding(dp(4), dp(4), dp(4), dp(4));
            treeBox.addView(note);
            return;
        }

        String query = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        ArrayList<Row> rows = new ArrayList<Row>();
        collect(projectDir, 0, query, rows);

        if (rows.isEmpty()) {
            TextView note = new TextView(activity);
            note.setText(query.isEmpty() ? "Pasta vazia." : "Nada encontrado.");
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

        line.setOnClickListener(v -> {
            if (row.dir) {
                String path = row.file.getAbsolutePath();
                if (!expanded.remove(path)) {
                    expanded.add(path);
                }
                renderTree();
            } else {
                Toast.makeText(activity, "O editor de codigo vem na proxima etapa.",
                    Toast.LENGTH_SHORT).show();
            }
        });
        return line;
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

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context context, int kind, int color) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.8f); // em unidades da grade 24x24
            paint.setColor(color);

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
                default:
                    path.moveTo(6f, 9f);
                    path.lineTo(12f, 15f);
                    path.lineTo(18f, 9f);
                    break;
            }
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
