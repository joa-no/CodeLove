package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
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
    static final String EXTRA_ORIENTATION = "org.love2d.android.RESTART_ORIENTATION";
    static final String EXTRA_READY = "org.love2d.android.RESTART_READY";
    static final int STOP_RUNTIME = IBinder.FIRST_CALL_TRANSACTION;
    static final int FINISH_RUNTIME = IBinder.FIRST_CALL_TRANSACTION + 1;
    private static final int RUNTIME_READY = IBinder.FIRST_CALL_TRANSACTION + 2;

    static boolean isHandoffOrientation(int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            || orientation == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            || orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
    }

    static void notifyRuntimeReady(Intent intent) {
        Bundle extras = intent.getExtras();
        IBinder ready = extras == null ? null : extras.getBinder(EXTRA_READY);
        if (ready == null) return;
        Parcel data = Parcel.obtain();
        try {
            ready.transact(RUNTIME_READY, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException ignored) {
            // The coordinator may already have been removed by Android.
        } finally {
            data.recycle();
        }
        intent.removeExtra(EXTRA_READY);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private IBinder token;
    private boolean launched;
    private int orientation;
    private final Runnable finishHandoff = () -> {
        if (!isFinishing() && !isDestroyed()) {
            finish();
            overridePendingTransition(0, 0);
        }
    };
    private final IBinder readyToken = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == RUNTIME_READY) {
                handler.post(finishHandoff);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };
    private final IBinder.DeathRecipient death = () -> handler.post(this::launchAfterDeath);
    private final Runnable stop = this::requestStop;
    private final Runnable timeout = this::fail;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(CodeLoveLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        orientation = getIntent().getIntExtra(EXTRA_ORIENTATION,
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (isHandoffOrientation(orientation)) setRequestedOrientation(orientation);
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        Bundle extras = getIntent().getExtras();
        token = extras == null ? null : extras.getBinder(EXTRA_TOKEN);
        String archive = getIntent().getStringExtra(LoveArchivePlayback.EXTRA_ARCHIVE);
        String project = getIntent().getStringExtra(Workspace.EXTRA_RUNTIME_PROJECT);
        if (token == null || !isHandoffOrientation(orientation)
                || (archive == null) == (project == null)) {
            fail();
            return;
        }

        try {
            token.linkToDeath(death, 0);
        } catch (RemoteException alreadyDead) {
            launchAfterDeath();
            return;
        }
        // Preserve the game's axis before the old Activity leaves the task.
        // Ask it to finish normally first; force termination only if necessary.
        Parcel data = Parcel.obtain();
        try {
            token.transact(FINISH_RUNTIME, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException alreadyDead) {
            if (!token.isBinderAlive()) launchAfterDeath();
        } finally {
            data.recycle();
        }
        if (launched) return;
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
        next.putExtra(EXTRA_ORIENTATION, orientation);
        Bundle handoff = new Bundle();
        handoff.putBinder(EXTRA_READY, readyToken);
        next.putExtras(handoff);
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
            // Do not remove the orientation anchor until the new runtime
            // acknowledges creation. A fallback avoids a stranded Activity.
            handler.postDelayed(finishHandoff, 10000L);
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
