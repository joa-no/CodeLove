package org.love2d.android;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import com.android.apksig.ApkVerifier;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Editor-only updater. No credentials, background service, or game changes. */
final class CodeLoveUpdater {
    private static final String API = "https://api.github.com/repos/joa-no/CodeLove/releases/latest";
    private static final String DOWNLOAD_ROOT = "https://github.com/joa-no/CodeLove/releases/download/";
    private static final long DAY = 86400000L;
    private static final long RETRY = 300000L;
    private static final long MAX_APK = 256L * 1024 * 1024;
    private static final int INSTALL_PERMISSION = 9741;
    private final EditorActivity activity;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean closed;
    private volatile boolean cancelled;
    private boolean busy;
    private long lastAttempt;
    private Release pending;
    private File installFile;
    private Dialog dialog;

    CodeLoveUpdater(EditorActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("codelove_updates", 0);
    }

    void onEditorFocus() {
        if (closed || busy || (dialog != null && dialog.isShowing())
                || !activity.hasWindowFocus() || !Workspace.canPromptForUpdate(activity)) return;
        if (pending != null) {
            Release release = pending;
            pending = null;
            offer(release);
        } else check(false);
    }

    void check(boolean manual) {
        if (closed || busy || !Workspace.canPromptForUpdate(activity)) return;
        long now = System.currentTimeMillis();
        long last = prefs.getLong("last_success", 0);
        if (!manual && ((now >= last && now - last < DAY)
                || (now >= lastAttempt && now - lastAttempt < RETRY))) return;
        lastAttempt = now;
        pending = null;
        busy = true;
        if (manual) dialog = CodeLoveModal.progress(activity,
            activity.getString(R.string.cl_update_check), activity.getString(R.string.cl_update_checking));
        worker.execute(() -> {
            Release release = null;
            boolean success = false;
            try {
                JSONObject json = new JSONObject(readMetadata());
                release = parseRelease(json, BuildConfig.VERSION_NAME);
                success = true;
            } catch (Exception e) {
                android.util.Log.w("CodeLoveUpdater", "Release check failed", e);
            }
            final Release result = release;
            final boolean ok = success;
            main.post(() -> {
                if (closed) return;
                dismissDialog();
                busy = false;
                if (!ok) {
                    if (manual) message(R.string.cl_update_check_failed);
                    return;
                }
                prefs.edit().putLong("last_success", System.currentTimeMillis()).apply();
                if (result == null) {
                    if (manual) message(R.string.cl_update_latest);
                    return;
                }
                if (!manual && result.version.equals(prefs.getString("skipped", ""))) return;
                if (manual || (activity.hasWindowFocus() && Workspace.canPromptForUpdate(activity))) {
                    offer(result);
                } else pending = result;
            });
        });
    }

    private void offer(Release release) {
        if (closed) return;
        String notes = release.notes.isEmpty() ? activity.getString(R.string.cl_update_no_notes) : release.notes;
        String detail = activity.getString(R.string.cl_update_detail,
            BuildConfig.VERSION_NAME, release.version, notes);
        dialog = CodeLoveModal.updateOffer(activity, detail,
            () -> download(release),
            () -> prefs.edit().putString("skipped", release.version).apply());
    }

    private void download(Release release) {
        if (closed || busy) return;
        if (!Workspace.canInstallUpdate(activity)) {
            message(R.string.cl_update_save_first);
            return;
        }
        busy = true;
        cancelled = false;
        TextView status = new TextView(activity);
        status.setText(activity.getString(R.string.cl_update_downloading, 0));
        dialog = CodeLoveModal.updateProgress(activity, status, () -> cancelled = true);
        worker.execute(() -> {
            File dir = new File(activity.getCacheDir(), "updates");
            File part = new File(dir, "update.part");
            File apk = new File(dir, "update.apk");
            boolean ok = false;
            try {
                if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cache unavailable");
                if (part.exists() && !part.delete()) throw new IOException("Old partial download");
                if (apk.exists() && !apk.delete()) throw new IOException("Old APK");
                downloadFile(release, part, status);
                checkCancelled();
                main.post(() -> { if (!closed && !cancelled) status.setText(R.string.cl_update_verifying); });
                validateApk(part, release);
                checkCancelled();
                if (!part.renameTo(apk)) throw new IOException("Cannot finalize update");
                ok = true;
            } catch (Exception e) {
                android.util.Log.w("CodeLoveUpdater", "Update download/verification failed", e);
            } finally {
                part.delete();
                if (!ok) apk.delete();
            }
            final boolean verified = ok;
            main.post(() -> {
                if (closed) return;
                dismissDialog();
                busy = false;
                if (cancelled) {
                    apk.delete();
                    return;
                }
                if (!verified) {
                    message(R.string.cl_update_failed);
                    return;
                }
                installFile = apk;
                CodeLoveModal.confirm(activity, activity.getString(R.string.cl_update_available),
                    activity.getString(R.string.cl_update_ready),
                    activity.getString(R.string.cl_update_install), this::requestInstall, () -> {});
            });
        });
    }

