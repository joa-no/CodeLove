package org.love2d.android;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Arrays;

/** Editor-only dialogs using the same colors, spacing and corners as CodeLove. */
final class CodeLoveModal {
    interface PasswordAction {
        /** The recipient must clear the char[] once finished. */
        void onPassword(char[] secret);
    }

    private CodeLoveModal() {}

    static void message(Activity activity, String title, String detail, String label) {
        if (notReady(activity)) return;
        LinearLayout body = body(activity, title, detail);
        TextView okay = button(activity, label, true);
        LinearLayout.LayoutParams buttonParams =
                new LinearLayout.LayoutParams(-1, dp(activity, 46));
        buttonParams.topMargin = dp(activity, 20);
        body.addView(okay, buttonParams);
        Dialog dialog = show(activity, body);
        okay.setOnClickListener(v -> dialog.dismiss());
    }

    static void confirm(Activity activity, String title, String detail,
                        String label, Runnable onConfirm, Runnable onCancel) {
        if (notReady(activity) || onConfirm == null || onCancel == null) return;
        LinearLayout body = body(activity, title, detail);
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(activity, activity.getString(R.string.cl_cancel), false);
        TextView confirm = button(activity, label, true);
        LinearLayout.LayoutParams cancelParams =
                new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        cancelParams.rightMargin = dp(activity, 10);
        actions.addView(cancel, cancelParams);
        actions.addView(confirm, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(activity, 20);
        body.addView(actions, actionsParams);

        Dialog dialog = show(activity, body);
        dialog.setOnCancelListener(d -> onCancel.run());
        cancel.setOnClickListener(v -> dialog.cancel());
        confirm.setOnClickListener(v -> {
            dialog.dismiss();
            onConfirm.run();
        });
    }

    static void choose(Activity activity, String title, String detail,
                       String firstLabel, Runnable firstAction,
                       String secondLabel, Runnable secondAction) {
        if (notReady(activity)) return;
        LinearLayout body = body(activity, title, detail);
        TextView first = button(activity, firstLabel, true);
        TextView second = button(activity, secondLabel, false);
        TextView cancel = button(activity, activity.getString(R.string.cl_cancel), false);
        for (TextView view : new TextView[]{first, second, cancel}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 46));
            params.topMargin = dp(activity, 10);
            body.addView(view, params);
        }
        Dialog dialog = show(activity, body);
        first.setOnClickListener(v -> { dialog.dismiss(); firstAction.run(); });
        second.setOnClickListener(v -> { dialog.dismiss(); secondAction.run(); });
        cancel.setOnClickListener(v -> dialog.dismiss());
    }

    // Keep the update offer, including its actions, scrollable on small screens.
    static Dialog updateOffer(Activity activity, String detail, Runnable download,
                              Runnable skip) {
        if (notReady(activity)) return null;
        LinearLayout body = body(activity, activity.getString(R.string.cl_update_available), detail);
        TextView first = button(activity, activity.getString(R.string.cl_update_download), true);
        TextView second = button(activity, activity.getString(R.string.cl_update_skip), false);
        TextView later = button(activity, activity.getString(R.string.cl_update_later), false);
        for (TextView view : new TextView[]{first, second, later}) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 46));
            params.topMargin = dp(activity, 10);
            body.addView(view, params);
        }
        android.widget.ScrollView scroll = new android.widget.ScrollView(activity);
        scroll.addView(body, new android.widget.ScrollView.LayoutParams(-1, -2));
        Dialog dialog = show(activity, scroll);
        first.setOnClickListener(v -> { dialog.dismiss(); download.run(); });
        second.setOnClickListener(v -> { dialog.dismiss(); skip.run(); });
        later.setOnClickListener(v -> dialog.dismiss());
        return dialog;
    }

    static Dialog updateProgress(Activity activity, TextView status, Runnable cancel) {
        LinearLayout body = body(activity, activity.getString(R.string.cl_update_download), "");
        status.setTextColor(Workspace.COLOR_MUTED);
        status.setTextSize(14);
        body.addView(status);
        TextView stop = button(activity, activity.getString(R.string.cl_cancel), false);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(activity, 46));
        params.topMargin = dp(activity, 16);
        body.addView(stop, params);
        Dialog dialog = show(activity, body);
        dialog.setCancelable(false);
        stop.setOnClickListener(v -> { cancel.run(); dialog.dismiss(); });
        return dialog;
    }

    static Dialog progress(Activity activity, String title, String detail) {
        LinearLayout content = body(activity, title, detail);
        Dialog dialog = show(activity, content);
        dialog.setCancelable(false);
        return dialog;
    }

    static void password(Activity activity, String title, String detail,
                         String label, PasswordAction action) {
        if (notReady(activity) || action == null) return;
        LinearLayout body = body(activity, title, detail);
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setTextColor(Workspace.COLOR_TEXT);
        field.setHintTextColor(Workspace.COLOR_MUTED);
        field.setHint(activity.getString(R.string.cl_locale_sign_51));
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setPadding(dp(activity, 14), 0, dp(activity, 14), 0);
        field.setBackground(background(activity, Workspace.COLOR_FIELD, 12, true));
        LinearLayout.LayoutParams fieldParams =
                new LinearLayout.LayoutParams(-1, dp(activity, 52));
        fieldParams.topMargin = dp(activity, 14);
        body.addView(field, fieldParams);

        TextView error = text(activity, "", 12, Workspace.COLOR_DANGER);
        LinearLayout.LayoutParams errorParams = new LinearLayout.LayoutParams(-1, -2);
        errorParams.topMargin = dp(activity, 8);
        body.addView(error, errorParams);

        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView cancel = button(activity, activity.getString(R.string.cl_cancel), false);
        TextView confirm = button(activity, label, true);
        LinearLayout.LayoutParams cancelParams =
                new LinearLayout.LayoutParams(0, dp(activity, 46), 1f);
        cancelParams.rightMargin = dp(activity, 10);
        actions.addView(cancel, cancelParams);
        actions.addView(confirm, new LinearLayout.LayoutParams(0, dp(activity, 46), 1f));
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(activity, 16);
        body.addView(actions, actionsParams);
        Dialog dialog = show(activity, body);

        cancel.setOnClickListener(v -> { field.setText(""); dialog.dismiss(); });
        confirm.setOnClickListener(v -> {
            char[] secret = field.getText().toString().toCharArray();
            if (secret.length == 0) {
                error.setText(activity.getString(R.string.cl_locale_sign_52));
                Arrays.fill(secret, '\0');
                return;
            }
            field.setText("");
            dialog.dismiss();
            try {
                action.onPassword(secret);
            } catch (RuntimeException ex) {
                Arrays.fill(secret, '\0');
                throw ex;
            }
        });
    }

    private static boolean notReady(Activity activity) {
        return activity == null || activity.isDestroyed() || activity.isFinishing();
    }

    private static LinearLayout body(Activity activity, String title, String detail) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 20), dp(activity, 22),
                dp(activity, 20), dp(activity, 20));
        body.setBackground(background(activity, Workspace.COLOR_PANEL, 18, false));
        body.setClipToOutline(true);
        TextView heading = text(activity, title, 20, Workspace.COLOR_TEXT);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        body.addView(heading);
        TextView description = text(activity, detail, 14, Workspace.COLOR_MUTED);
        description.setLineSpacing(dp(activity, 2), 1f);
        description.setPadding(0, dp(activity, 12), 0, 0);
        body.addView(description);
        return body;
    }

    private static Dialog show(Activity activity, android.view.View content) {
        Dialog dialog = new Dialog(activity);
        dialog.setContentView(content);
        dialog.setCanceledOnTouchOutside(false);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.65f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.min(width - dp(activity, 32), dp(activity, 440)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    private static TextView button(Activity activity, String name, boolean primary) {
        TextView view = text(activity, name, 15,
                primary ? Workspace.COLOR_ACCENT_TEXT : Workspace.COLOR_TEXT);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setGravity(Gravity.CENTER);
        view.setBackground(background(activity,
                primary ? Workspace.COLOR_FOLDER : Workspace.COLOR_FIELD, 12, !primary));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    private static TextView text(Activity activity, String value, int size, int color) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        return view;
    }

    private static GradientDrawable background(Activity activity, int color,
                                               int radius, boolean border) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(activity, radius));
        if (border) bg.setStroke(Math.max(1, dp(activity, 1)), Workspace.COLOR_BORDER);
        return bg;
    }

    private static int dp(Activity activity, float size) {
        return Math.round(size * activity.getResources().getDisplayMetrics().density);
    }
}
