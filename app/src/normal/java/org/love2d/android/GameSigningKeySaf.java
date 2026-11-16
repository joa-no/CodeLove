package org.love2d.android;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

/**
 * Android Storage Access Framework boundary for encrypted game signing keys.
 *
 * The Activity launches the returned intent and passes the chosen content Uri
 * to backupToDocument/restoreFromDocument on a background thread. No storage
 * permission, private key in the game project, or password persistence.
 */
final class GameSigningKeySaf {
    private static final String MIME_PKCS12 = "application/x-pkcs12";

    private GameSigningKeySaf() {}

    static Intent createBackupDocumentIntent(String suggestedFileName) {
        String fileName = suggestedFileName == null ? "" : suggestedFileName.trim();
        if (fileName.isEmpty()) fileName = "codelove-game-key";
        // Avoid treating a game title as a filesystem path.
        StringBuilder safeName = new StringBuilder(fileName.length());
        for (int i = 0; i < fileName.length(); i++) {
            char ch = fileName.charAt(i);
            safeName.append(ch == '/' || ch == '\\' || ch < 32 || ch == 127 ? '_' : ch);
        }
        fileName = safeName.toString();
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".p12")) {
            fileName += ".p12";
        }
        return new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(MIME_PKCS12)
                .putExtra(Intent.EXTRA_TITLE, fileName);
    }

    static Intent openBackupDocumentIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }

    static GameSigningKeyStore.Identity backupToDocument(
            Context context, String projectId, char[] password, Uri destination)
            throws IOException, GeneralSecurityException {
        if (context == null || destination == null
                || !ContentResolver.SCHEME_CONTENT.equals(destination.getScheme())) {
            throw new IOException("Invalid Android backup destination");
        }
        // Validate the password and existing identity before opening/truncating
        // the destination document.
        GameSigningKeyManager.inspect(context, projectId, password);
        try (OutputStream out = context.getContentResolver()
                .openOutputStream(destination, "wt")) {
            if (out == null) throw new FileNotFoundException("Cannot write backup document");
            return GameSigningKeyManager.backup(context, projectId, password, out);
        }
    }

    static GameSigningKeyStore.Identity restoreFromDocument(
            Context context, String projectId, char[] password, Uri source)
            throws IOException, GeneralSecurityException {
        if (context == null || source == null
                || !ContentResolver.SCHEME_CONTENT.equals(source.getScheme())) {
            throw new IOException("Invalid Android backup source");
        }
        // Refuse recovery before reading if the project already owns a key.
        if (GameSigningKeyManager.hasKey(context, projectId)) {
            throw new IOException("Project already has a signing key; refusing replacement");
        }
        try (InputStream in = context.getContentResolver().openInputStream(source)) {
            if (in == null) throw new FileNotFoundException("Cannot read backup document");
            // Store validates password, certificate, key match and file size,
            // and atomically publishes only a NEW private identity.
            return GameSigningKeyManager.restore(context, projectId, password, in);
        }
    }
}
