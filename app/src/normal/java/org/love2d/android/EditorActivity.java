package org.love2d.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RelativeLayout;

/** Editor CodeLove independente da superficie SDL. */
public final class EditorActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Workspace.applySavedOrientation(this);
        RelativeLayout root = new RelativeLayout(this);
        root.setFocusableInTouchMode(true);
        setContentView(root);
        Workspace.attachEditor(this, root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Workspace.applySavedOrientation(this);
        Workspace.onHostResume(this);
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
