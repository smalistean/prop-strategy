<script setup lang="ts">
interface Row {
  rank: number
  base: string
  shortVenue: string
  shortSymbol: string
  shortRate: number
  longVenue: string
  longSymbol: string
  longRate: number
  spreadAnnualPct: number
  thinLegWeeklyVolume: number
}
interface Book {
  status: string
  signalRunId?: string
  cutoffUtc?: string
  generatedAt?: string
  venues?: string
  lookbackDays?: number
  positions?: number
  legsConsidered?: number
  minSpreadPct?: number
  book: Row[]
}

const endpoint = useRuntimeConfig().public.signalApiUrl as string

// useFetch rather than a lifecycle hook: it gives pending/error without hand-rolled state, and with
// ssr:false it runs in the browser, so the page always shows what the ranker last wrote.
const { data, pending, error, refresh } = await useFetch<Book>(endpoint, {
  server: false,
  // The API caches for 60s and the ranker runs hourly; refetching faster asks for a book that
  // cannot have changed.
  getCachedData: undefined
})

const money = (n: number) =>
  n >= 1e9 ? `$${(n / 1e9).toFixed(1)}B`
  : n >= 1e6 ? `$${(n / 1e6).toFixed(0)}M`
  : n >= 1e3 ? `$${(n / 1e3).toFixed(0)}k`
  : `$${n.toFixed(0)}`

const ago = (iso?: string) => {
  if (!iso) return '—'
  const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000)
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins}m ago`
  const h = Math.floor(mins / 60)
  return h < 24 ? `${h}h ago` : `${Math.floor(h / 24)}d ago`
}

// Colour by venue so the cross-venue structure is readable at a glance rather than by reading names.
const venueClass = (v: string) => `venue venue-${v}`
</script>

<template>
  <main>
    <header>
      <h1>XVF signal</h1>
      <button :disabled="pending" @click="refresh()">
        {{ pending ? 'loading…' : 'refresh' }}
      </button>
    </header>

    <p v-if="error" class="notice error">
      Could not reach the signal API. {{ error.message }}
    </p>

    <p v-else-if="data?.status === 'NONE'" class="notice">
      No signal run has been written yet.
    </p>

    <template v-else-if="data">
      <dl class="meta">
        <div><dt>run</dt><dd>{{ data.signalRunId }}</dd></div>
        <div><dt>generated</dt><dd>{{ ago(data.generatedAt) }}</dd></div>
        <div><dt>positions</dt><dd>{{ data.positions }}</dd></div>
        <div><dt>legs considered</dt><dd>{{ data.legsConsidered?.toLocaleString() }}</dd></div>
        <div><dt>lookback</dt><dd>{{ data.lookbackDays }}d</dd></div>
        <div><dt>venues</dt><dd>{{ data.venues }}</dd></div>
      </dl>

      <p v-if="(data.lookbackDays ?? 7) < 7" class="notice warn">
        Lookback is {{ data.lookbackDays }} days, not 7. Observations began 2026-08-16, so spreads are
        annualised from a short window and are noisier than the production figure.
      </p>

      <table>
        <thead>
          <tr>
            <th class="num">#</th>
            <th>base</th>
            <th>short — pays more</th>
            <th>long — pays less</th>
            <th class="num">spread %</th>
            <th class="num">thin leg vol</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in data.book" :key="r.rank">
            <td class="num dim">{{ r.rank }}</td>
            <td class="base">{{ r.base }}</td>
            <td><span :class="venueClass(r.shortVenue)">{{ r.shortVenue }}</span> {{ r.shortSymbol }}</td>
            <td><span :class="venueClass(r.longVenue)">{{ r.longVenue }}</span> {{ r.longSymbol }}</td>
            <td class="num strong">{{ r.spreadAnnualPct.toFixed(1) }}</td>
            <td class="num dim">{{ money(r.thinLegWeeklyVolume) }}</td>
          </tr>
        </tbody>
      </table>

      <p class="footnote">
        Short the venue paying more funding, long the one paying less, equal notional on the same coin.
        Exploratory — every parameter was chosen while looking at the data, and the forward test has
        not run.
      </p>
    </template>
  </main>
</template>

<style>
:root {
  --bg: #fbfbfa; --fg: #1a1a1a; --dim: #6b6b6b; --line: #e4e4e1;
  --accent: #b45309; --warn-bg: #fef6e7; --err: #b42318;
  --binance: #b45309; --bybit: #0f766e; --hyperliquid: #6d28d9; --dydx: #1d4ed8;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg: #16161a; --fg: #ececec; --dim: #9a9a9a; --line: #2c2c32;
    --accent: #f59e0b; --warn-bg: #2a2313; --err: #f87171;
    --binance: #f59e0b; --bybit: #2dd4bf; --hyperliquid: #a78bfa; --dydx: #60a5fa;
  }
}
* { box-sizing: border-box; }
body {
  margin: 0; background: var(--bg); color: var(--fg);
  font: 15px/1.5 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
}
main { max-width: 1000px; margin: 0 auto; padding: 2rem 1.25rem 4rem; }
header { display: flex; align-items: baseline; justify-content: space-between; gap: 1rem; }
h1 { font-size: 1.35rem; font-weight: 600; margin: 0 0 1.25rem; letter-spacing: -0.01em; }
button {
  font: inherit; font-size: 0.85rem; padding: 0.3rem 0.75rem; cursor: pointer;
  border: 1px solid var(--line); border-radius: 6px; background: transparent; color: var(--dim);
}
button:hover:not(:disabled) { color: var(--fg); border-color: var(--dim); }
button:disabled { opacity: 0.5; cursor: default; }

.meta { display: flex; flex-wrap: wrap; gap: 0 2rem; margin: 0 0 1.5rem; }
.meta div { margin: 0; }
.meta dt { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--dim); }
.meta dd { margin: 0.1rem 0 0; font-variant-numeric: tabular-nums; }

.notice { padding: 0.75rem 1rem; border-radius: 6px; font-size: 0.9rem; }
.notice.warn { background: var(--warn-bg); border: 1px solid var(--line); }
.notice.error { color: var(--err); }

/* The table is the point of the page, so it scrolls itself rather than the body. */
table { width: 100%; border-collapse: collapse; font-size: 0.9rem; display: block; overflow-x: auto; }
thead th {
  text-align: left; font-weight: 500; font-size: 0.72rem; text-transform: uppercase;
  letter-spacing: 0.04em; color: var(--dim); padding: 0 0.6rem 0.5rem; white-space: nowrap;
}
tbody td { padding: 0.45rem 0.6rem; border-top: 1px solid var(--line); white-space: nowrap; }
tbody tr:hover td { background: color-mix(in srgb, var(--fg) 4%, transparent); }
.num { text-align: right; font-variant-numeric: tabular-nums; }
.dim { color: var(--dim); }
.strong { font-weight: 600; color: var(--accent); }
.base { font-weight: 600; }

.venue { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.03em; margin-right: 0.35rem; }
.venue-binance { color: var(--binance); }
.venue-bybit { color: var(--bybit); }
.venue-hyperliquid { color: var(--hyperliquid); }
.venue-dydx { color: var(--dydx); }

.footnote { margin-top: 2rem; font-size: 0.8rem; color: var(--dim); max-width: 62ch; }
</style>
