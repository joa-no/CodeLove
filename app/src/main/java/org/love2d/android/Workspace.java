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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
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
    private static final String PREF_WORKSPACE_ORIENTATION = "workspace_orientation";
    private static final String PREF_APPEARANCE = "appearance_theme";
    private static final String PREF_LANGUAGE = "workspace_language";
    private static Workspace currentInstance;

    /** Apenas caminhos: nenhuma Activity ou view e guardada na recriacao de tema. */
    private static final class ThemeSession {
        final String projectPath;
        final CodeEditor.ThemeTabs tabs;
        final boolean drawerOpen;

        ThemeSession(String projectPath, CodeEditor.ThemeTabs tabs,
                boolean drawerOpen) {
            this.projectPath = projectPath;
            this.tabs = tabs;
            this.drawerOpen = drawerOpen;
        }
    }
    private static ThemeSession pendingThemeSession;

    // Cores inspiradas no desenho de referencia (tema escuro).
    // Paleta ativa. O valor inicial mantem a aparencia aprovada na build #300.
    // Atualizada antes da criacao das views; nenhum jogo ou superficie SDL muda.
    static int COLOR_OUTER = 0xFF0D0F16;
    static int COLOR_PANEL = 0xFF151822;
    static int COLOR_FIELD = 0xFF1B1F2B;
    static int COLOR_BORDER = 0xFF272C3B;
    static int COLOR_TEXT = 0xFFE6E8EE;
    static int COLOR_MUTED = 0xFF8A8FA3;
    static int COLOR_FOLDER = 0xFFA78BFA;
    static int COLOR_LUA = 0xFF7AA2F7;
    static int COLOR_IMAGE = 0xFF5EEAD4;
    static int COLOR_OTHER = 0xFF9CA3AF;
    static int COLOR_DANGER = 0xFFFF7B8A;
    static int COLOR_ACCENT_TEXT = 0xFF0D0F16;
    private static boolean lightThemeActive;

    /** Resolve a preferencia persistente com a configuracao real do Android. */
    static void configureTheme(Context context) {
        String selected = context.getSharedPreferences("codelove", Context.MODE_PRIVATE)
            .getString(PREF_APPEARANCE, "dark");
        boolean systemLight = (context.getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES;
        lightThemeActive = "light".equals(selected)
            || ("auto".equals(selected) && systemLight);
        if (lightThemeActive) {
            COLOR_OUTER = 0xFFF3F5F9;
            COLOR_PANEL = 0xFFFFFFFF;
            COLOR_FIELD = 0xFFE9EDF5;
            COLOR_BORDER = 0xFFCAD3E1;
            COLOR_TEXT = 0xFF202638;
            COLOR_MUTED = 0xFF626C81;
            COLOR_FOLDER = 0xFF7751BC;
            COLOR_LUA = 0xFF3457AA;
            COLOR_IMAGE = 0xFF0D8177;
            COLOR_OTHER = 0xFF5A6576;
            COLOR_DANGER = 0xFFB3263C;
            COLOR_ACCENT_TEXT = 0xFFFFFFFF;
        } else {
            COLOR_OUTER = 0xFF0D0F16;
            COLOR_PANEL = 0xFF151822;
            COLOR_FIELD = 0xFF1B1F2B;
            COLOR_BORDER = 0xFF272C3B;
            COLOR_TEXT = 0xFFE6E8EE;
            COLOR_MUTED = 0xFF8A8FA3;
            COLOR_FOLDER = 0xFFA78BFA;
            COLOR_LUA = 0xFF7AA2F7;
            COLOR_IMAGE = 0xFF5EEAD4;
            COLOR_OTHER = 0xFF9CA3AF;
            COLOR_DANGER = 0xFFFF7B8A;
            COLOR_ACCENT_TEXT = 0xFF0D0F16;
        }
        CodeEditor.configureSyntaxTheme(lightThemeActive);
    }

    static boolean isLightTheme() {
        return lightThemeActive;
    }

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

    private File activeProject = null; // identificador virtual do projeto aberto
    private DirectSafStorage activeSafStorage = null; // fonte direta SAF, quando aplicavel
    private File runtimeProject = null; // snapshot temporario usado somente pelo LOVE
    private boolean playing = false;

    private OverlayUi ui;
    private SharedPreferences prefs;
    private File lastFolder = null; // ultima pasta tocada no projeto aberto
    private CodeEditor editor;
    private View editorView;
    private View drawerScrim;
    private boolean fileDrawerOpen;
    // 0 = fora da tela; 1 = totalmente aberto. Independente de fileDrawerOpen
    // enquanto o usuario arrasta o painel ou a animacao termina.
    private float drawerProgress;
    private ValueAnimator drawerAnimator;
    private boolean swipeCandidate;
    private boolean swipeCaptured;
    private float swipeDownX;
    private float swipeDownY;
    private float swipeStartProgress;
    private long swipeDownTime;
    private LinearLayout panel;
    private View movePanelButton;
    private Dialog settingsDialog;
    private LinearLayout placeholder;
    private LinearLayout playButton;
    private TextView resetButton;
    private IconView playIcon;
    private IconView headerPlayIcon;
    private IconView panelPlayIcon;
    private LinearLayout headerPlayButton;
    private LinearLayout panelPlayButton;
    private LinearLayout controlsBar;
    private LinearLayout closePlaybackButton;
    private IconView fullscreenIcon;
    private TextView projectTitle;
    private IconView saveIcon;
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
        configureTheme(activity);
        this.onLeft = prefs.getBoolean("panel_left", true);
        this.panelWidth = dp(PANEL_WIDTH_DP);
        this.margin = dp(MARGIN_DP);
    }

    /**
     * Liga a area de trabalho ao layout do SDL.
     * "layout" e o layout criado pelo SDL; "surface" e onde o LOVE desenha.
     */
    /** Aplica a orientacao salva antes de montar a interface do ambiente de trabalho. */
    public static void applySavedOrientation(Activity activity) {
        if (activity == null) return;
        SharedPreferences preferences =
            activity.getSharedPreferences("codelove", Context.MODE_PRIVATE);
        String saved = preferences.getString(PREF_WORKSPACE_ORIENTATION, "");
        if ("portrait".equals(saved)) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(saved)) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
        // Sem escolha salva, preserva a orientacao inicial definida por GameActivity.
        // Nao liberar para o SDL, pois ele pode voltar a impor o formato horizontal.
    }

    /** Ambiente de edicao Java sem uma superficie SDL sob os paineis. */
    public static void attachEditor(Activity activity, RelativeLayout layout) {
        if (activity == null || layout == null) return;
        File games = activity.getExternalFilesDir("games");
        if (games != null && !games.isDirectory()) games.mkdirs();
        Workspace workspace = new Workspace(activity, layout, null, games);
        currentInstance = workspace;
        workspace.build();
        workspace.importPreviouslyRegisteredProjects();
        workspace.restoreThemeSession();
        workspace.showInitialOrientationChooser();
    }

    public static void attach(Activity activity, ViewGroup layout, View surface) {
        if (activity == null || surface == null || !(layout instanceof RelativeLayout)) {
            return;
        }
        File games = activity.getExternalFilesDir("games");
        if (games != null) {
            if (!games.isDirectory()) {
                games.mkdirs();
            }
        }
        Workspace workspace = new Workspace(activity, (RelativeLayout) layout, surface, games);
        currentInstance = workspace;
        workspace.build();
        workspace.importPreviouslyRegisteredProjects();
        workspace.restoreThemeSession();
        workspace.showInitialOrientationChooser();
    }

    private void importPreviouslyRegisteredProjects() {
        List<SafProjectManager.RegisteredProject> projects =
            SafProjectManager.getRegisteredProjects(activity);
        if (projects.isEmpty()) return;

        // O projeto registrado e aberto diretamente pelo SAF. Nenhum arquivo e
        // copiado para gamesDir. Se houver varios, o ultimo selecionado pelo
        // usuario pode ser aberto novamente pelo seletor sem duplicacao local.
        SafProjectManager.RegisteredProject project = projects.get(0);
        DirectSafStorage storage = new DirectSafStorage(
            activity, project.treeUri, project.documentId, project.displayName);
        activeSafStorage = storage;
        activeProject = storage.getRoot();
        expanded.add(activeProject.getAbsolutePath());
        projectTitle.setText(storage.getDisplayName());
        placeholder.setVisibility(View.GONE);
        if (editor != null) editor.setStorage(storage);
        // O painel foi criado antes de recuperar o projeto salvo.
        // Reativa e atualiza o Play assim que o projeto SAF e restaurado.
        updatePlayButton();
        renderTree();
        final DirectSafStorage selectedStorage = storage;
        new Thread(() -> {
            if (selectedStorage == activeSafStorage && !disposed) {
                prepareRuntimeProject(false);
            }
        }, "CodeLove-SAF-RuntimeRestore").start();
    }

    /** Restaura visual e abas na mesma posicao, sem animar a gaveta nem reler codigo. */
    private void restoreThemeSession() {
        if (surface != null || pendingThemeSession == null) return;
        ThemeSession session = pendingThemeSession;
        pendingThemeSession = null;
        String projectPath = activeProject == null ? null : activeProject.getAbsolutePath();
        if (!java.util.Objects.equals(session.projectPath, projectPath)) return;

        // Ao recriar o layout, nao simular um clique no menu: mostrar diretamente
        // o painel na posicao final evita o efeito de fechar/abrir que o usuario viu.
        cancelDrawerAnimation();
        fileDrawerOpen = session.drawerOpen;
        drawerProgress = session.drawerOpen ? 1f : 0f;

        if (editor != null) {
            editor.restoreThemeTabs(session.tabs, activeSafStorage);
        }
        applyLayout();
        renderTree();
        saveState();
    }

    /** Restaura o estado do editor ao voltar da execucao SDL. */
    public static void onHostResume(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity && !workspace.disposed) {
            if (workspace.surface == null && workspace.playing) {
                workspace.playing = false;
                workspace.updatePlayButton();
                workspace.applyLayout();
            }
            workspace.renderTree();
        }
    }

    /** Mantido para preservar o contrato de ciclo de vida com as Activities. */
    public static void onHostPause(Activity activity) {
        // O console agora pertence exclusivamente a GameActivity.
    }

    public static void onHostDestroy(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity) {
            workspace.disposed = true;
            workspace.deleteRuntimeProject();
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

    /** Contrato entre editor Android e executor SDL. */
    public static final String EXTRA_RUNTIME_ONLY = "org.love2d.android.RUNTIME_ONLY";
    public static final String EXTRA_RUNTIME_PROJECT = "org.love2d.android.RUNTIME_PROJECT";
    public static final String EXTRA_RUNTIME_NAME = "org.love2d.android.RUNTIME_NAME";

    /** Insere open/play na fila limpa, antes de o SDL iniciar. */
    public static void queueStandaloneRuntime(Context context, String path, String name) {
        if (path == null || path.isEmpty())
            throw new IllegalArgumentException("Projeto ausente");
        File queue = new File(channelDir(context), "commands");
        if (!queue.isDirectory() && !queue.mkdirs())
            throw new IllegalStateException("Fila de execucao indisponivel");
        String safeName = name == null ? "" :
            name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
        writeStandaloneCommand(queue, 1, "open", path + "\t" + safeName);
        writeStandaloneCommand(queue, 2, "play", "");
    }

    private static void writeStandaloneCommand(File queue, int number,
            String command, String argument) {
        String filename = String.format(Locale.ROOT, "command-%010d.txt", number);
        File target = new File(queue, filename);
        File temporary = new File(queue, filename + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write((command + "\t" + argument).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        } catch (IOException e) {
            temporary.delete();
            throw new IllegalStateException("Falha ao iniciar o jogo", e);
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IllegalStateException("Falha ao publicar " + command);
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
        ui = new OverlayUi(activity, root);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(box(COLOR_PANEL, 0, false));
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
        movePanelButton = footerGlyphButton("\u21C4", v -> {
            onLeft = !onLeft;
            applyLayout();
            saveState();
        });
        setButtonHint(movePanelButton, activity.getString(R.string.cl_move_panel));
        footer.addView(movePanelButton, footerButtonParams(false));
        panel.addView(footer, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        // Barra flutuante herdada: so para a Activity com superficie SDL.
        // A EditorActivity usa exclusivamente os botoes integrados ao editor.
        playIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        playButton = new LinearLayout(activity);
        playButton.setGravity(Gravity.CENTER);
        playButton.setBackground(box(COLOR_FIELD, 22, false));
        playButton.addView(playIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        playButton.setOnClickListener(v -> togglePlayback());

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
        if (surface != null) {
            controlsBar.addView(resetButton, barButtonParams(false));
            controlsBar.addView(fullscreenButton, barButtonParams(false));
        }

        // No modo vertical, oferece uma saida visivel da visualizacao do jogo.
        closePlaybackButton = new LinearLayout(activity);
        closePlaybackButton.setGravity(Gravity.CENTER);
        closePlaybackButton.setBackground(box(COLOR_PANEL, 22, true));
        closePlaybackButton.addView(new IconView(activity, IconView.CLOSE, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(closePlaybackButton, activity.getString(R.string.cl_exit_visual));
        closePlaybackButton.setOnClickListener(v -> exitPlaybackToFiles());
        closePlaybackButton.setVisibility(View.GONE);

        // Play no topo do editor, inclusive com a area vazia e nenhum arquivo aberto.
        // A acao e a mesma do Play original, sem salvar arquivos automaticamente.
        headerPlayIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        headerPlayButton = new LinearLayout(activity);
        headerPlayButton.setGravity(Gravity.CENTER);
        headerPlayButton.setBackground(box(COLOR_FIELD, 10, false));
        headerPlayButton.addView(headerPlayIcon,
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(headerPlayButton, activity.getString(R.string.cl_run_project));
        headerPlayButton.setOnClickListener(v -> togglePlayback());

        placeholder = buildPlaceholder();

        saveIcon = new IconView(activity, IconView.SAVE, COLOR_TEXT);
        LinearLayout saveButton = new LinearLayout(activity);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setBackground(box(COLOR_FIELD, 22, false));
        saveButton.addView(saveIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(saveButton, activity.getString(R.string.cl_save_codelove));
        saveButton.setOnClickListener(v -> saveCurrentFile());

        editor = new CodeEditor(activity, new CodeEditor.Listener() {
            @Override
            public void onEdited() {
                if (saveIcon != null) saveIcon.setKind(IconView.SAVE);
            }

            @Override
            public void onSaved(File file) {
                onFileSaved(file);
            }

            @Override
            public void onClosed() {
                cancelDrawerAnimation();
                fileDrawerOpen = false;
                drawerProgress = 0f;
                applyLayout();
                renderTree();
                saveState();
            }

            @Override
            public void onFilesRequested() {
                if (root.getHeight() <= root.getWidth() || fullscreen) return;
                setDrawerOpen(!fileDrawerOpen);
            }

            @Override
            public void onActiveTabChanged() {
                applyLayout();
                renderTree();
                saveState();
            }
        });
        editor.addHeaderControl(headerPlayButton, 42, 8);
        editor.addFooterControl(saveButton, CONTROL_BUTTON_DP, 0);
        editorView = editor.getView();
        editorView.setVisibility(View.GONE);

        root.addView(placeholder);
        root.addView(editorView);
        drawerScrim = new View(activity);
        drawerScrim.setBackgroundColor(0x90000000);
        drawerScrim.setVisibility(View.GONE);
        drawerScrim.setOnClickListener(v -> setDrawerOpen(false));
        root.addView(drawerScrim);
        root.addView(panel);
        root.addView(controlsBar);
        root.addView(closePlaybackButton);

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
        updatePlayButton();
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

        // A engrenagem e o unico acesso do cabecalho as opcoes do aplicativo.
        // Orientacao e informacoes passam a ser acessadas dentro de Configuracoes.
        LinearLayout settingsButton = new LinearLayout(activity);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setBackground(box(COLOR_FIELD, 10, false));
        settingsButton.addView(new IconView(activity, IconView.SETTINGS, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(settingsButton, activity.getString(R.string.cl_settings));
        settingsButton.setClickable(true);
        settingsButton.setFocusable(true);
        settingsButton.setOnClickListener(v -> showSettings());
        header.addView(settingsButton, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // No vertical, a gaveta cobre o editor. O Play tambem deve existir
        // no painel de arquivos para executar o projeto sem abrir um arquivo.
        panelPlayIcon = new IconView(activity, IconView.PLAY, COLOR_TEXT);
        panelPlayButton = new LinearLayout(activity);
        panelPlayButton.setGravity(Gravity.CENTER);
        panelPlayButton.setBackground(box(COLOR_FIELD, 10, false));
        panelPlayButton.addView(panelPlayIcon,
            new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(panelPlayButton, activity.getString(R.string.cl_run_project));
        panelPlayButton.setOnClickListener(v -> togglePlayback());
        LinearLayout.LayoutParams panelPlayLp =
            new LinearLayout.LayoutParams(dp(40), dp(40));
        panelPlayLp.leftMargin = dp(8);
        header.addView(panelPlayButton, panelPlayLp);

        return header;
    }

    /** Configuracoes nativas com a identidade visual do CodeLove. */
    private void showSettings() {
        if (activity.isFinishing()) return;
        if (settingsDialog != null && settingsDialog.isShowing()) return;

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        content.setBackground(box(COLOR_PANEL, 18, false));

        TextView brand = new TextView(activity);
        brand.setText("CODELOVE");
        brand.setTextColor(COLOR_FOLDER);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.10f);
        content.addView(brand);

        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingLp.setMargins(0, dp(6), 0, dp(12));
        content.addView(heading, headingLp);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.cl_settings));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(COLOR_TEXT);
        heading.addView(title, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout close = new LinearLayout(activity);
        close.setGravity(Gravity.CENTER);
        close.setBackground(box(COLOR_FIELD, 10, false));
        close.addView(new IconView(activity, IconView.CLOSE, COLOR_MUTED),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        setButtonHint(close, activity.getString(R.string.cl_settings_close));
        close.setClickable(true);
        close.setFocusable(true);
        heading.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));

        TextView explanation = new TextView(activity);
        explanation.setText(activity.getString(R.string.cl_settings_desc));
        explanation.setTextColor(COLOR_MUTED);
        explanation.setTextSize(13);
        LinearLayout.LayoutParams explanationLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        explanationLp.setMargins(0, 0, 0, dp(14));
        content.addView(explanation, explanationLp);

        String appearance = prefs.getString(PREF_APPEARANCE, "dark");
        String appearanceName = activity.getString("light".equals(appearance)
            ? R.string.cl_theme_light
            : ("auto".equals(appearance) ? R.string.cl_theme_auto_android
                : R.string.cl_theme_dark));
        content.addView(buildSettingsRow(
            IconView.PALETTE, activity.getString(R.string.cl_appearance),
            activity.getString(R.string.cl_theme_status, appearanceName),
            v -> {
                if (settingsDialog != null) settingsDialog.dismiss();
                showAppearanceChooser();
            }));

        View appearanceDivider = new View(activity);
        appearanceDivider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams appearanceDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        appearanceDividerLp.setMargins(0, dp(10), 0, dp(10));
        content.addView(appearanceDivider, appearanceDividerLp);

        String language = prefs.getString(PREF_LANGUAGE, "auto");
        int languageNameId = "pt".equals(language) ? R.string.cl_language_pt
            : "en".equals(language) ? R.string.cl_language_en
            : "es".equals(language) ? R.string.cl_language_es
            : R.string.cl_language_auto_android;
        content.addView(buildSettingsRow(
            IconView.LANGUAGE, activity.getString(R.string.cl_language),
            activity.getString(R.string.cl_language_status,
                activity.getString(languageNameId)),
            v -> {
                if (settingsDialog != null) settingsDialog.dismiss();
                showLanguageChooser();
            }));
        View languageDivider = new View(activity);
        languageDivider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams languageDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        languageDividerLp.setMargins(0, dp(10), 0, dp(10));
        content.addView(languageDivider, languageDividerLp);

        content.addView(buildSettingsRow(
            IconView.ORIENTATION, activity.getString(R.string.cl_orientation),
            activity.getString(R.string.cl_orientation_desc), v -> {
                if (settingsDialog != null) settingsDialog.dismiss();
                showOrientationChooser(true);
            }));

        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dividerLp.setMargins(0, dp(10), 0, dp(10));
        content.addView(divider, dividerLp);

        String currentFolderName = activeSafStorage != null
            ? activeSafStorage.getDisplayName() : null;
        content.addView(buildSettingsRow(
            IconView.FOLDER, activity.getString(R.string.cl_main_folder),
            currentFolderName == null
                ? activity.getString(R.string.cl_main_folder_choose)
                : activity.getString(R.string.cl_main_folder_active, currentFolderName),
            v -> {
                if (settingsDialog != null) settingsDialog.dismiss();
                addExistingProject();
            }));

        View folderDivider = new View(activity);
        folderDivider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams folderDividerLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        folderDividerLp.setMargins(0, dp(10), 0, dp(10));
        content.addView(folderDivider, folderDividerLp);

        content.addView(buildSettingsRow(
            IconView.INFO, activity.getString(R.string.cl_about),
            activity.getString(R.string.cl_about_desc), v -> {
                if (settingsDialog != null) settingsDialog.dismiss();
                showAbout();
            }));

        // Uma ScrollView evita cortes em celulares no modo horizontal.
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Dialog dialog = new Dialog(activity);
        settingsDialog = dialog;
        dialog.setContentView(scroll);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (settingsDialog == dialog) settingsDialog = null;
        });
        close.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int availableWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(32);
            int dialogWidth = Math.max(dp(220), Math.min(dp(420), availableWidth));
            dialog.getWindow().setLayout(dialogWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private View buildSettingsRow(int iconKind, String name, String description,
        View.OnClickListener action) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.setBackground(box(COLOR_FIELD, 12, true));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(name + ". " + description);
        row.setOnClickListener(action);

        row.addView(new IconView(activity, iconKind, COLOR_FOLDER),
            new LinearLayout.LayoutParams(dp(22), dp(22)));

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams labelsLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelsLp.setMargins(dp(12), 0, dp(10), 0);
        row.addView(labels, labelsLp);

        TextView heading = new TextView(activity);
        heading.setText(name);
        heading.setTextColor(COLOR_TEXT);
        heading.setTextSize(15);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(heading);

        TextView hint = new TextView(activity);
        hint.setText(description);
        hint.setTextColor(COLOR_MUTED);
        hint.setTextSize(12);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(4), 0, 0);
        labels.addView(hint, hintLp);

        row.addView(new IconView(activity, IconView.CHEVRON_RIGHT, COLOR_MUTED),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        return row;
    }

    /** Seleciona o tema, sem inserir controles inativos no painel principal. */
    private void showAppearanceChooser() {
        if (activity.isFinishing()) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        content.setBackground(box(COLOR_PANEL, 18, false));

        TextView brand = new TextView(activity);
        brand.setText("CODELOVE");
        brand.setTextColor(COLOR_FOLDER);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(brand);

        TextView title = new TextView(activity);
        title.setText("Aparência");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, dp(8), 0, dp(12));
        content.addView(title, titleLp);

        String current = prefs.getString(PREF_APPEARANCE, "dark");
        Dialog dialog = new Dialog(activity);
        String[] values = {"auto", "dark", "light"};
        String[] names = {activity.getString(R.string.cl_theme_auto),
            activity.getString(R.string.cl_theme_dark),
            activity.getString(R.string.cl_theme_light)};
        String[] descriptions = {
            activity.getString(R.string.cl_theme_auto_desc),
            activity.getString(R.string.cl_theme_dark_desc),
            activity.getString(R.string.cl_theme_light_desc)
        };
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            final boolean selected = value.equals(current);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            row.setBackground(box(selected ? COLOR_FIELD : COLOR_PANEL,
                12, selected));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(activity);
            name.setText(names[i]);
            name.setTextColor(COLOR_TEXT);
            name.setTextSize(15);
            name.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            labels.addView(name);
            TextView description = new TextView(activity);
            description.setText(descriptions[i]);
            description.setTextColor(COLOR_MUTED);
            description.setTextSize(12);
            labels.addView(description);
            row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (selected) {
                row.addView(new IconView(activity, IconView.CHECK, COLOR_FOLDER),
                    new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(names[i] + (selected ? activity.getString(R.string.cl_selected) : ""));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                changeAppearance(value);
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i != 0) rowLp.topMargin = dp(8);
            content.addView(row, rowLp);
        }

        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cl_cancel));
        cancel.setTextColor(COLOR_MUTED);
        cancel.setTextSize(13);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(12), dp(10), dp(12), dp(10));
        cancel.setBackground(box(COLOR_FIELD, 10, false));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.END;
        cancelLp.topMargin = dp(14);
        content.addView(cancel, cancelLp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(content);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int width = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
            dialog.getWindow().setLayout(Math.min(dp(420), width),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Idioma do Android ou escolha manual. Sem alterar os comandos Lua. */
    private void showLanguageChooser() {
        if (activity.isFinishing()) return;
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(20));
        content.setBackground(box(COLOR_PANEL, 18, false));
        TextView brand = new TextView(activity);
        brand.setText("CODELOVE");
        brand.setTextColor(COLOR_FOLDER);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(brand);
        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.cl_language));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, dp(8), 0, dp(12));
        content.addView(title, titleLp);

        String[] values = {"auto", "pt", "en", "es"};
        int[] names = {R.string.cl_theme_auto, R.string.cl_language_pt,
            R.string.cl_language_en, R.string.cl_language_es};
        int[] descriptions = {R.string.cl_language_auto_desc, R.string.cl_language_pt_desc,
            R.string.cl_language_en_desc, R.string.cl_language_es_desc};
        String current = prefs.getString(PREF_LANGUAGE, "auto");
        Dialog dialog = new Dialog(activity);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            boolean selected = value.equals(current);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(12), dp(12));
            row.setBackground(box(selected ? COLOR_FIELD : COLOR_PANEL, 12, selected));
            LinearLayout labels = new LinearLayout(activity);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(activity);
            name.setText(activity.getString(names[i]));
            name.setTextSize(15);
            name.setTextColor(COLOR_TEXT);
            name.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            labels.addView(name);
            TextView description = new TextView(activity);
            description.setText(activity.getString(descriptions[i]));
            description.setTextColor(COLOR_MUTED);
            description.setTextSize(12);
            labels.addView(description);
            row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            if (selected) {
                row.addView(new IconView(activity, IconView.CHECK, COLOR_FOLDER),
                    new LinearLayout.LayoutParams(dp(20), dp(20)));
            }
            row.setClickable(true);
            row.setFocusable(true);
            row.setContentDescription(name.getText() +
                (selected ? activity.getString(R.string.cl_selected) : ""));
            row.setOnClickListener(v -> {
                dialog.dismiss();
                changeLanguage(value);
            });
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i != 0) rowLp.topMargin = dp(8);
            content.addView(row, rowLp);
        }
        TextView cancel = new TextView(activity);
        cancel.setText(activity.getString(R.string.cl_cancel));
        cancel.setTextColor(COLOR_MUTED);
        cancel.setTextSize(13);
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(12), dp(10), dp(12), dp(10));
        cancel.setBackground(box(COLOR_FIELD, 10, false));
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cancelLp.gravity = Gravity.END;
        cancelLp.topMargin = dp(14);
        content.addView(cancel, cancelLp);
        cancel.setOnClickListener(v -> dialog.dismiss());
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog.setContentView(scroll);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int width = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
            int height = activity.getResources().getDisplayMetrics().heightPixels - dp(36);
            dialog.getWindow().setLayout(Math.min(dp(420), width), Math.min(dp(450), height));
        }
    }

    private void changeLanguage(String selected) {
        if (prefs.getString(PREF_LANGUAGE, "auto").equals(selected)) return;
        if (editor != null && editor.hasUnsavedTabs()) {
            toast(activity.getString(R.string.cl_save_tabs_language));
            return;
        }
        if (surface == null) {
            pendingThemeSession = new ThemeSession(
                activeProject == null ? null : activeProject.getAbsolutePath(),
                editor == null ? null : editor.captureThemeTabs(), fileDrawerOpen);
        }
        prefs.edit().putString(PREF_LANGUAGE, selected).apply();
        if (activity.getWindow() != null) activity.getWindow().setWindowAnimations(0);
        activity.recreate();
    }

    private void changeAppearance(String selected) {
        String previous = prefs.getString(PREF_APPEARANCE, "dark");
        if (previous.equals(selected)) return;

        // Nao recriar o editor caso alguma aba ainda tenha texto nao salvo.
        if (editor != null && editor.hasUnsavedTabs()) {
            toast(activity.getString(R.string.cl_save_tabs_theme));
            return;
        }
        if (surface == null) {
            pendingThemeSession = new ThemeSession(
                activeProject == null ? null : activeProject.getAbsolutePath(),
                editor == null ? null : editor.captureThemeTabs(),
                fileDrawerOpen);
        }
        prefs.edit().putString(PREF_APPEARANCE, selected).apply();
        // Recria somente a Activity (a engine de jogo permanece isolada).
        // Sem animacao de janela adicional durante a mudanca de aparencia.
        if (activity.getWindow() != null) activity.getWindow().setWindowAnimations(0);
        activity.recreate();
    }

    private void showInitialOrientationChooser() {
        root.post(() -> {
            if (!prefs.contains(PREF_WORKSPACE_ORIENTATION) && !activity.isFinishing()) {
                showOrientationChooser(false);
            }
        });
    }

    private void showOrientationChooser(boolean allowDismiss) {
        if (activity.isFinishing()) return;

        // Janela desenhada com os mesmos componentes, cores e superficies do CodeLove.
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(16));
        content.setBackground(box(COLOR_PANEL, 18, false));

        TextView brand = new TextView(activity);
        brand.setText("CODELOVE");
        brand.setTextColor(COLOR_FOLDER);
        brand.setTextSize(11);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        brand.setLetterSpacing(0.10f);
        content.addView(brand);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.cl_orientation));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(5), 0, dp(5));
        content.addView(title, titleParams);

        TextView explanation = new TextView(activity);
        explanation.setText(activity.getString(R.string.cl_orientation_explanation));
        explanation.setTextColor(COLOR_MUTED);
        explanation.setTextSize(13);
        explanation.setPadding(0, 0, 0, dp(16));
        content.addView(explanation);

        View portraitOption = buildOrientationOption(true);
        LinearLayout.LayoutParams portraitParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(portraitOption, portraitParams);

        View divider = new View(activity);
        divider.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(12), 0, dp(12));
        content.addView(divider, dividerParams);

        View landscapeOption = buildOrientationOption(false);
        content.addView(landscapeOption, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView cancel = null;
        if (allowDismiss) {
            cancel = new TextView(activity);
            cancel.setText(activity.getString(R.string.cl_cancel));
            cancel.setTextColor(COLOR_MUTED);
            cancel.setTextSize(13);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(16), dp(10), dp(16), dp(10));
            cancel.setBackground(box(COLOR_FIELD, 10, false));
            cancel.setClickable(true);
            cancel.setFocusable(true);
            LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cancelParams.gravity = Gravity.END;
            cancelParams.setMargins(0, dp(14), 0, 0);
            content.addView(cancel, cancelParams);
        }

        // Usa Dialog simples em vez de AlertDialog: o AlertDialog envolve a view
        // em painéis internos do tema Android, que podem manter uma moldura quadrada
        // mesmo quando o background da janela é transparente.
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(content);
        dialog.setCancelable(allowDismiss);
        dialog.setCanceledOnTouchOutside(allowDismiss);

        content.findViewWithTag("orientation_portrait").setOnClickListener(v -> {
            saveOrientationChoice(true);
            dialog.dismiss();
        });
        content.findViewWithTag("orientation_landscape").setOnClickListener(v -> {
            saveOrientationChoice(false);
            dialog.dismiss();
        });
        if (cancel != null) {
            cancel.setOnClickListener(v -> dialog.dismiss());
        }

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new ColorDrawable(android.graphics.Color.TRANSPARENT));
            int availableWidth = activity.getResources().getDisplayMetrics().widthPixels - dp(40);
            dialog.getWindow().setLayout(Math.min(dp(420), availableWidth),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private View buildOrientationOption(boolean portrait) {
        LinearLayout option = new LinearLayout(activity);
        option.setTag(portrait ? "orientation_portrait" : "orientation_landscape");
        option.setOrientation(LinearLayout.HORIZONTAL);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(dp(12), dp(12), dp(12), dp(12));
        option.setBackground(box(COLOR_FIELD, 12, true));
        option.setClickable(true);
        option.setFocusable(true);

        OrientationPreviewView preview = new OrientationPreviewView(activity, portrait);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(dp(76), dp(76));
        previewParams.setMargins(0, 0, dp(14), 0);
        option.addView(preview, previewParams);

        LinearLayout labels = new LinearLayout(activity);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(portrait ? activity.getString(R.string.cl_portrait) : activity.getString(R.string.cl_landscape));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);

        TextView description = new TextView(activity);
        description.setText(portrait
            ? activity.getString(R.string.cl_portrait_desc)
            : activity.getString(R.string.cl_landscape_desc));
        description.setTextColor(COLOR_MUTED);
        description.setTextSize(12);
        LinearLayout.LayoutParams descriptionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        descriptionParams.setMargins(0, dp(4), 0, 0);
        labels.addView(description, descriptionParams);
        option.addView(labels, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return option;
    }

    private void saveOrientationChoice(boolean portrait) {
        String value = portrait ? "portrait" : "landscape";
        prefs.edit().putString(PREF_WORKSPACE_ORIENTATION, value).apply();
        activity.setRequestedOrientation(portrait
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        applyLayout();
    }

    private TextView mobileActionButton(String label, View.OnClickListener listener) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(12);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(6), 0, dp(6), 0);
        // Os comandos funcionam como abas da barra, sem caixas arredondadas
        // individuais competindo visualmente com o editor.
        button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        button.setOnClickListener(listener);
        return button;
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

    /** Dica visual por toque longo e descricao para leitores de tela. */
    private void setButtonHint(View button, String hint) {
        button.setContentDescription(hint);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            button.setTooltipText(hint);
        }
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

    private void showAbout() {
        ui.showAbout("joa-no", activity.getString(R.string.cl_credits_full),
            readAssetText("codelove-licenses.txt"));
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
            return activity.getString(R.string.cl_license_fallback, name);
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
        search.setHint(activity.getString(R.string.cl_search_files));
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
            if (surface != null) surface.requestFocus();
            else root.requestFocus();
            return true;
        });
        return search;
    }

    // ------------------------------------------------------------------
    // Posicao do painel, do botao e da area do jogo
    // ------------------------------------------------------------------

    /** Animacao unica para o menu, o toque externo e os gestos. */
    private void setDrawerOpen(boolean opened) {
        if (opened && !fileDrawerOpen) {
            InputMethodManager imm = (InputMethodManager)
                activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        fileDrawerOpen = opened;
        animateDrawerTo(opened ? 1f : 0f);
    }

    private void cancelDrawerAnimation() {
        if (drawerAnimator != null) {
            ValueAnimator old = drawerAnimator;
            drawerAnimator = null;
            old.cancel();
        }
    }

    private float clampDrawer(float progress) {
        return Math.max(0f, Math.min(1f, progress));
    }

    private int currentDrawerWidth() {
        ViewGroup.LayoutParams params = panel.getLayoutParams();
        if (params != null && params.width > 0) return params.width;
        float width = root.getWidth() - insetLeft - insetRight;
        return Math.max(1, Math.min((int) (width * 0.86f), dp(340)));
    }

    /** Desloca o painel sem recalcular o layout nem interromper a rolagem. */
    private void updateDrawerProgress(float progress) {
        drawerProgress = clampDrawer(progress);
        if (root.getHeight() <= root.getWidth() || surface != null || fullscreen) return;
        int width = currentDrawerWidth();
        boolean visible = drawerProgress > 0.0001f;
        panel.setVisibility(visible ? View.VISIBLE : View.GONE);
        panel.setTranslationX((drawerProgress - 1f) * width);
        drawerScrim.setAlpha(drawerProgress);
        drawerScrim.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void animateDrawerTo(float target) {
        cancelDrawerAnimation();
        target = clampDrawer(target);
        if (Math.abs(drawerProgress - target) < 0.001f) {
            updateDrawerProgress(target);
            return;
        }
        final float destination = target;
        ValueAnimator animation = ValueAnimator.ofFloat(drawerProgress, destination);
        drawerAnimator = animation;
        animation.setInterpolator(new DecelerateInterpolator());
        animation.setDuration(Math.max(120L,
            Math.round(260f * Math.abs(drawerProgress - destination))));
        animation.addUpdateListener(a -> updateDrawerProgress((Float) a.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (drawerAnimator == animation) {
                    drawerAnimator = null;
                    updateDrawerProgress(destination);
                }
            }
        });
        animation.start();
    }

    /**
     * 0 = toque normal, 1 = gesto capturado (cancelar toque filho),
     * 2 = continuar consumindo ate levantar. Apenas editor vertical.
     */
    static int handleEditorSwipe(Activity activity, MotionEvent event) {
        Workspace w = currentInstance;
        if (w == null || w.activity != activity || w.disposed
            || w.surface != null || w.fullscreen || w.root.getWidth() <= 0
            || w.root.getHeight() <= w.root.getWidth()) return 0;
        return w.handleDrawerTouch(event);
    }

    private int handleDrawerTouch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            swipeCaptured = false;
            swipeDownX = event.getX();
            swipeDownY = event.getY();
            swipeDownTime = event.getEventTime();
            swipeStartProgress = drawerProgress;
            float left = insetLeft;
            float width = currentDrawerWidth();
            swipeCandidate = fileDrawerOpen || drawerProgress > 0.001f
                ? swipeDownX >= left && swipeDownX <= left + width * drawerProgress
                : swipeDownX >= left && swipeDownX <= left + dp(44);
            return 0;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (swipeCaptured) {
                swipeCaptured = false;
                swipeCandidate = false;
                setDrawerOpen(drawerProgress >= 0.5f);
                return 2;
            }
            swipeCandidate = false;
            return 0;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!swipeCandidate && !swipeCaptured) return 0;
            if (event.getPointerCount() != 1) return swipeCaptured ? 2 : 0;
            float dx = event.getX() - swipeDownX;
            float dy = event.getY() - swipeDownY;
            boolean justCaptured = !swipeCaptured;
            if (!swipeCaptured) {
                // Nao capturar rolagem vertical na arvore nem no editor.
                if (Math.abs(dy) > dp(12) && Math.abs(dy) >= Math.abs(dx)) {
                    swipeCandidate = false;
                    return 0;
                }
                if (Math.abs(dx) < dp(12)
                    || Math.abs(dx) < Math.abs(dy) * 1.4f) return 0;
                if ((!fileDrawerOpen && dx <= 0) || (fileDrawerOpen && dx >= 0)) {
                    swipeCandidate = false;
                    return 0;
                }
                cancelDrawerAnimation();
                swipeCaptured = true;
                swipeStartProgress = fileDrawerOpen ? 1f : 0f;
            }
            updateDrawerProgress(swipeStartProgress + dx / currentDrawerWidth());
            return justCaptured ? 1 : 2;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean consumed = swipeCaptured;
            if (consumed) {
                float dx = event.getX() - swipeDownX;
                long elapsed = Math.max(1L, event.getEventTime() - swipeDownTime);
                float speed = Math.abs(dx) * 1000f / elapsed;
                boolean fling = action != MotionEvent.ACTION_CANCEL
                    && Math.abs(dx) >= dp(45) && speed >= dp(550);
                boolean open = action == MotionEvent.ACTION_CANCEL
                    ? fileDrawerOpen
                    : fling ? dx > 0 : drawerProgress >= 0.5f;
                setDrawerOpen(open);
            }
            swipeCaptured = false;
            swipeCandidate = false;
            return consumed ? 2 : 0;
        }
        return swipeCaptured ? 2 : 0;
    }

    private void applyLayout() {
        // Antes do primeiro layout, 0x0 nao pode ser interpretado como horizontal:
        // isso fechava a gaveta restaurada e ocultava momentaneamente o editor.
        boolean verticalMode;
        if (root.getWidth() > 0 && root.getHeight() > 0) {
            verticalMode = root.getHeight() > root.getWidth();
        } else {
            String choice = prefs.getString(PREF_WORKSPACE_ORIENTATION, "");
            verticalMode = "portrait".equals(choice)
                || (!"landscape".equals(choice)
                    && activity.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_PORTRAIT);
        }
        boolean editorOpen = editor != null && editor.isOpen() && !fullscreen;
        // EditorActivity nao possui superficie de jogo: o editor ocupa sempre
        // a area de trabalho, inclusive quando nenhum arquivo esta selecionado.
        boolean editorScreenVisible = !fullscreen && (surface == null || editorOpen);
        if (editor != null) {
            // As abas funcionam em ambas as orientacoes do editor Java;
            // o menu hamburguer e exclusivo do vertical.
            editor.setTabsMode(surface == null, verticalMode && surface == null);
            editor.setEmptyWorkspaceEnabled(surface == null);
        }
        if (!verticalMode) {
            fileDrawerOpen = false;
            cancelDrawerAnimation();
            drawerProgress = 0f;
            swipeCandidate = false;
            swipeCaptured = false;
        }
        boolean panelShown = !fullscreen && (!verticalMode
            || (surface == null ? fileDrawerOpen : (!editorOpen && !playing)));
        if (movePanelButton != null) {
            movePanelButton.setVisibility(verticalMode ? View.GONE : View.VISIBLE);
        }
        // Evita dois botoes Play simultaneos no modo horizontal: o Play do
        // editor ja fica no canto superior direito da area de trabalho.
        if (panelPlayButton != null) {
            panelPlayButton.setVisibility(verticalMode && surface == null
                ? View.VISIBLE : View.GONE);
        }
        int sideInset = onLeft ? insetLeft : insetRight;
        if (ui != null) {
            ui.topInset = insetTop;
        }

        float baseW = root.getWidth() - insetLeft - insetRight;
        float baseH = root.getHeight() - insetTop - insetBottom;

        // No modo horizontal, mantemos a largura original do painel.
        // No vertical, o painel de arquivos vira uma gaveta sobreposta.
        int effectivePanelWidth = panelWidth;
        int cursor = sideInset;
        int panelOffset = 0;
        int editorOffset = 0;
        int reserved = 0;

        if (panelShown && !verticalMode) {
            panelOffset = cursor + margin;
            cursor += margin + effectivePanelWidth + margin;
            reserved = cursor - sideInset;
        }

        int editorWidth = ViewGroup.LayoutParams.MATCH_PARENT;
        if (editorScreenVisible) {
            if (verticalMode) {
                editorOffset = 0;
                editorWidth = baseW > 0f
                    ? Math.max(0, (int) baseW)
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                editorOffset = panelShown ? cursor : sideInset;
                if (baseW > 0f) {
                    editorWidth = Math.max(0,
                        (int) baseW - (editorOffset - sideInset));
                }
            }
        }

        // O runtime continua com a mesma resolucao logica. No modo vertical,
        // os paineis sao sobrepostos e nao reduzem a area do jogo.
        if (surface != null) {
        RelativeLayout.LayoutParams surfaceLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        surfaceLp.leftMargin = insetLeft;
        surfaceLp.topMargin = insetTop;
        surfaceLp.rightMargin = insetRight;
        surfaceLp.bottomMargin = insetBottom;
        surface.setLayoutParams(surfaceLp);

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
        }

        RelativeLayout.LayoutParams placeholderLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        placeholderLp.topMargin = insetTop;
        placeholderLp.bottomMargin = insetBottom;
        placeholderLp.leftMargin = insetLeft + (!verticalMode && onLeft ? reserved : 0);
        placeholderLp.rightMargin = insetRight + (!verticalMode && !onLeft ? reserved : 0);
        placeholder.setLayoutParams(placeholderLp);

        if (verticalMode) {
            int drawerWidth = surface == null && !fullscreen
                ? Math.max(0, Math.min((int) (baseW * 0.86f), dp(340)))
                : Math.max(0, (int) baseW);
            RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
                drawerWidth, ViewGroup.LayoutParams.MATCH_PARENT);
            panelLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            panelLp.leftMargin = insetLeft;
            panelLp.rightMargin = insetRight;
            panelLp.topMargin = insetTop;
            panelLp.bottomMargin = insetBottomIme;
            panel.setLayoutParams(panelLp);
        } else {
            RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
                effectivePanelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
            panelLp.addRule(onLeft
                ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
            panelLp.topMargin = insetTop;
            panelLp.bottomMargin = insetBottomIme;
            if (onLeft) {
                panelLp.leftMargin = panelOffset - margin;
            } else {
                panelLp.rightMargin = panelOffset - margin;
            }
            panel.setLayoutParams(panelLp);
        }
        // O painel da Activity de edicao vertical e posicionado por translacao.
        // Nao alternar a visibilidade aqui durante o arraste.
        if (!verticalMode || surface != null || fullscreen) {
            panel.setTranslationX(0f);
            panel.setVisibility(panelShown ? View.VISIBLE : View.GONE);
        }
        RelativeLayout.LayoutParams scrimLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        scrimLp.leftMargin = insetLeft;
        scrimLp.rightMargin = insetRight;
        scrimLp.topMargin = insetTop;
        scrimLp.bottomMargin = insetBottomIme;
        drawerScrim.setLayoutParams(scrimLp);
        if (verticalMode && surface == null && !fullscreen) {
            updateDrawerProgress(drawerProgress);
        } else {
            drawerScrim.setAlpha(1f);
            drawerScrim.setVisibility(View.GONE);
        }

        RelativeLayout.LayoutParams editorLp = new RelativeLayout.LayoutParams(
            editorWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        editorLp.addRule(verticalMode || onLeft
            ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        editorLp.topMargin = insetTop;
        editorLp.bottomMargin = insetBottomIme;
        if (verticalMode || onLeft) {
            editorLp.leftMargin = verticalMode ? insetLeft + editorOffset : editorOffset;
        } else {
            editorLp.rightMargin = editorOffset;
        }
        editorView.setLayoutParams(editorLp);
        editorView.setVisibility(editorScreenVisible ? View.VISIBLE : View.GONE);

        int barHeight = dp(CONTROL_BUTTON_DP) + 2 * dp(BAR_PAD_DP);
        int buttonCount = surface == null ? 1 : 3;
        int barWidth = buttonCount * dp(CONTROL_BUTTON_DP)
            + (buttonCount - 1) * dp(BAR_GAP_DP) + 2 * dp(BAR_PAD_DP);
        int regionStart = insetLeft + (!verticalMode && onLeft ? reserved : 0);
        int regionWidth = root.getWidth() - insetLeft - insetRight
            - (!verticalMode ? reserved : 0);
        int barX;
        if (root.getWidth() <= 0) {
            barX = 0;
        } else if (fullscreen) {
            barX = onLeft
                ? insetLeft + margin
                : root.getWidth() - insetRight - margin - barWidth;
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

        RelativeLayout.LayoutParams closeLp = new RelativeLayout.LayoutParams(
            dp(CONTROL_BUTTON_DP), dp(CONTROL_BUTTON_DP));
        closeLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        closeLp.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        closeLp.bottomMargin = insetBottom + margin;
        closeLp.rightMargin = insetRight + margin;
        closePlaybackButton.setLayoutParams(closeLp);

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

        File treeRoot = activeSafStorage != null
            ? activeProject
            : ((gamesDir != null && gamesDir.isDirectory()) ? gamesDir : activeProject);

        if (treeRoot == null) {
            TextView note = new TextView(activity);
            note.setText(activity.getString(R.string.cl_folder_unavailable));
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
            note.setText(query.isEmpty() ? activity.getString(R.string.cl_no_files) : activity.getString(R.string.cl_no_results));
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

    /** Monta a lista de linhas visiveis, usando SAF direto quando o projeto externo esta aberto. */
    private boolean collect(File dir, int depth, String query, ArrayList<Row> out) {
        if (depth > MAX_DEPTH) return false;

        if (activeSafStorage != null && activeSafStorage.contains(dir)) {
            try {
                List<DirectSafStorage.Entry> kids = activeSafStorage.list(dir);
                boolean any = false;
                for (DirectSafStorage.Entry entry : kids) {
                    File f = entry.file;
                    boolean isDir = entry.directory;
                    boolean nameMatch = query.isEmpty()
                        || entry.name.toLowerCase(Locale.ROOT).contains(query);

                    if (isDir) {
                        if (query.isEmpty()) {
                            boolean isOpen = expanded.contains(f.getAbsolutePath());
                            out.add(new Row(f, depth, true, isOpen));
                            if (isOpen) collect(f, depth + 1, query, out);
                            any = true;
                        } else {
                            int mark = out.size();
                            out.add(new Row(f, depth, true, true));
                            boolean childMatch = collect(f, depth + 1, query, out);
                            if (childMatch || nameMatch) any = true;
                            else while (out.size() > mark) out.remove(out.size() - 1);
                        }
                    } else if (nameMatch) {
                        out.add(new Row(f, depth, false, false));
                        any = true;
                    }
                }
                return any;
            } catch (IOException e) {
                showProjectNotice(activity.getString(R.string.cl_access_lost));
                return false;
            }
        }

        if (dir == null || !dir.isDirectory()) return false;
        File[] kids = dir.listFiles();
        if (kids == null) return false;

        Arrays.sort(kids, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                boolean da = a.isDirectory();
                boolean db = b.isDirectory();
                if (da != db) return da ? -1 : 1;
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
                    if (isOpen) collect(f, depth + 1, query, out);
                    any = true;
                } else {
                    int mark = out.size();
                    out.add(new Row(f, depth, true, true));
                    boolean childMatch = collect(f, depth + 1, query, out);
                    if (childMatch || nameMatch) any = true;
                    else while (out.size() > mark) out.remove(out.size() - 1);
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

        // Pastas preservam o icone existente. Arquivos usam a mesma
        // identificacao de linguagem exibida nas abas do editor.
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        iconLp.setMargins(dp(6), 0, dp(8), 0);
        View fileIcon = row.dir
            ? new IconView(activity, IconView.FOLDER, colorFor(row))
            : new LanguageTabIcon(activity, row.file.getName());
        line.addView(fileIcon, iconLp);

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
        if (activeSafStorage != null && activeSafStorage.contains(file)) {
            return activeProject;
        }
        if (gamesDir == null) return null;
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
            if (activeProject == null || !sameFile(activeProject, project)) return;
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
        showNotice(activity.getString(R.string.cl_project_add_failure), message);
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
        body.setText(message == null ? activity.getString(R.string.cl_unexpected_error) : message);
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

        TextView retry = new TextView(activity);
        retry.setText(activity.getString(R.string.cl_select_again));
        retry.setTextColor(COLOR_ACCENT_TEXT);
        retry.setTextSize(13);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(box(COLOR_FOLDER, 10, false));
        retry.setPadding(dp(12), 0, dp(12), 0);
        actions.addView(retry, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        retry.setOnClickListener(v -> {
            dismissProjectNotice();
            addExistingProject();
        });
        sheet.addView(actions);

        // Comeca com largura disponivel e ajusta a largura final depois que
        // o layout raiz tiver sido medido (root.getWidth() pode ser zero no inicio).
        RelativeLayout.LayoutParams sheetLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetLp.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
        sheetLp.addRule(RelativeLayout.CENTER_HORIZONTAL);
        sheetLp.setMargins(dp(16), 0, dp(16), Math.max(dp(12), insetBottom));
        projectNotice = sheet;
        root.addView(sheet, sheetLp);
        sheet.bringToFront();
        root.post(() -> {
            if (projectNotice != sheet) return;
            int rootWidth = root.getWidth();
            if (rootWidth <= 0) {
                rootWidth = activity.getWindow().getDecorView().getWidth();
            }
            if (rootWidth <= 0) {
                rootWidth = activity.getResources().getDisplayMetrics().widthPixels;
            }
            int availableWidth = Math.max(dp(1), rootWidth - dp(32));
            int sheetWidth = Math.min(Math.round(availableWidth * 0.82f), dp(540));
            RelativeLayout.LayoutParams measuredLp =
                (RelativeLayout.LayoutParams) sheet.getLayoutParams();
            measuredLp.width = sheetWidth;
            measuredLp.leftMargin = 0;
            measuredLp.rightMargin = 0;
            sheet.setLayoutParams(measuredLp);
        });
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
        boolean lastFolderIsDirectory = lastFolder != null
            && (activeSafStorage != null && activeSafStorage.contains(lastFolder)
                ? activeSafStorage.isDirectory(lastFolder)
                : lastFolder.isDirectory());
        if (lastFolderIsDirectory && isInside(lastFolder, activeProject)) {
            return lastFolder;
        }
        return activeProject;
    }

    private void showAddMenu(View anchor) {
        // Sem projeto aberto, o botao + inicia diretamente a selecao da pasta.
        if (activeProject == null || activeSafStorage == null) {
            addExistingProject();
            return;
        }

        // Com um projeto aberto, o menu oferece apenas operacoes dentro dele.
        final File folder = targetFolder();
        if (folder == null) {
            addExistingProject();
            return;
        }

        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        labels.add(activity.getString(R.string.cl_add_file_in, folder.getName()));
        actions.add(() -> newFile(folder));
        labels.add(activity.getString(R.string.cl_add_folder_in, folder.getName()));
        actions.add(() -> newFolder(folder));
        labels.add(activity.getString(R.string.cl_change_main_folder));
        actions.add(this::addExistingProject);
        ui.showMenu(anchor, labels, actions);
    }

    private void addExistingProject() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            activity.startActivityForResult(intent, REQUEST_ADD_PROJECT_TREE);
        } catch (Exception e) {
            toast(activity.getString(R.string.cl_choose_folder_failure));
            Log.e(TAG, "Falha ao abrir o seletor SAF", e);
        }
    }

    static void handleActivityResult(Activity activity, int requestCode,
                                     int resultCode, Intent data) {
        if (requestCode != REQUEST_ADD_PROJECT_TREE) return;

        Workspace workspace = currentInstance;
        if (workspace == null || workspace.activity != activity
            || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        try {
            Uri treeUri = data.getData();
            int grantedFlags = data.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

            SafProjectManager.ProjectSelection selection =
                SafProjectManager.inspectProject(activity, treeUri);
            if (!selection.isValid()) {
                workspace.showProjectNotice(selection.error);
                return;
            }

            // Nao substituir a pasta persistida se houver edicoes ainda nao salvas.
            // closeAllTabs apenas exibe o aviso existente, sem descartar alteracoes.
            if (workspace.editor != null && workspace.editor.hasUnsavedTabs()) {
                workspace.editor.closeAllTabs();
                return;
            }

            String error = SafProjectManager.register(activity, treeUri, grantedFlags,
                selection.documentId, selection.displayName);
            if (error != null) {
                workspace.showProjectNotice(error);
                return;
            }

            DirectSafStorage storage = new DirectSafStorage(
                activity, treeUri, selection.documentId, selection.displayName);
            workspace.selectDirectProject(storage);
            workspace.dismissProjectNotice();
            workspace.toast(activity.getString(R.string.cl_folder_selected));
        } catch (RuntimeException e) {
            Log.e(TAG, "Falha inesperada ao processar a pasta selecionada pelo SAF", e);
            workspace.showProjectNotice(
                activity.getString(R.string.cl_android_folder_error));
        }
    }


    private void showRowMenu(Row row, View anchor) {
        final File file = row.file;
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        boolean externalProjectRoot = row.dir
            && activeSafStorage != null
            && sameFile(file, activeProject);
        if (row.dir) {
            labels.add(activity.getString(R.string.cl_add_file_here));
            actions.add(() -> newFile(file));
            labels.add(activity.getString(R.string.cl_add_folder_here));
            actions.add(() -> newFolder(file));
        }
        // A raiz do projeto aberto nao pode ser renomeada/apagada pelo menu.
        if (!externalProjectRoot) {
            labels.add(activity.getString(R.string.cl_rename));
            actions.add(() -> rename(file));
            labels.add(activity.getString(R.string.cl_delete));
            actions.add(() -> delete(file));
            if (activeProject != null && isInside(file, activeProject)) {
                labels.add(activity.getString(R.string.cl_move_to));
                actions.add(() -> showMoveDestinationMenu(file));
            }
        }
        ui.showMenu(anchor, labels, actions);
    }

    private void showMoveDestinationMenu(final File target) {
        if (activeProject == null || sameFile(target, activeProject)
            || !isInside(target, activeProject)) {
            return;
        }

        ArrayList<File> destinations = new ArrayList<File>();
        collectMoveFolders(activeProject, target, destinations);

        if (destinations.isEmpty()) {
            toast(activity.getString(R.string.cl_no_move_targets));
            return;
        }

        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        File targetParent = target.getParentFile();
        for (File destination : destinations) {
            if (targetParent != null && sameFile(destination, targetParent)) {
                continue;
            }
            String label;
            if (sameFile(destination, activeProject)) {
                label = activity.getString(R.string.cl_project_root);
            } else {
                int depth = relativeDepth(destination, activeProject);
                StringBuilder prefix = new StringBuilder();
                for (int i = 0; i < depth; i++) prefix.append("  ");
                label = prefix.toString() + destination.getName();
            }
            labels.add(label);
            actions.add(() -> moveItem(target, destination));
        }

        if (labels.isEmpty()) {
            toast(activity.getString(R.string.cl_no_move_targets));
            return;
        }

        ui.showScrollableMenu(null, activity.getString(R.string.cl_move_to), labels, actions);
    }

    private void collectMoveFolders(File directory, File target, List<File> out) {
        if (directory == null || !isInside(directory, activeProject)) return;
        if (sameFile(directory, target) || isInside(directory, target)) return;
        out.add(directory);

        if (out.size() >= 200) return;
        try {
            if (activeSafStorage != null && activeSafStorage.contains(directory)) {
                List<DirectSafStorage.Entry> children = activeSafStorage.list(directory);
                for (DirectSafStorage.Entry entry : children) {
                    if (entry.directory) {
                        collectMoveFolders(entry.file, target, out);
                        if (out.size() >= 200) return;
                    }
                }
            } else {
                File[] children = directory.listFiles();
                if (children == null) return;
                Arrays.sort(children, new Comparator<File>() {
                    @Override public int compare(File a, File b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                for (File child : children) {
                    if (child.isDirectory()) {
                        collectMoveFolders(child, target, out);
                        if (out.size() >= 200) return;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Falha ao listar destinos de movimento", e);
        }
    }

    private int relativeDepth(File file, File rootDir) {
        if (file == null || rootDir == null) return 0;
        String rootPath = rootDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (!isInside(file, rootDir)) return 0;
        if (sameFile(file, rootDir)) return 0;
        String relative = filePath.substring(rootPath.length() + 1);
        if (relative.isEmpty()) return 0;
        int depth = 1;
        for (int i = 0; i < relative.length(); i++) {
            if (relative.charAt(i) == File.separatorChar) depth++;
        }
        return depth;
    }

    private void moveItem(final File target, final File destination) {
        if (activeProject == null || destination == null
            || sameFile(target, activeProject) || sameFile(target, destination)
            || !isInside(target, activeProject) || !isInside(destination, activeProject)
            || isInside(destination, target)) {
            toast(activity.getString(R.string.cl_invalid_destination));
            return;
        }

        final File targetParent = target.getParentFile();
        if (targetParent != null && sameFile(targetParent, destination)) {
            toast(activity.getString(R.string.cl_already_there));
            return;
        }

        final File editedFile = editor.isOpen() ? editor.getFile() : null;
        final boolean editedInsideTarget = editedFile != null && isInside(editedFile, target);
        if (editor.hasTabInside(target) && !editor.closeTabsInside(target)) return;

        playing = false;
        sendCommand("close", "");

        final String oldPath = target.getAbsolutePath();
        final String editedRelativePath = editedInsideTarget && editedFile != null
            ? relativePath(target, editedFile) : null;

        try {
            File moved;
            if (activeSafStorage != null && activeSafStorage.contains(target)) {
                moved = activeSafStorage.move(target, destination).getAbsoluteFile();
            } else {
                moved = FileOps.move(target, destination);
            }

            if (moved == null) {
                toast(activity.getString(R.string.cl_cannot_move));
                return;
            }

            Set<String> next = new HashSet<String>();
            String newPath = moved.getAbsolutePath();
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
            expanded.add(destination.getAbsolutePath());
            lastFolder = destination;

            if (activeSafStorage != null) {
                prepareRuntimeProject(false);
            } else {
                sendCommand("open", activeProject.getAbsolutePath());
            }

            renderTree();
            if (editedInsideTarget && editedRelativePath != null) {
                File reopened = new File(moved, editedRelativePath);
                if (activeSafStorage != null) {
                    refreshSafPath(moved);
                }
                openInEditor(reopened);
            }
            saveState();
            toast("Item movido.");
        } catch (IOException e) {
            toast(e.getMessage() == null ? activity.getString(R.string.cl_cannot_move) : e.getMessage());
            renderTree();
        }
    }

    private String relativePath(File rootDir, File file) {
        String rootPath = rootDir.getAbsolutePath();
        String filePath = file.getAbsolutePath();
        if (!isInside(file, rootDir) || sameFile(file, rootDir)) return "";
        return filePath.substring(rootPath.length() + 1);
    }

    private void refreshSafPath(File directory) throws IOException {
        if (activeSafStorage == null || directory == null
            || !activeSafStorage.contains(directory)) return;
        List<DirectSafStorage.Entry> children = activeSafStorage.list(directory);
        for (DirectSafStorage.Entry child : children) {
            if (child.directory) {
                refreshSafPath(child.file);
            }
        }
    }

    private void newFile(final File folder) {
        ui.askText(activity.getString(R.string.cl_add_file_in, folder.getName()),
            "", activity.getString(R.string.cl_create),
            text -> {
                String name = FileOps.withDefaultExtension(text);
                if (activeSafStorage != null && activeSafStorage.contains(folder)) {
                    return isSafeName(name) ? null : activity.getString(R.string.cl_invalid_file_name);
                }
                return FileOps.checkName(folder, name);
            },
            text -> {
                String name = FileOps.withDefaultExtension(text);
                File file;
                if (activeSafStorage != null && activeSafStorage.contains(folder)) {
                    try {
                        file = activeSafStorage.createFile(folder, name).file;
                    } catch (IOException e) {
                        toast(e.getMessage() == null ? activity.getString(R.string.cl_cannot_create_file) : e.getMessage());
                        return;
                    }
                } else {
                    file = FileOps.createFile(folder, name);
                }
                if (file == null) {
                    toast(activity.getString(R.string.cl_cannot_create_file));
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
        ui.askText(activity.getString(R.string.cl_add_folder_in, parent.getName()),
            "", activity.getString(R.string.cl_create),
            text -> {
                if (activeSafStorage != null && activeSafStorage.contains(parent)) {
                    return isSafeName(text) ? null : activity.getString(R.string.cl_invalid_folder_name);
                }
                return translatedNameError(FileOps.checkName(parent, text));
            },
            text -> {
                File folder;
                if (activeSafStorage != null && activeSafStorage.contains(parent)) {
                    try {
                        folder = activeSafStorage.createDirectory(parent, text).file;
                    } catch (IOException e) {
                        toast(e.getMessage() == null ? activity.getString(R.string.cl_cannot_create_folder) : e.getMessage());
                        return;
                    }
                } else {
                    folder = FileOps.createFolder(parent, text);
                }
                if (folder == null) {
                    toast(activity.getString(R.string.cl_cannot_create_folder));
                    return;
                }
                enterProjectOf(folder);
                expanded.add(parent.getAbsolutePath());
                expanded.add(folder.getAbsolutePath());
                lastFolder = folder;
                renderTree();
            });
    }

    private void enterProjectOf(File file) {
        File project = projectOf(file);
        if (project != null && (activeProject == null || !sameFile(project, activeProject))) {
            selectProject(project);
        }
    }

    /** FileOps e Java puro; apenas os textos mostrados sao traduzidos. */
    private String translatedNameError(String error) {
        if (error == null) return null;
        if (error.startsWith("Nome muito longo (maximo "))
            return activity.getString(R.string.cl_fileops_long);
        if (error.startsWith("O nome nao pode ter "))
            return activity.getString(R.string.cl_fileops_chars);
        if (error.equals("Digite um nome."))
            return activity.getString(R.string.cl_fileops_enter);
        if (error.equals("Esse nome nao e permitido."))
            return activity.getString(R.string.cl_fileops_disallowed);
        if (error.equals("O nome nao pode terminar com ponto ou espaco."))
            return activity.getString(R.string.cl_fileops_ending);
        if (error.equals("Nomes terminados em .tmp sao reservados."))
            return activity.getString(R.string.cl_fileops_reserved);
        if (error.equals("Ja existe um item com esse nome."))
            return activity.getString(R.string.cl_fileops_exists);
        return error;
    }

    private void rename(final File target) {
        final File parent = target.getParentFile();
        ui.askText(activity.getString(R.string.cl_rename), target.getName(), activity.getString(R.string.cl_rename),
            text -> text.equals(target.getName()) ? null
                : ((activeSafStorage != null && activeSafStorage.contains(target))
                    ? (isSafeName(text) ? null : activity.getString(R.string.cl_invalid_name))
                    : translatedNameError(FileOps.checkName(parent, text))),
            text -> {
                if (text.equals(target.getName())) return;
                if (activeSafStorage != null && activeSafStorage.contains(target)) {
                    doDirectRename(target, text);
                } else {
                    doRename(target, new File(parent, text));
                }
            });
    }

    private void doDirectRename(File target, String newName) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        File editedFile = editor.isOpen() ? editor.getFile() : null;
        if (editor.hasTabInside(target) && !editor.closeTabsInside(target)) return;
        if (isActiveProject) sendCommand("close", "");

        try {
            File dest = activeSafStorage.rename(target, newName).getAbsoluteFile();
            String oldPath = target.getAbsolutePath();
            String newPath = dest.getAbsolutePath();
            Set<String> next = new HashSet<String>();
            for (String path : expanded) {
                if (path.equals(oldPath)) next.add(newPath);
                else if (path.startsWith(oldPath + File.separator))
                    next.add(newPath + path.substring(oldPath.length()));
                else next.add(path);
            }
            expanded.clear();
            expanded.addAll(next);

            if (isActiveProject) {
                activeProject = dest;
                projectTitle.setText(activeSafStorage.getDisplayName());
                prepareRuntimeProject(false);
            }
            renderTree();
            if (editedFile != null && sameFile(editedFile, target)) openInEditor(dest);
            saveState();
        } catch (IOException e) {
            toast(e.getMessage() == null ? activity.getString(R.string.cl_cannot_rename) : e.getMessage());
        }
    }

    private void doRename(File target, File dest) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        File editedFile = editor.isOpen() ? editor.getFile() : null;
        if (editor.hasTabInside(target) && !editor.closeTabsInside(target)) return;
        if (isActiveProject) sendCommand("close", "");

        if (!target.renameTo(dest)) {
            toast(activity.getString(R.string.cl_cannot_rename));
            if (isActiveProject) sendCommand("open", target.getAbsolutePath());
            renderTree();
            return;
        }

        String oldPath = target.getAbsolutePath();
        String newPath = dest.getAbsolutePath();
        Set<String> next = new HashSet<String>();
        for (String path : expanded) {
            if (path.equals(oldPath)) next.add(newPath);
            else if (path.startsWith(oldPath + File.separator))
                next.add(newPath + path.substring(oldPath.length()));
            else next.add(path);
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
        if (editedFile != null && sameFile(editedFile, target)) openInEditor(dest);
        saveState();
    }

    private void delete(final File target) {
        String what = "\"" + target.getName() + "\"";
        boolean targetIsDirectory = activeSafStorage != null
            && activeSafStorage.contains(target)
            ? activeSafStorage.isDirectory(target)
            : target.isDirectory();
        String message = targetIsDirectory
            ? activity.getString(R.string.cl_confirm_delete_folder, what)
            : activity.getString(R.string.cl_confirm_delete_file, what);
        ui.confirm(message, activity.getString(R.string.cl_delete), () -> doDelete(target));
    }

    private void doDelete(File target) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        if (editor.hasTabInside(target) && !editor.closeTabsInside(target)) return;
        if (isActiveProject) sendCommand("close", "");

        boolean ok;
        if (activeSafStorage != null && activeSafStorage.contains(target)) {
            ok = activeSafStorage.delete(target);
        } else {
            ok = FileOps.deleteRecursive(target);
        }
        if (!ok) {
            toast(activity.getString(R.string.cl_cannot_delete));
            return;
        }

        expanded.remove(target.getAbsolutePath());
        lastFolder = null;

        if (isActiveProject) {
            deleteRuntimeProject();
            activeProject = null;
            activeSafStorage = null;
            playing = false;
            projectTitle.setText("CodeLove");
            placeholder.setVisibility(View.VISIBLE);
            updatePlayButton();
            editor.setStorage(null);
        } else if (activeProject != null && isInside(target, activeProject)) {
            playing = false;
            prepareRuntimeProject(false);
            updatePlayButton();
        }
        renderTree();
        saveState();
    }

    private boolean isSafeName(String name) {
        return name != null && !name.trim().isEmpty()
            && !name.equals(".") && !name.equals("..")
            && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    private void openInEditor(File file) {
        editor.setStorage(activeSafStorage != null && activeSafStorage.contains(file)
            ? activeSafStorage : null);
        if (editor.open(file)) {
            setDrawerOpen(false);
            if (saveIcon != null) saveIcon.setKind(IconView.SAVE);
            applyLayout();
            renderTree();
            saveState();
        } else if (!editor.hasUnsavedChanges()) {
            Toast.makeText(activity, activity.getString(R.string.cl_file_unopenable),
                Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean isInside(File file, File dir) {
        String f = file.getAbsolutePath();
        String d = dir.getAbsolutePath();
        return f.equals(d) || f.startsWith(d + File.separator);
    }

    private void onFileSaved(File file) {
        if (activeProject != null && isInside(file, activeProject)) {
            playing = false;
            if (activeSafStorage != null) {
                final DirectSafStorage storage = activeSafStorage;
                new Thread(() -> {
                    if (storage != activeSafStorage || disposed) return;
                    prepareRuntimeProject(false);
                }, "CodeLove-SAF-RuntimeRefresh").start();
            } else {
                sendCommand("open", activeProject.getAbsolutePath());
            }
            updatePlayButton();
        }
    }

    private void saveCurrentFile() {
        if (editor == null || !editor.isOpen() || !editor.saveNow()) return;
        if (saveIcon != null) saveIcon.setKind(IconView.CHECK);
    }

    private String projectDisplayName(File project) {
        if (activeSafStorage != null && activeSafStorage.contains(project)) {
            return activeSafStorage.getDisplayName();
        }
        return project == null ? "CodeLove" : project.getName();
    }

    private void selectDirectProject(DirectSafStorage storage) {
        selectProjectInternal(storage.getRoot(), storage);
    }

    private void selectProject(File project) {
        selectProjectInternal(project, null);
    }

    private void selectProjectInternal(File project, DirectSafStorage storage) {
        boolean changingProject = activeProject == null
            || !sameFile(activeProject, project)
            || activeSafStorage != storage;
        if (editor != null && editor.isOpen() && changingProject && !editor.closeAllTabs()) {
            return;
        }

        if (activeSafStorage != null && activeSafStorage != storage) {
            sendCommand("close", "");
        }

        activeSafStorage = storage;
        activeProject = project;
        lastFolder = null;
        expanded.add(project.getAbsolutePath());
        playing = false;

        editor.setStorage(storage);
        if (storage != null) {
            final DirectSafStorage selectedStorage = storage;
            new Thread(() -> {
                if (selectedStorage != activeSafStorage || disposed) return;
                prepareRuntimeProject(false);
            }, "CodeLove-SAF-RuntimeOpen").start();
        } else {
            deleteRuntimeProject();
            sendCommand("open", project.getAbsolutePath());
        }

        projectTitle.setText(projectDisplayName(project));
        placeholder.setVisibility(View.GONE);
        updatePlayButton();
        renderTree();
        saveState();
    }

    /** Nunca gravar arquivos indiretamente ao executar ou reiniciar o jogo. */
    private boolean editorChangesAreSaved(int messageId) {
        if (editor != null && editor.hasUnsavedTabs()) {
            toast(activity.getString(messageId));
            return false;
        }
        return true;
    }

    private void openStandalonePlayback(File project, String displayName) {
        if (project == null || !project.isDirectory()) return;
        if (editor != null && editor.hasUnsavedTabs()) {
            toast(activity.getString(R.string.cl_save_before_play));
            return;
        }
        Intent intent = new Intent(activity, GameActivity.class);
        intent.putExtra(EXTRA_RUNTIME_ONLY, true);
        intent.putExtra(EXTRA_RUNTIME_PROJECT, project.getAbsolutePath());
        intent.putExtra(EXTRA_RUNTIME_NAME, displayName);
        playing = true;
        updatePlayButton();
        activity.startActivity(intent);
    }

    private void launchStandalonePlayback() {
        if (activeProject == null) {
            toast(activity.getString(R.string.cl_select_project_hint));
            return;
        }
        if (!editorChangesAreSaved(R.string.cl_save_before_play)) return;
        if (activeSafStorage != null) {
            final DirectSafStorage storage = activeSafStorage;
            final File selected = activeProject;
            new Thread(() -> {
                if (!prepareRuntimeProject(true)) return;
                activity.runOnUiThread(() -> {
                    if (disposed || currentInstance != this
                        || storage != activeSafStorage || activeProject == null
                        || !sameFile(activeProject, selected)) return;
                    openStandalonePlayback(runtimeProject, storage.getDisplayName());
                });
            }, "CodeLove-SAF-StandalonePlay").start();
        } else {
            openStandalonePlayback(activeProject, projectDisplayName(activeProject));
        }
    }

    private void togglePlayback() {
        if (surface == null) {
            launchStandalonePlayback();
            return;
        }
        if (playing) {
            playing = false;
            sendCommand("pause", "");
            updatePlayButton();
            return;
        }
        if (activeProject == null) {
            toast(activity.getString(R.string.cl_select_project_hint));
            return;
        }
        if (!editorChangesAreSaved(R.string.cl_save_before_play)) return;

        if (activeSafStorage != null) {
            final DirectSafStorage storage = activeSafStorage;
            final File project = activeProject;
            new Thread(() -> {
                if (storage != activeSafStorage || activeProject == null
                    || !sameFile(activeProject, project) || disposed) return;
                if (!prepareRuntimeProject(true)) return;
                activity.runOnUiThread(() -> {
                    if (currentInstance != this || disposed
                        || storage != activeSafStorage
                        || activeProject == null || !sameFile(activeProject, project)) return;
                    playing = true;
                    sendCommand("play", "");
                    // No modo vertical, o Play leva da edicao para a visualizacao do jogo.
                    if (editor != null && editor.isOpen()) editor.close();
                    updatePlayButton();
                });
            }, "CodeLove-SAF-Play").start();
            return;
        }

        sendCommand("open", activeProject.getAbsolutePath());
        playing = true;
        sendCommand("play", "");
        // No modo vertical, o Play leva da edicao para a visualizacao do jogo.
        if (editor != null && editor.isOpen()) editor.close();
        updatePlayButton();
    }

    private void exitPlaybackToFiles() {
        if (!playing) return;
        playing = false;
        sendCommand("pause", "");
        updatePlayButton();
        // Recalcula o layout para restaurar a arvore de arquivos no vertical.
        applyLayout();
        renderTree();
    }

    private void resetScene() {
        if (activeProject == null) return;
        if (!editorChangesAreSaved(R.string.cl_save_before_restart)) return;

        if (activeSafStorage != null) {
            final DirectSafStorage storage = activeSafStorage;
            final File project = activeProject;
            new Thread(() -> {
                if (storage != activeSafStorage || activeProject == null
                    || !sameFile(activeProject, project) || disposed) return;
                if (!prepareRuntimeProject(true)) return;
                activity.runOnUiThread(() -> {
                    if (currentInstance != this || disposed
                        || storage != activeSafStorage
                        || activeProject == null || !sameFile(activeProject, project)) return;
                    playing = false;
                    updatePlayButton();
                });
            }, "CodeLove-SAF-Reset").start();
            return;
        }

        sendCommand("open", activeProject.getAbsolutePath());
        playing = false;
        updatePlayButton();
    }

    private boolean prepareRuntimeProject(boolean announceFailure) {
        if (activeSafStorage == null || activeProject == null) {
            return activeProject != null;
        }
        DirectSafStorage storage = activeSafStorage;
        File project = activeProject;
        File storageRoot = storage.getRoot();
        File storageKey = storageRoot == null ? null : storageRoot.getParentFile();
        String runtimeKey = storageKey == null ? "project" : storageKey.getName();
        File destination = new File(new File(activity.getCacheDir(), "codelove-runtime"),
            runtimeKey);
        try {
            storage.createRuntimeSnapshot(destination);
            if (runtimeProject != null && !sameFile(runtimeProject, destination)) {
                storage.deleteLocalSnapshot(runtimeProject);
            }
            runtimeProject = destination;
            String runtimeName = storage.getDisplayName()
                .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
            sendCommand("open", runtimeProject.getAbsolutePath() + "\t" + runtimeName);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Falha ao preparar o projeto para execucao", e);
            if (announceFailure) {
                activity.runOnUiThread(() -> toast(
                    e.getMessage() == null
                        ? "Nao foi possivel preparar o projeto para executar."
                        : e.getMessage()));
            }
            return false;
        }
    }

    private void deleteRuntimeProject() {
        if (runtimeProject != null) {
            DirectSafStorage storage = activeSafStorage;
            if (storage != null) {
                storage.deleteLocalSnapshot(runtimeProject);
            } else {
                FileOps.deleteRecursive(runtimeProject);
            }
            runtimeProject = null;
        }
        File fallback = new File(activity.getCacheDir(), "codelove-runtime");
        if (fallback.isDirectory()) {
            File[] children = fallback.listFiles();
            if (children != null) {
                for (File child : children) FileOps.deleteRecursive(child);
            }
        }
    }

    private void updatePlayButton() {
        int kind = playing ? IconView.PAUSE : IconView.PLAY;
        if (playIcon != null) playIcon.setKind(kind);
        if (headerPlayIcon != null) headerPlayIcon.setKind(kind);
        if (panelPlayIcon != null) panelPlayIcon.setKind(kind);
        // Play sempre legivel e clicavel; sem projeto, o clique orienta
        // o usuario em vez de ficar escuro e ignorar o toque.
        if (headerPlayButton != null) {
            headerPlayButton.setEnabled(true);
            headerPlayButton.setAlpha(1f);
        }
        if (panelPlayButton != null) {
            panelPlayButton.setEnabled(true);
            panelPlayButton.setAlpha(1f);
        }
        updateControlsVisibility();
    }

    private void updateControlsVisibility() {
        boolean editorVisible = editor != null && editor.isOpen() && !fullscreen;
        boolean verticalMode = root.getHeight() > root.getWidth();
        // No vertical, os controles do editor ficam no rodape do proprio editor.
        // Esconde a barra flutuante para nao cobrir o botao + da arvore de arquivos.
        // A barra flutuante com Play nao faz parte da EditorActivity. No modo
        // horizontal, a execucao permanece no Play integrado ao rodape do editor.
        boolean showBar = surface != null && activeProject != null
            && !editorVisible && !verticalMode;
        if (controlsBar != null) {
            controlsBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        }
        if (closePlaybackButton != null) {
            closePlaybackButton.setVisibility(
                playing && verticalMode ? View.VISIBLE : View.GONE);
        }
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

    /** Le a saida do runtime; utilizado pelo console da GameActivity. */
    /** Le so o final do arquivo (ate maxBytes), sem a primeira linha se foi cortada. */
    static String readTail(File file, int maxBytes) {
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

    private boolean disposed = false;

    /** Aviso que cobre a area do jogo enquanto nenhum projeto estiver aberto. */
    private LinearLayout buildPlaceholder() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(COLOR_OUTER);
        box.setClickable(true); // nao deixa o toque passar para o jogo escondido
        box.setVisibility(activeProject == null ? View.VISIBLE : View.GONE);

        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.cl_none_selected));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView hint = new TextView(activity);
        hint.setText(activity.getString(R.string.cl_select_project_hint));
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
        static final int SAVE = 13;
        static final int CHECK = 14;
        static final int ORIENTATION = 15;
        static final int CLOSE = 16;
        static final int RELOAD = 17;
        static final int SETTINGS = 18;
        static final int PALETTE = 19;
        static final int LANGUAGE = 20;

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
                case LANGUAGE:
                    path.addCircle(11f, 12f, 9f, Path.Direction.CW);
                    path.moveTo(2f, 12f);
                    path.lineTo(20f, 12f);
                    path.moveTo(11f, 3f);
                    path.cubicTo(4f, 9f, 4f, 15f, 11f, 21f);
                    path.moveTo(11f, 3f);
                    path.cubicTo(18f, 9f, 18f, 15f, 11f, 21f);
                    path.moveTo(4f, 8f);
                    path.lineTo(18f, 8f);
                    path.moveTo(4f, 16f);
                    path.lineTo(18f, 16f);
                    break;
                case PALETTE:
                    path.addOval(3f, 3f, 21f, 21f, Path.Direction.CW);
                    path.addCircle(8f, 10f, 1.0f, Path.Direction.CW);
                    path.addCircle(12f, 7f, 1.0f, Path.Direction.CW);
                    path.addCircle(16f, 10f, 1.0f, Path.Direction.CW);
                    path.moveTo(15f, 15f);
                    path.cubicTo(18f, 13f, 19f, 19f, 15f, 20f);
                    break;
                case SETTINGS:
                    // Engrenagem vetorial, alinhada aos demais icones do CodeLove.
                    for (int i = 0; i < 32; i++) {
                        double angle = (Math.PI * 2.0 * i) / 32.0 - Math.PI / 2.0;
                        float radius = (i % 4 == 0 || i % 4 == 3) ? 10f : 7.6f;
                        float x = 12f + radius * (float) Math.cos(angle);
                        float y = 12f + radius * (float) Math.sin(angle);
                        if (i == 0) path.moveTo(x, y);
                        else path.lineTo(x, y);
                    }
                    path.close();
                    path.addCircle(12f, 12f, 3.2f, Path.Direction.CW);
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
                case SAVE:
                    path.moveTo(5f, 3f);
                    path.lineTo(17f, 3f);
                    path.lineTo(21f, 7f);
                    path.lineTo(21f, 21f);
                    path.lineTo(3f, 21f);
                    path.lineTo(3f, 3f);
                    path.close();
                    path.moveTo(7f, 3f);
                    path.lineTo(7f, 10f);
                    path.lineTo(17f, 10f);
                    path.lineTo(17f, 3f);
                    path.moveTo(7f, 21f);
                    path.lineTo(7f, 14f);
                    path.lineTo(17f, 14f);
                    path.lineTo(17f, 21f);
                    break;
                case CLOSE:
                    path.moveTo(6f, 6f);
                    path.lineTo(18f, 18f);
                    path.moveTo(18f, 6f);
                    path.lineTo(6f, 18f);
                    break;
                case RELOAD:
                    path.addArc(4f, 4f, 20f, 20f, 45f, 285f);
                    path.moveTo(19f, 5f);
                    path.lineTo(19f, 11f);
                    path.lineTo(13f, 9f);
                    break;
                case CHECK:
                    path.moveTo(4f, 12f);
                    path.lineTo(9f, 17f);
                    path.lineTo(20f, 6f);
                    break;
                case ORIENTATION:
                    path.addRoundRect(4f, 5f, 20f, 19f, 2f, 2f, Path.Direction.CW);
                    path.moveTo(8f, 2.5f);
                    path.lineTo(5f, 5.5f);
                    path.lineTo(8f, 8.5f);
                    path.moveTo(5f, 5.5f);
                    path.lineTo(10f, 5.5f);
                    path.moveTo(16f, 21.5f);
                    path.lineTo(19f, 18.5f);
                    path.lineTo(16f, 15.5f);
                    path.moveTo(19f, 18.5f);
                    path.lineTo(14f, 18.5f);
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

    /** Ilustracao simples do formato de tela na escolha inicial de orientacao. */
    private final class OrientationPreviewView extends View {
        private final boolean portrait;
        private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        OrientationPreviewView(Context context, boolean portrait) {
            super(context);
            this.portrait = portrait;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = getWidth();
            float height = getHeight();
            float screenWidth = portrait ? width * 0.46f : width * 0.84f;
            float screenHeight = portrait ? height * 0.84f : height * 0.52f;
            float left = (width - screenWidth) / 2f;
            float top = (height - screenHeight) / 2f;
            float radius = dp(5);

            // Moldura e superficie representam a janela do editor CodeLove.
            previewPaint.setStyle(Paint.Style.FILL);
            previewPaint.setColor(COLOR_OUTER);
            canvas.drawRoundRect(left, top, left + screenWidth, top + screenHeight,
                radius, radius, previewPaint);

            previewPaint.setStyle(Paint.Style.STROKE);
            previewPaint.setStrokeWidth(Math.max(1f, dp(1)));
            previewPaint.setColor(COLOR_BORDER);
            canvas.drawRoundRect(left, top, left + screenWidth, top + screenHeight,
                radius, radius, previewPaint);

            previewPaint.setStyle(Paint.Style.FILL);
            previewPaint.setColor(COLOR_FIELD);
            float headerHeight = Math.max(dp(5), screenHeight * 0.16f);
            canvas.drawRoundRect(left + dp(1), top + dp(1),
                left + screenWidth - dp(1), top + headerHeight, radius, radius, previewPaint);

            float innerTop = top + headerHeight + dp(3);
            float innerBottom = top + screenHeight - dp(4);
            if (!portrait) {
                float sidebarRight = left + screenWidth * 0.29f;
                previewPaint.setColor(COLOR_PANEL);
                canvas.drawRect(left + dp(2), innerTop, sidebarRight, innerBottom, previewPaint);
                previewPaint.setColor(COLOR_LUA);
                canvas.drawRect(left + dp(5), innerTop + dp(4),
                    left + dp(8), innerTop + dp(7), previewPaint);
                drawCodeLine(canvas, left + dp(12), innerTop + dp(5),
                    sidebarRight - dp(4), COLOR_MUTED);
                drawCodeLine(canvas, sidebarRight + dp(5), innerTop + dp(5),
                    left + screenWidth - dp(6), COLOR_FOLDER);
                drawCodeLine(canvas, sidebarRight + dp(5), innerTop + dp(11),
                    left + screenWidth - dp(12), COLOR_MUTED);
                drawCodeLine(canvas, sidebarRight + dp(5), innerTop + dp(17),
                    left + screenWidth - dp(18), COLOR_MUTED);
            } else {
                drawCodeLine(canvas, left + dp(6), innerTop + dp(5),
                    left + screenWidth - dp(6), COLOR_FOLDER);
                drawCodeLine(canvas, left + dp(6), innerTop + dp(11),
                    left + screenWidth - dp(12), COLOR_MUTED);
                drawCodeLine(canvas, left + dp(6), innerTop + dp(17),
                    left + screenWidth - dp(9), COLOR_MUTED);
                drawCodeLine(canvas, left + dp(6), innerTop + dp(23),
                    left + screenWidth - dp(18), COLOR_MUTED);
            }
        }

        private void drawCodeLine(Canvas canvas, float start, float y, float end, int color) {
            if (end <= start) return;
            previewPaint.setColor(color);
            previewPaint.setStrokeWidth(Math.max(1f, dp(2)));
            previewPaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(start, y, end, y, previewPaint);
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