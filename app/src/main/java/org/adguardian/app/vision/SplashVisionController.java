package org.adguardian.app.vision;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.debug.DebugLogStore;
import org.adguardian.app.engine.AdSdkSignatures;
import org.adguardian.app.engine.AdType;
import org.adguardian.app.engine.RuleEngine;
import org.adguardian.app.settings.PreferenceStore;

import java.util.ArrayDeque;

public final class SplashVisionController {
    private static final long FIRST_SCAN_DELAY_MS = 500L;
    private static final long POLL_INTERVAL_MS = 750L;
    private static final long CORE_WINDOW_MS = 8_000L;
    private static final long MAX_WINDOW_MS = 45_000L;
    private static final long LATE_PROBE_INTERVAL_MS = 3_000L;
    private static final long SCREENSHOT_TIMEOUT_MS = 2_000L;
    private static final long YOLO_WARMUP_DELAY_MS = 8_000L;
    private static final int MAX_TAPS_PER_SESSION = 3;
    private static final int SDK_SCAN_NODE_LIMIT = 100;

    private final AccessibilityService service;
    private final RuleEngine ruleEngine;
    private final OcrSkipDetector ocrDetector = new OcrSkipDetector();
    private final YoloSkipDetector yoloDetector;
    private final AdEvidenceTracker evidenceTracker = new AdEvidenceTracker();
    private final VisionProfileStore profileStore;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable warmupRunnable;

    private String packageName = "";
    private String activityName = "";
    private long sessionStartedUptime = 0L;
    private long lastLateProbeUptime = 0L;
    private long generation = 0L;
    private int tapsThisSession = 0;
    private boolean screenshotPending = false;
    private boolean fullProbe = true;
    private boolean sessionActive = false;
    private boolean sessionEvidenceSeen = false;

    public SplashVisionController(AccessibilityService service, RuleEngine ruleEngine) {
        this.service = service;
        this.ruleEngine = ruleEngine;
        this.yoloDetector = new YoloSkipDetector(service);
        this.profileStore = new VisionProfileStore(service);
        this.warmupRunnable = yoloDetector::warmUp;
        handler.postDelayed(warmupRunnable, YOLO_WARMUP_DELAY_MS);
    }

    public void beginSession(String pkg, String activity) {
        finishSessionIfNeeded();

        generation++;
        packageName = pkg == null ? "" : pkg;
        activityName = activity == null ? "" : activity;
        sessionStartedUptime = SystemClock.uptimeMillis();
        lastLateProbeUptime = 0L;
        tapsThisSession = 0;
        screenshotPending = false;
        sessionEvidenceSeen = false;
        evidenceTracker.beginSession(packageName);

        if (packageName.isEmpty()) {
            sessionActive = false;
            return;
        }

        VisionProfileStore.SessionPlan plan = profileStore.beginSession(packageName);
        fullProbe = plan.fullProbe;
        sessionActive = true;
        DebugLogStore.info(
                service,
                "VISION_SESSION_BEGIN",
                "pkg=" + packageName
                        + " fullProbe=" + fullProbe
                        + " downgraded=" + plan.downgraded
                        + " session=" + plan.sessionNumber
                        + " noEvidence=" + plan.noEvidenceStreak
        );

        if (fullProbe) {
            long token = generation;
            handler.postDelayed(() -> poll(token), FIRST_SCAN_DELAY_MS);
        }
    }

    public void updateActivity(String activity) {
        activityName = activity == null ? "" : activity;
    }

    public void observeAccessibilityEvidence(AccessibilityNodeInfo root, String activity) {
        updateActivity(activity);
        if (!sessionActive || fullProbe || !ownsActiveWindow(root)) {
            return;
        }
        if (!hasSdkEvidence(root, activityName)) {
            return;
        }
        fullProbe = true;
        sessionEvidenceSeen = true;
        evidenceTracker.update(packageName, true, null);
        profileStore.markEvidence(packageName);
        DebugLogStore.info(service, "VISION_PROFILE_UPGRADED", "pkg=" + packageName + " reason=sdk-evidence");
        long token = generation;
        handler.post(() -> poll(token));
    }

    public void noteL1Action() {
        if (!sessionActive) {
            return;
        }
        sessionEvidenceSeen = true;
        profileStore.markEvidence(packageName);
    }

    public void endSession() {
        generation++;
        finishSessionIfNeeded();
        screenshotPending = false;
        packageName = "";
        activityName = "";
    }

    public void stop() {
        generation++;
        finishSessionIfNeeded();
        handler.removeCallbacks(warmupRunnable);
        ocrDetector.close();
        yoloDetector.close();
        screenshotPending = false;
    }

