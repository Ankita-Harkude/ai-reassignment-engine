package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.exception.NoEligibleAgentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Application-service facade for routing. It owns exactly two things: WHO is allowed to receive an order
 * (the eligibility policy) and WHICH strategy ranks them (the selector). It knows nothing about JPA,
 * HTTP, events or suggestion persistence - those belong to the callers.
 *
 * It is also the last line of defence: if the active strategy throws or returns nothing usable, the
 * result is the rule-based pick, labelled RULE_BASED_FALLBACK - never an error and never a silent drop.
 */
@Service
public class RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingEngine.class);

    private final RoutingStrategySelector selector;
    private final RuleBasedStrategy safetyNet;

    public RoutingEngine(RoutingStrategySelector selector, RuleBasedStrategy safetyNet) {
        this.selector = selector;
        this.safetyNet = safetyNet;
    }

    /** Eligible = not OFFLINE, not the order's current agent, not the agent that just failed. */
    public List<AgentSnapshot> eligibleAgents(OrderSnapshot order, List<AgentSnapshot> roster, RoutingContext context) {
        return roster.stream()
                .filter(a -> a.status() != AgentStatus.OFFLINE)
                .filter(a -> !a.id().equals(order.currentAgentId()))
                .filter(a -> !a.id().equals(context.failedAgentId()))
                .toList();
    }

    public List<Recommendation> route(OrderSnapshot order, List<AgentSnapshot> roster, RoutingContext context) {
        List<AgentSnapshot> candidates = eligibleAgents(order, roster, context);
        if (candidates.isEmpty()) {
            throw new NoEligibleAgentException(order.id());
        }
        RoutingRequest request = new RoutingRequest(order, candidates, context);

        List<Recommendation> recs;
        String failure = null;
        try {
            recs = selector.active().recommend(request);
        } catch (RuntimeException e) {
            log.warn("Strategy '{}' failed for order {}: {}", selector.activeName(), order.id(), e.toString());
            recs = List.of();
            failure = "STRATEGY_ERROR";
        }

        // Defence in depth: a buggy strategy must never smuggle in an ineligible agent.
        Set<String> allowed = candidates.stream().map(AgentSnapshot::id).collect(Collectors.toSet());
        List<Recommendation> valid = recs == null ? List.of()
                : recs.stream().filter(r -> allowed.contains(r.agentId())).toList();
        if (!valid.isEmpty()) {
            return valid;
        }
        String reason = failure != null ? failure : "NO_VALID_RECOMMENDATION";
        log.warn("Falling back to rule-based for order {} ({})", order.id(), reason);
        return safetyNet.recommend(request).stream().map(r -> r.asFallback("Routing strategy", reason)).toList();
    }
}
