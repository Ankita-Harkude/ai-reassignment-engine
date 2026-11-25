# 5-minute demo script

Goal: show the agent-offline re-plan path end to end, then the design behind it.
Have both servers running and the UI open at http://localhost:5173 (restart the backend first for fresh seed data).

## 0:00 - 0:40 The problem and the screen
- "An agent drops out mid-shift. Today an ops manager notices, opens a spreadsheet, reassigns by hand."
- Point at the roster (5 agents), the metrics strip (8 open orders), and the empty suggestion queue.

## 0:40 - 2:15 The agentic loop (the main event)
1. Click **Set offline** on *Priya Sharma* (3 orders). Mention: no other click.
2. Within ~3 s three cards appear with the purple **Auto re-plan - agent offline** badge. Each still shows Priya as the current holder: **nothing is assigned yet**.
3. Read one reasoning line aloud. Point out the load is spread across agents, not piled on one.
4. Click **Set offline** on the same agent again / bring online and offline again -> *no duplicate cards* (idempotency).
5. **Accept** one -> the card disappears and the roster loads rebalance. **Reject** another -> it moves to *Pending orders with no open suggestion*; click *Request new suggestion*.

## 2:15 - 3:15 AI and resilience
- Flip **Routing** to `ai` (runtime switch, no restart). Take another agent offline.
- With a key set: cards show the green **AI recommendation** badge and the model's reasoning verbatim (recovery framing: names the failed agent).
- Without a key (or after breaking `LLM_API_KEY`): cards show the amber **AI unavailable - rule-based (NOT_CONFIGURED)** badge. "A failed AI call still produces a suggestion; it is never silently dropped."

## 3:15 - 4:30 Code tour (open these files)
- `AgentService.updateStatus` - publishes the event only on a real transition; returns immediately.
- `ReplanListener` - `@Async` + `AFTER_COMMIT`; `ReplanService.replanForOfflineAgent` - finds stranded orders.
- `SuggestionService.suggest` - idempotency check + unique `active_key`; the router runs outside a transaction.
- `RoutingStrategy` / `RoutingStrategySelector` - the Map-of-beans switch; "a ZoneAffinityStrategy is one new class."
- `RoutingPromptFactory` - two different prompts: routine review vs incident report.
- `AiRoutingStrategy` - every failure kind degrades to rule-based.

## 4:30 - 5:00 Wrap
- Human checkpoint is deliberate (ADR-4) and what I would need before removing it.
- Deliberately deferred: SSE streaming, circuit breaker, outbox (ADR-5). Run `mvn test` to show green.
