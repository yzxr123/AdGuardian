package org.adguardian.app.engine;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.settings.PreferenceStore;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class GenericAdEngine {
    private static final int MAX_TREE_NODES = 180;
    private static final int MAX_PARENT_DEPTH = 4;
    private static final long TREE_SCAN_INTERVAL_MS = 360L;

    private static final String[] FAST_TEXT_QUERIES = {
            "跳过",
            "跳過",
            "Skip",
            "关闭广告",
            "关闭推广",
            "跳过广告",
            "Close ad",
            "Skip ad"
    };

    private static final String[] ONBOARDING_EXCLUSIONS = {
            "下一步",
            "选择偏好",
            "选择兴趣",
            "阅读并同意",
            "权限设置"
    };

    private final AccessibilityService service;
    private final ActionGate actionGate = new ActionGate();
    private final Map<String, Long> lastTreeScanByPackage = new HashMap<>();

    public GenericAdEngine(AccessibilityService service) {
        this.service = service;
    }

    public Result handle(
            String packageName,
            String currentWindowClass,
            AccessibilityNodeInfo root,
            long launchHotUntilUptime,
            boolean allowGeneric
    ) {
        if (!allowGeneric || root == null) {
            return Result.none();
        }

        long now = SystemClock.uptimeMillis();
        boolean launchWindow = now <= launchHotUntilUptime;
        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        boolean startupExcluded = launchWindow && hasOnboardingExclusion(root);
        boolean sdkActivity = AdSdkSignatures.isAdSdkActivity(currentWindowClass);
        boolean rewardedActivity = AdSdkSignatures.isRewardedActivity(currentWindowClass);
        boolean adSignalSeen = sdkActivity;

        for (String query : FAST_TEXT_QUERIES) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(query);
            if (nodes == null || nodes.isEmpty()) {
                continue;
            }
            for (AccessibilityNodeInfo node : nodes) {
                Evaluation evaluation = evaluate(
                        node,
                        rootBounds,
                        launchWindow,
                        startupExcluded,
                        sdkActivity,
                        rewardedActivity,
                        currentWindowClass
                );
                adSignalSeen |= evaluation.adSignal;
                Result action = attempt(packageName, node, evaluation);
                if (action.clicked()) {
                    return action;
                }
            }
        }

        Long lastTreeScan = lastTreeScanByPackage.get(packageName);
        if (lastTreeScan != null && now - lastTreeScan < TREE_SCAN_INTERVAL_MS) {
            return adSignalSeen ? Result.signalOnly("fast-text-signal") : Result.none();
        }
        lastTreeScanByPackage.put(packageName, now);

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        int visited = 0;
        while (!queue.isEmpty() && visited < MAX_TREE_NODES) {
            AccessibilityNodeInfo node = queue.removeFirst();
            visited++;
            if (node.isVisibleToUser()) {
                Evaluation evaluation = evaluate(
                        node,
                        rootBounds,
                        launchWindow,
                        startupExcluded,
                        sdkActivity,
                        rewardedActivity,
                        currentWindowClass
                );
                adSignalSeen |= evaluation.adSignal;
                Result action = attempt(packageName, node, evaluation);
                if (action.clicked()) {
                    return action.withVisited(visited);
                }
            }
            int childCount = node.getChildCount();
            for (int index = 0; index < childCount && queue.size() < MAX_TREE_NODES; index++) {
                AccessibilityNodeInfo child = node.getChild(index);
                if (child != null) {
                    queue.addLast(child);
                }
            }
        }

        return adSignalSeen
                ? Result.signalOnly("tree-ad-signal visited=" + visited)
                : Result.none();
    }

    private Evaluation evaluate(
            AccessibilityNodeInfo node,
            Rect rootBounds,
            boolean launchWindow,
            boolean startupExcluded,
            boolean sdkActivity,
            boolean rewardedActivity,
            String currentWindowClass
    ) {
        String id = lower(node.getViewIdResourceName());
        String text = clean(node.getText());
        String desc = clean(node.getContentDescription());
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerDesc = desc.toLowerCase(Locale.ROOT);

        boolean sdkResourceSignal = AdSdkSignatures.containsResourceMarker(id);
        boolean localAdSignal = containsAdMarker(id)
                || sdkResourceSignal
                || containsAdText(text)
                || containsAdText(desc);
        boolean closeId = !id.isEmpty() && hasCloseToken(id);
        boolean contextualAdSignal = closeId && !localAdSignal && hasAdContext(node);
        boolean adSignal = localAdSignal || contextualAdSignal;

        if (launchWindow
                && !startupExcluded
                && PreferenceStore.isTypeEnabled(service, AdType.STARTUP)
                && matchesStartupSkip(node, id, lowerText, lowerDesc, rootBounds)) {
            return Evaluation.action(AdType.STARTUP, "global-startup-skip", true);
        }

        if (matchesExplicitAdClose(lowerText, lowerDesc)) {
            AdType type = classifyById(id);
            if (PreferenceStore.isTypeEnabled(service, type)) {
                return Evaluation.action(type, "global-explicit-ad-close", true);
            }
        }

        if (!id.isEmpty()) {
            boolean skipId = hasSkipToken(id) && !hasSkipExclusion(id);
            if (launchWindow
                    && !startupExcluded
                    && skipId
                    && PreferenceStore.isTypeEnabled(service, AdType.STARTUP)
                    && matchesSmallControl(node, rootBounds, true)) {
                return Evaluation.action(AdType.STARTUP, "global-skip-id:" + compactId(id), true);
            }

            if (closeId && (containsAdMarker(id) || sdkResourceSignal || contextualAdSignal)) {
                AdType type = classifyById(id);
                if (PreferenceStore.isTypeEnabled(service, type)
                        && matchesSmallControl(node, rootBounds, false)) {
                    return Evaluation.action(
                            type,
                            "global-ad-close-id:" + compactId(id)
                                    + ":sdk=" + AdSdkSignatures.sdkName(id, currentWindowClass),
                            true
                    );
                }
            }
        }

        if (sdkActivity
                && !rewardedActivity
                && launchWindow
                && isSdkActivityClose(text, desc, id)
                && matchesSmallControl(node, rootBounds, false)) {
            AdType type = classifyById(id);
            if (PreferenceStore.isTypeEnabled(service, type)) {
                return Evaluation.action(
                        type,
                        "sdk-activity-close:sdk=" + AdSdkSignatures.sdkName(id, currentWindowClass),
                        true
                );
            }
        }

        return Evaluation.none(adSignal);
    }

    private Result attempt(String packageName, AccessibilityNodeInfo node, Evaluation evaluation) {
        if (!evaluation.action) {
            return evaluation.adSignal ? Result.signalOnly(evaluation.evidence) : Result.none();
        }

        String gateId = packageName + '|' + evaluation.type.preferenceKey() + '|' + evaluation.evidence;
        if (!actionGate.canAct(gateId)) {
            return Result.signalOnly("cooldown:" + evaluation.evidence);
        }

        AccessibilityNodeInfo clickable = findClickable(node);
        if (clickable != null
                && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            actionGate.recordSuccess(gateId);
            return Result.clicked(
                    evaluation.type,
                    evaluation.evidence,
                    "ACTION_CLICK",
                    describe(node)
            );
        }

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() > 0 && bounds.height() > 0 && dispatchTap(bounds.centerX(), bounds.centerY())) {
            actionGate.recordSuccess(gateId);
            return Result.clicked(
                    evaluation.type,
                    evaluation.evidence,
                    "GESTURE_TAP",
                    describe(node)
            );
        }

        return Result.actionFailed(evaluation.type, evaluation.evidence, describe(node));
    }

    private boolean dispatchTap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(
                path,
                0L,
                45L
        );
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        return service.dispatchGesture(gesture, null, null);
    }

    private boolean matchesStartupSkip(
            AccessibilityNodeInfo node,
            String id,
            String text,
            String desc,
            Rect rootBounds
    ) {
        boolean textual = isSkipText(text) || isSkipText(desc);
        boolean idSignal = hasSkipToken(id) && !hasSkipExclusion(id);
        if (!textual && !idSignal) {
            return false;
        }
        return matchesSmallControl(node, rootBounds, true);
    }

    private boolean matchesSmallControl(
            AccessibilityNodeInfo node,
            Rect rootBounds,
            boolean requireUpperArea
    ) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        if (bounds.width() >= 500 || bounds.height() >= 300) {
            return false;
        }
        if (rootBounds.width() > 0 && bounds.width() >= rootBounds.width() * 0.55f) {
            return false;
        }
        if (rootBounds.height() > 0 && bounds.height() >= rootBounds.height() * 0.20f) {
            return false;
        }
        if (!requireUpperArea || rootBounds.height() <= 0) {
            return true;
        }
        return bounds.centerY() <= rootBounds.top + rootBounds.height() * 0.48f;
    }

    private boolean hasOnboardingExclusion(AccessibilityNodeInfo root) {
        for (String text : ONBOARDING_EXCLUSIONS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null && !nodes.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean isSkipText(String value) {
        if (value.isEmpty() || value.length() >= 10) {
            return false;
        }
        if (value.contains("视频")
                || value.contains("片头")
                || value.contains("片尾")
                || value.contains("教程")
                || value.contains("引导")) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("跳过")
                || value.contains("跳 過")
                || value.contains("跳過")
                || lower.contains("skip");
    }

    private boolean matchesExplicitAdClose(String text, String desc) {
        return explicitAdClose(text) || explicitAdClose(desc);
    }

    private boolean explicitAdClose(String value) {
        if (value.isEmpty() || value.length() > 18) {
            return false;
        }
        return value.equals("关闭广告")
                || value.equals("关闭此广告")
                || value.equals("关闭推广")
                || value.equals("跳过广告")
                || value.equals("close ad")
                || value.equals("close ads")
                || value.equals("skip ad")
                || value.equals("skip ads")
                || value.equals("dismiss ad");
    }

    private boolean hasSkipToken(String id) {
        return id.contains("skip")
                || id.endsWith("/tiaoguo")
                || id.contains("skip_container")
                || id.contains("tt_splash_skip_btn");
    }

    private boolean hasSkipExclusion(String id) {
        return id.contains("video")
                || id.contains("head")
                || id.contains("tail")
                || id.contains("intro")
                || id.contains("tutorial");
    }

    private boolean hasCloseToken(String id) {
        return id.contains("close") || id.contains("dismiss");
    }

    private boolean containsAdMarker(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return AdSdkSignatures.containsResourceMarker(lower)
                || lower.contains("advert")
                || lower.contains("splash")
                || lower.contains("promo")
                || lower.contains("banner_ad")
                || lower.contains("ad_banner")
                || lower.contains("feed_ad")
                || lower.contains("ad_feed")
                || lower.contains("ad_close")
                || lower.contains("close_ad")
                || lower.contains("ad_skip")
                || lower.contains("skip_ad")
                || lower.contains("gdt")
                || lower.contains("ksad")
                || lower.contains("pangle")
                || lower.contains("tt_splash")
                || lower.contains("jad_")
                || lower.contains("sigmob")
                || lower.contains("sponsor");
    }

    private boolean containsAdText(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return value.contains("广告")
                || value.contains("推广")
                || value.contains("赞助")
                || lower.contains("advertisement")
                || lower.contains("sponsored");
    }

    private boolean hasAdContext(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth <= 3 && current != null; depth++) {
            if (nodeHasAdMarker(current)) {
                return true;
            }
            AccessibilityNodeInfo parent = current.getParent();
            if (parent != null) {
                int childCount = Math.min(parent.getChildCount(), 12);
                for (int index = 0; index < childCount; index++) {
                    AccessibilityNodeInfo sibling = parent.getChild(index);
                    if (sibling != null && nodeHasAdMarker(sibling)) {
                        return true;
                    }
                }
            }
            current = parent;
        }
        return false;
    }

    private boolean nodeHasAdMarker(AccessibilityNodeInfo node) {
        return containsAdMarker(node.getViewIdResourceName())
                || containsAdText(clean(node.getText()))
                || containsAdText(clean(node.getContentDescription()));
    }

    private boolean isSdkActivityClose(String text, String desc, String id) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerDesc = desc.toLowerCase(Locale.ROOT);
        if (hasCloseToken(id)) {
            return true;
        }
        return text.equals("关闭")
                || desc.equals("关闭")
                || text.equals("×")
                || desc.equals("×")
                || lowerText.equals("close")
                || lowerDesc.equals("close")
                || lowerText.equals("dismiss")
                || lowerDesc.equals("dismiss");
    }

    private AdType classifyById(String id) {
        if (id.contains("feed")) {
            return AdType.FEED;
        }
        if (id.contains("banner") || id.contains("card")) {
            return AdType.CARD;
        }
        if (id.contains("float")) {
            return AdType.FLOATING;
        }
        return AdType.POPUP;
    }

    private AccessibilityNodeInfo findClickable(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; depth <= MAX_PARENT_DEPTH && current != null; depth++) {
            if (current.isVisibleToUser() && current.isEnabled() && current.isClickable()) {
                return current;
            }
            current = current.getParent();
        }
        return null;
    }

    private String describe(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        return "id=" + compactId(lower(node.getViewIdResourceName()))
                + " text=" + compact(clean(node.getText()))
                + " desc=" + compact(clean(node.getContentDescription()))
                + " bounds=" + bounds.left + ',' + bounds.top + ',' + bounds.right + ',' + bounds.bottom;
    }

    private String compactId(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        int marker = value.indexOf(":id/");
        return compact(marker >= 0 ? value.substring(marker + 4) : value);
    }

    private String compact(String value) {
        if (value == null || value.isEmpty()) {
            return "-";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 48 ? clean : clean.substring(0, 48) + "…";
    }

    private String clean(CharSequence value) {
        return value == null ? "" : value.toString().replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static final class Evaluation {
        private final boolean action;
        private final boolean adSignal;
        private final AdType type;
        private final String evidence;

        private Evaluation(boolean action, boolean adSignal, AdType type, String evidence) {
            this.action = action;
            this.adSignal = adSignal;
            this.type = type;
            this.evidence = evidence;
        }

        private static Evaluation action(AdType type, String evidence, boolean adSignal) {
            return new Evaluation(true, adSignal, type, evidence);
        }

        private static Evaluation none(boolean adSignal) {
            return new Evaluation(false, adSignal, null, "");
        }
    }

    public static final class Result {
        private final boolean clicked;
        private final boolean signalSeen;
        private final boolean actionFailed;
        private final AdType type;
        private final String evidence;
        private final String actionMode;
        private final String node;
        private final int visited;

        private Result(
                boolean clicked,
                boolean signalSeen,
                boolean actionFailed,
                AdType type,
                String evidence,
                String actionMode,
                String node,
                int visited
        ) {
            this.clicked = clicked;
            this.signalSeen = signalSeen;
            this.actionFailed = actionFailed;
            this.type = type;
            this.evidence = evidence;
            this.actionMode = actionMode;
            this.node = node;
            this.visited = visited;
        }

        public static Result none() {
            return new Result(false, false, false, null, "", "", "", 0);
        }

        public static Result signalOnly(String evidence) {
            return new Result(false, true, false, null, evidence, "", "", 0);
        }

        public static Result clicked(AdType type, String evidence, String actionMode, String node) {
            return new Result(true, true, false, type, evidence, actionMode, node, 0);
        }

        public static Result actionFailed(AdType type, String evidence, String node) {
            return new Result(false, true, true, type, evidence, "", node, 0);
        }

        public Result withVisited(int value) {
            return new Result(clicked, signalSeen, actionFailed, type, evidence, actionMode, node, value);
        }

        public boolean clicked() {
            return clicked;
        }

        public boolean signalSeen() {
            return signalSeen;
        }

        public boolean actionFailed() {
            return actionFailed;
        }

        public AdType type() {
            return type;
        }

        public String evidence() {
            return evidence;
        }

        public String actionMode() {
            return actionMode;
        }

        public String node() {
            return node;
        }

        public int visited() {
            return visited;
        }
    }
}
