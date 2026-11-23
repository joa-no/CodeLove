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
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Offline APK export coordinator for the editor flavor.
 * Builds and signs in private cache, then saves through SAF; never changes
 * the project files, retains passwords, or invokes Gradle/network services.
 */
final class GameExportApk {
    static final int REQUEST_SAVE_APK = 4107;
    private static final String TAG = "CodeLoveApkExport";
    private static final Object LOCK = new Object();
    private static boolean busy;
    private static Activity owner;
    private static File stagedApk;

    private GameExportApk() {}

    static void prepare(Activity activity, DirectSafStorage storage,
                        String name, String packageName, String versionName,
                        int versionCode, Uri iconUri) {
        if (activity == null || storage == null || activity.isFinishing()) return;
        final OfflineApkIdentity identity;
        final File signingKey;
        try {
            identity = OfflineApkIdentity.create(packageName, name, versionName, versionCode);
            String keyId = GameSigningKeyManager.projectIdForPackage(identity.packageName);
            signingKey = GameSigningKeyManager.privateKeyFile(activity, keyId);
            if (!signingKey.isFile()) {
                notifyUser(activity, activity.getString(R.string.cl_locale_sign_43));
                return;
            }
        } catch (GeneralSecurityException | IOException e) {
            Log.w(TAG, "Invalid APK data", e);
            notifyUser(activity, activity.getString(R.string.cl_locale_sign_62));
            return;
        }
        synchronized (LOCK) {
            if (busy) {
                notifyUser(activity, activity.getString(R.string.cl_locale_sign_44));
                return;
            }
        }
        CodeLoveModal.password(activity, activity.getString(R.string.cl_locale_export_10),
                activity.getString(R.string.cl_locale_sign_45),
                activity.getString(R.string.cl_locale_sign_46), password -> {
                    synchronized (LOCK) {
                        if (busy) {
                            Arrays.fill(password, '\0');
                            notifyUser(activity, activity.getString(R.string.cl_locale_sign_44));
                            return;
                        }
                        busy = true;
                        owner = activity;
                        stagedApk = null;
                    }
                    notifyUser(activity, activity.getString(R.string.cl_locale_sign_47));
                    new Thread(() -> build(activity, storage, identity, iconUri,
                            signingKey, password), "CodeLove-ApkExport").start();
                });
    }

    private static void build(Activity activity, DirectSafStorage storage,
                              OfflineApkIdentity identity, Uri iconUri,
                              File signingKey, char[] password) {
        File love = null;
        File apk = null;
        try {
            File folder = new File(activity.getCacheDir(), "codelove-apk-export");
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Cannot create temporary export folder");
            }
            // Clean abandoned exports from earlier interrupted app processes.
            File[] leftovers = folder.listFiles();
            if (leftovers != null) for (File entry : leftovers) {
                if (entry.isFile() && (entry.getName().endsWith(".apk")
                        || entry.getName().endsWith(".love"))) entry.delete();
            }
            love = File.createTempFile("game-", ".love", folder);
            try (FileOutputStream output = new FileOutputStream(love)) {
                GameExportArchive.write(activity, storage, output);
                output.getFD().sync();
            }
            apk = new File(folder, "game-" + UUID.randomUUID() + ".apk");
            OfflineApkExportPipeline.export(activity, love, apk,
                    signingKey, password, null, identity, iconUri);
            File ready = apk;
            activity.runOnUiThread(() -> {
                if (!isCurrentOwner(activity) || activity.isDestroyed() || activity.isFinishing()) {
                    finish(activity, ready);
                    return;
                }
                synchronized (LOCK) { stagedApk = ready; }
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/vnd.android.package-archive");
                intent.putExtra(Intent.EXTRA_TITLE, safeFileName(identity.displayName) + ".apk");
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try {
                    activity.startActivityForResult(intent, REQUEST_SAVE_APK);
                } catch (RuntimeException e) {
                    Log.e(TAG, "Cannot open destination picker", e);
                    finish(activity, ready);
                    notifyUser(activity, activity.getString(R.string.cl_locale_sign_48));
                }
            });
        } catch (Exception e) {
            if (apk != null) apk.delete();
            Log.e(TAG, "Could not assemble signed game APK", e);
            activity.runOnUiThread(() -> {
                finish(activity, null);
                notifyUser(activity, activity.getString(R.string.cl_locale_sign_63));
            });
        } finally {
            Arrays.fill(password, '\0');
            if (love != null) love.delete();
        }
    }

    static boolean handleResult(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST_SAVE_APK) return false;
        final File ready;
        synchronized (LOCK) {
            if (!busy) return true;
            if (owner != activity) {
                if (owner != null && !owner.isDestroyed() && !owner.isFinishing()) {
                    return true;
                }
                owner = activity;
            }
            ready = stagedApk;
            stagedApk = null;
        }
        if (ready == null || result != Activity.RESULT_OK || data == null
                || data.getData() == null
                || !"content".equals(data.getData().getScheme())) {
            finish(activity, ready);
            return true;
        }
        final Uri destination = data.getData();
        new Thread(() -> {
            boolean successful = false;
            try (FileInputStream input = new FileInputStream(ready)) {
                OutputStream stream = activity.getContentResolver()
                        .openOutputStream(destination, "wt");
                if (stream == null) throw new IOException("Cannot open APK destination");
                try (OutputStream output = stream) {
                    byte[] buffer = new byte[32768];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (n > 0) output.write(buffer, 0, n);
                    }
                    output.flush();
                }
                ExportDocumentVerification.verify(activity.getContentResolver(), destination, ready);
                successful = true;
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Could not save signed game APK", e);
                try {
                    if (DocumentsContract.isDocumentUri(activity, destination)) {
                        DocumentsContract.deleteDocument(activity.getContentResolver(), destination);
                    }
                } catch (Exception cleanup) {
                    Log.w(TAG, "Could not remove incomplete APK", cleanup);
                }
            } finally {
                boolean ok = successful;
                finish(activity, ready);
                activity.runOnUiThread(() -> notifyUser(activity,
                        ok ? activity.getString(R.string.cl_locale_sign_49)
                           : activity.getString(R.string.cl_locale_sign_50)));
            }
        }, "CodeLove-ApkSave").start();
        return true;
    }

    private static boolean isCurrentOwner(Activity activity) {
        synchronized (LOCK) { return busy && owner == activity; }
    }

    private static void finish(Activity activity, File ready) {
        if (ready != null) ready.delete();
        synchronized (LOCK) {
            if (owner == activity) {
                busy = false;
                stagedApk = null;
                owner = null;
            }
        }
    }

    private static String safeFileName(String name) {
        String safe = name.trim().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
        if (safe.isEmpty() || ".".equals(safe) || "..".equals(safe)) safe = "game";
        return safe.length() > 64 ? safe.substring(0, 64).trim() : safe;
    }

    private static void notifyUser(Activity activity, String message) {
        if (!activity.isDestroyed() && !activity.isFinishing()) {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
        }
    }
}
