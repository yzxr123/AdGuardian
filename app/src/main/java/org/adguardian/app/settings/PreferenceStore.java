package org.adguardian.app.settings;

import android.content.Context;
import android.content.SharedPreferences;

import org.adguardian.app.engine.AdType;

public final class PreferenceStore {
    private static final String FILE_NAME = "ad_guardian_settings";
    private static final String MASTER_KEY = "master";
    private static final String TYPE_PREFIX = "type.";

    private PreferenceStore() {
    }

    public static boolean isMasterEnabled(Context context) {
        return preferences(context).getBoolean(MASTER_KEY, true);
    }

    public static void setMasterEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(MASTER_KEY, enabled).apply();
    }

    public static boolean isTypeEnabled(Context context, AdType type) {
        return preferences(context).getBoolean(TYPE_PREFIX + type.preferenceKey(), true);
    }

    public static void setTypeEnabled(Context context, AdType type, boolean enabled) {
        preferences(context).edit().putBoolean(TYPE_PREFIX + type.preferenceKey(), enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }
}