    private void poll(long token) {
        if (token != generation || !sessionActive || packageName.isEmpty()) {
            return;
        }
        if (!PreferenceStore.isMasterEnabled(service)
                || !PreferenceStore.isTypeEnabled(service, AdType.STARTUP)) {
            finishSessionIfNeeded();
            return;
        }

        long now = SystemClock.uptimeMillis();
        long elapsed = now - sessionStartedUptime;
        if (elapsed > MAX_WINDOW_MS || tapsThisSession >= MAX_TAPS_PER_SESSION) {
            DebugLogStore.info(
                    service,
                    "VISION_SESSION_END",
                    "pkg=" + packageName
                            + " reason=" + (elapsed > MAX_WINDOW_MS ? "max-window" : "tap-limit")
                            + " taps=" + tapsThisSession
                            + " evidence=" + sessionEvidenceSeen
            );
            finishSessionIfNeeded();
            return;
        }

        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!ownsActiveWindow(root)) {
            scheduleNext(token, elapsed);
            return;
        }

        boolean sdkEvidence = hasSdkEvidence(root, activityName);
        if (sdkEvidence) {
            evidenceTracker.update(packageName, true, null);
            sessionEvidenceSeen = true;
        }

        long launchHotUntil = sessionStartedUptime + CORE_WINDOW_MS;
        boolean l1Clicked = ruleEngine.handle(
                packageName,
                activityName,
                root,
                launchHotUntil,
                true,
                false
        );
        if (l1Clicked) {
            tapsThisSession++;
            sessionEvidenceSeen = true;
        }

        boolean coreWindow = elapsed <= CORE_WINDOW_MS;
        boolean evidence = evidenceTracker.hasEvidence(packageName);
        boolean allowLateProbe = !coreWindow
                && (evidence || sdkEvidence || looksLikeSplash(root) || lateProbeDue(now));
        boolean allowImageLayer = fullProbe && (coreWindow || allowLateProbe);

        if (!l1Clicked
                && allowImageLayer
                && tapsThisSession < MAX_TAPS_PER_SESSION) {
            requestScreenshot(token, sdkEvidence);
        }

