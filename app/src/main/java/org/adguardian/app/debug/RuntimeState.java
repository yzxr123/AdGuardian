package org.adguardian.app.debug;

public final class RuntimeState {
    private static volatile String foregroundPackage = "";
    private static volatile String foregroundActivity = "";

    private RuntimeState() {
    }

    public static void updateForeground(String packageName, String activity) {
        foregroundPackage = packageName == null ? "" : packageName;
        foregroundActivity = activity == null ? "" : activity;
    }

    public static String foregroundPackage() {
        return foregroundPackage;
    }

    public static String foregroundActivity() {
        return foregroundActivity;
    }
}
