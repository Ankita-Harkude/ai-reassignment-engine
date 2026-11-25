# ZipRun - AI Reassignment Engine

When a delivery agent goes **OFFLINE**, the system notices on its own, finds that agent's orders, asks a routing strategy (rule-based or LLM) where each should go, and queues **PENDING suggestions** for ops to accept or reject. Nothing is auto-assigned: a human makes the final call.

```
PATCH /agents/{id}/status = OFFLINE
        |  (returns immediately)
        v
 AgentWentOfflineEvent --after commit, @Async--> ReplanService
        |                                            |
        |                      for each stranded order (idempotent)
        |                                            v
        |                    RoutingEngine -> active RoutingStrategy
        |                         (rule-based | ai -> falls back to rule-based, labelled)
        |                                            v
        |                      ReassignmentSuggestion(PENDING, AGENT_OFFLINE)
        v
 React ops UI polls -> Accept / Reject  <-- the human checkpoint
```

Design decisions are in [ADR.md](ADR.md) (three headline decisions up top, five detailed entries below). A 5-minute video script is in [DEMO.md](DEMO.md).

## Run it (under 5 minutes)

Requirements: **Java 21**, **Maven 3.9+**, **Node 18+**. No database or API key needed.

```powershell
# 1. backend -> http://localhost:8081
cd backend
mvn spring-boot:run
```

```powershell
# 2. frontend -> http://localhost:5173   (run in a second terminal)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173. Seed data (5 agents, 8 orders) loads automatically into in-memory H2, so every restart starts from the same state.

> The backend defaults to port **8081** because 8080 is commonly occupied (for example by Oracle XE's HTTP listener). Override it with the `PORT` environment variable and point the UI at it with `VITE_API_URL`.

### Configuration (all optional, environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8081` | Backend port |
| `ROUTING_STRATEGY` | `rule-based` | Starting strategy: `rule-based` or `ai` (switchable live afterwards) |
| `LLM_PROVIDER` | `gemini` | `gemini`, `groq` or `ollama` |
| `LLM_API_KEY` | _(empty)_ | Provider key. Never commit it. Not needed for `ollama`, or to run the app at all |
| `LLM_MODEL` | `gemini-2.5-flash` | Groq: `llama-3.1-8b-instant`. Ollama: `llama3.1` |
| `LLM_BASE_URL` | Gemini URL | Groq: `https://api.groq.com`. Ollama: `http://localhost:11434` |
| `VITE_API_URL` | `http://localhost:8081` | Backend URL used by the frontend |

### Using a real LLM (optional)

Without a key the `ai` strategy falls back to rule-based and says so in the UI. To use a model, set the variables **before** starting the backend:

```powershell
$env:LLM_PROVIDER = "groq"
$env:LLM_API_KEY  = "<your key>"
$env:LLM_MODEL    = "llama-3.1-8b-instant"
$env:LLM_BASE_URL = "https://api.groq.com"
$env:ROUTING_STRATEGY = "ai"
cd backend
mvn spring-boot:run
```

## Demo path

The path to show in the video: an agent drops out, suggestions appear by themselves, ops decide.

### Through the UI
1. Open http://localhost:5173. The queue is empty; the roster shows 5 agents.
2. In the **Agent roster**, click **Set offline** on *Priya Sharma* (she holds ORD-001, ORD-002 and ORD-008). No other click is needed.
3. Within one poll (3 s) three suggestion cards appear with the purple **Auto re-plan - agent offline** badge, spread across different agents. Each shows the recommended agent, a confidence bar and the reasoning text verbatim. Nothing has been assigned yet.
4. Click **Set offline** on Priya again, or bring her online and offline once more: no duplicate cards appear.
5. Click **Accept** on one card: the order moves to the new agent and both agents' loads update in the roster. Click **Reject** on another: it moves to *Pending orders with no open suggestion*; use **Request new suggestion** to get another.
6. Flip the **Routing** toggle to `ai` and take another agent offline. With a key you see the green **AI recommendation** badge; without one you see the amber **AI unavailable - rule-based (NOT_CONFIGURED)** badge and a rule-based suggestion.

### Through the API only
With the backend running (PowerShell):

```powershell
$api = "http://localhost:8081"
$json = "application/json"

# 1. Take Priya offline: returns immediately, re-planning happens in the background
Invoke-RestMethod -Method Patch "$api/agents/AGT-001/status" -ContentType $json -Body '{"status":"OFFLINE"}'

# 2. A moment later, three AGENT_OFFLINE suggestions are queued
Start-Sleep 3
Invoke-RestMethod "$api/suggestions?status=PENDING" |
  Format-Table id, orderId, fromAgentName, recommendedAgentName, confidence, source, triggerReason

# 3. The human checkpoint: accept one (use an id from the table above), reject another
Invoke-RestMethod -Method Patch "$api/suggestions/1" -ContentType $json -Body '{"status":"ACCEPTED"}'
Invoke-RestMethod -Method Patch "$api/suggestions/2" -ContentType $json -Body '{"status":"REJECTED"}'

# 4. See the result, and switch strategy at runtime
Invoke-RestMethod "$api/orders" | Format-Table id, status, assignedAgentName
Invoke-RestMethod -Method Patch "$api/routing/strategy" -ContentType $json -Body '{"strategy":"ai"}'
```

