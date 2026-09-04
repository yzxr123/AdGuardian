package org.adguardian.app.engine;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PackagePolicy {
    private static final Set<String> STATIC_BROWSERS = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "org.mozilla.firefox",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.mi.globalbrowser",
            "com.android.browser",
            "com.heytap.browser",
            "com.coloros.browser",
            "com.vivo.browser",
            "com.huawei.browser",
            "com.tencent.mtt",
            "com.baidu.browser.apps"
    ));

    private static final Set<String> BROWSER_EXCEPTIONS = new HashSet<>(Arrays.asList(
            "com.UCMobile",
            "com.ucmobile.lite",
            "com.quark.browser"
    ));

    private final Context context;
    private final Map<String, Boolean> genericAllowedCache = new HashMap<>();
    private final Set<String> detectedBrowsers = new HashSet<>();
    private final Set<String> homePackages = new HashSet<>();

    public PackagePolicy(Context context) {
        this.context = context.getApplicationContext();
        discoverBrowsers();
        discoverHomePackages();
    }

    public boolean allowGeneric(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        if (packageName.equals(context.getPackageName()) || packageName.equals("android")) {
            return false;
        }
        Boolean cached = genericAllowedCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        boolean allowed = !isSystemApplication(packageName) && !isExcludedBrowser(packageName);
        genericAllowedCache.put(packageName, allowed);
        return allowed;
    }

    public boolean isHomePackage(String packageName) {
        return packageName != null && homePackages.contains(packageName);
    }

    public boolean isExcludedBrowser(String packageName) {
        if (packageName == null || packageName.isEmpty() || BROWSER_EXCEPTIONS.contains(packageName)) {
            return false;
        }
        return STATIC_BROWSERS.contains(packageName) || detectedBrowsers.contains(packageName);
    }

    public int browserCount() {
        Set<String> all = new HashSet<>(STATIC_BROWSERS);
        all.addAll(detectedBrowsers);
        all.removeAll(BROWSER_EXCEPTIONS);
        return all.size();
    }

    private void discoverBrowsers() {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com/"));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        int flags = PackageManager.MATCH_DEFAULT_ONLY | PackageManager.GET_RESOLVED_FILTER;
        List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(intent, flags);
        for (ResolveInfo resolveInfo : handlers) {
            if (resolveInfo.activityInfo == null || resolveInfo.activityInfo.packageName == null) {
                continue;
            }
            IntentFilter filter = resolveInfo.filter;
            if (filter == null || filter.countDataAuthorities() == 0) {
                detectedBrowsers.add(resolveInfo.activityInfo.packageName);
            }
        }
    }

    private void discoverHomePackages() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> homes = context.getPackageManager().queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo resolveInfo : homes) {
            if (resolveInfo.activityInfo != null && resolveInfo.activityInfo.packageName != null) {
                homePackages.add(resolveInfo.activityInfo.packageName);
            }
        }
    }

    private boolean isSystemApplication(String packageName) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            int flags = info.flags;
            return (flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    || (flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (PackageManager.NameNotFoundException ignored) {
            return true;
        }
    }
}
