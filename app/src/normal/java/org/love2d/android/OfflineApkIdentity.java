package org.love2d.android;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Validated Android identity for a future offline manifest/resource rewrite.
 * This class does not mutate an APK. Do not report the template's fixed
 * package/name/version as personalized until the rewrite is implemented.
 */
final class OfflineApkIdentity {
    private static final Pattern SEGMENT =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern VERSION_NAME =
            Pattern.compile("[0-9A-Za-z][0-9A-Za-z._+-]{0,63}");

    final String packageName;
    final String displayName;
    final String versionName;
    final int versionCode;

    private OfflineApkIdentity(String packageName, String displayName,
                               String versionName, int versionCode) {
        this.packageName = packageName;
        this.displayName = displayName;
        this.versionName = versionName;
        this.versionCode = versionCode;
    }

    static OfflineApkIdentity create(String packageName, String displayName,
                                     String versionName, int versionCode)
            throws IOException {
        if (packageName == null || displayName == null || versionName == null) {
            throw new IOException("Missing Android game identity");
        }
        String pkg = packageName.trim();
        if (pkg.length() > 223 || pkg.length() < 3 || pkg.indexOf('.') < 0) {
            throw new IOException("Invalid Android package identifier");
        }
        String[] segments = pkg.split("\\.", -1);
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IOException("Invalid Android package segment: " + segment);
            }
        }
        String label = displayName.trim();
        if (label.isEmpty() || label.length() > 80) {
            throw new IOException("Game name must contain 1 to 80 characters");
        }
        for (int i = 0; i < label.length(); i++) {
            char c = label.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IOException("Game name contains control characters");
            }
        }
        String version = versionName.trim();
        if (!VERSION_NAME.matcher(version).matches() || versionCode <= 0) {
            throw new IOException("Invalid Android game version");
        }
        return new OfflineApkIdentity(pkg, label, version, versionCode);
    }
}
