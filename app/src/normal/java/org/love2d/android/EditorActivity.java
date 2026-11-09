package org.love2d.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.RelativeLayout;

/** Editor CodeLove independente da superficie SDL. */
public final class EditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Workspace.applySavedOrientation(this);
        hideStatusBar();
        RelativeLayout root = new RelativeLayout(this);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        Workspace.attachEditor(this, root);
        root.postOnAnimation(this::hideStatusBar);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Workspace.applySavedOrientation(this);
        Workspace.onHostResume(this);
        hideStatusBar();
        getWindow().getDecorView().postOnAnimation(this::hideStatusBar);
    }

    // Mantem somente a barra de status oculta, sem esconder a navegacao.
    private void hideStatusBar() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
            decor.getSystemUiVisibility() | View.SYSTEM_UI_FLAG_FULLSCREEN);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            android.view.WindowInsetsController controller =
                getWindow().getInsetsController();
            if (controller != null)
                controller.hide(android.view.WindowInsets.Type.statusBars());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }

    @Override
    protected void onPause() {
        Workspace.onHostPause(this);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        Workspace.onHostDestroy(this);
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Workspace.handleActivityResult(this, requestCode, resultCode, data);
    }
}
