package org.love2d.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/** Decode a SAF image into the five standard PNG launcher icon densities. */
final class OfflineApkIcon {
    private static final String[] DENSITIES =
            {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
    private static final int[] SIZES = {48, 72, 96, 144, 192};

    private OfflineApkIcon() {}

    /** Must run off the UI thread; never writes to the user's source image. */
    static Map<String, byte[]> fromDocument(Context context, Uri uri) throws IOException {
        if (context == null || uri == null || !"content".equals(uri.getScheme())) {
            throw new IOException("Select a valid icon image");
        }
        BitmapFactory.Options dimensions = new BitmapFactory.Options();
        dimensions.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open icon image");
            BitmapFactory.decodeStream(in, null, dimensions);
        }
        if (dimensions.outWidth < 1 || dimensions.outHeight < 1
                || dimensions.outWidth > 16000 || dimensions.outHeight > 16000) {
            throw new IOException("Invalid image dimensions");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inSampleSize = 1;
        while (Math.max(dimensions.outWidth, dimensions.outHeight) / options.inSampleSize > 1024) {
            options.inSampleSize *= 2;
        }
        Bitmap source;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot reopen icon");
            source = BitmapFactory.decodeStream(in, null, options);
        } catch (OutOfMemoryError e) {
            throw new IOException("Icon image too large", e);
        }
        if (source == null) throw new IOException("Cannot decode icon image");
        try {
            int square = Math.min(source.getWidth(), source.getHeight());
            int left = (source.getWidth() - square) / 2;
            int top = (source.getHeight() - square) / 2;
            Rect crop = new Rect(left, top, left + square, top + square);
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            Map<String, byte[]> icons = new LinkedHashMap<>();
            for (int i = 0; i < DENSITIES.length; i++) {
                Bitmap image = Bitmap.createBitmap(SIZES[i], SIZES[i], Bitmap.Config.ARGB_8888);
                try {
                    Canvas canvas = new Canvas(image);
                    canvas.drawBitmap(source, crop, new Rect(0, 0, SIZES[i], SIZES[i]), paint);
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    if (!image.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                        throw new IOException("Could not encode launcher PNG");
                    }
                    icons.put(DENSITIES[i], bytes.toByteArray());
                } finally {
                    image.recycle();
                }
            }
            return icons;
        } finally {
            source.recycle();
        }
    }
}
