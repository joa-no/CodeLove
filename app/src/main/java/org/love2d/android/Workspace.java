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
import android.app.Dialog;
import android.content.Context;
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
    private static final String PREF_WORKSPACE_ORIENTATION = "workspace_orientation";
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

    private File activeProject = null; // identificador virtual do projeto aberto
    private DirectSafStorage activeSafStorage = null; // fonte direta SAF, quando aplicavel
    private File runtimeProject = null; // snapshot temporario usado somente pelo LOVE
    private boolean playing = false;
    private String lastStatus = "";

    private OverlayUi ui;
    private SharedPreferences prefs;
    private LinearLayout consolePanel;
    private LinearLayout mobileBar;
    private boolean mobileFilesOpen = false;
    private TextView consoleText;
    private ScrollView consoleScroll;
    private LinearLayout consoleButton;
    private boolean consoleOpen = false;
    private String lastConsole = "";
    private File lastFolder = null; // ultima pasta tocada no projeto aberto
    private CodeEditor editor;
    private View editorView;
    private LinearLayout panel;
    private View movePanelButton;
    private LinearLayout placeholder;
    private LinearLayout playButton;
    private TextView resetButton;
    private IconView playIcon;
    private IconView footerPlayIcon;
    private LinearLayout controlsBar;
    private IconView fullscreenIcon;
    private TextView projectTitle;
    private TextView statusView;
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
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
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
        renderTree();
        final DirectSafStorage selectedStorage = storage;
        new Thread(() -> {
            if (selectedStorage == activeSafStorage && !disposed) {
                prepareRuntimeProject(false);
            }
        }, "CodeLove-SAF-RuntimeRestore").start();
    }

    /** Sincroniza o polling da interface com o ciclo de vida da Activity SDL. */
    public static void onHostResume(Activity activity) {
        Workspace workspace = currentInstance;
        if (workspace != null && workspace.activity == activity && !workspace.disposed) {
            workspace.hostResumed = true;
            workspace.renderTree();
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
        movePanelButton = footerGlyphButton("\u21C4", v -> {
            onLeft = !onLeft;
            applyLayout();
            saveState();
        });
        setButtonHint(movePanelButton, "Mover painel para o outro lado");
        footer.addView(movePanelButton, footerButtonParams(false));
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

        saveIcon = new IconView(activity, IconView.SAVE, COLOR_TEXT);
        LinearLayout saveButton = new LinearLayout(activity);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setBackground(box(COLOR_FIELD, 22, false));
        saveButton.addView(saveIcon, new LinearLayout.LayoutParams(dp(20), dp(20)));
        setButtonHint(saveButton, "Salvar alterações no CodeLove");
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
                applyLayout();
                renderTree();
                saveState();
            }
        });
        editor.addFooterControl(saveButton, CONTROL_BUTTON_DP, 0);
        editor.addFooterControl(footerPlayButton, CONTROL_BUTTON_DP, BAR_GAP_DP);
        editor.addFooterControl(footerResetButton, CONTROL_BUTTON_DP, BAR_GAP_DP);
        editor.addFooterControl(consoleButton, CONTROL_BUTTON_DP, BAR_GAP_DP);
        editorView = editor.getView();
        editorView.setVisibility(View.GONE);

        root.addView(placeholder);
        root.addView(editorView);
        root.addView(panel);
        root.addView(consolePanel);
        root.addView(controlsBar);
        mobileBar = buildMobileBar();
        root.addView(mobileBar);

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

        LinearLayout orientation = new LinearLayout(activity);
        orientation.setGravity(Gravity.CENTER);
        orientation.setBackground(box(COLOR_FIELD, 10, false));
        orientation.addView(new IconView(activity, IconView.ORIENTATION, COLOR_TEXT),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        setButtonHint(orientation, "Alterar orientação da tela");
        orientation.setOnClickListener(v -> showOrientationChooser(true));
        LinearLayout.LayoutParams orientationLp = new LinearLayout.LayoutParams(dp(32), dp(32));
        orientationLp.setMargins(0, 0, dp(6), 0);
        header.addView(orientation, orientationLp);

        LinearLayout info = new LinearLayout(activity);
        info.setGravity(Gravity.CENTER);
        info.setBackground(box(COLOR_FIELD, 10, false));
        info.addView(new IconView(activity, IconView.INFO, COLOR_MUTED),
            new LinearLayout.LayoutParams(dp(18), dp(18)));
        info.setOnClickListener(v -> showAbout());
        header.addView(info, new LinearLayout.LayoutParams(dp(32), dp(32)));

        return header;
    }

    private LinearLayout buildMobileBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setBackground(box(COLOR_PANEL, 14, true));

        TextView brand = new TextView(activity);
        brand.setText("CodeLove");
        brand.setTextColor(COLOR_TEXT);
        brand.setTextSize(15);
        brand.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(brand, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        bar.addView(mobileActionButton("Arquivos", v -> {
            mobileFilesOpen = !mobileFilesOpen;
            applyLayout();
        }));
        LinearLayout.LayoutParams consoleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        consoleParams.setMargins(dp(6), 0, 0, 0);
        bar.addView(mobileActionButton("Console", v -> {
            if (editor == null || !editor.isOpen()) {
                toast("Abra um arquivo para usar o console.");
                return;
            }
            consoleOpen = !consoleOpen;
            if (consoleOpen) updateConsole();
            applyLayout();
        }), consoleParams);
        LinearLayout.LayoutParams orientationParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        orientationParams.setMargins(dp(6), 0, 0, 0);
        bar.addView(mobileActionButton("Tela", v -> showOrientationChooser(true)),
            orientationParams);
        return bar;
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
        title.setText("Orientação da tela");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(5), 0, dp(5));
        content.addView(title, titleParams);

        TextView explanation = new TextView(activity);
        explanation.setText("Escolha o formato mais adequado ao tamanho da sua tela.");
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
            cancel.setText("Cancelar");
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
        title.setText(portrait ? "Vertical" : "Horizontal");
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        labels.addView(title);

        TextView description = new TextView(activity);
        description.setText(portrait
            ? "Esse formato é ideal para celulares. Não recomendado para telas grandes, como tablets."
            : "Esse formato é ideal para telas maiores, como tablets. Não recomendado para telas pequenas, como celulares.");
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
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(box(COLOR_FIELD, 10, false));
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
        boolean verticalMode = root.getHeight() > root.getWidth();
        boolean keyboardVisible = insetBottomIme > insetBottom + dp(80);
        if (verticalMode && keyboardVisible) {
            // O editor tem prioridade: o console nunca disputa espaco com o teclado.
            consoleOpen = false;
        }

        boolean panelShown = !fullscreen && (!verticalMode || mobileFilesOpen);
        // Inverter o lado só faz sentido no layout horizontal; no vertical,
        // o painel funciona como uma gaveta sobreposta e não tem lado persistente.
        if (movePanelButton != null) {
            movePanelButton.setVisibility(verticalMode ? View.GONE : View.VISIBLE);
        }
        boolean editorOpen = editor != null && editor.isOpen() && !fullscreen;
        int sideInset = onLeft ? insetLeft : insetRight;
        int mobileBarHeight = verticalMode && !fullscreen ? dp(48) : 0;
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
        if (editorOpen) {
            if (verticalMode) {
                editorOffset = margin;
                editorWidth = baseW > 0f
                    ? Math.max(0, (int) baseW - 2 * margin)
                    : ViewGroup.LayoutParams.MATCH_PARENT;
            } else {
                editorOffset = panelShown ? cursor : cursor + margin;
                if (baseW > 0f) {
                    editorWidth = Math.max(0,
                        (int) baseW - (editorOffset - sideInset) - margin);
                }
            }
        }

        int editorVisibleHeight = Math.max(0, root.getHeight()
            - insetTop - mobileBarHeight - insetBottomIme - 2 * margin);
        int consoleFooterSpace = dp(71);
        int consoleAvailableHeight = Math.max(0, editorVisibleHeight - consoleFooterSpace);
        int consoleHeight;
        if (verticalMode) {
            consoleHeight = Math.min(dp(240),
                Math.min((int) (editorVisibleHeight * 0.38f), consoleAvailableHeight));
        } else {
            consoleHeight = Math.min(dp(220),
                Math.min((int) (editorVisibleHeight * 0.38f), consoleAvailableHeight));
        }

        // O runtime continua com a mesma resolucao logica. No modo vertical,
        // os paineis sao sobrepostos e nao reduzem a area do jogo.
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

        RelativeLayout.LayoutParams placeholderLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        placeholderLp.topMargin = insetTop + mobileBarHeight;
        placeholderLp.bottomMargin = insetBottom;
        placeholderLp.leftMargin = insetLeft + (!verticalMode && onLeft ? reserved : 0);
        placeholderLp.rightMargin = insetRight + (!verticalMode && !onLeft ? reserved : 0);
        placeholder.setLayoutParams(placeholderLp);

        int panelTop = insetTop + mobileBarHeight + margin;
        if (verticalMode) {
            RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
                Math.max(0, (int) baseW - 2 * margin), ViewGroup.LayoutParams.MATCH_PARENT);
            panelLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            panelLp.leftMargin = insetLeft + margin;
            panelLp.rightMargin = insetRight + margin;
            panelLp.topMargin = panelTop;
            panelLp.bottomMargin = insetBottomIme + margin;
            panel.setLayoutParams(panelLp);
        } else {
            RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
                effectivePanelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
            panelLp.addRule(onLeft
                ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
            panelLp.topMargin = insetTop + margin;
            panelLp.bottomMargin = insetBottomIme + margin;
            if (onLeft) {
                panelLp.leftMargin = panelOffset;
            } else {
                panelLp.rightMargin = panelOffset;
            }
            panel.setLayoutParams(panelLp);
        }
        panel.setVisibility(panelShown ? View.VISIBLE : View.GONE);

        RelativeLayout.LayoutParams editorLp = new RelativeLayout.LayoutParams(
            editorWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        editorLp.addRule(verticalMode || onLeft
            ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        editorLp.topMargin = insetTop + mobileBarHeight + margin;
        editorLp.bottomMargin = insetBottomIme + margin;
        if (verticalMode || onLeft) {
            editorLp.leftMargin = verticalMode ? insetLeft + editorOffset : editorOffset;
        } else {
            editorLp.rightMargin = editorOffset;
        }
        editorView.setLayoutParams(editorLp);
        editorView.setVisibility(editorOpen ? View.VISIBLE : View.GONE);

        // Barra superior exclusiva do modo vertical.
        RelativeLayout.LayoutParams mobileBarLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        mobileBarLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        mobileBarLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
        mobileBarLp.leftMargin = insetLeft + margin;
        mobileBarLp.rightMargin = insetRight + margin;
        mobileBarLp.topMargin = insetTop + margin;
        mobileBar.setLayoutParams(mobileBarLp);
        mobileBar.setVisibility(verticalMode && !fullscreen ? View.VISIBLE : View.GONE);

        int barHeight = dp(CONTROL_BUTTON_DP) + 2 * dp(BAR_PAD_DP);
        int buttonCount = 3;
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

        RelativeLayout.LayoutParams consoleLp;
        if (verticalMode) {
            consoleLp = new RelativeLayout.LayoutParams(
                Math.max(0, (int) baseW - 2 * margin), consoleHeight);
            consoleLp.addRule(RelativeLayout.ALIGN_PARENT_LEFT);
            consoleLp.leftMargin = insetLeft + margin;
            consoleLp.rightMargin = insetRight + margin;
            consoleLp.bottomMargin = insetBottomIme + margin + consoleFooterSpace;
        } else {
            consoleLp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, consoleHeight);
            consoleLp.addRule(onLeft
                ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
            int consoleInset = dp(12);
            if (onLeft) {
                consoleLp.leftMargin = editorOffset + consoleInset;
                consoleLp.rightMargin = insetRight + margin + consoleInset;
            } else {
                consoleLp.rightMargin = editorOffset + consoleInset;
                consoleLp.leftMargin = insetLeft + margin + consoleInset;
            }
            consoleLp.bottomMargin = insetBottomIme + margin + consoleFooterSpace;
        }
        consolePanel.setLayoutParams(consoleLp);
        consolePanel.setVisibility((consoleOpen && editorOpen
            && consoleHeight > 0 && !(verticalMode && keyboardVisible))
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

        File treeRoot = activeSafStorage != null
            ? activeProject
            : ((gamesDir != null && gamesDir.isDirectory()) ? gamesDir : activeProject);

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
            note.setText(query.isEmpty() ? "Nenhum arquivo ainda." : "Nada encontrado.");
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
                showProjectNotice("Nao consegui listar essa pasta. O acesso do Android pode ter sido alterado ou removido.");
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
            if (root.getHeight() > root.getWidth()) {
                mobileFilesOpen = false;
                applyLayout();
            }
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

        TextView retry = new TextView(activity);
        retry.setText("Selecionar novamente");
        retry.setTextColor(0xFF1B1F2B);
        retry.setTextSize(13);
        retry.setGravity(Gravity.CENTER);
        retry.setBackground(box(0xFFA78BFA, 10, false));
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
        labels.add("Novo arquivo em " + folder.getName());
        actions.add(() -> newFile(folder));
        labels.add("Nova pasta em " + folder.getName());
        actions.add(() -> newFolder(folder));
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
            toast("Nao foi possivel abrir o seletor de pastas do Android.");
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
            workspace.toast("Pasta selecionada com sucesso.");
        } catch (RuntimeException e) {
            Log.e(TAG, "Falha inesperada ao processar a pasta selecionada pelo SAF", e);
            workspace.showProjectNotice(
                "O Android encontrou um erro inesperado ao processar essa pasta. Tente novamente.");
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
            labels.add("Novo arquivo aqui");
            actions.add(() -> newFile(file));
            labels.add("Nova pasta aqui");
            actions.add(() -> newFolder(file));
        }
        // A raiz do projeto aberto nao pode ser renomeada/apagada pelo menu.
        if (!externalProjectRoot) {
            labels.add("Renomear");
            actions.add(() -> rename(file));
            labels.add("Excluir");
            actions.add(() -> delete(file));
        }
        ui.showMenu(anchor, labels, actions);
    }

    private void newFile(final File folder) {
        ui.askText("Novo arquivo em " + folder.getName(), "", "Criar",
            text -> {
                String name = FileOps.withDefaultExtension(text);
                if (activeSafStorage != null && activeSafStorage.contains(folder)) {
                    return isSafeName(name) ? null : "Nome de arquivo invalido.";
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
                        toast(e.getMessage() == null ? "Nao consegui criar o arquivo." : e.getMessage());
                        return;
                    }
                } else {
                    file = FileOps.createFile(folder, name);
                }
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
            text -> {
                if (activeSafStorage != null && activeSafStorage.contains(parent)) {
                    return isSafeName(text) ? null : "Nome de pasta invalido.";
                }
                return FileOps.checkName(parent, text);
            },
            text -> {
                File folder;
                if (activeSafStorage != null && activeSafStorage.contains(parent)) {
                    try {
                        folder = activeSafStorage.createDirectory(parent, text).file;
                    } catch (IOException e) {
                        toast(e.getMessage() == null ? "Nao consegui criar a pasta." : e.getMessage());
                        return;
                    }
                } else {
                    folder = FileOps.createFolder(parent, text);
                }
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

    private void enterProjectOf(File file) {
        File project = projectOf(file);
        if (project != null && (activeProject == null || !sameFile(project, activeProject))) {
            selectProject(project);
        }
    }

    private void rename(final File target) {
        final File parent = target.getParentFile();
        ui.askText("Renomear", target.getName(), "Renomear",
            text -> text.equals(target.getName()) ? null
                : ((activeSafStorage != null && activeSafStorage.contains(target))
                    ? (isSafeName(text) ? null : "Nome invalido.")
                    : FileOps.checkName(parent, text)),
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
        if (editedFile != null && isInside(editedFile, target) && !editor.close()) return;
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
            toast(e.getMessage() == null ? "Nao consegui renomear." : e.getMessage());
        }
    }

    private void doRename(File target, File dest) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        File editedFile = editor.isOpen() ? editor.getFile() : null;
        if (editedFile != null && isInside(editedFile, target) && !editor.close()) return;
        if (isActiveProject) sendCommand("close", "");

        if (!target.renameTo(dest)) {
            toast("Nao consegui renomear.");
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
            ? "Excluir a pasta " + what + " e tudo que esta dentro dela? Isso nao pode ser desfeito."
            : "Excluir o arquivo " + what + "? Isso nao pode ser desfeito.";
        ui.confirm(message, "Excluir", () -> doDelete(target));
    }

    private void doDelete(File target) {
        boolean isActiveProject = activeProject != null && sameFile(target, activeProject);
        if (editor.isOpen() && isInside(editor.getFile(), target) && !editor.close()) return;
        if (isActiveProject) sendCommand("close", "");

        boolean ok;
        if (activeSafStorage != null && activeSafStorage.contains(target)) {
            ok = activeSafStorage.delete(target);
        } else {
            ok = FileOps.deleteRecursive(target);
        }
        if (!ok) {
            toast("Nao consegui apagar tudo.");
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
            if (saveIcon != null) saveIcon.setKind(IconView.SAVE);
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
        if (editor != null && editor.isOpen() && changingProject && !editor.close()) {
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

    private void togglePlayback() {
        if (playing) {
            playing = false;
            sendCommand("pause", "");
            updatePlayButton();
            return;
        }
        if (activeProject == null) return;
        if (editor != null && !editor.saveNow()) return;

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
                    updatePlayButton();
                });
            }, "CodeLove-SAF-Play").start();
            return;
        }

        sendCommand("open", activeProject.getAbsolutePath());
        playing = true;
        sendCommand("play", "");
        updatePlayButton();
    }

    private void resetScene() {
        if (activeProject == null || !editor.saveNow()) return;

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
        if (footerPlayIcon != null) footerPlayIcon.setKind(kind);
        updateControlsVisibility();
    }

    private void updateControlsVisibility() {
        boolean editorVisible = editor != null && editor.isOpen() && !fullscreen;
        boolean showBar = activeProject != null && !editorVisible;
        if (controlsBar != null) {
            controlsBar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        }
        if (consolePanel != null) {
            consolePanel.setVisibility(
                (consoleOpen && editorVisible) ? View.VISIBLE : View.GONE);
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
        static final int SAVE = 13;
        static final int CHECK = 14;
        static final int ORIENTATION = 15;

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