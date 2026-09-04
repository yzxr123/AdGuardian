package org.adguardian.app.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.BuildConfig;
import org.adguardian.app.debug.DebugLogStore;
import org.adguardian.app.debug.RuntimeState;
import org.adguardian.app.engine.PackagePolicy;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.engine.RuleRepository;
import org.adguardian.app.settings.PreferenceStore;
import org.adguardian.app.vision.SplashVisionController;

public final class AdAccessibilityService extends AccessibilityService {
    private static final long LAUNCH_HOT_WINDOW_MS = 10_000L;
    private static final long REENTRY_IDLE_MS = 2_000L;
    private static final long CONTENT_EVENT_DEBOUNCE_MS = 140L;
    private static final long SERVICE_MISS_LOG_INTERVAL_MS = 1_500L;

    private RuleEngine ruleEngine;
    private PackagePolicy packagePolicy;
    private SplashVisionController visionController;
    private String foregroundPackage = "";
    private String currentWindowClass = "";
    private long launchHotUntilUptime = 0L;
    private long lastTargetEventUptime = 0L;
    private long lastContentScanUptime = 0L;
    private long lastRootNullLogUptime = 0L;
    private long lastRootMismatchLogUptime = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ruleEngine = new RuleEngine(this);
        packagePolicy = new PackagePolicy(this);
        visionController = new SplashVisionController(this, ruleEngine);

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100L;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.packageNames = null;
        setServiceInfo(info);

