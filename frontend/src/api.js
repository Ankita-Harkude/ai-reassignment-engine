const BASE = import.meta.env.VITE_API_URL || 'http://localhost:8081'

/** Thin fetch wrapper. Surfaces the backend's ProblemDetail "detail" so the UI can show a real message. */
async function request(path, options = {}) {
  let res
  try {
    res = await fetch(BASE + path, {
      headers: { 'Content-Type': 'application/json' },
      ...options,
    })
  } catch {
    throw new Error(`Cannot reach the backend at ${BASE}. Is it running?`)
  }
  if (!res.ok) {
    let message = `Request failed (${res.status})`
    try {
      const problem = await res.json()
      message = problem.detail || problem.title || message
    } catch { /* non-JSON error body */ }
    throw new Error(message)
  }
  return res.json()
}

const patch = (path, body) => request(path, { method: 'PATCH', body: JSON.stringify(body) })

export const api = {
  agents: () => request('/agents'),
  orders: () => request('/orders'),
  suggestions: (status = 'PENDING') => request(`/suggestions?status=${status}`),
  strategy: () => request('/routing/strategy'),
  setStrategy: (strategy) => patch('/routing/strategy', { strategy }),
  setAgentStatus: (id, status) => patch(`/agents/${id}/status`, { status }),
  resolveSuggestion: (id, status) => patch(`/suggestions/${id}`, { status }),
  requestSuggestion: (orderId) => request(`/orders/${orderId}/suggest`, { method: 'POST' }),
}
