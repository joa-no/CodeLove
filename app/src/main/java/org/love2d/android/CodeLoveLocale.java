package org.love2d.android;

import android.content.Context;
import android.content.res.Configuration;
import java.util.Locale;

/** Contexto localizado apenas para os controles do CodeLove. */
final class CodeLoveLocale {
    private CodeLoveLocale() { }

    static Context wrap(Context base) {
        String selected = base.getSharedPreferences("codelove", Context.MODE_PRIVATE)
            .getString("workspace_language", "auto");
        if (!"pt".equals(selected) && !"en".equals(selected) && !"es".equals(selected)) {
            return base; // Usa automaticamente o idioma do Android.
        }
        Configuration configuration = new Configuration(
            base.getResources().getConfiguration());
        configuration.setLocale(new Locale(selected));
        return base.createConfigurationContext(configuration);
    }
}
