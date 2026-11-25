package com.ziprun.reassignment.ai;

import com.ziprun.reassignment.ai.LlmFailureException.Kind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Edge cases of turning raw model text into a typed recommendation. */
class LlmRecommendationParserTest {

    private final LlmRecommendationParser parser = new LlmRecommendationParser();

    private void assertFails(String raw, Kind expected) {
        assertThatThrownBy(() -> parser.parse(raw))
                .isInstanceOfSatisfying(LlmFailureException.class, e -> assertThat(e.kind()).isEqualTo(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t"})
    void nullEmptyOrBlankResponseIsUnparseable(String raw) {
        assertFails(raw, Kind.UNPARSEABLE);
    }

    @Test
    void plainProseWithNoJsonIsUnparseable() {
        assertFails("I would send it to Rahul, he is free.", Kind.UNPARSEABLE);
    }

    @Test
    void invalidJsonInsideBracesIsUnparseable() {
        assertFails("{agentId: AGT-2, confidence: high}", Kind.UNPARSEABLE);
    }

    @Test
    void twoJsonObjectsGlued_togetherAreRejectedNotGuessed() {
        assertFails("{\"agentId\":\"A\",\"confidence\":0.5,\"reasoning\":\"x\"} {\"agentId\":\"B\"}", Kind.UNPARSEABLE);
    }

    @Test
    void missingAgentIdIsUnparseable() {
        assertFails("{\"confidence\":0.5,\"reasoning\":\"x\"}", Kind.UNPARSEABLE);
    }

    @Test
    void nonStringAgentIdIsUnparseable() {
        assertFails("{\"agentId\":5,\"confidence\":0.5,\"reasoning\":\"x\"}", Kind.UNPARSEABLE);
    }

    @Test
    void blankAgentIdIsUnparseable() {
        assertFails("{\"agentId\":\"  \",\"confidence\":0.5,\"reasoning\":\"x\"}", Kind.UNPARSEABLE);
    }

    @Test
    void missingOrBlankReasoningIsUnparseable() {
        assertFails("{\"agentId\":\"A\",\"confidence\":0.5}", Kind.UNPARSEABLE);
        assertFails("{\"agentId\":\"A\",\"confidence\":0.5,\"reasoning\":\"   \"}", Kind.UNPARSEABLE);
    }

    @Test
    void missingConfidenceIsInvalidConfidence() {
        assertFails("{\"agentId\":\"A\",\"reasoning\":\"x\"}", Kind.INVALID_CONFIDENCE);
    }

    @Test
    void confidenceAsStringIsRejectedEvenIfItLooksNumeric() {
        assertFails("{\"agentId\":\"A\",\"confidence\":\"0.9\",\"reasoning\":\"x\"}", Kind.INVALID_CONFIDENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-0.01", "1.01", "85", "-1"})
    void confidenceOutsideZeroToOneIsInvalid(String confidence) {
        assertFails("{\"agentId\":\"A\",\"confidence\":" + confidence + ",\"reasoning\":\"x\"}", Kind.INVALID_CONFIDENCE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.0", "1", "1.0", "0.5"})
    void confidenceBoundariesAreAccepted(String confidence) {
        var parsed = parser.parse("{\"agentId\":\"A\",\"confidence\":" + confidence + ",\"reasoning\":\"x\"}");
        assertThat(parsed.confidence()).isBetween(0.0, 1.0);
    }

    @Test
    void jsonWrappedInMarkdownFencesAndChatterIsExtracted() {
        var parsed = parser.parse("Sure! Here is my answer:\n```json\n"
                + "{\"agentId\":\"AGT-2\",\"confidence\":0.8,\"reasoning\":\"closest\"}\n```\nHope that helps.");

        assertThat(parsed.agentId()).isEqualTo("AGT-2");
        assertThat(parsed.reasoning()).isEqualTo("closest");
    }

    @Test
    void agentIdAndReasoningAreTrimmed() {
        var parsed = parser.parse("{\"agentId\":\"  AGT-2  \",\"confidence\":0.7,\"reasoning\":\"  ok  \"}");

        assertThat(parsed.agentId()).isEqualTo("AGT-2");
        assertThat(parsed.reasoning()).isEqualTo("ok");
    }

    @Test
    void braceCharactersInsideReasoningDoNotBreakExtraction() {
        var parsed = parser.parse("{\"agentId\":\"A\",\"confidence\":0.6,\"reasoning\":\"uses {zone} logic\"}");

        assertThat(parsed.reasoning()).isEqualTo("uses {zone} logic");
    }

    @Test
    void extraUnknownFieldsFromTheModelAreIgnored() {
        var parsed = parser.parse("{\"agentId\":\"A\",\"confidence\":0.6,\"reasoning\":\"x\",\"thoughts\":\"...\"}");

        assertThat(parsed.agentId()).isEqualTo("A");
    }
}
