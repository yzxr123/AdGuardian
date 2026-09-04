package org.adguardian.app.vision;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

public final class VisionProfileStore {
    private static final String FILE_NAME = "ad_guardian_vision_profiles";
    private static final int DOWNGRADE_AFTER_NO_EVIDENCE_SESSIONS = 15;
    private static final int FULL_PROBE_INTERVAL_WHEN_DOWNGRADED = 5;

    private final Context context;
    private final SharedPreferences preferences;

    public VisionProfileStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public SessionPlan beginSession(String packageName) {
        long installedVersion = installedVersion(packageName);
        long storedVersion = preferences.getLong(key(packageName, "version"), -1L);
        if (storedVersion != installedVersion) {
            preferences.edit()
                    .putLong(key(packageName, "version"), installedVersion)
                    .putInt(key(packageName, "sessions"), 0)
                    .putInt(key(packageName, "no_evidence"), 0)
                    .apply();
        }

        int sessions = preferences.getInt(key(packageName, "sessions"), 0) + 1;
        int noEvidence = preferences.getInt(key(packageName, "no_evidence"), 0);
        preferences.edit().putInt(key(packageName, "sessions"), sessions).apply();

        boolean downgraded = noEvidence >= DOWNGRADE_AFTER_NO_EVIDENCE_SESSIONS;
        boolean fullProbe = !downgraded || sessions % FULL_PROBE_INTERVAL_WHEN_DOWNGRADED == 0;
        return new SessionPlan(fullProbe, downgraded, sessions, noEvidence);
    }

    public void markEvidence(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        preferences.edit().putInt(key(packageName, "no_evidence"), 0).apply();
    }

    public void finishSession(String packageName, boolean evidenceSeen) {
        if (packageName == null || packageName.isEmpty()) {
            return;
        }
        int noEvidence = preferences.getInt(key(packageName, "no_evidence"), 0);
        if (evidenceSeen) {
            noEvidence = 0;
        } else if (noEvidence < 10_000) {
            noEvidence++;
        }
        preferences.edit().putInt(key(packageName, "no_evidence"), noEvidence).apply();
    }

    private long installedVersion(String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return info.getLongVersionCode();
            }
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException ignored) {
            return -1L;
        }
    }

    private String key(String packageName, String suffix) {
        return packageName + '|' + suffix;
    }

    public static final class SessionPlan {
        public final boolean fullProbe;
        public final boolean downgraded;
        public final int sessionNumber;
        public final int noEvidenceStreak;

        private SessionPlan(boolean fullProbe, boolean downgraded, int sessionNumber, int noEvidenceStreak) {
            this.fullProbe = fullProbe;
            this.downgraded = downgraded;
            this.sessionNumber = sessionNumber;
            this.noEvidenceStreak = noEvidenceStreak;
        }
    }
}
