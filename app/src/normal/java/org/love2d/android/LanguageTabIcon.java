package org.love2d.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import java.util.Locale;

/**
 * Pequenos icones desenhados pelo Android para as abas e a arvore de arquivos.
 * Nao depende de fonte externa, rede ou pacote adicional.
 */
final class LanguageTabIcon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String extension;

    LanguageTabIcon(Context context, String filename) {
        super(context);
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        setContentDescription("Tipo de arquivo: " + (extension.isEmpty() ? "generico" : extension));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float scale = Math.min(getWidth(), getHeight()) / 24f;
        float x = (getWidth() - 24f * scale) / 2f;
        float y = (getHeight() - 24f * scale) / 2f;
        canvas.save();
        canvas.translate(x, y);
        canvas.scale(scale, scale);
        switch (extension) {
            case "lua":
                drawLua(canvas);
                break;
            case "py":
            case "pyw":
                drawPython(canvas);
                break;
            case "js":
            case "jsx":
                drawBadge(canvas, 0xFFF4D34E, "JS", 0xFF242424);
                break;
            case "ts":
            case "tsx":
                drawBadge(canvas, 0xFF3178C6, "TS", 0xFFFFFFFF);
                break;
            case "json":
                drawBadge(canvas, 0xFF586B82, "{}", 0xFFFFFFFF);
                break;
            case "java":
                drawBadge(canvas, 0xFFB85B4C, "J", 0xFFFFFFFF);
                break;
            case "html":
            case "htm":
                drawBadge(canvas, 0xFFE06A3C, "<>", 0xFFFFFFFF);
                break;
            case "css":
                drawBadge(canvas, 0xFF357DBB, "#", 0xFFFFFFFF);
                break;
            case "xml":
                drawBadge(canvas, 0xFF8C65BB, "<>", 0xFFFFFFFF);
                break;
            // Media uses distinct pictograms, rather than the generic document.
            case "png":
            case "jpg":
            case "jpeg":
            case "gif":
            case "webp":
            case "bmp":
            case "svg":
            case "ico":
            case "avif":
            case "heic":
            case "heif":
                drawImage(canvas);
                break;
            case "wav":
            case "ogg":
            case "mp3":
            case "flac":
            case "aac":
            case "m4a":
            case "opus":
            case "mid":
            case "midi":
            case "aiff":
            case "aif":
                drawAudio(canvas);
                break;
            case "md":
                drawBadge(canvas, 0xFF6A7586, "M", 0xFFFFFFFF);
                break;
            case "txt":
                drawDocument(canvas, 0xFF9CA8BD);
                break;
            default:
                drawDocument(canvas, 0xFF8B95AA);
                break;
        }
        canvas.restore();
    }

    private void circle(Canvas c, int color, float x, float y, float radius) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        c.drawCircle(x, y, radius, paint);
    }

    private void drawLua(Canvas c) {
        // Lua: disco azul e esfera orbital.
        circle(c, 0xFF3152B5, 10.3f, 13.8f, 8.4f);
        circle(c, 0xFF151822, 14.3f, 9.6f, 3.0f);
        circle(c, 0xFF6B86DC, 18.6f, 4.8f, 2.8f);
    }

    private void drawPython(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF3674A5);
        c.drawRoundRect(new RectF(3, 3.5f, 16.5f, 13.6f), 3.2f, 3.2f, paint);
        paint.setColor(0xFFF3CE49);
        c.drawRoundRect(new RectF(7.5f, 10.4f, 21, 20.5f), 3.2f, 3.2f, paint);
        circle(c, 0xFFFFFFFF, 8.4f, 6.6f, 1.05f);
        circle(c, 0xFF344D68, 15.6f, 17.3f, 1.05f);
    }

    private void drawBadge(Canvas c, int background, String text, int foreground) {
        paint.setColor(background);
        paint.setStyle(Paint.Style.FILL);
        c.drawRoundRect(new RectF(1.5f, 1.5f, 22.5f, 22.5f), 4.2f, 4.2f, paint);
        paint.setColor(foreground);
        paint.setTypeface(android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        paint.setTextSize(text.length() > 1 ? 11f : 15f);
        paint.setTextAlign(Paint.Align.CENTER);
        c.drawText(text, 12f, 16.3f, paint);
    }

    /** Image frame with sun and hills; distinguishable at 18dp. */
    private void drawImage(Canvas c) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xFF61A9D6);
        c.drawRoundRect(new RectF(2f, 4f, 22f, 20f), 3f, 3f, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF82C2E8);
        c.drawCircle(16.5f, 9f, 1.8f, paint);

        Path hills = new Path();
        hills.moveTo(4.2f, 17.8f);
        hills.lineTo(10f, 11.4f);
        hills.lineTo(13.3f, 14.5f);
        hills.lineTo(15.3f, 12.9f);
        hills.lineTo(19.8f, 17.8f);
        hills.close();
        paint.setColor(0xFF61A9D6);
        c.drawPath(hills, paint);
    }

    /** Two joined musical notes for audio and music files. */
    private void drawAudio(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF59BDA8);
        c.drawOval(new RectF(3.5f, 15.8f, 10.2f, 20.5f), paint);
        c.drawOval(new RectF(12.7f, 12.7f, 19.4f, 17.4f), paint);

        Path notes = new Path();
        notes.moveTo(8.1f, 17.7f);
        notes.lineTo(8.1f, 5.6f);
        notes.lineTo(18.1f, 3.6f);
        notes.lineTo(18.1f, 14.7f);
        notes.lineTo(16.1f, 14.7f);
        notes.lineTo(16.1f, 7.6f);
        notes.lineTo(10.1f, 8.8f);
        notes.lineTo(10.1f, 17.7f);
        notes.close();
        c.drawPath(notes, paint);
    }

    private void drawDocument(Canvas c, int color) {
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.8f);
        c.drawRoundRect(new RectF(4, 2, 20, 22), 2f, 2f, paint);
        c.drawLine(8, 9, 16, 9, paint);
        c.drawLine(8, 13, 16, 13, paint);
        c.drawLine(8, 17, 14, 17, paint);
        paint.setStyle(Paint.Style.FILL);
    }
}