    private void requestInstall() {
        if (closed || installFile == null || !installFile.isFile()) return;
        if (!Workspace.canInstallUpdate(activity)) {
            message(R.string.cl_update_save_first);
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            CodeLoveModal.confirm(activity, activity.getString(R.string.cl_update_install),
                activity.getString(R.string.cl_update_permission),
                activity.getString(R.string.cl_update_install), () -> {
                    try {
                        activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName())), INSTALL_PERMISSION);
                    } catch (RuntimeException e) { message(R.string.cl_update_install_failed); }
                }, () -> {});
            return;
        }
        openInstaller();
    }

    boolean onActivityResult(int requestCode) {
        if (requestCode != INSTALL_PERMISSION) return false;
        if (Build.VERSION.SDK_INT >= 26 && activity.getPackageManager().canRequestPackageInstalls()) {
            main.post(this::openInstaller);
        } else message(R.string.cl_update_install_failed);
        return true;
    }

    private void openInstaller() {
        if (closed || installFile == null || !installFile.isFile()) return;
        if (!Workspace.canInstallUpdate(activity)) {
            message(R.string.cl_update_save_first);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", installFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(android.content.ClipData.newRawUri("CodeLove update", uri));
            activity.startActivity(intent);
        } catch (RuntimeException e) {
            message(R.string.cl_update_install_failed);
        }
    }

    private void validateApk(File file, Release release) throws Exception {
        PackageManager pm = activity.getPackageManager();
        PackageInfo info = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (info == null || !activity.getPackageName().equals(info.packageName)
                || !release.version.equals(info.versionName)
                || info.versionCode <= BuildConfig.VERSION_CODE) throw new IOException("Invalid package/version");
        PackageInfo current = pm.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNATURES);
        ApkVerifier.Result verified = new ApkVerifier.Builder(file).build().verify();
        if (!verified.isVerified() || !verified.isVerifiedUsingV2Scheme()
                || verified.getSignerCertificates().size() != 1
                || current.signatures == null || current.signatures.length != 1
                || !MessageDigest.isEqual(verified.getSignerCertificates().get(0).getEncoded(),
                    current.signatures[0].toByteArray())) throw new IOException("APK signature mismatch");
    }

    private void downloadFile(Release release, File part, TextView status) throws Exception {
        HttpURLConnection c = connect(release.url, false);
        try {
            long length = c.getContentLengthLong();
            if (length > MAX_APK || (length >= 0 && length != release.size)) throw new IOException("Unexpected length");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            int last = -1;
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    checkCancelled();
                    total += n;
                    if (total > release.size || total > MAX_APK) throw new IOException("Download too large");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                    int percent = (int) (total * 100 / release.size);
                    if (percent != last) {
                        last = percent;
                        main.post(() -> { if (!closed && !cancelled)
                            status.setText(activity.getString(R.string.cl_update_downloading, percent)); });
                    }
                }
            }
            if (total != release.size || !release.digest.equals(hex(digest.digest())))
                throw new IOException("APK digest mismatch");
        } finally { c.disconnect(); }
    }

    private String readMetadata() throws Exception {
        HttpURLConnection c = connect(API, true);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (closed || out.size() + n > 1024 * 1024) throw new IOException("Invalid metadata size");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { c.disconnect(); }
    }

    private static HttpURLConnection connect(String address, boolean metadata) throws Exception {
        URL url = new URL(address);
        for (int redirects = 0; redirects <= 5; redirects++) {
            String host = url.getHost().toLowerCase(Locale.ROOT);
            boolean allowed = metadata ? host.equals("api.github.com")
                : host.equals("github.com") || host.equals("release-assets.githubusercontent.com")
                    || host.equals("objects.githubusercontent.com") || host.equals("github-releases.githubusercontent.com");
            if (!allowed || !"https".equals(url.getProtocol()) || url.getUserInfo() != null
                    || (url.getPort() != -1 && url.getPort() != 443)) throw new IOException("Unexpected GitHub URL");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "CodeLove/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", metadata ? "application/vnd.github+json" : "application/octet-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = c.getResponseCode();
                if (code == 200) return c;
                String location = c.getHeaderField("Location");
                if ((code == 301 || code == 302 || code == 303 || code == 307 || code == 308)
                        && location != null) {
                    url = new URL(url, location);
                } else throw new IOException("GitHub HTTP " + code);
            } catch (Exception e) {
                c.disconnect();
                throw e;
            }
            c.disconnect();
        }
        throw new IOException("Too many redirects");
    }

    static Release parseRelease(JSONObject json, String installed) throws Exception {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null;
        String tag = json.getString("tag_name");
        if (!tag.matches("v[0-9]{1,9}\\.[0-9]{1,9}\\.0")) return null;
        String version = tag.substring(1);
        if (!isNewer(version, installed)) return null;
        JSONArray assets = json.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!"app-release.apk".equals(asset.optString("name"))) continue;
            String url = asset.getString("browser_download_url");
            String hash = asset.optString("digest");
            long size = asset.getLong("size");
            if (!url.equals(DOWNLOAD_ROOT + tag + "/app-release.apk")
                    || !hash.matches("sha256:[0-9a-fA-F]{64}") || size <= 0 || size > MAX_APK)
                throw new IOException("Unverifiable release asset");
            String notes = json.optString("body", "");
            if (notes.length() > 5000) notes = notes.substring(0, 5000) + "…";
            return new Release(version, url, hash.substring(7).toLowerCase(Locale.ROOT), size, notes);
        }
        throw new IOException("Official APK asset missing");
    }

    static boolean isNewer(String candidate, String installed) {
        if (!candidate.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
                || !installed.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")) return false;
        String[] a = candidate.split("\\."), b = installed.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }

    private void checkCancelled() throws IOException {
        if (cancelled || closed || Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
    }

    private void message(int id) {
        if (!closed && !activity.isFinishing() && !activity.isDestroyed())
            CodeLoveModal.message(activity, activity.getString(R.string.cl_update_check),
                activity.getString(id), activity.getString(android.R.string.ok));
    }

    private void dismissDialog() {
        if (dialog != null) { dialog.dismiss(); dialog = null; }
    }

    void close() {
        closed = true;
        cancelled = true;
        dismissDialog();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    static final class Release {
        final String version, url, digest, notes;
        final long size;
        Release(String version, String url, String digest, long size, String notes) {
            this.version = version; this.url = url; this.digest = digest;
            this.size = size; this.notes = notes;
        }
    }
}
