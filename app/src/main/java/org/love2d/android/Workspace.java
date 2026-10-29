/*
 * CodeLove - protótipo P1 da "área de trabalho".
 *
 * Objetivo deste arquivo: provar que dá para ter, na MESMA tela do app,
 * um painel lateral (que abre/fecha e pode ficar à esquerda ou à direita)
 * e a área do jogo do LÖVE ocupando o espaço que sobra.
 *
 * Por enquanto o painel só mostra um texto de exemplo. Depois ele vira a
 * árvore de pastas e arquivos do projeto.
 *
 * Baseado no LÖVE para Android (licença zlib). Versão alterada.
 */

package org.love2d.android;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

public final class Workspace {
    private static final int PANEL_WIDTH_DP = 240;

    private final Activity activity;
    private final RelativeLayout root;
    private final View surface;
    private final int panelWidth;

    private LinearLayout panel;
    private Button toggle;
    private boolean open = true;
    private boolean onLeft = true;

    private Workspace(Activity activity, RelativeLayout root, View surface) {
        this.activity = activity;
        this.root = root;
        this.surface = surface;
        this.panelWidth = dp(PANEL_WIDTH_DP);
    }

    /**
     * Liga a área de trabalho ao layout do SDL.
     * "layout" é o layout que o SDL cria; "surface" é a superfície onde o LÖVE desenha.
     */
    public static void attach(Activity activity, ViewGroup layout, View surface) {
        if (activity == null || surface == null || !(layout instanceof RelativeLayout)) {
            return;
        }
        new Workspace(activity, (RelativeLayout) layout, surface).build();
    }

    private int dp(int value) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }

    private void build() {
        int pad = dp(12);

        panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xFF1E1E24);
        panel.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(activity);
        title.setText("CodeLove");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        panel.addView(title);

        TextView tree = new TextView(activity);
        tree.setText("\n\u25BE meu_jogo\n      main.lua\n      conf.lua\n    \u25BE imagens\n          player.png");
        tree.setTextColor(0xFFCCCCCC);
        tree.setTextSize(14);
        tree.setTypeface(Typeface.MONOSPACE);
        panel.addView(tree);

        Button swap = new Button(activity);
        swap.setText("Trocar lado");
        swap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLeft = !onLeft;
                applyLayout();
            }
        });
        panel.addView(swap);

        toggle = new Button(activity);
        toggle.setMinWidth(0);
        toggle.setMinimumWidth(0);
        toggle.setPadding(dp(10), 0, dp(10), 0);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                open = !open;
                applyLayout();
            }
        });

        root.addView(panel);
        root.addView(toggle);
        applyLayout();
    }

    /** Reposiciona painel, botão e a área do jogo conforme o estado atual. */
    private void applyLayout() {
        // Área do jogo: ocupa tudo, menos a largura do painel (se estiver aberto).
        RelativeLayout.LayoutParams surfaceLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (open) {
            if (onLeft) {
                surfaceLp.leftMargin = panelWidth;
            } else {
                surfaceLp.rightMargin = panelWidth;
            }
        }
        surface.setLayoutParams(surfaceLp);

        // Painel lateral.
        RelativeLayout.LayoutParams panelLp = new RelativeLayout.LayoutParams(
            panelWidth, ViewGroup.LayoutParams.MATCH_PARENT);
        panelLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        panel.setLayoutParams(panelLp);
        panel.setVisibility(open ? View.VISIBLE : View.GONE);

        // Botão de abrir/fechar: fica no canto de cima, junto à borda interna do painel.
        RelativeLayout.LayoutParams toggleLp = new RelativeLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        toggleLp.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        toggleLp.addRule(onLeft ? RelativeLayout.ALIGN_PARENT_LEFT : RelativeLayout.ALIGN_PARENT_RIGHT);
        int edge = open ? panelWidth : 0;
        if (onLeft) {
            toggleLp.leftMargin = edge;
        } else {
            toggleLp.rightMargin = edge;
        }
        toggle.setLayoutParams(toggleLp);

        // Seta aponta para onde o painel vai se mover ao tocar.
        boolean pointLeft = (onLeft == open);
        toggle.setText(pointLeft ? "<" : ">");
    }
}
