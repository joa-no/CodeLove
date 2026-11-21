package org.love2d.android;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Offline staging boundary for a separately built, unsigned embed-flavor APK.
 *
 * Importing a template is an internal provisioning step, not a user-facing
 * export workflow. No network access and no edits to the user's SAF project.
 * The imported template is not a finished game APK.
 */
final class OfflineApkTemplateStore {
    private static final String DIRECTORY = "offline-apk-template";
    private static final String FILE_NAME = "embed-unsigned.apk";
    // Optional prebuilt template packaged with CodeLove at build time.
    private static final String BUNDLED_ASSET = "offline/embed-unsigned.apk";
    private static final long MAX_TEMPLATE_BYTES = 256L * 1024L * 1024L;

    private OfflineApkTemplateStore() {}

    /** Caller must run this off the main thread. */
    static synchronized OfflineApkTemplateValidator.Result importFrom(
            Context context, Uri source) throws IOException {
        if (context == null || source == null
                || !"content".equals(source.getScheme())) {
            throw new IOException("Invalid offline APK template source");
        }
        File directory = new File(context.getFilesDir(), DIRECTORY);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Cannot create private APK template directory");
        }
        File staged = File.createTempFile("template-", ".apk", directory);
        try {
            long bytes = 0;
            try (InputStream input = context.getContentResolver().openInputStream(source);
                 FileOutputStream output = new FileOutputStream(staged)) {
                if (input == null) throw new IOException("Cannot read APK template");
                byte[] buffer = new byte[32768];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    bytes += read;
                    if (bytes > MAX_TEMPLATE_BYTES) {
                        throw new IOException("APK template exceeds 256 MiB");
                    }
                    output.write(buffer, 0, read);
                }
                output.getFD().sync();
            }
            OfflineApkTemplateValidator.Result result =
                    OfflineApkTemplateValidator.inspect(staged);
            if (!result.alreadyContainsGame) {
                throw new IOException("APK template has no embedded game placeholder");
            }
            File destination = new File(directory, FILE_NAME);
            // Keep the existing validated template if replacement fails.
            File previous = new File(directory, FILE_NAME + ".previous");
            if (previous.exists() && !previous.delete()) {
                throw new IOException("Cannot clear previous APK template backup");
            }
            boolean hadPrevious = destination.exists();
            if (hadPrevious && !destination.renameTo(previous)) {
                throw new IOException("Cannot preserve existing APK template");
            }
            if (!staged.renameTo(destination)) {
                if (hadPrevious && !previous.renameTo(destination)) {
                    throw new IOException("Cannot install or restore APK template");
                }
                throw new IOException("Cannot install APK template");
            }
            if (hadPrevious) previous.delete();
            return result;
        } finally {
            if (staged.exists()) staged.delete();
        }
    }

    /** Returns a validated private file, never an untrusted external URI. */
    static synchronized File get(Context context) throws IOException {
        if (context == null) throw new IOException("Missing context");
        File template = new File(new File(context.getFilesDir(), DIRECTORY), FILE_NAME);
        long installedAt = 0;
        try {
            installedAt = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0).lastUpdateTime;
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            throw new IOException("Cannot determine CodeLove update time", error);
        }
        if (!template.isFile() || template.lastModified() < installedAt) {
            provisionBundledTemplate(context, template);
        }
        OfflineApkTemplateValidator.Result result =
                OfflineApkTemplateValidator.inspect(template);
        if (!result.alreadyContainsGame) {
            throw new IOException("APK template has no embedded game placeholder");
        }
        return template;
    }
    /** Install the optional bundled template once, without any network request. */
    private static void provisionBundledTemplate(Context context, File destination)
            throws IOException {
        File directory = destination.getParentFile();
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            throw new IOException("Cannot prepare offline APK template storage");
        }
        File temporary = File.createTempFile("bundled-", ".apk", directory);
        try {
            long size = 0;
            try (InputStream input = context.getAssets().open(BUNDLED_ASSET);
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count == 0) continue;
                    size += count;
                    if (size > MAX_TEMPLATE_BYTES) {
                        throw new IOException("Bundled APK template exceeds 256 MiB");
                    }
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            } catch (java.io.FileNotFoundException missing) {
                throw new IOException("Offline APK template not bundled in this build", missing);
            }
            OfflineApkTemplateValidator.Result checked =
                    OfflineApkTemplateValidator.inspect(temporary);
            if (!checked.alreadyContainsGame) {
                throw new IOException("Bundled APK template lacks a game slot");
            }
            // Atomic replacement on the same private filesystem.
            try {
                android.system.Os.rename(temporary.getAbsolutePath(), destination.getAbsolutePath());
            } catch (android.system.ErrnoException error) {
                throw new IOException("Cannot install bundled APK template", error);
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }
}
