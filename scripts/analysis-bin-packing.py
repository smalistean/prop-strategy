#!/usr/bin/env python3
"""If two venues both have idle capital and a pair uses both, size that pair up.

Result: deployment rises 62.0% -> 71.3% at a 1.5x position cap, with effective
positions unchanged at 18.9. Uncapped it reaches 97.4% but collapses the book to
2.2 effective positions, which is not the same strategy.

Input is pairs.csv - see scripts/analysis-stamp-timing.py for the query.

Equal sizing wastes capital because the book's legs land unevenly across venues
while the funding is anchored to each venue's p90 leg count. This asks what
happens if position sizes are allowed to vary to fill the slack instead.

It is a bin-packing problem: choose size s_i >= 0 for each pair, subject to
  for every venue v:  sum of s_i over pairs touching v  <=  capacity_v
maximising deployed capital, with a cap on any single position so the book does
not collapse into a few large bets.

Sizes are in units of the base leg notional. Capacity is in leg-slots: p90
anchoring funds 61 slots (dydx 17, hyperliquid 17, binance 14, bybit 13) and a
20-pair book uses 40 of them under equal sizing, hence 65.6% deployment.

Solved by water-filling in small increments - always feed the pair with the most
headroom on its tighter venue. For this structure that is within a fraction of a
percent of the true optimum and is far easier to check than an LP; deployment
came out identical under three different tie-break orders, which suggests the
bound is tight.

CAVEAT: ties are broken by list order, and pairs arrive ranked by spread. That
makes the method look like it favours better pairs (corr(size, rank) = -0.271)
when under random tie-breaking it is neutral (-0.011). Sizes are driven by venue
availability, not by the signal. Breaking ties toward the wider spread is a real
and free lever worth +6.4% average spread - but it is a mild form of the
spread-weighting that already cost a third of the Sharpe, so measure it on
forward returns before trusting it.
"""
import csv
import statistics
from collections import defaultdict

CAPACITY = {"dydx": 17.0, "hyperliquid": 17.0, "binance": 14.0, "bybit": 13.0}
TOTAL_SLOTS = sum(CAPACITY.values())
STEP = 0.02

weeks = defaultdict(list)
with open("pairs.csv") as handle:
    for row in csv.DictReader(handle):
        weeks[row["w"]].append((row["sv"], row["lv"]))


def water_fill(pairs, max_size):
    """Greedy fill. Returns per-pair sizes."""
    sizes = [0.0] * len(pairs)
    used = defaultdict(float)
    while True:
        best, best_head = -1, 0.0
        for i, (a, b) in enumerate(pairs):
            if sizes[i] >= max_size:
                continue
            head = min(CAPACITY.get(a, 0) - used[a],
                       CAPACITY.get(b, 0) - used[b],
                       max_size - sizes[i])
            if head > best_head:
                best, best_head = i, head
        if best < 0 or best_head < STEP:
            break
        a, b = pairs[best]
        sizes[best] += STEP
        used[a] += STEP
        used[b] += STEP
    return sizes


print(f"weeks: {len(weeks)}   capacity: {TOTAL_SLOTS:.0f} leg-slots\n")
print(f"{'policy':28s} {'deployed':>9s} {'largest pos':>12s} {'eff. positions':>15s}")

# Equal sizing: every pair size 1, but only where both venues have room.
dep, largest, effective = [], [], []
for _, pairs in weeks.items():
    used = defaultdict(float)
    sizes = []
    for a, b in pairs:
        s = 1.0 if (used[a] + 1 <= CAPACITY.get(a, 0)
                    and used[b] + 1 <= CAPACITY.get(b, 0)) else 0.0
        sizes.append(s)
        used[a] += s
        used[b] += s
    total = sum(sizes)
    if total <= 0:
        continue
    dep.append(2 * total / TOTAL_SLOTS)
    largest.append(max(sizes) / total)
    effective.append(1.0 / sum((s / total) ** 2 for s in sizes if s > 0))
print(f"{'equal, skip if no room':28s} {100*statistics.mean(dep):8.1f}% "
      f"{100*statistics.mean(largest):11.1f}% {statistics.mean(effective):15.1f}")

for cap in (1.5, 2.0, 3.0, 99.0):
    dep, largest, effective = [], [], []
    for _, pairs in weeks.items():
        sizes = water_fill(pairs, cap)
        total = sum(sizes)
        if total <= 0:
            continue
        dep.append(2 * total / TOTAL_SLOTS)
        largest.append(max(sizes) / total)
        effective.append(1.0 / sum((s / total) ** 2 for s in sizes if s > 0))
    label = f"bin-packed, max {cap:g}x" if cap < 90 else "bin-packed, uncapped"
    print(f"{label:28s} {100*statistics.mean(dep):8.1f}% "
          f"{100*statistics.mean(largest):11.1f}% {statistics.mean(effective):15.1f}")
