package org.adguardian.app.engine;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import org.adguardian.app.BuildConfig;
import org.adguardian.app.debug.DebugLogStore;
import org.adguardian.app.debug.DiagnosticSnapshot;
import org.adguardian.app.settings.PreferenceStore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RuleEngine {
    private static final long NO_MATCH_LOG_INTERVAL_MS = 1_500L;
    private static final long ACTION_FAILURE_LOG_INTERVAL_MS = 1_000L;

    private final Context context;
    private final RuleMatcher matcher = new RuleMatcher();
    private final ActionGate actionGate = new ActionGate();
    private final GenericAdEngine genericAdEngine;
    private final Map<String, Long> lastNoMatchLogByScope = new HashMap<>();
    private final Map<String, Long> lastActionFailureLogByRule = new HashMap<>();

    private long activeLaunchWindowToken = 0L;
    private boolean startupActionDone = false;

    public RuleEngine(AccessibilityService service) {
        this.context = service.getApplicationContext();
        this.genericAdEngine = new GenericAdEngine(service);
    }

    public boolean handle(
            String packageName,
            String currentWindowClass,
            AccessibilityNodeInfo root,
            long launchHotUntilUptime,
            boolean allowGeneric,
            boolean windowChanged
    ) {
        if (launchHotUntilUptime != activeLaunchWindowToken) {
            activeLaunchWindowToken = launchHotUntilUptime;
            startupActionDone = false;
        }

        long now = SystemClock.uptimeMillis();
        SpecificResult specific = handleSpecific(
                packageName,
                currentWindowClass,
                root,
                launchHotUntilUptime,
                now
        );
        if (specific.clicked) {
            return true;
        }

        GenericAdEngine.Result generic = genericAdEngine.handle(
                packageName,
                currentWindowClass,
                root,
                launchHotUntilUptime,
                allowGeneric
        );
        if (generic.clicked()) {
            if (generic.type() == AdType.STARTUP) {
                startupActionDone = true;
            }
            DebugLogStore.success(
                    context,
                    "GENERIC_BLOCKED",
                    "pkg=" + packageName
                            + " type=" + generic.type().displayName()
                            + " evidence=" + generic.evidence()
                            + " action=" + generic.actionMode()
                            + " visited=" + generic.visited()
                            + " node={" + generic.node() + "}"
            );
            return true;
        }

        if (generic.actionFailed()) {
            logGenericActionFailure(packageName, generic, now);
        }

        boolean launchWindow = now <= launchHotUntilUptime;
        if (generic.signalSeen()) {
            logNoMatch(
                    "AD_SIGNAL_NOT_HANDLED",
                    packageName,
                    currentWindowClass,
                    root,
                    specific.enabledRuleCount,
                    specific.attemptedRuleCount,
                    launchWindow,
                    "genericEvidence=" + generic.evidence(),
                    now
            );
        } else if (windowChanged || launchWindow || specific.enabledRuleCount > 0) {
            logNoMatch(
                    "NO_MATCH",
                    packageName,
                    currentWindowClass,
                    root,
                    specific.enabledRuleCount,
                    specific.attemptedRuleCount,
                    launchWindow,
                    "generic=false",
                    now
            );
        }
        return false;
    }

    private SpecificResult handleSpecific(
            String packageName,
            String currentWindowClass,
            AccessibilityNodeInfo root,
            long launchHotUntilUptime,
            long now
    ) {
        List<AppRule> rules = RuleRepository.rulesFor(packageName);
        if (rules.isEmpty()) {
            return SpecificResult.none();
        }

        int enabledRuleCount = 0;
        int attemptedRuleCount = 0;
        for (AppRule rule : rules) {
            if (!PreferenceStore.isTypeEnabled(context, rule.type())) {
                continue;
            }
            enabledRuleCount++;
            if (!rule.matchesActivity(currentWindowClass)) {
                continue;
            }
            if (rule.launchWindowOnly()) {
                if (now > launchHotUntilUptime || startupActionDone) {
                    continue;
                }
            }
            if (!actionGate.canAct(rule.id())) {
                continue;
            }

            attemptedRuleCount++;
            RuleMatcher.MatchResult result = matcher.matchAndClick(root, rule);
            if (result.clicked()) {
                actionGate.recordSuccess(rule.id());
                if (rule.type() == AdType.STARTUP) {
                    startupActionDone = true;
                }
                DebugLogStore.success(
                        context,
                        "SPECIFIC_BLOCKED",
                        "app=" + RuleRepository.displayNameFor(packageName)
                                + " pkg=" + packageName
                                + " type=" + rule.type().displayName()
                                + " rule=" + rule.id()
                                + " candidates=" + result.candidateCount()
                );
                return new SpecificResult(true, enabledRuleCount, attemptedRuleCount);
            }

            if (result.candidateCount() > 0 && shouldLogActionFailure(rule.id(), now)) {
                DebugLogStore.miss(
                        context,
                        "SPECIFIC_ACTION_NOT_COMPLETED",
                        "app=" + RuleRepository.displayNameFor(packageName)
                                + " rule=" + rule.id()
                                + " candidates=" + result.candidateCount()
                                + " eligible=" + result.eligibleCount()
                                + " clickable=" + result.clickableCount()
                                + " actionFailed=" + result.failedActionCount()
                );
            }
        }
        return new SpecificResult(false, enabledRuleCount, attemptedRuleCount);
    }

    private boolean shouldLogActionFailure(String ruleId, long now) {
        Long last = lastActionFailureLogByRule.get(ruleId);
        if (last != null && now - last < ACTION_FAILURE_LOG_INTERVAL_MS) {
            return false;
        }
        lastActionFailureLogByRule.put(ruleId, now);
        return true;
    }

    private void logGenericActionFailure(
            String packageName,
            GenericAdEngine.Result result,
            long now
    ) {
        String key = "generic|" + packageName + '|' + result.evidence();
        if (!shouldLogActionFailure(key, now)) {
            return;
        }
        DebugLogStore.miss(
                context,
                "GENERIC_ACTION_NOT_COMPLETED",
                "pkg=" + packageName
                        + " type=" + (result.type() == null ? "unknown" : result.type().displayName())
                        + " evidence=" + result.evidence()
                        + " node={" + result.node() + "}"
        );
    }

    private void logNoMatch(
            String code,
            String packageName,
            String currentWindowClass,
            AccessibilityNodeInfo root,
            int enabledRuleCount,
            int attemptedRuleCount,
            boolean launchWindow,
            String extra,
            long now
    ) {
        if (!BuildConfig.DEBUG) {
            return;
        }
        String scope = code + '|' + packageName + '|' + currentWindowClass;
        Long last = lastNoMatchLogByScope.get(scope);
        if (last != null && now - last < NO_MATCH_LOG_INTERVAL_MS) {
            return;
        }
        lastNoMatchLogByScope.put(scope, now);
        DebugLogStore.miss(
                context,
                code,
                "app=" + RuleRepository.displayNameFor(packageName)
                        + " pkg=" + packageName
                        + " activity=" + currentWindowClass
                        + " specificEnabled=" + enabledRuleCount
                        + " specificAttempted=" + attemptedRuleCount
                        + " launchWindow=" + launchWindow
                        + " " + extra
                        + " snapshot={" + DiagnosticSnapshot.capture(root) + "}"
        );
    }

    private static final class SpecificResult {
        private final boolean clicked;
        private final int enabledRuleCount;
        private final int attemptedRuleCount;

        private SpecificResult(boolean clicked, int enabledRuleCount, int attemptedRuleCount) {
            this.clicked = clicked;
            this.enabledRuleCount = enabledRuleCount;
            this.attemptedRuleCount = attemptedRuleCount;
        }

        private static SpecificResult none() {
            return new SpecificResult(false, 0, 0);
        }
    }
}
