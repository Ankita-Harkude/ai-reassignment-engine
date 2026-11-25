package com.ziprun.reassignment.domain;

import com.ziprun.reassignment.exception.InvalidTransitionException;
import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "reassignment_suggestions")
public class ReassignmentSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id")
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommended_agent_id")
    private Agent recommendedAgent;

    /** 0.0 - 1.0. For AI this is the model's self-reported confidence; for rule-based a fixed heuristic tier. */
    @Column(nullable = false)
    private double confidence;

    @Column(nullable = false, length = 2000)
    private String reasoning;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SuggestionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_reason", nullable = false)
    private TriggerReason triggerReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RecommendationSource source;

    /** Why the AI was bypassed (TIMEOUT, UNKNOWN_AGENT, ...). Null unless source = RULE_BASED_FALLBACK. */
    @Column(name = "fallback_reason")
    private String fallbackReason;

    /**
     * "<TRIGGER>:<orderId>" while PENDING, null once resolved. The unique constraint makes
     * "at most one open suggestion per order per trigger" a database guarantee, not just a check.
     */
    @Column(name = "active_key", unique = true)
    private String activeKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Version
    private Long version;

    protected ReassignmentSuggestion() {
    }

    public ReassignmentSuggestion(Order order, Agent recommendedAgent, double confidence, String reasoning,
                                  TriggerReason triggerReason, RecommendationSource source, String fallbackReason) {
        this.order = order;
        this.recommendedAgent = recommendedAgent;
        this.confidence = confidence;
        this.reasoning = reasoning;
        this.triggerReason = triggerReason;
        this.source = source;
        this.fallbackReason = fallbackReason;
        this.status = SuggestionStatus.PENDING;
        this.activeKey = keyFor(triggerReason, order.getId());
        this.createdAt = Instant.now();
    }

    public static String keyFor(TriggerReason trigger, String orderId) {
        return trigger.name() + ":" + orderId;
    }

    // ---- state machine: PENDING -> ACCEPTED | REJECTED ---------------------------------------

    public void accept() {
        resolve(SuggestionStatus.ACCEPTED);
    }

    public void reject() {
        resolve(SuggestionStatus.REJECTED);
    }

    private void resolve(SuggestionStatus target) {
        if (status != SuggestionStatus.PENDING) {
            throw new InvalidTransitionException("Suggestion " + id + " is already " + status);
        }
        this.status = target;
        this.activeKey = null;
        this.resolvedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public Agent getRecommendedAgent() { return recommendedAgent; }
    public double getConfidence() { return confidence; }
    public String getReasoning() { return reasoning; }
    public SuggestionStatus getStatus() { return status; }
    public TriggerReason getTriggerReason() { return triggerReason; }
    public RecommendationSource getSource() { return source; }
    public String getFallbackReason() { return fallbackReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