        DebugLogStore.info(
                this,
                "SERVICE_CONNECTED",
                "debug=" + BuildConfig.DEBUG
                        + " scope=all-third-party-apps"
                        + " pipeline=L1-accessibility,L2-ocr,L3-yolo"
                        + " browsersExcluded=" + packagePolicy.browserCount()
                        + " specificPackages=" + RuleRepository.supportedPackages().size()
                        + " specificRules=" + RuleRepository.ruleCount()
        );
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!BuildConfig.DEBUG) {
            handleEvent(event);
            return;
        }
        try {
            handleEvent(event);
        } catch (RuntimeException exception) {
            DebugLogStore.error(
                    this,
                    "SERVICE_EXCEPTION",
                    "pkg=" + eventPackage(event)
                            + " type=" + (event == null ? -1 : event.getEventType())
                            + " activity=" + currentWindowClass,
                    exception
            );
        }
    }

    private void handleEvent(AccessibilityEvent event) {
        if (event == null || ruleEngine == null || packagePolicy == null || visionController == null) {
            return;
        }
        if (!PreferenceStore.isMasterEnabled(this)) {
            return;
        }

        CharSequence packageSequence = event.getPackageName();
        if (packageSequence == null) {
            DebugLogStore.miss(this, "EVENT_NO_PACKAGE", "type=" + event.getEventType());
            return;
        }

        String packageName = packageSequence.toString();
        if (packageName.equals(getPackageName())) {
            return;
        }

        int eventType = event.getEventType();
        boolean windowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;

        if (windowChanged && (packagePolicy.isHomePackage(packageName)
                || packagePolicy.isExcludedBrowser(packageName))) {
            if (!packageName.equals(foregroundPackage)) {
                foregroundPackage = packageName;
                currentWindowClass = event.getClassName() == null ? "" : event.getClassName().toString();
                visionController.endSession();
            }
        }

        boolean hasSpecificRules = RuleRepository.hasRulesFor(packageName);
        boolean allowGeneric = packagePolicy.allowGeneric(packageName);
        boolean allowVision = allowGeneric || hasSpecificRules;
        if (!hasSpecificRules && !allowGeneric) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        long previousTargetEventUptime = lastTargetEventUptime;
        lastTargetEventUptime = now;

        boolean packageChanged = !packageName.equals(foregroundPackage);
        if (packageChanged) {
            foregroundPackage = packageName;
            currentWindowClass = "";
            launchHotUntilUptime = now + LAUNCH_HOT_WINDOW_MS;
            lastContentScanUptime = 0L;
        }

        boolean restartVision = false;
        if (windowChanged) {
            restartVision = handleWindowStateChanged(
                    event,
                    packageName,
                    now,
                    previousTargetEventUptime,
                    packageChanged
            );
        } else if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            if (now - lastContentScanUptime < CONTENT_EVENT_DEBOUNCE_MS) {
                return;
            }
            lastContentScanUptime = now;
        }

        RuntimeState.updateForeground(packageName, currentWindowClass);
        if (allowVision && (packageChanged || restartVision)) {
            visionController.beginSession(packageName, currentWindowClass);
        } else if (allowVision && windowChanged) {
            visionController.updateActivity(currentWindowClass);
        }

        if (packageChanged) {
            DebugLogStore.info(
                    this,
                    "APP_ENTER",
                    "app=" + RuleRepository.displayNameFor(packageName)
                            + " pkg=" + packageName
                            + " generic=" + allowGeneric
                            + " specific=" + hasSpecificRules
                            + " launchWindowMs=" + LAUNCH_HOT_WINDOW_MS
            );
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            if (now - lastRootNullLogUptime >= SERVICE_MISS_LOG_INTERVAL_MS) {
                lastRootNullLogUptime = now;
                DebugLogStore.miss(
                        this,
                        "ROOT_NULL",
                        "app=" + RuleRepository.displayNameFor(packageName)
                                + " pkg=" + packageName
                                + " activity=" + currentWindowClass
                );
            }
            return;
        }

        CharSequence rootPackageSequence = root.getPackageName();
        if (rootPackageSequence == null || !packageName.contentEquals(rootPackageSequence)) {
            if (now - lastRootMismatchLogUptime >= SERVICE_MISS_LOG_INTERVAL_MS) {
                lastRootMismatchLogUptime = now;
                DebugLogStore.miss(
                        this,
                        "ROOT_PACKAGE_MISMATCH",
                        "eventPkg=" + packageName
                                + " rootPkg=" + (rootPackageSequence == null ? "null" : rootPackageSequence)
                                + " activity=" + currentWindowClass
                );
            }
            return;
        }

        visionController.observeAccessibilityEvidence(root, currentWindowClass);

        boolean action = ruleEngine.handle(
                packageName,
                currentWindowClass,
                root,
                launchHotUntilUptime,
                allowGeneric,
                windowChanged
        );
        if (action) {
            visionController.noteL1Action();
        }
    }

    private boolean handleWindowStateChanged(
            AccessibilityEvent event,
            String packageName,
            long now,
            long previousTargetEventUptime,
            boolean packageChanged
    ) {
        CharSequence classSequence = event.getClassName();
        if (classSequence == null) {
            DebugLogStore.miss(
                    this,
                    "WINDOW_NO_CLASS",
                    "app=" + RuleRepository.displayNameFor(packageName) + " pkg=" + packageName
            );
            return false;
        }

        String eventWindowClass = classSequence.toString();
        boolean launchActivity = RuleRepository.isLaunchActivity(packageName, eventWindowClass);
        boolean reenteredAfterIdle = previousTargetEventUptime == 0L
                || now - previousTargetEventUptime >= REENTRY_IDLE_MS;

        boolean restartVision = !packageChanged && launchActivity && reenteredAfterIdle;
        if (restartVision) {
            launchHotUntilUptime = now + LAUNCH_HOT_WINDOW_MS;
        }
        if (eventWindowClass.endsWith("Activity")) {
            currentWindowClass = eventWindowClass;
        }

        DebugLogStore.info(
                this,
                "WINDOW",
                "app=" + RuleRepository.displayNameFor(packageName)
                        + " pkg=" + packageName
                        + " class=" + eventWindowClass
                        + " launchActivity=" + launchActivity
                        + " hot=" + (now <= launchHotUntilUptime)
        );
        return restartVision;
    }

    private String eventPackage(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) {
            return "null";
        }
        return event.getPackageName().toString();
    }

    @Override
    public void onInterrupt() {
        DebugLogStore.info(this, "SERVICE_INTERRUPTED", "system interrupted accessibility service");
    }

    @Override
    public void onDestroy() {
        if (visionController != null) {
            visionController.stop();
            visionController = null;
        }
        super.onDestroy();
    }
}
