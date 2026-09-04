package org.adguardian.app.engine;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.Collections;
import java.util.List;

public final class RuleMatcher {
    private static final int MAX_PARENT_DEPTH = 4;

    public MatchResult matchAndClick(AccessibilityNodeInfo root, AppRule rule) {
        List<AccessibilityNodeInfo> candidates = findCandidates(root, rule);
        if (candidates.isEmpty()) {
            return MatchResult.noCandidates();
        }

        Rect rootBounds = new Rect();
        root.getBoundsInScreen(rootBounds);
        int eligibleCount = 0;
        int clickableCount = 0;
        int failedActionCount = 0;

        for (AccessibilityNodeInfo node : candidates) {
            if (!matches(node, rule, rootBounds)) {
                continue;
            }
            eligibleCount++;
            AccessibilityNodeInfo clickable = findClickable(node);
            if (clickable == null) {
                continue;
            }
            clickableCount++;
            if (clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return MatchResult.clicked(candidates.size(), eligibleCount, clickableCount, failedActionCount);
            }
            failedActionCount++;
        }
        return MatchResult.notClicked(candidates.size(), eligibleCount, clickableCount, failedActionCount);
    }

    private List<AccessibilityNodeInfo> findCandidates(AccessibilityNodeInfo root, AppRule rule) {
        List<AccessibilityNodeInfo> result;
        if (rule.target() == AppRule.Target.VIEW_ID) {
            result = root.findAccessibilityNodeInfosByViewId(rule.value());
        } else {
            result = root.findAccessibilityNodeInfosByText(rule.value());
        }
        return result == null ? Collections.emptyList() : result;
    }

    private boolean matches(AccessibilityNodeInfo node, AppRule rule, Rect rootBounds) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }

        if (rule.target() == AppRule.Target.VIEW_ID) {
            String viewId = node.getViewIdResourceName();
            return rule.value().equals(viewId);
        }

        String text = node.getText() == null ? "" : node.getText().toString().trim();
        String description = node.getContentDescription() == null
                ? ""
                : node.getContentDescription().toString().trim();

        if (!matchesValue(text, rule) && !matchesValue(description, rule)) {
            return false;
        }
        return matchesBounds(node, rule, rootBounds);
    }

    private boolean matchesValue(String candidate, AppRule rule) {
        if (candidate.isEmpty()) {
            return false;
        }
        if (rule.maxTextLength() > 0 && candidate.length() >= rule.maxTextLength()) {
            return false;
        }
        if (rule.mode() == AppRule.Mode.EXACT) {
            return candidate.equals(rule.value());
        }
        return candidate.contains(rule.value());
    }

    private boolean matchesBounds(AccessibilityNodeInfo node, AppRule rule, Rect rootBounds) {
        if (rule.maxWidthPx() <= 0
                && rule.maxHeightPx() <= 0
                && rule.maxWidthRatio() <= 0.0f
                && rule.maxHeightRatio() <= 0.0f) {
            return true;
        }

        Rect nodeBounds = new Rect();
        node.getBoundsInScreen(nodeBounds);
        if (rule.maxWidthPx() > 0 && nodeBounds.width() >= rule.maxWidthPx()) {
            return false;
        }
        if (rule.maxHeightPx() > 0 && nodeBounds.height() >= rule.maxHeightPx()) {
            return false;
        }

        if (rule.maxWidthRatio() <= 0.0f && rule.maxHeightRatio() <= 0.0f) {
            return true;
        }
        if (rootBounds.width() <= 0 || rootBounds.height() <= 0) {
            return false;
        }
        if (rule.maxWidthRatio() > 0.0f
                && nodeBounds.width() >= rootBounds.width() * rule.maxWidthRatio()) {
            return false;
        }
        return rule.maxHeightRatio() <= 0.0f
                || nodeBounds.height() < rootBounds.height() * rule.maxHeightRatio();
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

    public static final class MatchResult {
        private final int candidateCount;
        private final int eligibleCount;
        private final int clickableCount;
        private final int failedActionCount;
        private final boolean clicked;

        private MatchResult(
                int candidateCount,
                int eligibleCount,
                int clickableCount,
                int failedActionCount,
                boolean clicked
        ) {
            this.candidateCount = candidateCount;
            this.eligibleCount = eligibleCount;
            this.clickableCount = clickableCount;
            this.failedActionCount = failedActionCount;
            this.clicked = clicked;
        }

        public static MatchResult noCandidates() {
            return new MatchResult(0, 0, 0, 0, false);
        }

        public static MatchResult notClicked(
                int candidateCount,
                int eligibleCount,
                int clickableCount,
                int failedActionCount
        ) {
            return new MatchResult(candidateCount, eligibleCount, clickableCount, failedActionCount, false);
        }

        public static MatchResult clicked(
                int candidateCount,
                int eligibleCount,
                int clickableCount,
                int failedActionCount
        ) {
            return new MatchResult(candidateCount, eligibleCount, clickableCount, failedActionCount, true);
        }

        public int candidateCount() {
            return candidateCount;
        }

        public int eligibleCount() {
            return eligibleCount;
        }

        public int clickableCount() {
            return clickableCount;
        }

        public int failedActionCount() {
            return failedActionCount;
        }

        public boolean clicked() {
            return clicked;
        }
    }
}