        scheduleNext(token, elapsed);
    }

    private void scheduleNext(long token, long elapsed) {
        if (token != generation || !sessionActive || elapsed >= MAX_WINDOW_MS) {
            return;
        }
        handler.postDelayed(() -> poll(token), POLL_INTERVAL_MS);
    }

    private boolean lateProbeDue(long now) {
        if (lastLateProbeUptime == 0L || now - lastLateProbeUptime >= LATE_PROBE_INTERVAL_MS) {
            lastLateProbeUptime = now;
            return true;
        }
        return false;
    }

    private boolean ownsActiveWindow(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) {
            return false;
        }
        return packageName.contentEquals(root.getPackageName());
    }

    private boolean looksLikeSplash(AccessibilityNodeInfo root) {
        int count = countNodes(root, 40);
        return count > 0 && count <= 24;
    }

    private int countNodes(AccessibilityNodeInfo node, int limit) {
        if (node == null || limit <= 0) {
            return 0;
        }
        int total = 1;
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount && total < limit; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                total += countNodes(child, limit - total);
            }
        }
        return total;
    }

    private boolean hasSdkEvidence(AccessibilityNodeInfo root, String activity) {
        if (AdSdkSignatures.isAdSdkActivity(activity)) {
            return true;
        }
        if (root == null) {
            return false;
        }

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < SDK_SCAN_NODE_LIMIT) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            String viewId = node.getViewIdResourceName();
            CharSequence className = node.getClassName();
            if (AdSdkSignatures.containsResourceMarker(viewId)
                    || AdSdkSignatures.isAdSdkActivity(className == null ? "" : className.toString())) {
                return true;
            }
            int childCount = node.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }
        return false;
    }

    private void requestScreenshot(long token, boolean sdkEvidence) {
        if (screenshotPending || ocrDetector.isBusy() || yoloDetector.isBusy()) {
            return;
        }
        screenshotPending = true;
        final boolean[] completed = {false};
        final boolean[] timedOut = {false};
        handler.postDelayed(() -> {
            if (token == generation && !completed[0] && screenshotPending) {
                timedOut[0] = true;
                screenshotPending = false;
                DebugLogStore.miss(service, "L2_SCREENSHOT_TIMEOUT", "pkg=" + packageName);
            }
        }, SCREENSHOT_TIMEOUT_MS);

        service.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        completed[0] = true;
                        screenshotPending = false;
                        if (token != generation || timedOut[0]) {
                            closeBuffer(result.getHardwareBuffer());
                            return;
                        }
                        Bitmap bitmap = copyBitmap(result);
                        if (bitmap != null) {
                            analyzeScreenshot(token, bitmap, sdkEvidence);
                        }
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        completed[0] = true;
                        screenshotPending = false;
                        if (token == generation) {
                            DebugLogStore.miss(
                                    service,
                                    "L2_SCREENSHOT_FAILED",
                                    "pkg=" + packageName + " code=" + errorCode
                            );
                        }
                    }
                }
        );
    }

    private Bitmap copyBitmap(AccessibilityService.ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        ColorSpace colorSpace = result.getColorSpace();
        try {
            Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardwareBitmap == null) {
                DebugLogStore.miss(service, "L2_BITMAP_NULL", "pkg=" + packageName);
                return null;
            }
            return hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
        } finally {
            closeBuffer(buffer);
        }
    }

    private void closeBuffer(HardwareBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    private void analyzeScreenshot(long token, Bitmap bitmap, boolean sdkEvidence) {
        boolean accepted = ocrDetector.analyze(bitmap, new OcrSkipDetector.Callback() {
            @Override
            public void onResult(OcrSkipDetector.Result result) {
                if (token != generation || !sessionActive) {
                    recycle(bitmap);
                    return;
                }

                boolean evidence = evidenceTracker.update(
                        packageName,
                        result.adEvidence || sdkEvidence,
                        result.countdown
                );
                if (evidence) {
                    sessionEvidenceSeen = true;
                    DebugLogStore.info(
                            service,
                            "AD_EVIDENCE",
                            "pkg=" + packageName
                                    + " ocr=" + result.adEvidence
                                    + " sdk=" + sdkEvidence
                                    + " countdown=" + result.countdown
                    );
                }

                if (result.targetFound && tapsThisSession < MAX_TAPS_PER_SESSION) {
                    recycle(bitmap);
                    dispatchTap(
                            result.x,
                            result.y,
                            "L2_OCR",
                            result.evidence,
                            "text=" + safe(result.text) + " bounds=" + result.bounds
                    );
                    return;
                }

                if (evidence && tapsThisSession < MAX_TAPS_PER_SESSION) {
                    analyzeYolo(token, bitmap);
                } else {
                    recycle(bitmap);
                }
            }

            @Override
            public void onError(Exception error) {
                if (token != generation || !sessionActive) {
                    recycle(bitmap);
                    return;
                }
                DebugLogStore.error(service, "L2_OCR_ERROR", "pkg=" + packageName, error);
                if (sdkEvidence && tapsThisSession < MAX_TAPS_PER_SESSION) {
                    sessionEvidenceSeen = true;
                    evidenceTracker.update(packageName, true, null);
                    analyzeYolo(token, bitmap);
                } else {
                    recycle(bitmap);
                }
            }
        });

        if (!accepted) {
            recycle(bitmap);
        }
    }

    private void analyzeYolo(long token, Bitmap bitmap) {
        boolean accepted = yoloDetector.analyze(bitmap, result -> {
            recycle(bitmap);
            if (token != generation || !sessionActive || !result.found) {
                return;
            }
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (!ownsActiveWindow(root)) {
                DebugLogStore.miss(service, "L3_YOLO_TARGET_STALE", "pkg=" + packageName);
                return;
            }
            dispatchTap(
                    result.bounds.exactCenterX(),
                    result.bounds.exactCenterY(),
                    "L3_YOLO",
                    "yolo-skip-button",
                    "backend=" + result.backend
                            + " confidence=" + String.format(java.util.Locale.ROOT, "%.3f", result.confidence)
                            + " bounds=" + result.bounds
            );
        });
        if (!accepted) {
            recycle(bitmap);
        }
    }

    private void dispatchTap(
            float x,
            float y,
            String layer,
            String evidence,
            String detail
    ) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (!ownsActiveWindow(root)) {
            DebugLogStore.miss(service, layer + "_TARGET_STALE", "pkg=" + packageName);
            return;
        }

        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 48L))
                .build();
        String sessionPackage = packageName;
        long sessionGeneration = generation;
        boolean accepted = service.dispatchGesture(
                gesture,
                new AccessibilityService.GestureResultCallback() {
                    @Override
                    public void onCompleted(GestureDescription gestureDescription) {
                        if (sessionGeneration == generation) {
                            DebugLogStore.success(
                                    service,
                                    layer + "_BLOCKED",
                                    "pkg=" + sessionPackage
                                            + " result=completed"
                                            + " evidence=" + evidence
                                            + " " + detail
                            );
                        }
                    }

                    @Override
                    public void onCancelled(GestureDescription gestureDescription) {
                        if (sessionGeneration == generation) {
                            DebugLogStore.miss(
                                    service,
                                    layer + "_ACTION_CANCELLED",
                                    "pkg=" + sessionPackage
                                            + " evidence=" + evidence
                                            + " " + detail
                            );
                        }
                    }
                },
                null
        );

        if (accepted) {
            tapsThisSession++;
            sessionEvidenceSeen = true;
        } else {
            DebugLogStore.miss(
                    service,
                    layer + "_ACTION_REJECTED",
                    "pkg=" + packageName + " evidence=" + evidence
            );
        }
    }

    private void finishSessionIfNeeded() {
        if (!sessionActive) {
            return;
        }
        boolean evidence = sessionEvidenceSeen || evidenceTracker.hasEvidence(packageName) || tapsThisSession > 0;
        profileStore.finishSession(packageName, evidence);
        sessionActive = false;
    }

    private void recycle(Bitmap bitmap) {
        if (bitmap != null && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    private String safe(String value) {
        if (value == null) {
            return "";
        }
        String compact = value.replace('\n', ' ').trim();
        return compact.length() <= 40 ? compact : compact.substring(0, 40);
    }
}
