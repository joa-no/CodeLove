package org.love2d.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

/** Explicit user confirmation before running externally supplied Lua code. */
public final class LoveArchiveActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(CodeLoveLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            uri = intent.getData();
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream instanceof Uri) uri = (Uri) stream;
        }
        if (uri == null || !"content".equals(uri.getScheme())) {
            Toast.makeText(this, R.string.cl_love_open_failed, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        final Uri archive = uri;
        Workspace.configureTheme(this);
        CodeLoveModal.confirm(this, getString(R.string.cl_love_open_title),
            getString(R.string.cl_love_open_confirm),
            getString(R.string.cl_love_open_run),
            () -> LoveArchivePlayback.open(this, archive, intent.getType(), true),
            this::finish);
    }
}
