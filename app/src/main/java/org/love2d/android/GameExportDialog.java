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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.regex.Pattern;

/**
 * Configuration-only UI. This intentionally does NOT claim to compile an APK.
 * The compiler transport and game signing still need to be implemented.
 */
final class GameExportDialog {
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
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) activity.getContentResolver().takePersistableUriPermission(uri, flags);
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("selected_icon", uri.toString()).apply();
            Toast.makeText(activity, R.string.cl_export_icon_selected, Toast.LENGTH_SHORT).show();
        } catch (SecurityException ex) {
            Toast.makeText(activity, R.string.cl_export_icon_failed, Toast.LENGTH_LONG).show();
        }
        return true;
    }

    static void show(Activity activity, String projectId, String projectName) {
        if (activity.isFinishing()) return;
        final SharedPreferences prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final String key = Integer.toHexString(projectId.hashCode()) + "_";
        final float density = activity.getResources().getDisplayMetrics().density;
        final int padding = (int) (20 * density + .5f);
        final boolean light = Workspace.COLOR_PANEL == Color.WHITE;

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
        body.addView(heading);

        TextView note = new TextView(activity);
        note.setText(R.string.cl_export_not_ready);
        note.setTextSize(13);
        note.setTextColor(Workspace.COLOR_MUTED);
        note.setPadding(0, (int)(8*density), 0, (int)(12*density));
        body.addView(note);

        EditText name = field(activity, body, R.string.cl_export_name,
            prefs.getString(key + "name", projectName), false);
        EditText pack = field(activity, body, R.string.cl_export_package,
            prefs.getString(key + "package", "com.exemplo.meujogo"), false);
        EditText version = field(activity, body, R.string.cl_export_version,
            prefs.getString(key + "version", "1.0.0"), false);
        EditText versionCode = field(activity, body, R.string.cl_export_version_code,
            prefs.getString(key + "version_code", "1"), true);

        TextView icon = action(activity, activity.getString(R.string.cl_export_icon));
        body.addView(icon);
        TextView iconValue = new TextView(activity);
        String selectedIcon = prefs.getString("selected_icon", "");
        iconValue.setText(selectedIcon.isEmpty()
            ? activity.getString(R.string.cl_export_icon_none)
            : activity.getString(R.string.cl_export_icon_selected));
        iconValue.setTextSize(12);
        iconValue.setTextColor(Workspace.COLOR_MUTED);
        body.addView(iconValue);

        TextView save = action(activity, activity.getString(R.string.cl_export_save));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, (int)(44*density));
        saveParams.topMargin = (int)(15*density);
        body.addView(save, saveParams);

        TextView status = new TextView(activity);
        status.setText(R.string.cl_export_disabled);
        status.setTextColor(Workspace.COLOR_MUTED);
        status.setTextSize(12);
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
                return;
            }
            prefs.edit().putString(key + "name", n).putString(key + "package", p)
                .putString(key + "version", ver).putString(key + "version_code", code)
                .apply();
            Toast.makeText(activity, R.string.cl_export_saved, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int)(10 * activity.getResources().getDisplayMetrics().density);
        body.addView(input, lp);
        return input;
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