## API

Base URL `http://localhost:8081`. JSON in and out.

| Method | Path | Body | Success | Purpose |
|---|---|---|---|---|
| `POST` | `/orders` | `{description, assignedAgentId}` | 201 + `Location` | Create an order already assigned to an agent (simulates the morning assignment) |
| `GET` | `/orders` | - | 200 | List orders; optional `?status=ASSIGNED\|REASSIGNMENT_PENDING\|REASSIGNED\|DELIVERED` |
| `POST` | `/orders/{id}/suggest` | - | 201 new / 200 already open | On-demand suggestion using the active strategy (`triggerReason = INITIAL`) |
| `GET` | `/agents` | - | 200 | Agent roster with status and active order count |
| `PATCH` | `/agents/{id}/status` | `{status: AVAILABLE\|BUSY\|OFFLINE}` | 200 | Update availability. A change to `OFFLINE` starts the agentic re-plan loop |
| `GET` | `/suggestions` | - | 200 | List suggestions; optional `?status=PENDING\|ACCEPTED\|REJECTED` |
| `PATCH` | `/suggestions/{id}` | `{status: ACCEPTED\|REJECTED}` | 200 | The human decision. Accepting moves the order to the recommended agent |
| `GET` | `/routing/strategy` | - | 200 | Active strategy and the available ones |
| `PATCH` | `/routing/strategy` | `{strategy}` | 200 | Switch strategy at runtime, no restart |
| `GET` | `/actuator/health` | - | 200 | Health check |
| - | `/h2-console` | - | - | In-memory DB console. JDBC URL `jdbc:h2:mem:ziprun`, user `sa`, empty password |

**Errors.** Every error is an RFC 7807 `ProblemDetail` (`application/problem+json`) with the matching HTTP status and a stable `code`:

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Body fails Bean Validation (blank or too-long fields, null status) |
| 400 | `MALFORMED_REQUEST` | Missing or invalid JSON, unknown enum value, unknown field, bad parameter type |
| 400 | `BAD_REQUEST` | Business-level bad input (for example resolving a suggestion to `PENDING`, unknown strategy name) |
| 404 | `NOT_FOUND` | Unknown order, agent or suggestion, or unknown endpoint |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED` / `NOT_ACCEPTABLE` / `UNSUPPORTED_MEDIA_TYPE` | Protocol errors |
| 409 | `INVALID_TRANSITION` | Illegal state change (suggestion already resolved, recommended agent now offline, order for an offline agent) |
| 409 | `CONCURRENT_MODIFICATION` | Two writers collided; retry |
| 422 | `NO_ELIGIBLE_AGENT` | Nobody is available to take the order |
| 500 | `INTERNAL_ERROR` | Anything unexpected (no stack trace in the response) |

## State machines

- **Order:** `ASSIGNED` -> `REASSIGNMENT_PENDING` -> `REASSIGNED` (-> `DELIVERED`, reserved for a later sprint). A reassigned order can become pending again if its new agent drops out.
- **Agent:** `AVAILABLE` / `BUSY` / `OFFLINE`.
- **Suggestion:** `PENDING` -> `ACCEPTED` or `REJECTED`, with `triggerReason` = `INITIAL` or `AGENT_OFFLINE`, and `source` = `AI`, `RULE_BASED` or `RULE_BASED_FALLBACK`.

## Project layout

```
backend/src/main/java/com/ziprun/reassignment/
  domain/       Order, Agent, ReassignmentSuggestion + enums (state machines live on the entities)
  routing/      RoutingStrategy (contract), RuleBasedStrategy, AiRoutingStrategy,
                RoutingStrategySelector (runtime switch), RoutingEngine (eligibility + strategy)
  ai/           LlmGateway, HttpLlmGateway (Gemini/Groq/Ollama), RoutingPromptFactory (2 prompts), parser
  service/      AgentService, OrderService, SuggestionService, ReplanService
  event/        AgentWentOfflineEvent, ReplanListener (@Async + AFTER_COMMIT)
  controller/ dto/ repository/ exception/ config/
frontend/       React 18 + Vite: polling ops dashboard
```

## Tests

```powershell
cd backend
mvn test
```

104 tests, no network or API key needed. They cover: rule-based ranking, ties and confidence; every AI failure mode falling back with a label (timeout, 429, server error, garbage, hallucinated or failed agent id, bad confidence, no key); strict response parsing; the HTTP gateway against a local fake server (key never in the URL or in errors); the two prompts being genuinely different; runtime strategy switching including a third strategy; entity state machines; and end to end through Spring and H2: offline -> suggestions appear, nothing is auto-assigned, repeated OFFLINE creates no duplicates, accept / reject / double-resolve, accept refused when the recommended agent went offline, and the error-response matrix.

## Honest notes
- The real LLM call has been tested against a local fake server, not against a live provider (no key was available in this build). Verify with your own key.
- Data and events are in-memory (H2, Spring events): see ADR-4 for the outbox tradeoff.
- Out of scope this sprint, by design: zone/capacity/weight/SLA logic (the columns exist as nullable placeholders), a full dispatch board, SSE streaming, retries or a circuit breaker for the LLM. See ADR-5.
