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

    CodeLoveConsolePanel(Context context, Runnable collapse,
                         Runnable clearLog, Runnable reloadProject) {
        super(context);
        setOrientation(VERTICAL);
        setPadding(dp(context, 12), dp(context, 12),
            dp(context, 12), dp(context, 12));
        setBackground(rounded(context, Workspace.COLOR_PANEL, 16, true));
        setClickable(true);

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        Workspace.IconView terminal = new Workspace.IconView(context,
            Workspace.IconView.CONSOLE, Workspace.COLOR_FOLDER);
        header.addView(terminal, new LayoutParams(dp(context, 20), dp(context, 20)));

        TextView title = new TextView(context);
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
