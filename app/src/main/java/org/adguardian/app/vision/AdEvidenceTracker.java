package org.adguardian.app.vision;

import java.util.HashMap;
import java.util.Map;

public final class AdEvidenceTracker {
    private final Map<String, Integer> lastCountdownByPackage = new HashMap<>();
    private final Map<String, Boolean> evidenceByPackage = new HashMap<>();

    public void beginSession(String packageName) {
        lastCountdownByPackage.remove(packageName);
        evidenceByPackage.remove(packageName);
    }

    public boolean update(String packageName, boolean ocrAdEvidence, Integer countdown) {
        boolean evidence = Boolean.TRUE.equals(evidenceByPackage.get(packageName)) || ocrAdEvidence;
        Integer previous = lastCountdownByPackage.get(packageName);
        if (countdown != null) {
            if (previous != null && countdown < previous && previous - countdown <= 5) {
                evidence = true;
            }
            lastCountdownByPackage.put(packageName, countdown);
        }
        evidenceByPackage.put(packageName, evidence);
        return evidence;
    }

    public boolean hasEvidence(String packageName) {
        return Boolean.TRUE.equals(evidenceByPackage.get(packageName));
    }
}
