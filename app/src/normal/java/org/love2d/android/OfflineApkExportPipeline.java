package org.love2d.android;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Internal offline APK assembly pipeline. This is not yet exposed in the UI.
 *
 * Requires a provisioned unsigned embed template and an existing per-game
 * PKCS#12 identity. It never calls Gradle, downloads anything, or modifies
 * the SAF project. Package and version are rewritten before signing; display
 * name and icon are rewritten before signing; the user-facing flow is pending.
 */
final class OfflineApkExportPipeline {
    private OfflineApkExportPipeline() {}

    /**
     * Runs off the main thread. Destination must not exist and is only
     * retained when both assembly and signature verification succeed.
     */
    static OfflineApkSigner.Result export(
            Context context, File gameLove, File destination,
            File gameKeyStore, char[] password, String alias,
            OfflineApkIdentity identity, Uri iconUri)
            throws IOException, GeneralSecurityException {
        if (context == null || gameLove == null || destination == null
                || gameKeyStore == null || password == null || identity == null
                || iconUri == null) {
            throw new IOException("Missing offline export inputs");
        }
        File template = OfflineApkTemplateStore.get(context);
        File parent = destination.getAbsoluteFile().getParentFile();
        if (parent == null || !parent.isDirectory() || destination.exists()) {
            throw new IOException("Destination must be a new file in an existing directory");
        }
        File unsigned = File.createTempFile("codelove-unsigned-", ".apk",
                context.getCacheDir());
        try {
            // Direct assets are the preferred embedding format for LÖVE 11.4+.
            try (FileOutputStream output = new FileOutputStream(unsigned)) {
                OfflineGameApk.writeUnsignedAssets(template, gameLove, output,
                        identity, OfflineApkIcon.fromDocument(context, iconUri));
            }
            try {
                return OfflineApkSigner.sign(unsigned, destination,
                        gameKeyStore, password, alias);
            } catch (IOException | GeneralSecurityException | RuntimeException error) {
                // Never leave an unverified, apparently finished APK behind.
                if (destination.exists()) destination.delete();
                throw error;
            }
        } finally {
            if (unsigned.exists()) unsigned.delete();
        }
    }
}
