package org.love2d.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a portable, versioned APK build request from a saved SAF project.
 *
 * This is NOT an APK and contains no credentials, signing keys or remote
 * destination. The bundle is intended for an isolated authenticated builder.
 * Neither the selected project root nor its files are modified.
 */
final class GameExportBuildRequest {
    private static final long MAX_ICON_INPUT = 8L * 1024L * 1024L;
    private static final long MAX_ICON_PNG = 8L * 1024L * 1024L;
    private static final int BUF = 32768;

    private GameExportBuildRequest() {}

    static void write(Context context, DirectSafStorage storage,
                      String gameName, String packageName, String versionName,
                      int versionCode, String selectedIcon, OutputStream output)
            throws IOException {
        if (context == null || storage == null || output == null
                || gameName == null || gameName.trim().isEmpty()
                || gameName.length() > 100 || gameName.contains("\n")
                || gameName.contains("\r") || packageName == null
                || !packageName.matches(
                    "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+")
                || versionName == null || versionName.trim().isEmpty()
                || versionName.length() > 64 || versionCode < 1
                || versionCode > 2100000000) {
            throw new IOException("Invalid build request settings");
        }
        File archive = File.createTempFile(
            "codelove-game-", ".love", context.getCacheDir());
        try {
            try (FileOutputStream out = new FileOutputStream(archive)) {
                GameExportArchive.write(context, storage, out);
                out.getFD().sync();
            }
            byte[] iconPng = selectedIcon == null || selectedIcon.trim().isEmpty()
                ? null : readAndNormalizeIcon(context, Uri.parse(selectedIcon));
            JSONObject request = new JSONObject();
            try {
                request.put("format", "codelove.android-build.v1");
                request.put("name", gameName.trim());
                request.put("package", packageName);
                request.put("version_name", versionName);
                request.put("version_code", versionCode);
                request.put("game_love", metadata(archive));
                if (iconPng != null) {
                    request.put("icon_png", metadata(iconPng));
                }
            } catch (JSONException e) {
                throw new IOException("Failed to encode build metadata", e);
            }

            ZipOutputStream zip = new ZipOutputStream(output);
            byte[] metadata = request.toString().getBytes(
                java.nio.charset.StandardCharsets.UTF_8);
            zip.putNextEntry(new ZipEntry("request.json"));
            zip.write(metadata);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry("game.love"));
            try (FileInputStream in = new FileInputStream(archive)) {
                copy(in, zip);
            }
            zip.closeEntry();
            if (iconPng != null) {
                zip.putNextEntry(new ZipEntry("icon.png"));
                zip.write(iconPng);
                zip.closeEntry();
            }
            // Do not close the caller-owned OutputStream.
            zip.finish();
            zip.flush();
        } finally {
            if (!archive.delete() && archive.exists()) {
                android.util.Log.w("CodeLoveExport", "Temporary game archive retained");
            }
        }
    }

    private static byte[] readAndNormalizeIcon(Context context, Uri uri)
            throws IOException {
        if (!"content".equals(uri.getScheme())) {
            throw new IOException("Icon must be selected with Android SAF");
        }
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("Selected icon is unavailable");
            byte[] buffer = new byte[BUF];
            int length;
            while ((length = stream.read(buffer)) != -1) {
                if (length == 0) continue;
                if ((long) input.size() + length > MAX_ICON_INPUT) {
                    throw new IOException("Selected icon is too large");
                }
                input.write(buffer, 0, length);
            }
        }
        byte[] encoded = input.toByteArray();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(encoded, 0, encoded.length, bounds);
        if (bounds.outWidth < 192 || bounds.outHeight < 192
                || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
            throw new IOException("Icon must be between 192 and 4096 pixels");
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight)
                / decode.inSampleSize > 1024) {
            decode.inSampleSize *= 2;
        }
        Bitmap original = BitmapFactory.decodeByteArray(
            encoded, 0, encoded.length, decode);
        if (original == null) throw new IOException("Cannot decode selected icon");
        Bitmap square = null;
        Bitmap scaled = null;
        try {
            int side = Math.min(original.getWidth(), original.getHeight());
            square = Bitmap.createBitmap(original,
                (original.getWidth() - side) / 2,
                (original.getHeight() - side) / 2, side, side);
            scaled = Bitmap.createScaledBitmap(square, 512, 512, true);
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (!scaled.compress(Bitmap.CompressFormat.PNG, 100, png)
                    || png.size() > MAX_ICON_PNG) {
                throw new IOException("Failed to normalize game icon");
            }
            return png.toByteArray();
        } finally {
            if (scaled != null && scaled != square && scaled != original) scaled.recycle();
            if (square != null && square != original) square.recycle();
            original.recycle();
        }
    }

    private static JSONObject metadata(File file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[BUF];
        long size = 0L;
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (n == 0) continue;
                size += n;
                digest.update(buffer, 0, n);
            }
        }
        return buildMetadata(size, digest.digest());
    }

    private static JSONObject metadata(byte[] input) throws IOException {
        MessageDigest digest = sha256();
        digest.update(input);
        return buildMetadata(input.length, digest.digest());
    }

    private static JSONObject buildMetadata(long size, byte[] hash) throws IOException {
        JSONObject result = new JSONObject();
        try {
            result.put("size", size);
            result.put("sha256", hex(hash));
        } catch (JSONException e) {
            throw new IOException("Failed to encode file digest", e);
        }
        return result;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        final char[] symbols = "0123456789abcdef".toCharArray();
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; ++i) {
            chars[i * 2] = symbols[(bytes[i] >>> 4) & 0xF];
            chars[i * 2 + 1] = symbols[bytes[i] & 0xF];
        }
        return new String(chars);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUF];
        int n;
        while ((n = in.read(buffer)) != -1) {
            if (n > 0) out.write(buffer, 0, n);
        }
    }
}
