package com.ziprun.reassignment.domain;

/** Who actually produced a recommendation - lets ops see when the AI was bypassed. */
public enum RecommendationSource {
    AI, RULE_BASED, RULE_BASED_FALLBACK
}
