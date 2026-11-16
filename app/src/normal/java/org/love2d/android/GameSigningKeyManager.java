package org.love2d.android;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Android-owned storage boundary for the per-project APK signing identity.
 *
 * Identities live in getNoBackupFilesDir() so they remain private and are
 * never silently restored from Android cloud backup onto another device.
 * IMPORTANT: uninstalling or clearing CodeLove data destroys these local
 * identities. The user must export an encrypted .p12 backup to SAF BEFORE
 * uninstalling if they want to update an installed game afterwards.
 *
 * No UI, no network and no retained password. All methods must run off the
 * main thread and use the stable SAF project ID, never the editable game name.
 */
final class GameSigningKeyManager {
    private static final String KEY_DIR = "game-signing-keys";

    private GameSigningKeyManager() {}

    /**
     * Stable identity for an Android game. A package name is the identity
     * Android itself uses for app updates; it survives project folder moves,
     * folder renames, and changes to the game's display name.
     *
     * The UI must pass the SAVED applicationId, not a filesystem path.
     * Changing applicationId intentionally creates a different Android app
     * and therefore a different signing identity.
     */
    static String projectIdForPackage(String applicationId)
            throws GeneralSecurityException {
        if (applicationId == null || !applicationId.matches(
                "[a-zA-Z_][a-zA-Z0-9_]*(\\\\.[a-zA-Z_][a-zA-Z0-9_]*)+")) {
            throw new GeneralSecurityException("Invalid Android application ID");
        }
        return "android-package:" + applicationId;
    }

    static File privateKeyFile(Context context, String projectId)
            throws IOException, GeneralSecurityException {
        if (context == null || projectId == null || projectId.trim().isEmpty()) {
            throw new IOException("Missing project identity for APK signing");
        }
        File noBackupRoot = context.getNoBackupFilesDir();
        if (noBackupRoot == null || !noBackupRoot.isDirectory()) {
            throw new IOException("Android private no-backup storage is unavailable");
        }
        File directory = new File(noBackupRoot, KEY_DIR);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("Could not create private signing key directory");
        }
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                projectId.getBytes(StandardCharsets.UTF_8));
        StringBuilder id = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            id.append(Character.forDigit((value >>> 4) & 0xf, 16));
            id.append(Character.forDigit(value & 0xf, 16));
        }
        return new File(directory, id + ".p12");
    }

    static boolean hasKey(Context context, String projectId)
            throws IOException, GeneralSecurityException {
        return privateKeyFile(context, projectId).isFile();
    }

    static GameSigningKeyStore.Identity generate(Context context, String projectId,
                                                 char[] password)
            throws IOException, GeneralSecurityException {
        // Fail rather than overwrite a preexisting key. Overwriting it would
        // prevent users from updating games signed with that certificate.
        return GameSigningKeyStore.create(privateKeyFile(context, projectId), password);
    }

    static GameSigningKeyStore.Identity inspect(Context context, String projectId,
                                                char[] password)
            throws IOException, GeneralSecurityException {
        return GameSigningKeyStore.inspect(
                privateKeyFile(context, projectId), password);
    }

    /**
     * Output should be an ACTION_CREATE_DOCUMENT content Uri stream opened by
     * the UI, never the project's SAF directory by default. Stream ownership
     * remains with the caller, which must close/clean up on failure.
     */
    static GameSigningKeyStore.Identity backup(Context context, String projectId,
                                               char[] password, OutputStream output)
            throws IOException, GeneralSecurityException {
        return GameSigningKeyStore.backup(
                privateKeyFile(context, projectId), password, output);
    }

    /**
     * Restore an external backup to a project which does NOT already have a
     * signing identity. Never replace a key without explicit recovery policy.
     */
    static GameSigningKeyStore.Identity restore(Context context, String projectId,
                                                char[] password, InputStream backup)
            throws IOException, GeneralSecurityException {
        return GameSigningKeyStore.restore(
                backup, privateKeyFile(context, projectId), password);
    }
}
