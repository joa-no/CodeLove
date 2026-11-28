package org.love2d.android;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Console visual compartilhado pelo editor e pela execucao SDL.
 * A saida continua pertencendo ao runtime (console.txt); esta classe
 * desenha somente a interface e nao cria arquivos nem executa jogos.
 */
final class CodeLoveConsolePanel extends LinearLayout {
    private final TextView logText;
    private final ScrollView logScroll;
    private TextView runtimeStats;
    private final LinearLayout.LayoutParams runtimeStatsParams;
    private final LinearLayout header;
    private final TextView title;
    private final Workspace.IconView terminal;
    private TextView opacityControl;

    interface DragAction {
        void start();
        void move(float dx, float dy);
        void end();
    }

    CodeLoveConsolePanel(Context context, Runnable collapse,
                         Runnable clearLog, Runnable reloadProject) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(context, 12), dp(context, 12),
            dp(context, 12), dp(context, 12));
        setBackground(rounded(context, Workspace.COLOR_PANEL, 16, true));
        setClickable(true);

        header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        terminal = new Workspace.IconView(context,
            Workspace.IconView.CONSOLE, Workspace.COLOR_FOLDER);
        header.addView(terminal, new LayoutParams(dp(context, 20), dp(context, 20)));

        title = new TextView(context);
        title.setText(CodeLoveLocale.wrap(context).getString(R.string.cl_console));
        title.setTextColor(Workspace.COLOR_TEXT);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LayoutParams titleLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(context, 9);
        header.addView(title, titleLp);

        if (clearLog != null) {
            TextView clear = new TextView(context);
            clear.setText(CodeLoveLocale.wrap(context).getString(R.string.cl_clear));
            clear.setTextColor(Workspace.COLOR_TEXT);
            clear.setTextSize(12);
            clear.setGravity(Gravity.CENTER);
            clear.setPadding(dp(context, 11), 0, dp(context, 11), 0);
            clear.setBackground(rounded(context, Workspace.COLOR_FIELD, 10, false));
            clear.setOnClickListener(v -> clearLog.run());
            header.addView(clear, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 32)));
        }

        if (reloadProject != null) {
            LinearLayout reload = iconButton(context, Workspace.IconView.RELOAD,
                Workspace.COLOR_TEXT, reloadProject,
                CodeLoveLocale.wrap(context).getString(R.string.cl_reload_project), 32);
            LayoutParams lp = new LayoutParams(dp(context, 32), dp(context, 32));
            lp.leftMargin = dp(context, 7);
            header.addView(reload, lp);
        }

        LinearLayout collapseButton = iconButton(context,
            Workspace.IconView.CHEVRON_DOWN, Workspace.COLOR_MUTED, collapse,
            CodeLoveLocale.wrap(context).getString(R.string.cl_collapse_console), 32);
        LayoutParams collapseLp = new LayoutParams(dp(context, 32), dp(context, 32));
        collapseLp.leftMargin = dp(context, 7);
        header.addView(collapseButton, collapseLp);
        addView(header, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 34)));
        runtimeStatsParams = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        runtimeStatsParams.topMargin = dp(context, 3);

        View separator = new View(context);
        separator.setBackgroundColor(Workspace.COLOR_BORDER);
        LayoutParams separatorLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 1)));
        separatorLp.topMargin = dp(context, 9);
        separatorLp.bottomMargin = dp(context, 8);
        addView(separator, separatorLp);

        logText = new TextView(context);
        logText.setTypeface(Typeface.MONOSPACE);
        logText.setTextSize(12);
        logText.setTextColor(Workspace.COLOR_TEXT);
        logText.setTextIsSelectable(true);
        logText.setPadding(dp(context, 2), dp(context, 2),
            dp(context, 2), dp(context, 2));

        logScroll = new ScrollView(context);
        logScroll.setFillViewport(true);
        logScroll.addView(logText, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(logScroll, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    /** Floating controls are exclusive to the visual runtime console. */
    void enableFloatingControls(DragAction move, DragAction resize,
                                Runnable changeOpacity, int opacity) {
        Context context = getContext();
        Context localized = CodeLoveLocale.wrap(context);
        setPadding(dp(context, 8), dp(context, 6), dp(context, 8), dp(context, 6));
        title.setContentDescription(localized.getString(R.string.cl_console_move));
        title.setOnTouchListener(dragListener(move));
        terminal.setOnTouchListener(dragListener(move));
        header.setOnTouchListener(dragListener(move));

        opacityControl = new TextView(context);
        opacityControl.setTextColor(Workspace.COLOR_TEXT);
        opacityControl.setTextSize(11);
        opacityControl.setGravity(Gravity.CENTER);
        opacityControl.setBackground(rounded(context, Workspace.COLOR_FIELD, 8, false));
        opacityControl.setOnClickListener(v -> changeOpacity.run());
        LayoutParams opacityLp = new LayoutParams(dp(context, 44), dp(context, 32));
        header.addView(opacityControl, header.getChildCount() - 1, opacityLp);

        // A dedicated grip prevents resize gestures from stealing log scrolling
        // or selection. Its narrow gutter leaves the rest of the logs usable.
        removeView(logScroll);
        android.widget.FrameLayout content = new android.widget.FrameLayout(context);
        android.widget.FrameLayout.LayoutParams logLp =
            new android.widget.FrameLayout.LayoutParams(-1, -1);
        logLp.rightMargin = dp(context, 28);
        content.addView(logScroll, logLp);
        TextView grip = new TextView(context);
        grip.setText("↘");
        grip.setTextSize(18);
        grip.setGravity(Gravity.CENTER);
        grip.setTextColor(Workspace.COLOR_TEXT);
        grip.setBackground(rounded(context, Workspace.COLOR_FIELD, 8, false));
        grip.setContentDescription(localized.getString(R.string.cl_console_resize));
        grip.setOnTouchListener(dragListener(resize));
        android.widget.FrameLayout.LayoutParams gripLp =
            new android.widget.FrameLayout.LayoutParams(dp(context, 28), dp(context, 28),
                Gravity.BOTTOM | Gravity.RIGHT);
        content.addView(grip, gripLp);
        addView(content, new LayoutParams(-1, 0, 1f));
        setPanelOpacity(opacity);
    }

    void setPanelOpacity(int percent) {
        int alpha = Math.round(255 * percent / 100f);
        setBackground(rounded(getContext(), (Workspace.COLOR_PANEL & 0x00FFFFFF)
            | (alpha << 24), 16, true));
        if (opacityControl != null) {
            String value = percent + "%";
            opacityControl.setText(value);
            opacityControl.setContentDescription(CodeLoveLocale.wrap(getContext())
                .getString(R.string.cl_console_opacity, percent));
        }
    }

    private static View.OnTouchListener dragListener(DragAction action) {
        return new View.OnTouchListener() {
            private float startX, startY;
            private int pointer = -1;
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                switch (event.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        pointer = event.getPointerId(0);
                        startX = event.getRawX();
                        startY = event.getRawY();
                        action.start();
                        return true;
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (pointer != -1 && event.getPointerId(0) == pointer) {
                            action.move(event.getRawX() - startX, event.getRawY() - startY);
                        }
                        return true;
                    case android.view.MotionEvent.ACTION_POINTER_UP:
                        if (event.getPointerId(event.getActionIndex()) != pointer) return true;
                        // End when the original finger leaves, rather than jumping
                        // to the coordinates of another finger.
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (pointer != -1) {
                            pointer = -1;
                            action.end();
                        }
                        return true;
                    default:
                        return true;
                }
            }
        };
    }

    void enableRuntimeStats() {
        if (runtimeStats != null) return;
        runtimeStats = new TextView(getContext());
        runtimeStats.setTextSize(11);
        runtimeStats.setTextColor(Workspace.COLOR_MUTED);
        runtimeStats.setTypeface(Typeface.MONOSPACE);
        runtimeStats.setGravity(Gravity.START);
        addView(runtimeStats, 1, runtimeStatsParams);
        setRuntimeStats("—", "—", "—");
    }

    void setRuntimeStats(String fps, String frameTime, String renderer) {
        if (runtimeStats == null) return;
        String value = CodeLoveLocale.wrap(getContext()).getString(
            R.string.cl_console_metrics, fps, frameTime, renderer);
        if (!value.contentEquals(runtimeStats.getText())) runtimeStats.setText(value);
    }

    TextView output() { return logText; }

    ScrollView scrolling() { return logScroll; }

    void setOutput(String value) {
        logText.setText(value);
        logScroll.post(() -> logScroll.scrollTo(0,
            Math.max(0, logText.getHeight() - logScroll.getHeight())));
    }

    static LinearLayout iconButton(Context context, int icon,
                                   int color, Runnable action,
                                   String description, int sizeDp) {
        LinearLayout button = new LinearLayout(context);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(context, Workspace.COLOR_FIELD, 12, false));
        Workspace.IconView iconView = new Workspace.IconView(context, icon, color);
        button.addView(iconView, new LayoutParams(dp(context, 20), dp(context, 20)));
        button.setContentDescription(description);
        button.setOnClickListener(v -> action.run());
        button.setMinimumWidth(dp(context, sizeDp));
        button.setMinimumHeight(dp(context, sizeDp));
        return button;
    }

    private static GradientDrawable rounded(Context context, int color,
                                            int radiusDp, boolean border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radiusDp));
        if (border) shape.setStroke(Math.max(1, dp(context, 1)),
            Workspace.COLOR_BORDER);
        return shape;
    }

    private static int dp(Context context, int amount) {
        return Math.round(amount * context.getResources().getDisplayMetrics().density);
    }
}
