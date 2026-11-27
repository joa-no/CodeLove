package org.love2d.android;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Preview-only renderer preference. Exported games retain their own configuration. */
final class RuntimeRenderer {
    private static final String PREF = "runtime_renderer";
    static final String OPENGL = "opengl";
    static final String VULKAN = "vulkan";

    private RuntimeRenderer() {}

    static boolean supportsVulkan(Context context) {
        // LÖVE's Android Vulkan backend rejects devices below Vulkan 1.1.
        return Build.VERSION.SDK_INT >= 24
            && context.getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, 0x00401000);
    }

    static String selected(Context context) {
        String saved = context.getSharedPreferences("codelove", Context.MODE_PRIVATE)
            .getString(PREF, OPENGL);
        return VULKAN.equals(saved) && supportsVulkan(context) ? VULKAN : OPENGL;
    }

    static void select(Context context, String renderer) {
        String value = VULKAN.equals(renderer) && supportsVulkan(context) ? VULKAN : OPENGL;
        context.getSharedPreferences("codelove", Context.MODE_PRIVATE)
            .edit().putString(PREF, value).apply();
    }

    static String launchArgument(Context context) {
        // The pinned LÖVE version tries Vulkan first, then OpenGL if creating
        // the Vulkan graphics instance fails. This is not a crash recovery.
        return VULKAN.equals(selected(context)) ? "vulkan,opengl" : "opengl";
    }
}
