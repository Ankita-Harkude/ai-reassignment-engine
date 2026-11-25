# Architecture Decision Records

Format per entry: **Context -> Options considered -> Decision -> Tradeoffs accepted.**
Stack: Spring Boot 3.5 / Java 21 / Spring Data JPA / H2, React 18 + Vite. (Frontend choice: React, because the ops screen is a single polling view and Vite gives the shortest path to a working one; Angular's structure would not have paid for itself at this size.)

## The three main design decisions

Everything else in this project follows from three choices. Each is summarised here in the same four-part format; the numbered entries further down (ADR-1 to ADR-5) hold the full detail and the code pointers.

### Decision A - Routing is a pluggable strategy behind a pure contract, switchable at runtime
*(detail: ADR-1, ADR-2)*

**Context.** The same routing question is asked from two places - an HTTP endpoint and an async re-plan listener - and sprint 2 will add a third strategy (`ZoneAffinityStrategy`). The active strategy must change without a restart.

**Options considered.**
1. Routing inside the service/controller that saves suggestions - quickest, but ties ranking to JPA and HTTP.
2. A manual factory with a `switch` on a config value - explicit, but every new strategy edits the factory.
3. A `RoutingStrategy` interface whose implementations are Spring beans named by key, injected as a `Map<String, RoutingStrategy>` into a selector that holds the active name in an `AtomicReference`.

**Decision.** Option 3. `RoutingStrategy.recommend(RoutingRequest)` takes immutable snapshots and returns ranked recommendations - no entities, no transactions. `RoutingEngine` owns the eligibility policy (not offline, not the current or failed agent) and the active-strategy lookup; `SuggestionService` only loads, calls the engine and persists. Switching is `PATCH /routing/strategy` (or `ROUTING_STRATEGY` at boot) and applies to the next HTTP call *and* the next async re-plan. Adding `ZoneAffinityStrategy` = one new `@Component("zone-affinity")` class; a test proves the selector discovers it.

**Tradeoffs accepted.** The active strategy lives in memory, so it resets on restart and is per-instance. Snapshot records duplicate a few entity fields and need mapping code. The bean-name convention is implicit; a fail-fast startup check is what makes a typo loud.

### Decision B - The agentic loop is event-driven, asynchronous, idempotent, and stops at a human checkpoint
*(detail: ADR-4)*

**Context.** `PATCH /agents/{id}/status` must return fast, but re-planning calls a router (possibly an LLM) once per stranded order. The loop must fire because something changed, not because a timer ticked, and must never assign orders without a person's approval.

**Options considered.**
1. A scheduled poller that scans for offline agents - simple, but it is automation, not an agent: delayed by one tick and re-scans everything.
2. Re-plan synchronously inside the PATCH - blocks the response on LLM latency.
3. `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on a dedicated executor.
4. A message broker (Kafka/RabbitMQ) - durable, but far beyond this sprint.

**Decision.** Option 3. `AgentService` publishes `AgentWentOfflineEvent` only on a real transition into OFFLINE; the listener runs after commit on the `replanExecutor` pool. The PATCH returned in about 0.26 s in a live run. The loop maps to code as observe (event) -> reason (`ReplanService` finds that agent's stranded orders) -> act (one `PENDING` suggestion each) -> checkpoint (`SuggestionService.resolve`, the only place an order changes hands). Idempotency is layered: an open-suggestion lookup plus a unique database key that exists only while a suggestion is `PENDING`. Each routing request also sees suggestions already queued, so a bulk re-plan spreads load instead of piling onto the one idle agent.

**Tradeoffs accepted.** Events are in-memory: a crash between commit and listener loses the re-plan (a transactional outbox or a start-up reconciliation would fix it; deferred). Suggestions are not auto-accepted, so recovery is only as fast as ops - deliberate, and ADR-4 states the conditions under which I would remove that checkpoint.

### Decision C - The AI only advises: every output is validated, and any failure degrades to a labelled rule-based suggestion
*(detail: ADR-3)*

**Context.** LLM calls fail in many ways (timeout, quota, malformed JSON, invented agent ids, nonsense confidence), and the async re-plan has nobody watching. A silent drop would leave stranded orders with no suggestion - the exact failure this product exists to remove.

**Options considered.**
1. Let exceptions propagate and make each caller handle them - every caller must remember to.
2. A circuit-breaker library (Resilience4j) - good for sustained outages, heavy for this scope.
3. The AI strategy itself guarantees it never throws for an AI problem, and the routing engine adds a second safety net for any strategy.

**Decision.** Option 3. `AiRoutingStrategy` runs gateway -> parse -> validate in one guarded block; the agent id must be one of the *eligible candidates* (not merely a known agent), so the model cannot pick the failed or an offline agent. On any failure it returns the rule-based result tagged `RULE_BASED_FALLBACK` with a reason (`TIMEOUT`, `QUOTA`, `UNPARSEABLE`, `UNKNOWN_AGENT`, `INVALID_CONFIDENCE`, `NOT_CONFIGURED`, ...). The label is stored on the suggestion, prefixed in the reasoning text and shown as an amber badge in the UI, and the failure is logged. The API key is read only from `LLM_API_KEY`, sent in a header (never a URL), and the app runs fully without it.

**Tradeoffs accepted.** No retry and no circuit breaker: during a sustained outage each order pays the full timeout (bounded at about 15 s) before falling back, and the on-demand `POST /suggest` call waits it out. Fallback confidence is a fixed heuristic tier, not a probability. The real provider call path has been tested against a local fake server but not against a live provider.

---

## ADR-1: Where does routing logic live?

**Context.** Routing is called from two places - `POST /orders/{id}/suggest` (HTTP) and the async re-plan listener - and sprint 2 adds more strategies. A controller or a growing `SuggestionService` that mixes routing, persistence and events would force every new caller or strategy to touch it.

**Options.**
(a) In the controller / service that persists suggestions - fastest, but couples ranking to JPA and HTTP and makes it untestable without a database.
(b) On the `Order`/`Agent` entities (rich domain model) - but "best agent" is a policy across many agents, not a behaviour of one entity.
(c) A dedicated `routing` package: a pure `RoutingStrategy` interface, plus a `RoutingEngine` application service in front of it.

**Decision.** (c). `RoutingStrategy.recommend(RoutingRequest)` takes immutable snapshots (`OrderSnapshot`, `AgentSnapshot`, `RoutingContext`) and returns ranked `Recommendation`s. No entities, no transactions, no HTTP. `RoutingEngine` owns exactly two things: the *eligibility policy* (not offline, not the current agent, not the failed agent) and *which strategy is active*. `SuggestionService` only orchestrates: load a snapshot -> call the engine -> persist. The routing package has no dependency on JPA, which is why the strategy tests need no Spring context.

**Tradeoffs.** Snapshot records duplicate a few entity fields and need mapping code in `SuggestionService.prepare`. In return the strategy contract is trivially testable and cannot accidentally lazy-load or write to the database. Also, `SuggestionService` deliberately runs the router *outside* a DB transaction (it uses `TransactionTemplate` for the read and the write separately) - more code than a single `@Transactional` method, but a 15 s LLM call never holds a connection.

---

## ADR-2: How does runtime strategy switching work?

**Context.** The strategy must change without a restart and be honoured by both callers. Sprint 2 adds a third strategy (`ZoneAffinityStrategy`).

**Options.**
(a) `@Qualifier` + a config property - needs a restart to switch.
(b) A manual factory with a `switch` - every new strategy edits the factory.
(c) Spring injects `Map<String, RoutingStrategy>` (bean name -> bean) into a `RoutingStrategySelector` holding an `AtomicReference<String>` with the active name.
(d) Re-read `application.properties` on a schedule / Actuator refresh.

**Decision.** (c). Strategies are `@Component("rule-based")` / `@Component("ai")`. `RoutingStrategySelector.active()` is read on every call, so a switch (`PATCH /routing/strategy`, or `routing.strategy` / `ROUTING_STRATEGY` at boot) takes effect on the next request *and* the next async re-plan, with no shared state other than the atomic reference. An unknown name at startup fails fast (`@PostConstruct`); an unknown name at runtime is a 400 and leaves the active strategy unchanged.
**Adding `ZoneAffinityStrategy` touches one new file**: a class implementing `RoutingStrategy` annotated `@Component("zone-affinity")`. The selector, engine, controller and UI strategy toggle all discover it automatically (`RoutingStrategySelectorTest.newStrategyJustNeedsToBeRegistered` proves it, and the UI renders whatever `GET /routing/strategy` lists).

**Tradeoffs.** The active strategy is in-memory, so it resets to the configured default on restart and is per-instance - with several backend nodes it would need to move to the database or a config service. "Switchable via config" is honoured as "config sets the default, an admin endpoint changes it live" because plain Spring does not hot-reload properties. The bean-name convention is implicit; the startup check is what makes a typo loud.

---

## ADR-3: How does the system stay healthy when the LLM is unavailable?

**Context.** LLM calls fail in distinct ways, and the async re-plan has no human watching it. A silent drop would leave stranded orders with no suggestion - exactly the failure the product exists to remove.

**Options.**
(a) Let exceptions propagate; the caller handles it - every caller must remember to.
(b) A circuit breaker library (Resilience4j) - good for sustained outages, heavy for a 5-hour scope.
(c) The AI strategy itself guarantees it never throws for an AI problem: any failure degrades to the rule-based result.

**Decision.** (c). `AiRoutingStrategy` wraps gateway call -> parse -> validate in one try block and degrades to `RuleBasedStrategy` on any `LlmFailureException`, tagging the result `RULE_BASED_FALLBACK` with the failure kind. Failure modes handled explicitly and unit-tested:

| Failure | Kind | Result |
|---|---|---|
| No API key | `NOT_CONFIGURED` | fallback (no network call) |
| Read/connect timeout (3 s / 15 s) | `TIMEOUT` / `NETWORK` | fallback |
| HTTP 429 | `QUOTA` | fallback |
| Other non-2xx | `HTTP_ERROR` | fallback |
| Not JSON / missing fields | `UNPARSEABLE` | fallback |
| Confidence missing or outside 0..1 | `INVALID_CONFIDENCE` | fallback |
| Agent id not in the roster (hallucination) | `UNKNOWN_AGENT` | fallback |
| Anything else | `UNEXPECTED` | fallback |
| Active strategy throws / returns no eligible agent (any strategy, checked in `RoutingEngine`) | `STRATEGY_ERROR` / `NO_VALID_RECOMMENDATION` | fallback |

Every fallback is **labelled**: `source = RULE_BASED_FALLBACK`, the machine-readable `fallbackReason`, and a human prefix in the reasoning text ("AI unavailable (TIMEOUT), rule-based pick instead. ..."). The UI shows an amber badge; the failure is also logged server-side.

The agent id is validated against the *eligible candidate list*, not just "exists in the DB", so the model cannot pick the failed agent or an offline one. `RoutingEngine` filters again as defence in depth. The fallback reason is stored on the suggestion and shown in the UI ("AI unavailable (TIMEOUT)"), so ops know the reasoning is a rule, not a model. Markdown-fenced JSON is tolerated by the parser. The API key is read from the environment only and sent in a header, never a URL.

**Tradeoffs.** No retry and no circuit breaker: during a sustained outage every order pays the full timeout before falling back (bounded at ~15 s, off the HTTP thread for re-plans, but the on-demand `POST /suggest` call waits it out). Fallback confidence is a heuristic tier, not a probability. We accept both for now; a breaker is the first thing to add if the LLM becomes a real dependency.

---

## ADR-4: How is the agentic loop triggered and kept off the request path?

**Context.** `PATCH /agents/{id}/status` must return fast; re-planning calls an LLM per stranded order. The loop must fire because *something changed*, not because a timer ticked.

**Options.**
(a) A scheduled poller for OFFLINE agents - simple, but it is automation, not an agent: latency of one tick, and it re-scans everything.
(b) Call the re-plan synchronously in the service - blocks the response on LLM latency.
(c) `ApplicationEventPublisher` + `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on a dedicated executor.
(d) A message broker (Kafka/RabbitMQ) - durable and scalable; far beyond sprint scope.

**Decision.** (c). `AgentService.updateStatus` changes the row and publishes `AgentWentOfflineEvent` only on a *real transition into OFFLINE* (flipping OFFLINE -> OFFLINE publishes nothing). `ReplanListener` runs **after commit** (so it sees the committed state and never reacts to a rolled-back change) on the `replanExecutor` pool (so slow LLM calls cannot starve Tomcat). Measured against the running app: the PATCH returns in ~0.26 s; the three suggestions appear within the next poll.
The loop, mapped to code: **observe** = `AgentWentOfflineEvent`; **reason** = `ReplanService.replanForOfflineAgent` finds that agent's non-delivered orders and builds a `RoutingContext.agentOffline(...)` incident; **act** = `SuggestionService.suggest` per order; **checkpoint** = `SuggestionService.resolve`, the only place an order changes hands.
*Idempotency* is layered: `findByActiveKey("AGENT_OFFLINE:<orderId>")` returns the open suggestion instead of creating one, and a unique constraint on `active_key` (set while PENDING, nulled on resolution) makes concurrent duplicates impossible even if the check races; the loser catches the integrity violation and returns the winner's row. Re-plan failures are isolated per order and logged; an order with no eligible agent is logged and left unchanged rather than half-transitioned.
*Load spreading*: each routing request sees `pendingIncoming` (suggestions already queued per agent), so three stranded orders do not all land on the one idle agent - verified live (Rahul / Kiran split).

**Human checkpoint, and when I would remove it.** The system proposes; ops disposes. Accept re-validates the recommended agent is still online (409 otherwise) and rejects competing suggestions for the same order. I would consider auto-accepting only when *all* hold: confidence above a tuned threshold on `source = AI` (never on fallback), an imminent SLA breach where waiting for a human costs more than a wrong assignment, and an easy undo (re-suggest) - and I would start with a shadow mode that logs "would have auto-accepted" and compare against ops decisions first.

**Tradeoffs.** Events are in-memory: if the JVM dies between commit and listener, the re-plan is lost (no outbox / replay). The "durable" fix is a transactional outbox table or a startup reconciliation that re-plans OFFLINE agents' un-suggested orders; deferred. `CallerRunsPolicy` on a full queue means an overload briefly makes the PATCH thread do the work - chosen over silently dropping a re-plan.

---

## ADR-5: What did I design to extend, and what did I deliberately leave for later?

**Extension seams (pointing at real code).**
- *Sprint 2 - zone, capacity, weight class.* The columns already exist, nullable and unused: `Agent.currentZone`, `Agent.maxCapacity`, `Order.pickupZone/dropoffZone/weightClass`. Activating them is a code change, not a migration. Capacity and weight rules belong in `RoutingEngine.eligibleAgents` (the single eligibility policy); zone preference belongs in a new `ZoneAffinityStrategy` (one new `@Component`, see ADR-2). `AgentSnapshot`/`OrderSnapshot` gain the fields; the strategy interface does not change.
- *Sprint 3 - proactive SLA loop.* `Order.slaDeadline` exists. `ReplanService.replanOrders(orderIds, RoutingContext)` is trigger-agnostic: an SLA monitor publishes its own event (or a scheduler calls it directly) with a new `TriggerReason.SLA_BREACH_RISK` and a matching `RoutingContext` factory; `RoutingPromptFactory.build` dispatches on `context.trigger()` so a third prompt is one more method. The idempotency key already includes the trigger, so an SLA suggestion and an OFFLINE suggestion for the same order can coexist without colliding. The strategy contract is already callable from a scheduled monitor - it is pure.

**Deliberately not built, and why.**
- *SSE streaming of LLM reasoning (bonus).* A visibility nicety; the correctness surface (fallback, idempotency, checkpoint) matters more, and token streaming would also mean a different, non-JSON prompt contract.
- *Retry / circuit breaker for the LLM.* The fallback already guarantees a suggestion; retries add latency and complexity for a gain that only appears under sustained outages.
- *Transactional outbox for the offline event.* A correctness improvement for crash recovery, but it needs a durable DB (we use in-memory H2) to mean anything.
- *Capacity enforcement, zone-aware routing, SLA countdown, full dispatch board, delivery workflow.* Sprint 2/3 scope. I deferred the dispatch board because the agentic loop is a correctness requirement and the board is a visibility enhancement; the UI is the required floor only (suggestions with reasoning, accept/reject, re-plan badge, agent roster, polling, loading/error states). The sprint 2/3 fields exist only as nullable, unused columns, so no logic depends on them yet. `DELIVERED` exists in the status enum per the lifecycle but nothing transitions to it today.
- *Authentication / ops identity on accept.* Out of scope for the sprint, but "who approved this" is the first audit field I would add to `ReassignmentSuggestion`.

**Known limits worth naming.** `activeOrderCount` is a denormalised counter maintained by the services (cheaper than counting per routing call, but it can drift if rows are edited outside the app). Reject leaves the order `REASSIGNMENT_PENDING` with no open suggestion until ops requests another - a conscious choice over silently reverting to a dead agent. `weightClass` is a String placeholder, to become an enum when sprint 2 defines it.
