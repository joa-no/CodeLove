/*
 * Copyright (c) 2006-2024 LOVE Development Team
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

package org.love2d.android;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Keep;
import androidx.core.app.ActivityCompat;

import org.libsdl.app.SDLActivity;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class GameActivity extends SDLActivity {
    private static final String TAG = "GameActivity";
    public static final int RECORD_AUDIO_REQUEST_CODE = 3;

    protected Vibrator vibrator;
    protected boolean shortEdgesMode;
    protected final int[] recordAudioRequestDummy = new int[1];
    private Uri delayedUri = null;
    private String[] args;
    private boolean isFused;
    private boolean standaloneRuntime;
    /** Exported games run LÖVE directly; CodeLove workspace is normal-flavor only. */
    private final boolean exportedGame = BuildConfig.FLAVOR.startsWith("embed");
    private CodeLoveConsolePanel standaloneConsole;
    private boolean standaloneConsoleOpen;
    private String lastStandaloneConsole;
    private final Runnable standaloneConsolePoller = new Runnable() {
        @Override public void run() {
            if (!standaloneRuntime || !standaloneConsoleOpen
                || standaloneConsole == null || isFinishing() || isDestroyed()) return;
            updateStandaloneConsole();
            standaloneConsole.postDelayed(this, 700L);
        }
    };

    private static native void nativeSetDefaultStreamValues(int sampleRate, int framesPerBurst);

    @Override
    protected String getMainSharedObject() {
        String[] libs = getLibraries();
        // Since Lollipop, you can simply pass "libname.so" to dlopen
        // and it will resolve correct paths and load correct library.
        // This is mandatory for extractNativeLibs=false support in
        // Marshmallow.
        return "lib" + libs[libs.length - 1] + ".so";
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{
            "c++_shared",
            "SDL3",
            "oboe",
            "openal",
            "luajit",
            "liblove",
            "love",
        };
    }

    @Override
    protected String[] getArguments() {
        return args;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d(TAG, "started");
        isFused = hasEmbeddedGame();
        args = new String[0];

        if (checkCallingOrSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED) {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }

        Intent intent = getIntent();
        standaloneRuntime = !exportedGame && intent.getBooleanExtra(Workspace.EXTRA_RUNTIME_ONLY, false);
        handleIntent(intent, true);
        // Prevent SDL sending filedropped event. Let us do that instead.
        intent.setData(null);

        // CodeLove: prepara o canal de comandos e diz ao executor (Lua) onde ele fica.
        // Precisa vir ANTES do super.onCreate, que inicia o LOVE.
        if (!exportedGame) {
            String channel = Workspace.prepareChannel(this);
            if (standaloneRuntime) {
                Workspace.queueStandaloneRuntime(this,
                    intent.getStringExtra(Workspace.EXTRA_RUNTIME_PROJECT),
                    intent.getStringExtra(Workspace.EXTRA_RUNTIME_NAME));
            }
            if (isFused) {
                args = new String[]{channel};
            }
        }

        super.onCreate(savedInstanceState);
        // Tema da UI do executor (botao de voltar), sem alterar o jogo SDL.
        if (!exportedGame) {
            Workspace.configureTheme(this);
            // O jogo exportado usa a orientacao do seu proprio conf.lua / SDL.
            Workspace.applySavedOrientation(this);
        }
        if (standaloneRuntime) hideStandaloneStatusBar();

        if (mBrokenLibraries) {
            return;
        }

        // Set low-latency audio values
        nativeSetDefaultStreamValues(getAudioFreq(), getAudioSMP());

        // Editor separado: o executor SDL fica livre de paineis.
        if (standaloneRuntime) showStandaloneExit();
        else if (!exportedGame) Workspace.attach(this, mLayout, mSurface);

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attr = getWindow().getAttributes();
            attr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            shortEdgesMode = false;
        }

        if (delayedUri != null) {
            // This delayed fd is only sent if an embedded game is present.
            sendUriAsDroppedFile(delayedUri);
            delayedUri = null;
        }
    }

    /**
     * O SDL3 chama setWindowStyle(false) na inicializacao e pode repetir
     * o comando ao configurar a janela do jogo. No CodeLove, a barra de
     * status deve permanecer oculta na Activity isolada de execucao.
     *
     * Impedir apenas a transicao para o modo com barras evita o ciclo
     * ocultar/mostrar, sem alterar o SDL usado nas outras variantes
     * ou os pedidos explicitos de fullscreen dos jogos.
     */
    @Override
    protected boolean sendCommand(int command, Object data) {
        if (standaloneRuntime
                && command == COMMAND_CHANGE_WINDOW_STYLE
                && data instanceof Integer
                && ((Integer) data).intValue() == 0) {
            return true;
        }
        return super.sendCommand(command, data);
    }

    /**
     * A GameActivity e executada em processo SDL proprio, pois o encerramento
     * nativo do LOVE pode finalizar o processo da Activity.
     * Reordena a Activity de desenvolvimento existente antes de encerrar.
     */
    private void returnToEditor() {
        if (!standaloneRuntime) return;
        // EditorActivity exists only in the normal flavor. Do not reference
        // its class symbol here: GameActivity must compile in embed flavors.
        Intent editorIntent = new Intent();
        editorIntent.setClassName(this, "org.love2d.android.EditorActivity");
        editorIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(editorIntent);
        finish();
    }

    /** Oculta a barra de status em modo de execucao; a navegacao permanece. */
    private void hideStandaloneStatusBar() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller =
                getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.statusBars());
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (standaloneRuntime && hasFocus) hideStandaloneStatusBar();
    }

    /** Controles da visualizacao: console ao lado do X, sem sair do jogo. */
    private void showStandaloneExit() {
        android.content.Context localized = CodeLoveLocale.wrap(this);
        float density = getResources().getDisplayMetrics().density;
        int size = Math.round(44 * density);
        int margin = Math.round(12 * density);
        int gap = Math.round(8 * density);

        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        actions.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.CENTER_VERTICAL);

        android.widget.LinearLayout consoleButton = CodeLoveConsolePanel.iconButton(this,
            Workspace.IconView.CONSOLE, Workspace.COLOR_FOLDER,
            () -> setStandaloneConsoleOpen(!standaloneConsoleOpen),
            localized.getString(R.string.cl_toggle_console), 44);
        actions.addView(consoleButton, new android.widget.LinearLayout.LayoutParams(size, size));

        android.widget.LinearLayout close = CodeLoveConsolePanel.iconButton(this,
            Workspace.IconView.CLOSE, Workspace.COLOR_TEXT,
            this::returnToEditor, localized.getString(R.string.cl_back_to_codelove), 44);
        android.widget.LinearLayout.LayoutParams closeLp =
            new android.widget.LinearLayout.LayoutParams(size, size);
        closeLp.leftMargin = gap;
        actions.addView(close, closeLp);

        android.widget.RelativeLayout.LayoutParams buttonsLp =
            new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, size);
        buttonsLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
        buttonsLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT);
        buttonsLp.topMargin = margin;
        buttonsLp.rightMargin = margin;
        mLayout.addView(actions, buttonsLp);

        standaloneConsole = new CodeLoveConsolePanel(this,
            () -> setStandaloneConsoleOpen(false), null, null);
        standaloneConsole.setVisibility(View.GONE);
        android.widget.RelativeLayout.LayoutParams consoleLp =
            new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                Math.round(220 * density));
        consoleLp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
        consoleLp.leftMargin = margin;
        consoleLp.rightMargin = margin;
        consoleLp.bottomMargin = margin;
        mLayout.addView(standaloneConsole, consoleLp);

        // Em celular horizontal o painel deve ser mais baixo; em tablet, ate 240dp.
        mLayout.addOnLayoutChangeListener((v, left, top, right, bottom,
                                            oldLeft, oldTop, oldRight, oldBottom) -> {
            int height = bottom - top;
            if (height <= 0 || standaloneConsole == null) return;
            int panelHeight = Math.min(Math.round(240 * density),
                Math.max(Math.round(90 * density), Math.round(height * 0.38f)));
            android.widget.RelativeLayout.LayoutParams lp =
                (android.widget.RelativeLayout.LayoutParams) standaloneConsole.getLayoutParams();
            if (lp.height != panelHeight) {
                lp.height = panelHeight;
                standaloneConsole.setLayoutParams(lp);
            }
        });
    }

    private void setStandaloneConsoleOpen(boolean visible) {
        if (!standaloneRuntime || standaloneConsole == null) return;
        standaloneConsoleOpen = visible;
        standaloneConsole.setVisibility(visible ? View.VISIBLE : View.GONE);
        standaloneConsole.removeCallbacks(standaloneConsolePoller);
        if (visible) {
            updateStandaloneConsole();
            standaloneConsole.postDelayed(standaloneConsolePoller, 700L);
        }
    }

    /** O editor e a tela visual leem exatamente o mesmo canal do executor. */
    private void updateStandaloneConsole() {
        if (standaloneConsole == null) return;
        String value = Workspace.readTail(
            new java.io.File(Workspace.channelDir(this), "console.txt"), 32768);
        if (value == null) {
            value = CodeLoveLocale.wrap(this).getString(R.string.cl_console_waiting);
        }
        if (!value.equals(lastStandaloneConsole)) {
            lastStandaloneConsole = value;
            standaloneConsole.setOutput(value);
        }
    }

    @Override
    public void onBackPressed() {
        if (standaloneRuntime) {
            returnToEditor();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (!exportedGame) Workspace.handleActivityResult(this, requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent, false);
    }

    @Override
    protected void onDestroy() {
        if (standaloneConsole != null) {
            standaloneConsole.removeCallbacks(standaloneConsolePoller);
        }
        if (!exportedGame) Workspace.onHostDestroy(this);
        if (vibrator != null) {
            Log.d(TAG, "Cancelling vibration");
            vibrator.cancel();
        }

        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (standaloneConsole != null) {
            standaloneConsole.removeCallbacks(standaloneConsolePoller);
        }
        if (!exportedGame) Workspace.onHostPause(this);
        if (vibrator != null) {
            Log.d(TAG, "Cancelling vibration");
            vibrator.cancel();
        }
        super.onPause();
    }

    /**
     * O SDL pode solicitar uma orientacao sempre que o runtime cria ou redimensiona
     * a janela. Depois que o usuario escolhe o formato do CodeLove, essa escolha
     * persistida tem prioridade sobre qualquer solicitacao automatica do jogo.
     */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        if (exportedGame) {
            super.setOrientationBis(w, h, resizable, hint);
            return;
        }
        SharedPreferences preferences = getSharedPreferences("codelove", MODE_PRIVATE);
        String saved = preferences.getString("workspace_orientation", "");

        int requested;
        if ("portrait".equals(saved)) {
            requested = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if ("landscape".equals(saved)) {
            requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else {
            // Na primeira abertura, trava a orientacao que o aparelho ja esta usando.
            // O SDL nao deve impor a orientacao padrao horizontal antes da escolha.
            int currentOrientation = getResources().getConfiguration().orientation;
            int initialOrientation = currentOrientation == Configuration.ORIENTATION_PORTRAIT
                ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            setRequestedOrientation(initialOrientation);
            return;
        }

        if (getRequestedOrientation() != requested) {
            setRequestedOrientation(requested);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reaplica a orientacao escolhida apos retornar do segundo plano.
        // O SDL/Android pode restaurar a orientacao do sistema durante a retomada.
        if (!exportedGame) {
            Workspace.applySavedOrientation(this);
            Workspace.onHostResume(this);
        }
        if (standaloneRuntime && standaloneConsoleOpen && standaloneConsole != null) {
            standaloneConsole.removeCallbacks(standaloneConsolePoller);
            updateStandaloneConsole();
            standaloneConsole.postDelayed(standaloneConsolePoller, 700L);
        }
        if (standaloneRuntime) {
            hideStandaloneStatusBar();
            getWindow().getDecorView().post(this::hideStandaloneStatusBar);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (grantResults.length > 0) {
            Log.d("GameActivity", "Received a request permission result");

            if (requestCode == RECORD_AUDIO_REQUEST_CODE) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d("GameActivity", "Mic permission granted");
                } else {
                    Log.d("GameActivity", "Did not get mic permission.");
                }

                Log.d("GameActivity", "Unlocking LÖVE thread");
                synchronized (recordAudioRequestDummy) {
                    recordAudioRequestDummy[0] = grantResults[0];
                    recordAudioRequestDummy.notify();
                }
            } else {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }
    }

    @Keep
    public boolean hasEmbeddedGame() {
        AssetManager am = getAssets();
        InputStream inputStream;

        try {
            // Prioritize main.lua in assets folder
            inputStream = am.open("main.lua");
        } catch (IOException e) {
            // Not found, try game.love in assets folder
            try {
                inputStream = am.open("game.love");
            } catch (IOException e2) {
                // Not found
                return false;
            }
        }

        try {
            inputStream.close();
        } catch (IOException ignored) {
        }

        return true;
    }

    @Keep
    public void vibrate(double seconds) {
        if (vibrator != null) {
            long duration = (long) (seconds * 1000.);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                VibrationEffect ve = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE);
                vibrator.vibrate(ve);
            } else {
                vibrator.vibrate(duration);
            }
        }
    }

    @Keep
    public boolean hasBackgroundMusic() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return audioManager.isMusicActive();
    }

    @Keep
    public String[] buildFileTree() {
        // Map key is path, value is directory flag
        HashMap<String, Boolean> map = buildFileTree(getAssets(), "", new HashMap<>());
        ArrayList<String> result = new ArrayList<>();

        for (Map.Entry<String, Boolean> data : map.entrySet()) {
            result.add((data.getValue() ? "d" : "f") + data.getKey());
        }

        String[] r = new String[result.size()];
        result.toArray(r);
        return r;
    }

    @Keep
    public float getDPIScale() {
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return metrics.density;
    }

    @Keep
    public Rect getSafeArea() {
        Rect rect = null;

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = getWindow().getDecorView().getRootWindowInsets().getDisplayCutout();

            if (cutout != null) {
                rect = new Rect();
                rect.set(
                    cutout.getSafeInsetLeft(),
                    cutout.getSafeInsetTop(),
                    cutout.getSafeInsetRight(),
                    cutout.getSafeInsetBottom()
                );
            }
        }

        return rect;
    }

    @Keep
    public String getCRequirePath() {
        ApplicationInfo applicationInfo = getApplicationInfo();

        if (isNativeLibsExtracted()) {
            return applicationInfo.nativeLibraryDir + "/?.so";
        } else {
            // The native libs are inside the APK and can be loaded directly.
            // FIXME: What about split APKs?
            String abi = android.os.Build.SUPPORTED_ABIS[0];
            return applicationInfo.sourceDir + "!/lib/" + abi + "/?.so";
        }
    }

    @Keep
    public void setImmersiveMode(boolean enable) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attr = getWindow().getAttributes();

            if (enable) {
                attr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            } else {
                attr.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            }
        }

        shortEdgesMode = enable;
    }

    @Keep
    public boolean getImmersiveMode() {
        return shortEdgesMode;
    }

    @Keep
    public boolean hasRecordAudioPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Keep
    public void requestRecordAudioPermission() {
        if (ActivityCompat.checkSelfPermission(this,
            Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        Log.d("GameActivity", "Requesting mic permission and locking LÖVE thread until we have an answer.");
        ActivityCompat.requestPermissions(this,
            new String[]{Manifest.permission.RECORD_AUDIO},
            RECORD_AUDIO_REQUEST_CODE);

        synchronized (recordAudioRequestDummy) {
            try {
                recordAudioRequestDummy.wait();
            } catch (InterruptedException e) {
                Log.d("GameActivity", "requesting mic permission", e);
            }
        }
    }

    public int getAudioSMP() {
        int smp = 256;
        AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (a != null) {
            int b = Integer.parseInt(a.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER));
            smp = b > 0 ? b : smp;
        }

        return smp;
    }

    public int getAudioFreq() {
        int freq = 44100;
        AudioManager a = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        if (a != null) {
            int b = Integer.parseInt(a.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE));
            freq = b > 0 ? b : freq;
        }

        return freq;
    }

    public boolean isNativeLibsExtracted() {
        ApplicationInfo appInfo = getApplicationInfo();
        return (appInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) != 0;
    }

    public void sendUriAsDroppedFile(Uri uri) {
        SDLActivity.onNativeDropFile(uri.toString());
    }

    private void handleIntent(Intent intent, boolean onCreate) {
        Uri game = intent.getData();
        if (game == null) {
            return;
        }

        if (onCreate) {
            // Game is not running
            if (isFused) {
                // Send it as dropped file later
                delayedUri = game;
            } else {
                // Process for arguments
                processOpenGame(game);
            }
        } else {
            // Game is already running. Send it as dropped file.
            sendUriAsDroppedFile(game);
        }
    }

    private HashMap<String, Boolean> buildFileTree(AssetManager assetManager, String dir, HashMap<String, Boolean> map) {
        String strippedDir = dir.endsWith("/") ? dir.substring(0, dir.length() - 1) : dir;

        // Try open dir
        try {
            InputStream test = assetManager.open(strippedDir);
            // It's a file
            test.close();
            map.put(strippedDir, false);
        } catch (FileNotFoundException e) {
            // It's a directory
            String[] list = null;

            // List files
            try {
                list = assetManager.list(strippedDir);
            } catch (IOException e2) {
                Log.e(TAG, strippedDir, e2);
            }

            // Mark as file
            map.put(dir, true);

            if (!strippedDir.equals(dir)) {
                map.put(strippedDir, true);
            }

            if (list != null) {
                for (String path : list) {
                    buildFileTree(assetManager, dir + path + "/", map);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, dir, e);
        }

        return map;
    }

    private void processOpenGame(Uri game) {
        String scheme = game.getScheme();
        String path = game.getPath();

        if (scheme != null) {
            if (scheme.equals("content")) {
                // Pass content URI as-is.
                args = new String[]{game.toString()};
            } else if (scheme.equals("file")) {
                // Regular file, pass as-is.
                args = new String[]{path};
            }
        }
    }
}
