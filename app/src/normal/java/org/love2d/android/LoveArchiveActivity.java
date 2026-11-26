package org.love2d.android;

import android.app.Activity;
import android.app.AlertDialog;
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
        new AlertDialog.Builder(this)
            .setTitle(R.string.cl_love_open_title)
            .setMessage(R.string.cl_love_open_confirm)
            .setPositiveButton(R.string.cl_love_open_run,
                (dialog, which) -> LoveArchivePlayback.open(this, archive, intent.getType(), true))
            .setNegativeButton(R.string.cl_cancel, (dialog, which) -> finish())
            .setOnCancelListener(dialog -> finish())
            .show();
    }
}
