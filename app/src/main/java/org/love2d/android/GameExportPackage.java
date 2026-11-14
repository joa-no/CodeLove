package org.love2d.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Exports a saved SAF project as a standard .love file.
 *
 * This is an intermediate asset for an Android build, not an installable APK.
 * The temporary ZIP is created BEFORE ACTION_CREATE_DOCUMENT so that the user
 * can choose a folder within the original project without recursively
 * including the newly created export in that ZIP.
 */
final class GameExportPackage {
    static final int REQUEST_SAVE_LOVE = 4104;
    private static final String TAG = "CodeLoveExport";
    private static final Object LOCK = new Object();
    private static boolean busy;
    private static Activity owner;
    private static File stagedFile;

    private GameExportPackage() {}

    static void prepare(Activity activity, DirectSafStorage storage, String title) {
        if (activity == null || storage == null || activity.isFinishing()) return;
        synchronized (LOCK) {
            if (busy) {
                Toast.makeText(activity, R.string.cl_export_love_busy,
                    Toast.LENGTH_SHORT).show();
                return;
            }
            busy = true;
            owner = activity;
        }
        Toast.makeText(activity, R.string.cl_export_love_preparing,
            Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            File temp = null;
            try {
                File directory = new File(activity.getCacheDir(), "codelove-export");
                if (!directory.isDirectory() && !directory.mkdirs()) {
                    throw new IOException("Could not create the export cache directory");
                }
                File[] leftovers = directory.listFiles();
                if (leftovers != null) {
                    for (File old : leftovers) {
                        if (old.isFile() && old.getName().endsWith(".love")) old.delete();
                    }
                }
                temp = File.createTempFile("game-", ".love", directory);
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    GameExportArchive.write(activity, storage, out);
                    out.getFD().sync();
                }
                final File ready = temp;
                activity.runOnUiThread(() -> {
                    if (!isCurrentOwner(activity) || activity.isFinishing()
                            || activity.isDestroyed()) {
                        finish(activity, ready);
                        return;
                    }
                    synchronized (LOCK) {
                        stagedFile = ready;
                    }
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("application/zip");
                    intent.putExtra(Intent.EXTRA_TITLE, safeFileName(title) + ".love");
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    try {
                        activity.startActivityForResult(intent, REQUEST_SAVE_LOVE);
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Cannot open file picker", e);
                        finish(activity, ready);
                        Toast.makeText(activity, R.string.cl_export_love_failed,
                            Toast.LENGTH_LONG).show();
                    }
                });
            } catch (IOException | RuntimeException e) {
                if (temp != null) temp.delete();
                Log.w(TAG, "Failed to prepare LOVE archive", e);
                activity.runOnUiThread(() -> {
                    finish(activity, null);
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity, R.string.cl_export_love_failed,
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "CodeLove-LoveExport").start();
    }

    static boolean handleResult(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST_SAVE_LOVE) return false;
        final File packageFile;
        synchronized (LOCK) {
            if (!busy) return true;
            // The Android system may recreate EditorActivity while the document
            // picker is open. Its result still belongs to this single request.
            if (owner != activity) {
                if (owner != null && !owner.isDestroyed() && !owner.isFinishing()) {
                    return true;
                }
                owner = activity;
            }
            packageFile = stagedFile;
            stagedFile = null;
        }
        if (packageFile == null) {
            finish(activity, null);
            Toast.makeText(activity, R.string.cl_export_love_failed, Toast.LENGTH_LONG).show();
            return true;
        }
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            finish(activity, packageFile);
            return true;
        }
        Uri destination = data.getData();
        if (!"content".equals(destination.getScheme())) {
            finish(activity, packageFile);
            Toast.makeText(activity, R.string.cl_export_love_failed, Toast.LENGTH_LONG).show();
            return true;
        }
        new Thread(() -> {
            boolean ok = false;
            try (FileInputStream in = new FileInputStream(packageFile)) {
                OutputStream target = activity.getContentResolver()
                    .openOutputStream(destination, "wt");
                if (target == null) throw new IOException("SAF destination could not be opened");
                try (OutputStream out = target) {
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (n > 0) out.write(buffer, 0, n);
                    }
                    out.flush();
                }
                ok = true;
            } catch (IOException | SecurityException e) {
                Log.w(TAG, "Failed to copy LOVE archive to selected document", e);
                // This document was created by our own ACTION_CREATE_DOCUMENT request.
                // Avoid leaving a truncated package that appears to be valid.
                try {
                    if (DocumentsContract.isDocumentUri(activity, destination)) {
                        DocumentsContract.deleteDocument(
                            activity.getContentResolver(), destination);
                    }
                } catch (RuntimeException | IOException deleteError) {
                    Log.w(TAG, "Could not clean up failed export document", deleteError);
                }
            } finally {
                final boolean successful = ok;
                finish(activity, packageFile);
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        Toast.makeText(activity,
                            successful ? R.string.cl_export_love_saved
                                       : R.string.cl_export_love_failed,
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "CodeLove-LoveSave").start();
        return true;
    }

    private static String safeFileName(String value) {
        String safe = value == null ? "" :
            value.trim().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) safe = "game";
        return safe.length() > 64 ? safe.substring(0, 64).trim() : safe;
    }

    private static boolean isCurrentOwner(Activity activity) {
        synchronized (LOCK) {
            return busy && owner == activity;
        }
    }

    private static void finish(Activity activity, File file) {
        synchronized (LOCK) {
            if (owner == activity) {
                stagedFile = null;
                busy = false;
                owner = null;
            }
        }
        if (file != null) file.delete();
    }
}
