package org.love2d.android;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runs an existing LOVE archive without extracting it into the workspace. */
final class LoveArchivePlayback {
    static final String EXTRA_ARCHIVE = "org.love2d.android.RUNTIME_ARCHIVE";
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final long STALE_AGE = 7L * 24L * 60L * 60L * 1000L;
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private LoveArchivePlayback() {}

    static boolean isBusy() {
        return BUSY.get();
    }

    static boolean isLoveName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".love");
    }

    static boolean isLoveMime(String type) {
        return "application/x-love-game".equalsIgnoreCase(type)
            || "application/x-love".equalsIgnoreCase(type);
    }

    static String displayName(Activity host, Uri uri) {
        if ("content".equals(uri.getScheme())) {
            try (Cursor cursor = host.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
            } catch (RuntimeException ignored) {
                // Some document providers do not expose DISPLAY_NAME.
            }
        }
        return uri.getLastPathSegment();
    }

    private static File directory(Activity host) {
        return new File(host.getCacheDir(), "love-playback");
    }

    /** Accept only a private, staged archive; the runtime is not externally exported. */
    static File checkedArchive(Activity host, String path) throws IOException {
        if (path == null) throw new IOException("Missing archive");
        File root = directory(host).getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !isLoveName(file.getName())
                || !file.isFile() || file.length() > MAX_BYTES) {
            throw new IOException("Invalid staged archive");
        }
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry main = zip.getEntry("main.lua");
            if (main == null || main.isDirectory()) throw new IOException("Missing main.lua");
        }
        return file;
    }

    static boolean hasConf(File archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry conf = zip.getEntry("conf.lua");
            return conf != null && !conf.isDirectory();
        }
    }

    static void open(Activity host, Uri uri, String mime, boolean external) {
        if (uri == null || (external && !"content".equals(uri.getScheme()))) {
            fail(host, external);
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            Toast.makeText(host, R.string.cl_love_open_busy, Toast.LENGTH_SHORT).show();
            if (external) host.finish();
            return;
        }
        Toast.makeText(host, R.string.cl_love_open_preparing, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            File staged = null;
            boolean ready = false;
            try {
                if (external && !isLoveName(displayName(host, uri)) && !isLoveMime(mime)) {
                    throw new IOException("Not a LOVE archive");
                }
                File root = directory(host);
                if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cache unavailable");
                File[] previous = root.listFiles();
                long cutoff = System.currentTimeMillis() - STALE_AGE;
                if (previous != null) {
                    for (File file : previous) {
                        if (isLoveName(file.getName()) && file.lastModified() < cutoff) file.delete();
                    }
                }
                staged = File.createTempFile("game-", ".love", root);
                try (InputStream in = host.getContentResolver().openInputStream(uri);
                        FileOutputStream out = new FileOutputStream(staged)) {
                    if (in == null) throw new IOException("Cannot read archive");
                    byte[] buffer = new byte[64 * 1024];
                    long total = 0;
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_BYTES) throw new IOException("Archive too large");
                        out.write(buffer, 0, count);
                    }
                }
                checkedArchive(host, staged.getAbsolutePath());
                ready = true;
            } catch (IOException | RuntimeException ignored) {
                // Do not leak provider paths or user file names into logs.
            } finally {
                if (!ready && staged != null) staged.delete();
                final File result = ready ? staged : null;
                host.runOnUiThread(() -> {
                    BUSY.set(false);
                    if (host.isFinishing() || host.isDestroyed()) {
                        if (result != null) result.delete();
                        return;
                    }
                    if (result == null) {
                        fail(host, external);
                        return;
                    }
                    try {
                        Intent intent = new Intent(host, GameActivity.class);
                        intent.putExtra(Workspace.EXTRA_RUNTIME_ONLY, true);
                        intent.putExtra(EXTRA_ARCHIVE, result.getAbsolutePath());
                        host.startActivity(intent);
                        if (external) host.finish();
                    } catch (RuntimeException ignored) {
                        result.delete();
                        fail(host, external);
                    }
                });
            }
        }, "CodeLove-love-open").start();
    }

    private static void fail(Activity host, boolean external) {
        Toast.makeText(host, R.string.cl_love_open_failed, Toast.LENGTH_LONG).show();
        if (external) host.finish();
    }
}
