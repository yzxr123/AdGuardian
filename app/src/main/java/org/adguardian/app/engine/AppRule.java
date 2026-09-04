package org.adguardian.app.engine;

public final class AppRule {
    public enum Target {
        TEXT_OR_DESCRIPTION,
        VIEW_ID
    }

    public enum Mode {
        EXACT,
        CONTAINS
    }

    private final String id;
    private final AdType type;
    private final Target target;
    private final Mode mode;
    private final String value;
    private final boolean launchWindowOnly;
    private final String activitySuffix;
    private final int maxTextLength;
    private final int maxWidthPx;
    private final int maxHeightPx;
    private final float maxWidthRatio;
    private final float maxHeightRatio;

    private AppRule(
            String id,
            AdType type,
            Target target,
            Mode mode,
            String value,
            boolean launchWindowOnly,
            String activitySuffix,
            int maxTextLength,
            int maxWidthPx,
            int maxHeightPx,
            float maxWidthRatio,
            float maxHeightRatio
    ) {
        this.id = id;
        this.type = type;
        this.target = target;
        this.mode = mode;
        this.value = value;
        this.launchWindowOnly = launchWindowOnly;
        this.activitySuffix = activitySuffix == null ? "" : activitySuffix;
        this.maxTextLength = maxTextLength;
        this.maxWidthPx = maxWidthPx;
        this.maxHeightPx = maxHeightPx;
        this.maxWidthRatio = maxWidthRatio;
        this.maxHeightRatio = maxHeightRatio;
    }

    public static AppRule viewId(
            String id,
            AdType type,
            String viewId,
            boolean launchWindowOnly,
            String activitySuffix
    ) {
        return new AppRule(
                id,
                type,
                Target.VIEW_ID,
                Mode.EXACT,
                viewId,
                launchWindowOnly,
                activitySuffix,
                0,
                0,
                0,
                0.0f,
                0.0f
        );
    }

    public static AppRule text(
            String id,
            AdType type,
            Mode mode,
            String value,
            boolean launchWindowOnly,
            String activitySuffix,
            int maxTextLength,
            int maxWidthPx,
            int maxHeightPx,
            float maxWidthRatio,
            float maxHeightRatio
    ) {
        return new AppRule(
                id,
                type,
                Target.TEXT_OR_DESCRIPTION,
                mode,
                value,
                launchWindowOnly,
                activitySuffix,
                maxTextLength,
                maxWidthPx,
                maxHeightPx,
                maxWidthRatio,
                maxHeightRatio
        );
    }

    public String id() {
        return id;
    }

    public AdType type() {
        return type;
    }

    public Target target() {
        return target;
    }

    public Mode mode() {
        return mode;
    }

    public String value() {
        return value;
    }

    public boolean launchWindowOnly() {
        return launchWindowOnly;
    }

    public int maxTextLength() {
        return maxTextLength;
    }

    public int maxWidthPx() {
        return maxWidthPx;
    }

    public int maxHeightPx() {
        return maxHeightPx;
    }

    public float maxWidthRatio() {
        return maxWidthRatio;
    }

    public float maxHeightRatio() {
        return maxHeightRatio;
    }

    public boolean matchesActivity(String currentWindowClass) {
        return activitySuffix.isEmpty()
                || (!currentWindowClass.isEmpty() && currentWindowClass.endsWith(activitySuffix));
    }
}
