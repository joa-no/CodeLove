package org.love2d.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import java.util.regex.Pattern;

/**
 * Configuration-only UI. This intentionally does NOT claim to compile an APK.
 * The compiler transport and game signing still need to be implemented.
 */
final class GameExportDialog {
    interface PrepareListener {
        void onPrepare(String name);
    }

    static final int REQUEST_ICON = 4103;
    private static final String PREFS = "codelove_game_export";
    private static final Pattern PACKAGE = Pattern.compile(
        "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)+");
    private static final int PURPLE = 0xFFA78BFA;

    private GameExportDialog() {}

    static boolean handleIconResult(Activity activity, int request, int result, Intent data) {
        if (request != REQUEST_ICON) return false;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) return true;
        SharedPreferences preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = preferences.getString("pending_icon_key", "");
        if (key.isEmpty()) return true;
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) activity.getContentResolver().takePersistableUriPermission(uri, flags);
            preferences.edit().putString(key + "icon", uri.toString())
                .remove("pending_icon_key").apply();
            Toast.makeText(activity, R.string.cl_export_icon_selected, Toast.LENGTH_SHORT).show();
        } catch (SecurityException ex) {
            Toast.makeText(activity, R.string.cl_export_icon_failed, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    static void show(Activity activity, String projectId, String projectName,
                     PrepareListener onPrepareLove) {
        if (activity.isFinishing()) return;
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String key = Integer.toHexString(projectId.hashCode()) + "_";
        final float density = activity.getResources().getDisplayMetrics().density;
        final int padding = (int) (20 * density + .5f);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(padding, padding, padding, padding);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Workspace.COLOR_PANEL);
        background.setCornerRadius(18 * density);
        body.setBackground(background);

        TextView heading = new TextView(activity);
        heading.setText(R.string.cl_export_title);
        heading.setTextSize(21);
        heading.setTextColor(Workspace.COLOR_TEXT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.addView(symbol(activity, "package", PURPLE), new LinearLayout.LayoutParams(
            Math.round(23*density), Math.round(23*density)));
        LinearLayout.LayoutParams headerLabelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLabelParams.leftMargin = Math.round(10*density);
        headerRow.addView(heading, headerLabelParams);
        body.addView(headerRow);

        TextView note = new TextView(activity);
        note.setText("Configure e prepare seu jogo para Android.");
        note.setTextSize(13);
        note.setTextColor(Workspace.COLOR_MUTED);
        note.setPadding(0, (int)(8*density), 0, (int)(12*density));
        body.addView(note);

        section(activity, body, "1. Informações do jogo", "settings");
        EditText name = field(activity, body, R.string.cl_export_name,
            prefs.getString(key + "name", projectName), false);
        EditText pack = field(activity, body, R.string.cl_export_package,
            prefs.getString(key + "package", "com.exemplo.meujogo"), false);

        LinearLayout versionRow = new LinearLayout(activity);
        versionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout versionColumn = new LinearLayout(activity);
        versionColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout codeColumn = new LinearLayout(activity);
        codeColumn.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        left.rightMargin = (int)(10*density);
        versionRow.addView(versionColumn, left);
        versionRow.addView(codeColumn, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(versionRow);
        EditText version = field(activity, versionColumn, R.string.cl_export_version,
            prefs.getString(key + "version", "1.0.0"), false);
        EditText versionCode = field(activity, codeColumn, R.string.cl_export_version_code,
            prefs.getString(key + "version_code", "1"), true);

        // Secondary action: outlined row with an icon and chevron.
        LinearLayout icon = secondaryAction(activity,
            "Selecionar ícone", "image");
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(44 * density));
        body.addView(icon, iconParams);
        TextView iconValue = new TextView(activity);
        String selectedIcon = prefs.getString(key + "icon", "");
        iconValue.setText(selectedIcon.isEmpty()
            ? activity.getString(R.string.cl_export_icon_none)
            : activity.getString(R.string.cl_export_icon_selected));
        iconValue.setTextSize(12);
        iconValue.setTextColor(Workspace.COLOR_MUTED);
        iconValue.setVisibility(selectedIcon.isEmpty() ? View.GONE : View.VISIBLE);
        body.addView(iconValue);

        section(activity, body, "2. Assinatura do aplicativo", "shield");
        // Normal editor flavor provides the signing UI; exported games do not.
        if (!BuildConfig.FLAVOR.startsWith("embed")) {
            LinearLayout signing = secondaryAction(activity, "Gerenciar chave de assinatura",
                "key");
            LinearLayout.LayoutParams signingParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int)(44*density));
            signingParams.topMargin = (int)(2*density);
            body.addView(signing, signingParams);
            addActionDescription(activity, signing, "Criar, salvar backup ou restaurar a chave.");
            signing.setOnClickListener(v -> {
                String packageName = pack.getText().toString().trim();
                if (!PACKAGE.matcher(packageName).matches()) {
                    Toast.makeText(activity, R.string.cl_export_invalid, Toast.LENGTH_LONG).show();
                    return;
                }
                // Only the normal source set contains the signing implementation.
                try {
                    Class<?> type = Class.forName("org.love2d.android.GameSigningKeyUi");
                    java.lang.reflect.Method method = type.getDeclaredMethod(
                        "show", Activity.class, String.class);
                    method.setAccessible(true);
                    method.invoke(null, activity, packageName);
                } catch (ReflectiveOperationException ex) {
                    Toast.makeText(activity, "Gerenciamento de chaves indisponível",
                        Toast.LENGTH_LONG).show();
                }
            });
        }

        section(activity, body, "3. Preparar exportação", "package");
        LinearLayout prepareLove = secondaryAction(activity,
            "Exportar pacote .love", "archive");
        LinearLayout.LayoutParams loveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(44*density));
        body.addView(prepareLove, loveParams);

        TextView save = action(activity,
            activity.getString(R.string.cl_export_save));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(42*density));
        saveParams.topMargin = (int)(12*density);
        body.addView(save, saveParams);

        TextView status = new TextView(activity);
        status.setText(R.string.cl_export_disabled);
        status.setTextColor(Workspace.COLOR_MUTED);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, (int)(12*density), 0, 0);
        body.addView(status);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(scroll);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                Math.min(activity.getResources().getDisplayMetrics().widthPixels - (int)(24*density),
                    (int)(480*density)),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        icon.setOnClickListener(v -> {
            prefs.edit().putString("pending_icon_key", key).apply();
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            try {
                activity.startActivityForResult(intent, REQUEST_ICON);
            } catch (Exception ex) {
                Toast.makeText(activity, R.string.cl_export_icon_failed, Toast.LENGTH_LONG).show();
            }
        });

        save.setOnClickListener(v -> {
            String value = saveFields(activity, prefs, key,
                name, pack, version, versionCode);
            if (value == null) return;
            Toast.makeText(activity, R.string.cl_export_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
        prepareLove.setOnClickListener(v -> {
            String value = saveFields(activity, prefs, key,
                name, pack, version, versionCode);
            if (value == null) return;
            dialog.dismiss();
            if (onPrepareLove != null) onPrepareLove.onPrepare(value);
        });
    }

    private static String saveFields(Activity activity, SharedPreferences prefs,
                                     String key, EditText name, EditText pack,
                                     EditText version, EditText versionCode) {
        String n = name.getText().toString().trim();
        String p = pack.getText().toString().trim();
        String ver = version.getText().toString().trim();
        String code = versionCode.getText().toString().trim();
        int number;
        try { number = Integer.parseInt(code); }
        catch (NumberFormatException ex) { number = 0; }
        if (n.isEmpty() || n.contains("\n") || n.contains("\r")
                || !PACKAGE.matcher(p).matches() || ver.isEmpty() || number < 1) {
            Toast.makeText(activity, R.string.cl_export_invalid, Toast.LENGTH_LONG).show();
            return null;
        }
        prefs.edit().putString(key + "name", n).putString(key + "package", p)
            .putString(key + "version", ver).putString(key + "version_code", code)
            .apply();
        return n;
    }

    private static void section(Activity activity, LinearLayout body,
                                String title, String iconType) {
        float d = activity.getResources().getDisplayMetrics().density;
        View separator = new View(activity);
        separator.setBackgroundColor(Workspace.COLOR_BORDER);
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(d)));
        sep.topMargin = Math.round(18*d);
        sep.bottomMargin = Math.round(20*d);
        body.addView(separator, sep);
        LinearLayout heading = new LinearLayout(activity);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(symbol(activity, iconType, PURPLE), new LinearLayout.LayoutParams(
            Math.round(22*d), Math.round(22*d)));
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(Workspace.COLOR_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Math.round(10*d);
        heading.addView(label, lp);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headingParams.bottomMargin = Math.round(16*d);
        body.addView(heading, headingParams);
    }

    private static void addActionDescription(Activity activity, LinearLayout action,
                                              String description) {
        float d = activity.getResources().getDisplayMetrics().density;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) action.getLayoutParams();
        lp.height = Math.round(66*d);
        action.setLayoutParams(lp);
        TextView label = new TextView(activity);
        label.setText(description);
        label.setTextSize(12);
        label.setTextColor(Workspace.COLOR_MUTED);
        label.setPadding(0, Math.round(3*d), 0, 0);
        ((LinearLayout) action.getChildAt(1)).addView(label);
    }

    private static EditText field(Activity activity, LinearLayout body, int title,
                                  String value, boolean numeric) {
        TextView label = new TextView(activity);
        label.setText(title);
        label.setTextSize(13);
        label.setTextColor(Workspace.COLOR_MUTED);
        body.addView(label);
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setText(value);
        input.setTextSize(15);
        input.setTextColor(Workspace.COLOR_TEXT);
        input.setHintTextColor(Workspace.COLOR_MUTED);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER
            : InputType.TYPE_CLASS_TEXT);
        // EditText herda um fundo claro do tema Android; aplicar a paleta
        // CodeLove explicitamente para manter contraste nos dois temas.
        final float density = activity.getResources().getDisplayMetrics().density;
        GradientDrawable fieldBackground = new GradientDrawable();
        fieldBackground.setColor(Workspace.COLOR_FIELD);
        fieldBackground.setCornerRadius(10 * density);
        // Filled field, matching the approved design.
        input.setBackground(fieldBackground);
        input.setTextColor(Workspace.COLOR_TEXT);
        input.setHintTextColor(Workspace.COLOR_MUTED);
        input.setPadding(Math.round(14 * density), Math.round(12 * density),
            Math.round(12 * density), Math.round(10 * density));
        input.setSelectAllOnFocus(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int)(10 * activity.getResources().getDisplayMetrics().density);
        body.addView(input, lp);
        return input;
    }

    private static LinearLayout secondaryAction(Activity activity, String label, String iconType) {
        float density = activity.getResources().getDisplayMetrics().density;
        LinearLayout button = new LinearLayout(activity);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(Math.round(14*density), 0,
            Math.round(14*density), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Workspace.COLOR_PANEL);
        bg.setCornerRadius(12*density);
        bg.setStroke(Math.max(1, Math.round(density)), Workspace.COLOR_BORDER);
        button.setBackground(bg);
        button.setClickable(true);
        button.setFocusable(true);
        button.setContentDescription(label);
        button.addView(symbol(activity, iconType, iconType.equals("key") ? PURPLE : Workspace.COLOR_MUTED),
            new LinearLayout.LayoutParams(Math.round(20*density), Math.round(20*density)));
        LinearLayout textGroup = new LinearLayout(activity);
        textGroup.setOrientation(LinearLayout.VERTICAL);
        TextView text = new TextView(activity);
        text.setText(label);
        text.setTextSize(15);
        text.setTextColor(Workspace.COLOR_TEXT);
        textGroup.addView(text);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.leftMargin = Math.round(10*density);
        button.addView(textGroup, labelParams);
        TextView chevron = new TextView(activity);
        chevron.setText("›");
        chevron.setTextSize(25);
        chevron.setTextColor(Workspace.COLOR_MUTED);
        button.addView(chevron);
        return button;
    }

    private static View symbol(Activity activity, String type, int color) {
        return new View(activity) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                canvas.save();
                canvas.scale(getWidth()/24f, getHeight()/24f);
                paint.setColor(color);
                paint.setStrokeWidth(1.9f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setStyle(Paint.Style.STROKE);
                switch (type) {
                    case "settings":
                        canvas.drawLine(3, 7, 21, 7, paint);
                        canvas.drawLine(3, 17, 21, 17, paint);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(9, 7, 3, paint);
                        canvas.drawCircle(16, 17, 3, paint);
                        break;
                    case "shield":
                        android.graphics.Path shield = new android.graphics.Path();
                        shield.moveTo(12, 2); shield.lineTo(21, 6);
                        shield.lineTo(20, 15); shield.quadTo(18, 20, 12, 22);
                        shield.quadTo(6, 20, 4, 15); shield.lineTo(3, 6);
                        shield.close(); canvas.drawPath(shield, paint);
                        canvas.drawLine(9, 12, 11, 14, paint);
                        canvas.drawLine(11, 14, 16, 9, paint);
                        break;
                    case "key":
                        canvas.drawCircle(8, 9, 4, paint);
                        canvas.drawLine(11, 12, 20, 21, paint);
                        canvas.drawLine(16, 17, 19, 14, paint);
                        canvas.drawLine(18, 19, 21, 16, paint);
                        break;
                    case "image":
                        canvas.drawRoundRect(new RectF(3, 4, 21, 20), 2, 2, paint);
                        canvas.drawCircle(9, 9, 2, paint);
                        canvas.drawLine(4, 18, 10, 12, paint);
                        canvas.drawLine(10, 12, 14, 16, paint);
                        canvas.drawLine(14, 16, 17, 13, paint);
                        canvas.drawLine(17, 13, 21, 17, paint);
                        break;
                    case "archive":
                        canvas.drawRoundRect(new RectF(5, 3, 19, 21), 2, 2, paint);
                        canvas.drawLine(12, 3, 12, 15, paint);
                        canvas.drawLine(10, 6, 14, 6, paint);
                        canvas.drawLine(10, 10, 14, 10, paint);
                        canvas.drawLine(10, 15, 14, 15, paint);
                        break;
                    default:
                        canvas.drawRect(4, 5, 20, 19, paint);
                        canvas.drawLine(4, 9, 20, 9, paint);
                        canvas.drawLine(8, 5, 8, 19, paint);
                }
                canvas.restore();
            }
        };
    }

    private static TextView action(Activity activity, String label) {
        TextView button = new TextView(activity);
        button.setText(label);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextColor(0xFF17131F);
        button.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(PURPLE);
        bg.setCornerRadius(12 * activity.getResources().getDisplayMetrics().density);
        button.setBackground(bg);
        button.setPadding(12, 12, 12, 12);
        return button;
    }
}
