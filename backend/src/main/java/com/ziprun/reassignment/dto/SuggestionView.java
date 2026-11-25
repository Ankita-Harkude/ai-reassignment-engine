package com.ziprun.reassignment.dto;

import com.ziprun.reassignment.domain.*;

import java.time.Instant;

public record SuggestionView(Long id, String orderId, String orderDescription, OrderStatus orderStatus,
                             String fromAgentId, String fromAgentName,
                             String recommendedAgentId, String recommendedAgentName,
                             double confidence, String reasoning,
                             SuggestionStatus status, TriggerReason triggerReason,
                             RecommendationSource source, String fallbackReason,
                             Instant createdAt, Instant resolvedAt) {

    public static SuggestionView from(ReassignmentSuggestion s) {
        Order o = s.getOrder();
        return new SuggestionView(s.getId(), o.getId(), o.getDescription(), o.getStatus(),
                o.getAssignedAgent().getId(), o.getAssignedAgent().getName(),
                s.getRecommendedAgent().getId(), s.getRecommendedAgent().getName(),
                s.getConfidence(), s.getReasoning(), s.getStatus(), s.getTriggerReason(),
                s.getSource(), s.getFallbackReason(), s.getCreatedAt(), s.getResolvedAt());
    }
}
