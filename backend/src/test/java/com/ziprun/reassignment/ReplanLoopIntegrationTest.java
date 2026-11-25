package com.ziprun.reassignment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end through the real Spring context and H2. No LLM key is configured here, so the "ai" strategy
 * also exercises the real fallback path (NOT_CONFIGURED -> RULE_BASED_FALLBACK).
 */
@SpringBootTest(properties = "llm.api-key=")   // force "no key" even if LLM_API_KEY is set in the shell
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ReplanLoopIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @BeforeEach
    void useRuleBased() throws Exception {
        patch("/routing/strategy", "{\"strategy\":\"rule-based\"}");
    }

    private JsonNode patch(String url, String body) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.patch(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private JsonNode get(String url) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get(url)).andExpect(status().isOk()).andReturn();
        return json.readTree(r.getResponse().getContentAsString());
    }

    private List<JsonNode> pendingOfflineSuggestions() throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode s : get("/suggestions?status=PENDING")) {
            if ("AGENT_OFFLINE".equals(s.get("triggerReason").asText())) out.add(s);
        }
        return out;
    }

    @Test
    void agentGoingOfflineQueuesOneSuggestionPerStrandedOrderAndDoesNotAutoAssign() throws Exception {
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");   // owns ORD-001, 002, 008

        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);

        List<JsonNode> suggestions = pendingOfflineSuggestions();
        assertThat(suggestions).extracting(s -> s.get("orderId").asText())
                .containsExactlyInAnyOrder("ORD-001", "ORD-002", "ORD-008");
        assertThat(suggestions).allSatisfy(s -> {
            assertThat(s.get("recommendedAgentId").asText()).isNotEqualTo("AGT-001");
            assertThat(s.get("fromAgentId").asText()).isEqualTo("AGT-001");
            assertThat(s.get("reasoning").asText()).isNotBlank();
        });
        // load is spread: the three recommendations are not all the same agent
        assertThat(suggestions.stream().map(s -> s.get("recommendedAgentId").asText()).distinct().count())
                .isGreaterThan(1);

        // checkpoint: nothing changed hands yet
        for (JsonNode o : get("/orders?status=REASSIGNMENT_PENDING")) {
            assertThat(o.get("assignedAgentId").asText()).isEqualTo("AGT-001");
        }
        assertThat(get("/orders?status=REASSIGNMENT_PENDING")).hasSize(3);
    }

    @Test
    void repeatedOfflineEventsDoNotCreateDuplicates() throws Exception {
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);

        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");           // no transition -> no event
        patch("/agents/AGT-001/status", "{\"status\":\"AVAILABLE\"}");
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");           // real second transition
        Thread.sleep(1500);                                                      // let the 2nd re-plan finish

        assertThat(pendingOfflineSuggestions()).hasSize(3);
    }

    @Test
    void acceptingASuggestionMovesTheOrderAndRebalancesLoad() throws Exception {
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);
        JsonNode suggestion = pendingOfflineSuggestions().get(0);
        String orderId = suggestion.get("orderId").asText();
        String newAgent = suggestion.get("recommendedAgentId").asText();

        JsonNode accepted = patch("/suggestions/" + suggestion.get("id").asLong(), "{\"status\":\"ACCEPTED\"}");

        assertThat(accepted.get("status").asText()).isEqualTo("ACCEPTED");
        JsonNode order = null;
        for (JsonNode o : get("/orders")) if (o.get("id").asText().equals(orderId)) order = o;
        assertThat(order.get("status").asText()).isEqualTo("REASSIGNED");
        assertThat(order.get("assignedAgentId").asText()).isEqualTo(newAgent);

        // resolving twice is a conflict, not a silent success
        mvc.perform(MockMvcRequestBuilders.patch("/suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"REJECTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
    }

    @Test
    void rejectingLeavesTheOrderPendingForAnotherSuggestion() throws Exception {
        patch("/agents/AGT-003/status", "{\"status\":\"OFFLINE\"}");          // ORD-003, ORD-007
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 2);
        JsonNode suggestion = pendingOfflineSuggestions().get(0);

        patch("/suggestions/" + suggestion.get("id").asLong(), "{\"status\":\"REJECTED\"}");

        // a fresh on-demand suggestion can now be requested for that order
        mvc.perform(post("/orders/" + suggestion.get("orderId").asText() + "/suggest"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.triggerReason").value("INITIAL"));
    }

    @Test
    void aiStrategyWithNoKeyStillProducesARuleBasedSuggestion() throws Exception {
        patch("/routing/strategy", "{\"strategy\":\"ai\"}");

        mvc.perform(post("/orders/ORD-004/suggest"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("RULE_BASED_FALLBACK"))
                .andExpect(jsonPath("$.fallbackReason").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.reasoning").isNotEmpty());
    }

    @Test
    void suggestingTwiceReturnsTheSameOpenSuggestion() throws Exception {
        mvc.perform(post("/orders/ORD-004/suggest")).andExpect(status().isCreated());
        mvc.perform(post("/orders/ORD-004/suggest")).andExpect(status().isOk());
        assertThat(get("/suggestions?status=PENDING")).hasSize(1);
    }

    @Test
    void validationAndErrorShapes() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-404/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"OFFLINE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-001/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ASLEEP\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(MockMvcRequestBuilders.patch("/routing/strategy").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"strategy\":\"nope\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordersStrandedWithNobodyToTakeThemAreStillSurfacedAsPendingNotLeftInvisible() throws Exception {
        for (String id : List.of("AGT-002", "AGT-003", "AGT-004", "AGT-005")) {
            patch("/agents/" + id + "/status", "{\"status\":\"OFFLINE\"}");
        }
        Thread.sleep(2000);                                          // let those re-plans finish
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");   // last agent standing: nobody is left

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            List<String> pending = new ArrayList<>();
            for (JsonNode o : get("/orders?status=REASSIGNMENT_PENDING")) pending.add(o.get("id").asText());
            return pending.containsAll(List.of("ORD-001", "ORD-002", "ORD-008"));
        });
        // ...with no suggestion for them (there is no one to suggest), so the UI lists them for ops.
        for (JsonNode s : get("/suggestions?status=PENDING")) {
            assertThat(s.get("orderId").asText()).isNotIn("ORD-001", "ORD-002", "ORD-008");
        }
        // and asking for a suggestion explains why it is impossible right now
        mvc.perform(post("/orders/ORD-001/suggest"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("NO_ELIGIBLE_AGENT"));
    }

    @Autowired com.ziprun.reassignment.service.SuggestionService suggestionService;

    @Test
    void concurrentSuggestRequestsForTheSameOrderCreateExactlyOneSuggestion() throws Exception {
        int threads = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var startGun = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<com.ziprun.reassignment.service.SuggestionService.Outcome>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                startGun.await();
                return suggestionService.suggest("ORD-004", com.ziprun.reassignment.routing.RoutingContext.initial());
            }));
        }
        startGun.countDown();

        java.util.Set<Long> ids = new java.util.HashSet<>();
        long created = 0;
        for (var f : results) {
            var outcome = f.get(20, java.util.concurrent.TimeUnit.SECONDS);   // none may throw
            ids.add(outcome.suggestion().id());
            if (outcome.created()) created++;
        }
        pool.shutdown();

        assertThat(ids).hasSize(1);
        assertThat(created).isEqualTo(1);
        assertThat(get("/suggestions?status=PENDING")).hasSize(1);
    }

    @Test
    void whenARecommendedAgentGoesOfflineTheStaleSuggestionsAreVoidedAndReplanned() throws Exception {
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);
        String target = pendingOfflineSuggestions().get(0).get("recommendedAgentId").asText();

        patch("/agents/" + target + "/status", "{\"status\":\"OFFLINE\"}");   // the person we suggested drops out

        await().atMost(Duration.ofSeconds(10)).until(() -> {
            JsonNode pending = get("/suggestions?status=PENDING");
            if (pending.size() != 3) return false;                            // every order still has one open suggestion
            for (JsonNode s : pending) if (s.get("recommendedAgentId").asText().equals(target)) return false;
            return true;
        });
        // nobody is pointed at either offline agent any more
        for (JsonNode s : get("/suggestions?status=PENDING")) {
            assertThat(s.get("recommendedAgentId").asText()).isNotIn("AGT-001", target);
            assertThat(s.get("triggerReason").asText()).isEqualTo("AGENT_OFFLINE");
        }
        // and the voided ones are recorded as REJECTED, not deleted
        assertThat(get("/suggestions?status=REJECTED").size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void offlineAgentWithNoActiveOrdersQueuesNothing() throws Exception {
        patch("/agents/AGT-002/status", "{\"status\":\"OFFLINE\"}");   // AGT-002 holds no orders
        Thread.sleep(1500);

        assertThat(get("/suggestions")).isEmpty();
        assertThat(get("/orders?status=REASSIGNMENT_PENDING")).isEmpty();
    }

    @Test
    void acceptIsRefusedWhenTheRecommendedAgentWentOfflineInTheMeantime() throws Exception {
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);
        JsonNode suggestion = pendingOfflineSuggestions().get(0);
        String target = suggestion.get("recommendedAgentId").asText();

        patch("/agents/" + target + "/status", "{\"status\":\"OFFLINE\"}");   // the person we recommended dropped out

        mvc.perform(MockMvcRequestBuilders.patch("/suggestions/" + suggestion.get("id").asLong())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"));
        // the order did NOT move
        for (JsonNode o : get("/orders")) {
            if (o.get("id").asText().equals(suggestion.get("orderId").asText())) {
                assertThat(o.get("assignedAgentId").asText()).isEqualTo("AGT-001");
            }
        }
    }

    @Test
    void acceptingOneSuggestionRejectsTheCompetingOpenSuggestionForTheSameOrder() throws Exception {
        mvc.perform(post("/orders/ORD-001/suggest")).andExpect(status().isCreated());     // INITIAL, open
        patch("/agents/AGT-001/status", "{\"status\":\"OFFLINE\"}");                      // + AGENT_OFFLINE for ORD-001
        await().atMost(Duration.ofSeconds(10)).until(() -> pendingOfflineSuggestions().size() == 3);
        assertThat(get("/suggestions?status=PENDING")).hasSize(4);                         // 3 offline + 1 initial

        JsonNode offlineForOrd1 = pendingOfflineSuggestions().stream()
                .filter(s -> s.get("orderId").asText().equals("ORD-001")).findFirst().orElseThrow();
        patch("/suggestions/" + offlineForOrd1.get("id").asLong(), "{\"status\":\"ACCEPTED\"}");

        List<String> rejectedTriggers = new ArrayList<>();
        for (JsonNode s : get("/suggestions?status=REJECTED")) {
            if (s.get("orderId").asText().equals("ORD-001")) rejectedTriggers.add(s.get("triggerReason").asText());
        }
        assertThat(rejectedTriggers).containsExactly("INITIAL");
        for (JsonNode s : get("/suggestions?status=PENDING")) {
            assertThat(s.get("orderId").asText()).isNotEqualTo("ORD-001");
        }
    }

    /** Every error, whatever raised it, must be application/problem+json with a matching status and a code. */
    private void assertProblem(org.springframework.test.web.servlet.ResultActions r, int status, String code) throws Exception {
        r.andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.code").value(code));
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Test
    void everyErrorIsAProblemDetailWithTheRightStatus() throws Exception {
        // 400 - request-body validation, one case per constraint
        assertProblem(mvc.perform(post("/orders").contentType(JSON).content("{}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(post("/orders").contentType(JSON)
                .content("{\"description\":\"   \",\"assignedAgentId\":\"AGT-002\"}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(post("/orders").contentType(JSON)
                .content("{\"description\":\"" + "x".repeat(256) + "\",\"assignedAgentId\":\"AGT-002\"}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(post("/orders").contentType(JSON)
                .content("{\"description\":\"ok\",\"assignedAgentId\":\"\"}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-001/status").contentType(JSON)
                .content("{}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/suggestions/1").contentType(JSON)
                .content("{}")), 400, "VALIDATION_FAILED");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/routing/strategy").contentType(JSON)
                .content("{\"strategy\":\"\"}")), 400, "VALIDATION_FAILED");

        // 400 - malformed bodies / parameters
        assertProblem(mvc.perform(post("/orders").contentType(JSON).content("{not json")), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-001/status").contentType(JSON)), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-001/status").contentType(JSON)
                .content("{\"status\":\"ASLEEP\"}")), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-001/status").contentType(JSON)
                .content("{\"status\":\"BUSY\",\"bogus\":1}")), 400, "MALFORMED_REQUEST");   // unknown field
        assertProblem(mvc.perform(MockMvcRequestBuilders.get("/orders?status=BOGUS")), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/suggestions/abc").contentType(JSON)
                .content("{\"status\":\"ACCEPTED\"}")), 400, "MALFORMED_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/suggestions/1").contentType(JSON)
                .content("{\"status\":\"PENDING\"}")), 400, "BAD_REQUEST");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/routing/strategy").contentType(JSON)
                .content("{\"strategy\":\"nope\"}")), 400, "BAD_REQUEST");

        // 404 - unknown resource / endpoint
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/agents/AGT-404/status").contentType(JSON)
                .content("{\"status\":\"OFFLINE\"}")), 404, "NOT_FOUND");
        assertProblem(mvc.perform(post("/orders/NOPE/suggest")), 404, "NOT_FOUND");
        assertProblem(mvc.perform(MockMvcRequestBuilders.patch("/suggestions/9999").contentType(JSON)
                .content("{\"status\":\"ACCEPTED\"}")), 404, "NOT_FOUND");
        assertProblem(mvc.perform(post("/orders").contentType(JSON)
                .content("{\"description\":\"ok\",\"assignedAgentId\":\"AGT-404\"}")), 404, "NOT_FOUND");
        assertProblem(mvc.perform(MockMvcRequestBuilders.get("/no/such/endpoint")), 404, "NOT_FOUND");

        // 405 / 406 / 415 - protocol errors raised by Spring MVC itself
        assertProblem(mvc.perform(MockMvcRequestBuilders.delete("/orders")), 405, "METHOD_NOT_ALLOWED");
        assertProblem(mvc.perform(MockMvcRequestBuilders.get("/agents").accept(MediaType.APPLICATION_XML)), 406, "NOT_ACCEPTABLE");
        assertProblem(mvc.perform(post("/orders").contentType(MediaType.TEXT_PLAIN).content("x")), 415, "UNSUPPORTED_MEDIA_TYPE");
    }

    @Test
    void domainConflictsAndUnprocessableAreProblemDetails() throws Exception {
        // 409 - cannot create an order for an offline agent
        patch("/agents/AGT-002/status", "{\"status\":\"OFFLINE\"}");
        assertProblem(mvc.perform(post("/orders").contentType(JSON)
                .content("{\"description\":\"x\",\"assignedAgentId\":\"AGT-002\"}")), 409, "INVALID_TRANSITION");

        // 422 - nobody left to take the order (everyone else is offline)
        for (String id : List.of("AGT-003", "AGT-004", "AGT-005")) {
            patch("/agents/" + id + "/status", "{\"status\":\"OFFLINE\"}");
        }
        assertProblem(mvc.perform(post("/orders/ORD-001/suggest")), 422, "NO_ELIGIBLE_AGENT");
    }

    @Test
    void createOrderAssignsToAgentAndIncrementsLoad() throws Exception {
        mvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"Flowers - Indiranagar to HSR\",\"assignedAgentId\":\"AGT-002\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.assignedAgentId").value("AGT-002"));

        for (JsonNode a : get("/agents")) {
            if (a.get("id").asText().equals("AGT-002")) {
                assertThat(a.get("activeOrderCount").asInt()).isEqualTo(1);
                assertThat(a.get("status").asText()).isEqualTo("BUSY");
            }
        }
    }
}
