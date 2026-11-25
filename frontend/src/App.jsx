import { useCallback, useEffect, useRef, useState } from 'react'
import { api } from './api.js'

const POLL_MS = 3000

export default function App() {
  const [agents, setAgents] = useState([])
  const [orders, setOrders] = useState([])
  const [suggestions, setSuggestions] = useState([])
  const [strategy, setStrategy] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [updatedAt, setUpdatedAt] = useState(null)
  const [busy, setBusy] = useState({}) // key -> true while an action is in flight
  const inFlight = useRef(false)

  const refresh = useCallback(async () => {
    if (inFlight.current) return
    inFlight.current = true
    try {
      const [a, o, s, st] = await Promise.all([
        api.agents(), api.orders(), api.suggestions('PENDING'), api.strategy(),
      ])
      setAgents(a); setOrders(o); setSuggestions(s); setStrategy(st)
      setError(null)
      setUpdatedAt(new Date())
    } catch (e) {
      setError(e.message)
    } finally {
      inFlight.current = false
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    refresh()
    const timer = setInterval(refresh, POLL_MS)
    return () => clearInterval(timer)
  }, [refresh])

  /** Runs an action, tracks its busy state, shows errors, then refreshes. */
  const act = async (key, fn) => {
    setBusy((b) => ({ ...b, [key]: true }))
    try {
      await fn()
      setError(null)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy((b) => ({ ...b, [key]: false }))
      refresh()
    }
  }

  const offlineAgents = agents.filter((a) => a.status === 'OFFLINE').length
  const replanCount = suggestions.filter((s) => s.triggerReason === 'AGENT_OFFLINE').length
  // Orders left in REASSIGNMENT_PENDING with nothing to approve (e.g. after a reject).
  const orderIdsWithSuggestion = new Set(suggestions.map((s) => s.orderId))
  const awaiting = orders.filter((o) => o.status === 'REASSIGNMENT_PENDING' && !orderIdsWithSuggestion.has(o.id))

  return (
    <div className="app">
      <header className="topbar">
        <div>
          <h1>ZipRun Ops</h1>
          <p className="sub">Reassignment queue &middot; AI proposes, you decide</p>
        </div>
        <div className="topbar-right">
          <StrategySwitch strategy={strategy} busy={busy.strategy}
            onSelect={(name) => act('strategy', () => api.setStrategy(name))} />
          <button className="btn" onClick={refresh} disabled={loading}>Refresh</button>
          <span className="stamp" title="Auto-refreshes every 3 seconds">
            <span className={`dot ${error ? 'dot-bad' : 'dot-ok'}`} />
            {updatedAt ? `Updated ${updatedAt.toLocaleTimeString()}` : 'Connecting...'}
          </span>
        </div>
      </header>

      {error && (
        <div className="banner" role="alert">
          <span>{error}</span>
          <button className="link" onClick={() => setError(null)}>Dismiss</button>
        </div>
      )}

      <section className="metrics">
        <Metric label="Pending suggestions" value={suggestions.length} accent={suggestions.length > 0} />
        <Metric label="Auto re-plans waiting" value={replanCount} accent={replanCount > 0} />
        <Metric label="Agents offline" value={offlineAgents} warn={offlineAgents > 0} />
        <Metric label="Open orders" value={orders.filter((o) => o.status !== 'DELIVERED').length} />
      </section>

      {loading ? (
        <p className="empty">Loading dashboard...</p>
      ) : (
        <main className="layout">
          <section className="col-main">
            <h2>Pending reassignment suggestions</h2>
            {suggestions.length === 0 ? (
              <p className="empty">
                Nothing waiting for approval. Set an agent offline in the roster to watch the engine re-plan.
              </p>
            ) : (
              suggestions.map((s) => (
                <SuggestionCard key={s.id} s={s}
                  busy={busy[`s${s.id}`]}
                  onAccept={() => act(`s${s.id}`, () => api.resolveSuggestion(s.id, 'ACCEPTED'))}
                  onReject={() => act(`s${s.id}`, () => api.resolveSuggestion(s.id, 'REJECTED'))} />
              ))
            )}

            {awaiting.length > 0 && (
              <>
                <h2 className="spaced">Pending orders with no open suggestion</h2>
                {awaiting.map((o) => (
                  <div className="order" key={o.id}>
                    <div>
                      <strong>{o.id}</strong> <span className="muted">&middot; {o.description} &middot; {o.assignedAgentName}</span>
                    </div>
                    <button className="btn btn-small" disabled={busy[o.id]}
                      onClick={() => act(o.id, () => api.requestSuggestion(o.id))}>
                      Request new suggestion
                    </button>
                  </div>
                ))}
              </>
            )}
          </section>

          <aside className="col-side">
            <h2>Agent roster</h2>
            <ul className="roster">
              {agents.map((a) => (
                <li key={a.id} className={`agent agent-${a.status.toLowerCase()}`}>
                  <div>
                    <strong>{a.name}</strong>
                    <span className="muted"> {a.id}</span>
                    <div className="muted small">{a.activeOrderCount} active order{a.activeOrderCount === 1 ? '' : 's'}</div>
                  </div>
                  <div className="agent-actions">
                    <span className={`chip chip-${a.status.toLowerCase()}`}>{a.status}</span>
                    {a.status === 'OFFLINE' ? (
                      <button className="btn btn-small" disabled={busy[a.id]}
                        onClick={() => act(a.id, () => api.setAgentStatus(a.id, 'AVAILABLE'))}>Bring online</button>
                    ) : (
                      <button className="btn btn-small btn-danger" disabled={busy[a.id]}
                        onClick={() => act(a.id, () => api.setAgentStatus(a.id, 'OFFLINE'))}>Set offline</button>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </aside>
        </main>
      )}

    </div>
  )
}

function Metric({ label, value, accent, warn }) {
  return (
    <div className={`metric ${accent ? 'metric-accent' : ''} ${warn ? 'metric-warn' : ''}`}>
      <div className="metric-value">{value}</div>
      <div className="metric-label">{label}</div>
    </div>
  )
}

function StrategySwitch({ strategy, busy, onSelect }) {
  if (!strategy) return null
  return (
    <div className="switch" title="Routing strategy, switchable at runtime">
      <span className="muted small">Routing</span>
      {strategy.available.map((name) => (
        <button key={name} disabled={busy}
          className={`seg ${strategy.active === name ? 'seg-on' : ''}`}
          onClick={() => strategy.active !== name && onSelect(name)}>
          {name}
        </button>
      ))}
    </div>
  )
}

function SourceBadge({ source, reason }) {
  if (source === 'AI') return <span className="badge badge-ai">AI recommendation</span>
  if (source === 'RULE_BASED_FALLBACK')
    return <span className="badge badge-fallback" title={`AI bypassed: ${reason}`}>AI unavailable &middot; rule-based ({reason})</span>
  return <span className="badge badge-rule">Rule-based</span>
}

function SuggestionCard({ s, busy, onAccept, onReject }) {
  const replan = s.triggerReason === 'AGENT_OFFLINE'
  const pct = Math.round(s.confidence * 100)
  return (
    <article className={`card ${replan ? 'card-replan' : ''}`}>
      <div className="card-head">
        <div>
          <strong>{s.orderId}</strong>
          <span className="muted"> &middot; {s.orderDescription}</span>
        </div>
        <div className="badges">
          {replan
            ? <span className="badge badge-replan">Auto re-plan &middot; agent offline</span>
            : <span className="badge badge-manual">Manual request</span>}
          <SourceBadge source={s.source} reason={s.fallbackReason} />
        </div>
      </div>

      <div className="move">
        <span className="who who-from">{s.fromAgentName}</span>
        <span className="arrow">&rarr;</span>
        <span className="who who-to">{s.recommendedAgentName}</span>
        <span className="conf" title="Confidence reported by the recommender">
          <span className="bar"><span className="bar-fill" style={{ width: `${pct}%` }} /></span>
          {pct}%
        </span>
      </div>

      <blockquote className="reasoning">{s.reasoning}</blockquote>

      <div className="card-actions">
        <button className="btn btn-primary" disabled={busy} onClick={onAccept}>{busy ? 'Working...' : 'Accept'}</button>
        <button className="btn" disabled={busy} onClick={onReject}>Reject</button>
      </div>
    </article>
  )
}
