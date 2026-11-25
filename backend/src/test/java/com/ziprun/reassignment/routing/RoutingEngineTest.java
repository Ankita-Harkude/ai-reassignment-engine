package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.RecommendationSource;
import com.ziprun.reassignment.exception.NoEligibleAgentException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingEngineTest {

    private final RuleBasedStrategy ruleBased = new RuleBasedStrategy();
    private final OrderSnapshot order = new OrderSnapshot("ORD-1", "Test", "AGT-1", "Priya");
    private final List<AgentSnapshot> roster = List.of(
            new AgentSnapshot("AGT-1", "Priya", AgentStatus.BUSY, 3, 0),      // current holder
            new AgentSnapshot("AGT-2", "Rahul", AgentStatus.AVAILABLE, 0, 0),
            new AgentSnapshot("AGT-3", "Ananya", AgentStatus.OFFLINE, 0, 0),  // offline
            new AgentSnapshot("AGT-4", "Kiran", AgentStatus.BUSY, 2, 0));

    private RoutingEngine engineWith(RoutingStrategy active) {
        var selector = new RoutingStrategySelector(Map.of("rule-based", ruleBased, "custom", active), "custom");
        return new RoutingEngine(selector, ruleBased);
    }

    @Test
    void eligibilityExcludesOfflineCurrentAndFailedAgents() {
        var engine = engineWith(ruleBased);
        var ctx = RoutingContext.agentOffline("AGT-2", "Rahul", List.of("ORD-1"));

        assertThat(engine.eligibleAgents(order, roster, ctx)).extracting(AgentSnapshot::id).containsExactly("AGT-4");
    }

    @Test
    void noEligibleAgentIsAnExplicitError() {
        var engine = engineWith(ruleBased);
        var onlyIneligible = List.of(roster.get(0), roster.get(2));

        assertThatThrownBy(() -> engine.route(order, onlyIneligible, RoutingContext.initial()))
                .isInstanceOf(NoEligibleAgentException.class);
    }

    @Test
    void aStrategyThatThrowsFallsBackToRuleBasedAndIsLabelled() {
        var recs = engineWith(req -> { throw new IllegalStateException("boom"); })
                .route(order, roster, RoutingContext.initial());

        assertThat(recs.get(0).agentId()).isEqualTo("AGT-2");
        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.RULE_BASED_FALLBACK);
        assertThat(recs.get(0).fallbackReason()).isEqualTo("STRATEGY_ERROR");
        assertThat(recs.get(0).reasoning()).contains("unavailable (STRATEGY_ERROR)");
    }

    @Test
    void aStrategyReturningAnIneligibleAgentIsRejectedAndFallsBackLabelled() {
        var offlineAgent = new Recommendation("AGT-3", 0.99, "pick the offline one", RecommendationSource.AI, null);
        var recs = engineWith(req -> List.of(offlineAgent)).route(order, roster, RoutingContext.initial());

        assertThat(recs).extracting(Recommendation::agentId).doesNotContain("AGT-3");
        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.RULE_BASED_FALLBACK);
        assertThat(recs.get(0).fallbackReason()).isEqualTo("NO_VALID_RECOMMENDATION");
    }

    @Test
    void aHealthyStrategyResultPassesThroughUnlabelled() {
        var good = new Recommendation("AGT-4", 0.9, "closest", RecommendationSource.AI, null);
        var recs = engineWith(req -> List.of(good)).route(order, roster, RoutingContext.initial());

        assertThat(recs).containsExactly(good);
    }
}
