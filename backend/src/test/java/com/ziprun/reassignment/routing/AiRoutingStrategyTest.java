package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.ai.LlmFailureException;
import com.ziprun.reassignment.ai.LlmGateway;
import com.ziprun.reassignment.ai.LlmRecommendationParser;
import com.ziprun.reassignment.ai.RoutingPromptFactory;
import com.ziprun.reassignment.domain.AgentStatus;
import com.ziprun.reassignment.domain.RecommendationSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiRoutingStrategyTest {

    private final RoutingRequest request = new RoutingRequest(
            new OrderSnapshot("ORD-1", "Groceries", "AGT-X", "Failed Agent"),
            List.of(new AgentSnapshot("AGT-2", "Rahul", AgentStatus.AVAILABLE, 0, 0),
                    new AgentSnapshot("AGT-4", "Kiran", AgentStatus.BUSY, 2, 0)),
            RoutingContext.initial());

    private AiRoutingStrategy strategyReturning(LlmGateway gateway) {
        return new AiRoutingStrategy(gateway, new RoutingPromptFactory(), new LlmRecommendationParser(),
                new RuleBasedStrategy());
    }

    private void assertFellBackBecause(List<Recommendation> recs, String reason) {
        assertThat(recs).isNotEmpty();
        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.RULE_BASED_FALLBACK);
        assertThat(recs.get(0).fallbackReason()).isEqualTo(reason);
        assertThat(recs.get(0).agentId()).isEqualTo("AGT-2");   // rule-based winner
        assertThat(recs.get(0).reasoning()).contains("AI unavailable");
    }

    @Test
    void validResponseIsUsedVerbatim() {
        var recs = strategyReturning(p -> """
                {"agentId":"AGT-4","confidence":0.91,"reasoning":"Kiran is nearest to pickup."}""")
                .recommend(request);

        assertThat(recs).hasSize(1);
        assertThat(recs.get(0).agentId()).isEqualTo("AGT-4");
        assertThat(recs.get(0).confidence()).isEqualTo(0.91);
        assertThat(recs.get(0).reasoning()).isEqualTo("Kiran is nearest to pickup.");
        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.AI);
        assertThat(recs.get(0).fallbackReason()).isNull();
    }

    @Test
    void markdownFencedJsonIsAccepted() {
        var recs = strategyReturning(p -> "```json\n{\"agentId\":\"AGT-2\",\"confidence\":0.8,\"reasoning\":\"ok\"}\n```")
                .recommend(request);

        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.AI);
    }

    @Test
    void hallucinatedAgentIdFallsBack() {
        assertFellBackBecause(strategyReturning(p ->
                "{\"agentId\":\"AGT-999\",\"confidence\":0.9,\"reasoning\":\"trust me\"}").recommend(request),
                "UNKNOWN_AGENT");
    }

    @Test
    void garbageResponseFallsBack() {
        assertFellBackBecause(strategyReturning(p -> "Sure! I'd pick Rahul.").recommend(request), "UNPARSEABLE");
    }

    @Test
    void outOfRangeConfidenceFallsBack() {
        assertFellBackBecause(strategyReturning(p ->
                "{\"agentId\":\"AGT-4\",\"confidence\":1.7,\"reasoning\":\"x\"}").recommend(request),
                "INVALID_CONFIDENCE");
    }

    @Test
    void timeoutFallsBack() {
        assertFellBackBecause(strategyReturning(p -> {
            throw new LlmFailureException(LlmFailureException.Kind.TIMEOUT, "read timed out");
        }).recommend(request), "TIMEOUT");
    }

    @Test
    void quotaExhaustedFallsBack() {
        assertFellBackBecause(strategyReturning(p -> {
            throw new LlmFailureException(LlmFailureException.Kind.QUOTA, "HTTP 429");
        }).recommend(request), "QUOTA");
    }

    @Test
    void unexpectedRuntimeErrorStillFallsBack() {
        assertFellBackBecause(strategyReturning(p -> {
            throw new IllegalStateException("boom");
        }).recommend(request), "UNEXPECTED");
    }

    @Test
    void agentIdCaseAndWhitespaceAreForgivenAndResolvedToTheCanonicalId() {
        var recs = strategyReturning(p ->
                "{\"agentId\":\"  agt-4 \",\"confidence\":0.7,\"reasoning\":\"fine\"}").recommend(request);

        assertThat(recs.get(0).source()).isEqualTo(RecommendationSource.AI);
        assertThat(recs.get(0).agentId()).isEqualTo("AGT-4");
    }

    @Test
    void nullResponseFromTheGatewayFallsBack() {
        assertFellBackBecause(strategyReturning(p -> null).recommend(request), "UNPARSEABLE");
    }

    @Test
    void modelPickingTheFailedOrCurrentAgentIsTreatedAsAHallucination() {
        // AGT-X is the order's current (failed) agent and is deliberately not in the candidate list.
        assertFellBackBecause(strategyReturning(p ->
                "{\"agentId\":\"AGT-X\",\"confidence\":0.95,\"reasoning\":\"they know the area\"}")
                .recommend(request), "UNKNOWN_AGENT");
    }

    @Test
    void everyFallbackRecommendationIsLabelledNotJustTheFirst() {
        var recs = strategyReturning(p -> "garbage").recommend(request);

        assertThat(recs).hasSize(2);
        assertThat(recs).allSatisfy(r -> {
            assertThat(r.source()).isEqualTo(RecommendationSource.RULE_BASED_FALLBACK);
            assertThat(r.fallbackReason()).isEqualTo("UNPARSEABLE");
        });
    }

    @Test
    void theModelIsAskedOncePerCallAndItsPromptNamesTheSituation() {
        var prompts = new java.util.ArrayList<String>();
        var replan = new RoutingRequest(request.order(), request.candidates(),
                RoutingContext.agentOffline("AGT-X", "Failed Agent", List.of("ORD-1", "ORD-2")));

        strategyReturning(p -> {
            prompts.add(p);
            return "{\"agentId\":\"AGT-2\",\"confidence\":0.8,\"reasoning\":\"x\"}";
        }).recommend(replan);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("RECOVERY RE-PLAN", "Failed Agent", "ORD-1, ORD-2");
    }
}
