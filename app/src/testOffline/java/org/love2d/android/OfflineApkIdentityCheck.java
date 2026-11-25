package org.love2d.android;

import java.io.IOException;

/** JVM-only contract test; no Android device or Gradle needed. */
public final class OfflineApkIdentityCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        accept("com.example.game", "My Game", "1.0.0", 1);
        accept("org.example.game_2", "Jogo Árvore", "2.1-beta", 42);
        reject("single", "Game", "1.0", 1);
        reject("com..game", "Game", "1.0", 1);
        reject("1com.example", "Game", "1.0", 1);
        reject("com.example.game", " ", "1.0", 1);
        reject("com.example.game", "Game" + (char) 10 + "Other", "1.0", 1);
        reject("com.example.game", "Game", "1 0", 1);
        reject("com.example.game", "Game", "1.0", 0);
        reject("com.example.game", "Game", "1.0", -1);
        System.out.println("Offline APK identity checks passed: " + checks);
    }

    private static void accept(String pkg, String label, String version, int code)
            throws IOException {
        OfflineApkIdentity identity = OfflineApkIdentity.create(pkg, label, version, code);
        if (!pkg.equals(identity.packageName) || !label.equals(identity.displayName)
                || !version.equals(identity.versionName) || code != identity.versionCode) {
            throw new AssertionError("Unexpected normalized game identity");
        }
        checks++;
    }

    private static void reject(String pkg, String label, String version, int code)
            throws Exception {
        try {
            OfflineApkIdentity.create(pkg, label, version, code);
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Invalid game identity accepted: " + pkg);
    }
}
