/**
 * What the conversation is costing, and what must not be thrown away.
 *
 * The engine holds a conversation and reports its occupancy after each turn; the
 * page decides what history to hand back on the next one. Those two facts are what
 * make a meter and a keep button possible at all -- chat-demo had neither because
 * the server owned the transcript and the window.
 */
import { create } from 'zustand'

type Meter = {
  used: number | null
  window: number | null
}

export const useContextMeter = create<Meter>(() => ({ used: null, window: null }))

export const setContextMeter = (used: number | null, window: number | null) => {
  useContextMeter.setState((state) =>
    state.used === used && state.window === window ? state : { used, window }
  )
}

const KEPT_KEY = 'null:kept-messages'

const loadKept = (): Set<string> => {
  try {
    const raw = localStorage.getItem(KEPT_KEY)
    const parsed: unknown = raw ? JSON.parse(raw) : []
    return new Set(Array.isArray(parsed) ? parsed.filter((id) => typeof id === 'string') : [])
  } catch {
    return new Set()
  }
}

type KeptState = {
  kept: Set<string>
}

export const useKept = create<KeptState>(() => ({ kept: loadKept() }))

export const toggleKept = (id: string) => {
  useKept.setState((state) => {
    const kept = new Set(state.kept)
    if (kept.has(id)) {
      kept.delete(id)
    } else {
      kept.add(id)
    }
    try {
      localStorage.setItem(KEPT_KEY, JSON.stringify([...kept]))
    } catch {
      // 存不下只影响下次打开，不影响这一轮
    }
    return { kept }
  })
}

/** Mirrors the engine's own estimate closely enough to fill a budget. */
export const estimateTokens = (text: string) =>
  text.length === 0 ? 0 : Math.floor(text.length / 3) + 1

/**
 * Drops the oldest turns until the rest fits [budgetTokens].
 *
 * Kept turns stay. The rule a window needs is not "keep the newest N" -- it is
 * "keep what the user said matters, and fill the rest" -- which is the whole
 * reason NULL had a keep button while the server-side clients did not.
 *
 * Returns the turns to send and how many were dropped.
 */
export const trimTurns = <T extends { id?: string; text: string; fromUser: boolean }>(
  turns: T[],
  budgetTokens: number,
  kept: Set<string>
): { turns: T[]; dropped: number } => {
  const cost = turns.reduce((sum, turn) => sum + estimateTokens(turn.text), 0)
  if (budgetTokens <= 0 || cost <= budgetTokens) {
    return { turns, dropped: 0 }
  }
  const keep: T[] = []
  const rest: T[] = []
  for (const turn of turns) {
    if (turn.id && kept.has(turn.id)) {
      keep.push(turn)
    } else {
      rest.push(turn)
    }
  }
  let budget = budgetTokens - keep.reduce((sum, turn) => sum + estimateTokens(turn.text), 0)
  const keptTail: T[] = []
  for (let index = rest.length - 1; index >= 0; index -= 1) {
    const turn = rest[index]
    const price = estimateTokens(turn.text)
    if (price > budget) {
      break
    }
    budget -= price
    keptTail.unshift(turn)
  }
  const surviving = new Set([...keep, ...keptTail])
  const ordered = turns.filter((turn) => surviving.has(turn))
  return { turns: ordered, dropped: turns.length - ordered.length }
}
