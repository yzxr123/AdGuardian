package org.adguardian.app.engine;

import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

public final class ActionGate {
    private static final long SAME_RULE_COOLDOWN_MS = 650L;

    private final Map<String, Long> lastSuccessByRule = new HashMap<>();

    public boolean canAct(String ruleId) {
        Long lastSuccess = lastSuccessByRule.get(ruleId);
        return lastSuccess == null
                || SystemClock.uptimeMillis() - lastSuccess >= SAME_RULE_COOLDOWN_MS;
    }

    public void recordSuccess(String ruleId) {
        lastSuccessByRule.put(ruleId, SystemClock.uptimeMillis());
    }
}
