package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.RecommendationSource;

public record Recommendation(String agentId, double confidence, String reasoning,
                             RecommendationSource source, String fallbackReason) {

    /**
     * Relabels a rule-based pick as a fallback: source RULE_BASED_FALLBACK, the machine-readable reason,
     * and a human-readable prefix so ops can see in the UI that the preferred strategy was bypassed.
     */
    public Recommendation asFallback(String subject, String reason) {
        return new Recommendation(agentId, confidence,
                subject + " unavailable (" + reason + "), rule-based pick instead. " + reasoning,
                RecommendationSource.RULE_BASED_FALLBACK, reason);
    }
}
