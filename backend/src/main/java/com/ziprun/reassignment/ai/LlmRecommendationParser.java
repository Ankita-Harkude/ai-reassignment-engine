package com.ziprun.reassignment.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ziprun.reassignment.ai.LlmFailureException.Kind;
import org.springframework.stereotype.Component;

/** Turns raw model text into a typed result, rejecting anything that isn't exactly what we asked for. */
@Component
public class LlmRecommendationParser {

    public record Parsed(String agentId, double confidence, String reasoning) {
    }

    // Strict on trailing content: two glued objects are ambiguous, so reject (-> fallback) rather than guess.
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Parsed parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "Empty LLM response");
        }
        String json = extractJsonObject(raw);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception e) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "LLM response is not valid JSON", e);
        }
        JsonNode agent = node.get("agentId");
        JsonNode confidence = node.get("confidence");
        JsonNode reasoning = node.get("reasoning");
        if (agent == null || !agent.isTextual() || agent.asText().isBlank()
                || reasoning == null || !reasoning.isTextual() || reasoning.asText().isBlank()) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "LLM JSON is missing agentId or reasoning");
        }
        if (confidence == null || !confidence.isNumber()
                || confidence.asDouble() < 0.0 || confidence.asDouble() > 1.0) {
            throw new LlmFailureException(Kind.INVALID_CONFIDENCE, "LLM confidence missing or outside 0..1");
        }
        return new Parsed(agent.asText().trim(), confidence.asDouble(), reasoning.asText().trim());
    }

    /** Models sometimes wrap JSON in ```json fences or add a sentence around it; take the outermost braces. */
    static String extractJsonObject(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmFailureException(Kind.UNPARSEABLE, "No JSON object found in LLM response");
        }
        return raw.substring(start, end + 1);
    }
}
