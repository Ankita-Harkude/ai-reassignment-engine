package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.RecommendationSource;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Deterministic, dependency-free strategy: the eligible agent with the fewest orders wins.
 * "Fewest" counts suggestions already queued for the agent, so a bulk re-plan spreads out.
 * Ties: AVAILABLE before BUSY, then agent id (stable and testable).
 *
 * Confidence is a fixed heuristic tier, NOT a probability: it is higher when the winner is idle and
 * clearly lighter than the runner-up. Documented in the ADR.
 */
@Component("rule-based")
public class RuleBasedStrategy implements RoutingStrategy {

    static final int MAX_RESULTS = 3;

    private static final Comparator<AgentSnapshot> ORDERING = Comparator
            .comparingInt(AgentSnapshot::projectedLoad)
            .thenComparing(a -> a.status() == AgentStatus.AVAILABLE ? 0 : 1)
            .thenComparing(AgentSnapshot::id);

    @Override
    public List<Recommendation> recommend(RoutingRequest request) {
        List<AgentSnapshot> ranked = request.candidates().stream().sorted(ORDERING).toList();
        int shown = Math.min(MAX_RESULTS, ranked.size());
        return IntStream.range(0, shown).mapToObj(i -> toRecommendation(ranked, i)).toList();
    }

    private Recommendation toRecommendation(List<AgentSnapshot> ranked, int index) {
        AgentSnapshot agent = ranked.get(index);
        AgentSnapshot next = index + 1 < ranked.size() ? ranked.get(index + 1) : null;

        double tier = 0.65;
        if (agent.status() == AgentStatus.AVAILABLE) tier += 0.10;
        if (next == null || agent.projectedLoad() < next.projectedLoad()) tier += 0.10;
        double confidence = Math.max(0.5, tier - 0.1 * index);

        StringBuilder why = new StringBuilder(agent.name()).append(" (").append(agent.status()).append(") is carrying ")
                .append(agent.activeOrders()).append(agent.activeOrders() == 1 ? " active order" : " active orders");
        if (agent.pendingIncoming() > 0) {
            why.append(" plus ").append(agent.pendingIncoming()).append(" already suggested");
        }
        why.append(", the lightest load among ").append(ranked.size()).append(" eligible agents");
        if (index > 0) {
            why = new StringBuilder(agent.name()).append(" is alternative #").append(index + 1)
                    .append(" with a projected load of ").append(agent.projectedLoad());
        }
        return new Recommendation(agent.id(), Math.round(confidence * 100) / 100.0,
                why.append('.').toString(), RecommendationSource.RULE_BASED, null);
    }
}
