package com.ziprun.reassignment.routing;

import com.ziprun.reassignment.ai.LlmFailureException;
import com.ziprun.reassignment.ai.LlmFailureException.Kind;
import com.ziprun.reassignment.ai.LlmGateway;
import com.ziprun.reassignment.ai.LlmRecommendationParser;
import com.ziprun.reassignment.ai.RoutingPromptFactory;
import com.ziprun.reassignment.domain.RecommendationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM-backed strategy. The contract it keeps with callers: it NEVER throws for an AI problem. Any failure
 * (timeout, quota, garbage JSON, hallucinated agent id, bad confidence) degrades to the rule-based result,
 * tagged RULE_BASED_FALLBACK with the failure kind, so a re-plan is never silently dropped.
 */
@Component("ai")
public class AiRoutingStrategy implements RoutingStrategy {

    private static final Logger log = LoggerFactory.getLogger(AiRoutingStrategy.class);

    private final LlmGateway gateway;
    private final RoutingPromptFactory prompts;
    private final LlmRecommendationParser parser;
    private final RuleBasedStrategy fallback;

    public AiRoutingStrategy(LlmGateway gateway, RoutingPromptFactory prompts,
                             LlmRecommendationParser parser, RuleBasedStrategy fallback) {
        this.gateway = gateway;
        this.prompts = prompts;
        this.parser = parser;
        this.fallback = fallback;
    }

    @Override
    public List<Recommendation> recommend(RoutingRequest request) {
        try {
            String raw = gateway.complete(prompts.build(request));
            LlmRecommendationParser.Parsed parsed = parser.parse(raw);

            AgentSnapshot chosen = request.candidates().stream()
                    .filter(a -> a.id().equalsIgnoreCase(parsed.agentId()))
                    .findFirst()
                    .orElseThrow(() -> new LlmFailureException(Kind.UNKNOWN_AGENT,
                            "Model returned agent '" + parsed.agentId() + "' which is not in the roster"));

            return List.of(new Recommendation(chosen.id(), parsed.confidence(), parsed.reasoning(),
                    RecommendationSource.AI, null));
        } catch (LlmFailureException e) {
            return degrade(request, e.kind().name(), e);
        } catch (RuntimeException e) {
            return degrade(request, "UNEXPECTED", e);
        }
    }

    private List<Recommendation> degrade(RoutingRequest request, String reason, RuntimeException cause) {
        log.warn("AI routing failed for order {} ({}): {} - falling back to rule-based",
                request.order().id(), reason, cause.getMessage());
        return fallback.recommend(request).stream().map(r -> r.asFallback("AI", reason)).toList();
    }
}
