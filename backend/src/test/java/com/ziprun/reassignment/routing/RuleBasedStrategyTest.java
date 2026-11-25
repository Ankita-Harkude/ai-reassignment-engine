package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.RecommendationSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedStrategyTest {

    private final RuleBasedStrategy strategy = new RuleBasedStrategy();

    static RoutingRequest request(RoutingContext ctx, AgentSnapshot... agents) {
        return new RoutingRequest(new OrderSnapshot("ORD-1", "Test order", "AGT-X", "Failed Agent"),
                List.of(agents), ctx);
    }

    @Test
    void picksAgentWithFewestActiveOrders() {
        var recs = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 3, 0),
                new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 1, 0),
                new AgentSnapshot("A3", "Cy", AgentStatus.BUSY, 2, 0)));

        assertThat(recs.get(0).agentId()).isEqualTo("A2");
        assertThat(recs).extracting(Recommendation::agentId).containsExactly("A2", "A3", "A1");
        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.RULE_BASED);
    }

    @Test
    void queuedSuggestionsCountTowardsLoadSoBulkReplanSpreads() {
        var recs = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.AVAILABLE, 0, 2),   // idle but 2 already queued
                new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 1, 0)));

        assertThat(recs.get(0).agentId()).isEqualTo("A2");
    }

    @Test
    void tiesPreferAvailableThenId() {
        var recs = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 1, 0),
                new AgentSnapshot("A3", "Cy", AgentStatus.AVAILABLE, 1, 0),
                new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 1, 0)));

        assertThat(recs).extracting(Recommendation::agentId).containsExactly("A3", "A1", "A2");
    }

    @Test
    void confidenceIsWithinRangeAndHigherForClearIdleWinner() {
        var clear = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.AVAILABLE, 0, 0),
                new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 4, 0))).get(0);
        var close = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 2, 0),
                new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 2, 0))).get(0);

        assertThat(clear.confidence()).isBetween(0.0, 1.0).isGreaterThan(close.confidence());
        assertThat(clear.reasoning()).contains("Ann");
    }

    @Test
    void returnsAtMostThreeRecommendations() {
        var recs = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "a", AgentStatus.BUSY, 1, 0),
                new AgentSnapshot("A2", "b", AgentStatus.BUSY, 2, 0),
                new AgentSnapshot("A3", "c", AgentStatus.BUSY, 3, 0),
                new AgentSnapshot("A4", "d", AgentStatus.BUSY, 4, 0)));

        assertThat(recs).hasSize(3);
    }

    @Test
    void noCandidatesYieldsNoRecommendationsInsteadOfFailing() {
        assertThat(strategy.recommend(request(RoutingContext.initial()))).isEmpty();
    }

    @Test
    void singleCandidateIsStillRecommendedWithAHigherConfidenceWhenIdle() {
        var idle = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.AVAILABLE, 0, 0)));
        var busy = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 5, 0)));

        assertThat(idle).hasSize(1);
        assertThat(idle.get(0).confidence()).isEqualTo(0.85);
        assertThat(busy.get(0).confidence()).isEqualTo(0.75);
    }

    @Test
    void confidenceNeverDropsBelowTheFloorEvenForLowRankedAlternatives() {
        var recs = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "a", AgentStatus.BUSY, 2, 0),
                new AgentSnapshot("A2", "b", AgentStatus.BUSY, 2, 0),
                new AgentSnapshot("A3", "c", AgentStatus.BUSY, 2, 0),
                new AgentSnapshot("A4", "d", AgentStatus.BUSY, 2, 0)));

        assertThat(recs).extracting(Recommendation::confidence).allSatisfy(c -> assertThat(c).isBetween(0.5, 1.0));
    }

    @Test
    void reasoningMentionsAlreadyQueuedSuggestionsAndUsesSingularForOneOrder() {
        var rec = strategy.recommend(request(RoutingContext.initial(),
                new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 1, 2))).get(0);

        assertThat(rec.reasoning()).contains("1 active order ").contains("plus 2 already suggested");
    }

    @Test
    void identicalAgentsAreRankedDeterministicallyRegardlessOfInputOrder() {
        var a = new AgentSnapshot("A1", "Ann", AgentStatus.BUSY, 1, 0);
        var b = new AgentSnapshot("A2", "Bob", AgentStatus.BUSY, 1, 0);

        assertThat(strategy.recommend(request(RoutingContext.initial(), a, b)).get(0).agentId())
                .isEqualTo(strategy.recommend(request(RoutingContext.initial(), b, a)).get(0).agentId())
                .isEqualTo("A1");
    }
}
