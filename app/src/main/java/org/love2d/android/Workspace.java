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
import android.app.AlertDialog;
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
    private boolean synchronizing = false;
    private final Set<String> pendingExternalFiles = new HashSet<String>();
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
        }
        Workspace workspace = new Workspace(activity, (RelativeLayout) layout, surface, games);
        currentInstance = workspace;
        workspace.build();
        workspace.importPreviouslyRegisteredProjects();
    }

    private void importPreviouslyRegisteredProjects() {
        List<SafProjectManager.RegisteredProject> pending =
            SafProjectManager.getProjectsNeedingImport(activity);
        if (pending.isEmpty()) return;

        new Thread(() -> {
            for (SafProjectManager.RegisteredProject project : pending) {
                SafProjectManager.ProjectCopy copy = SafProjectManager.ensureWorkingCopy(
                    activity, project.treeUri, project.documentId, project.displayName, gamesDir);
                activity.runOnUiThread(() -> {
                    if (currentInstance != this || disposed || activity.isFinishing()) return;
                    if (copy.isSuccess()) {
                        expanded.add(copy.directory.getAbsolutePath());
                        renderTree();
                    } else {
                        showProjectNotice(copy.error);
                    }
                });
            }
        }, "CodeLove-SAF-Restore").start();
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

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
