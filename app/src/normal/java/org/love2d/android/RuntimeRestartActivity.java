package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Restart handoff in the editor process. Never start a new SDL Activity until
 * the previous runtime process has died; its native shutdown may exit().
 */
public final class RuntimeRestartActivity extends Activity {
    static final String EXTRA_TOKEN = "org.love2d.android.RESTART_TOKEN";
    static final int STOP_RUNTIME = IBinder.FIRST_CALL_TRANSACTION;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IBinder token;
    private boolean launched;
    private final IBinder.DeathRecipient death = () -> handler.post(this::launchAfterDeath);
    private final Runnable stop = this::requestStop;
    private final Runnable timeout = this::fail;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(CodeLoveLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        Bundle extras = getIntent().getExtras();
        token = extras == null ? null : extras.getBinder(EXTRA_TOKEN);
        String archive = getIntent().getStringExtra(LoveArchivePlayback.EXTRA_ARCHIVE);
        String project = getIntent().getStringExtra(Workspace.EXTRA_RUNTIME_PROJECT);
        if (token == null || (archive == null) == (project == null)) {
            fail();
            return;
        }

        try {
            token.linkToDeath(death, 0);
        } catch (RemoteException alreadyDead) {
            launchAfterDeath();
            return;
        }
        // The old Activity finishes immediately after handoff. Prefer its
        // normal SDL shutdown; if it hangs or leaves the process alive,
        // request termination through the private binder in that process.
        handler.postDelayed(stop, 1500L);
        handler.postDelayed(timeout, 10000L);
    }

    private void requestStop() {
        if (launched || isFinishing() || isDestroyed()) return;
        if (!token.isBinderAlive()) {
            launchAfterDeath();
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            token.transact(STOP_RUNTIME, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException alreadyDead) {
            if (!token.isBinderAlive()) launchAfterDeath();
        } finally {
            data.recycle();
        }
    }

    private void launchAfterDeath() {
        if (launched || isFinishing() || isDestroyed()) return;
        if (token != null && token.isBinderAlive()) return;
        launched = true;
        handler.removeCallbacks(stop);
        handler.removeCallbacks(timeout);
        Intent next = new Intent(this, GameActivity.class);
        next.putExtra(Workspace.EXTRA_RUNTIME_ONLY, true);
        String archive = getIntent().getStringExtra(LoveArchivePlayback.EXTRA_ARCHIVE);
        if (archive != null) {
            next.putExtra(LoveArchivePlayback.EXTRA_ARCHIVE, archive);
        } else {
            next.putExtra(Workspace.EXTRA_RUNTIME_PROJECT,
                getIntent().getStringExtra(Workspace.EXTRA_RUNTIME_PROJECT));
        }
        try {
            startActivity(next);
            overridePendingTransition(0, 0);
            finish();
        } catch (RuntimeException e) {
            launched = false;
            fail();
        }
    }

    private void fail() {
        if (launched || isFinishing() || isDestroyed()) return;
        Toast.makeText(this, R.string.cl_restart_game_failed, Toast.LENGTH_LONG).show();
        Intent editor = new Intent(this, EditorActivity.class);
        editor.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(editor);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Do not expose the editor or another launch during the handoff.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (token != null) {
            try {
                token.unlinkToDeath(death, 0);
            } catch (RuntimeException ignored) {
                // Already dead or never linked.
            }
        }
        super.onDestroy();
    }
}
