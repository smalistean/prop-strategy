# Earnings-results filing-night continuation study

Measured 2026-09-12 from the rule and source inventory frozen in
`EARNINGS_NIGHT_PREREGISTRATION.md`. Full event ledger:
`research/earnings-night-result-2026-09-12.json`.

## Verdict

**Failed. Do not trade continuation and do not invert it into a reversal rule.**

The primary, pilot-excluded cohort had enough information to decide: 32 usable triggers across 21 independent
New York filing nights. After funding and the frozen 13 bp cost, the date-declustered mean was **-56.4 bp**, the
median **-35.2 bp**, and t **-0.56**. Only 9 of 21 nights were positive. The economic and statistical gates both
failed, as did the quarter, leave-best-date and leave-best-symbol robustness gates.

The negative result is present before meaningful cost assumptions. Gross mean was -43.4 bp/night; the frozen
9/13/25 bp cost rows were -52.4/-56.4/-68.4 bp. Funding helped by only +1.4 bp/night on average.

## Primary results

| Measure | Result |
|---|---:|
| Source-qualified cohort-B events | 45 |
| Triggered usable events | 32 |
| Independent nights | 21 |
| Date-declustered mean net, 13 bp | -56.4 bp |
| Median night | -35.2 bp |
| t-statistic | -0.56 |
| Positive nights | 42.9% |
| Worst night | -961.6 bp |
| Event-weighted mean net | -72.3 bp |
| Mean without best night | -107.4 bp |
| Mean without best-contributing symbol (APP) | -88.1 bp |

Direction did not reveal a hidden continuation edge: upward shocks averaged -77.8 bp across 10 nights and
downward shocks -10.0 bp across 14 nights. Q2 was +98.0 bp but only four nights (t 0.98); Q3 was -92.7 bp across
17 nights (t -0.76). Only one quarter was positive, versus the frozen requirement of two quarters with at least
three nights each.

The pilot-contaminated cohort A also failed to reproduce the original impression under the exact event and
causal execution rule: 18 triggers across 14 nights averaged -48.3 bp (t -0.37), despite a positive +111.3 bp
median. Its event-weighted mean was only +11.2 bp. This is descriptive only and was not used for the decision.

## Control and interpretation

The pooled same-clock, non-Item-2.02 generic-shock control contained 98 rows on 32 dates. It also lost money:
-73.3 bp/night, t -0.91. The predeclared fine matching cells (direction, 3-5/5-10/10+% shock, weekday and quarter)
were empty for some cohort-B events, so the exact control-adjusted aggregate is correctly reported as
unavailable rather than rebucketed after seeing the outcome.

That control suggests a useful interpretation but not a new strategy: large after-hours moves in these perps
were noisy in general, and the verified earnings-results subset did not turn that noise into continuation.
Because the negative estimate is statistically weak and the reversal direction was not preregistered, this is
not evidence to short the move either.

The original eight-event pilot was therefore a small-sample false lead, or at least a rule that did not transfer
to a fresh universe. It used an hourly price-move proxy without a reproducible earnings calendar, while this
study used timestamped SEC Item 2.02 filings, deterministic document checks, a fresh-symbol cohort and a causal
decision/fill boundary.

## Execution and capacity

Capacity was not the blocker. Across the 32 primary triggers, 10% of the completed decision-hour quote volume
had a minimum of about $2,159, 25th percentile $16,057 and median $69,936; all observations exceeded $1,000.
These are volume proxies rather than executable order-book depth, but they are comfortably above the small
ticket sizes being discussed. The edge itself was absent.

## Reproduction

The outcome-blind manifest is frozen at SHA-256
`f318b8cf40fe06e78d4a5d2d88be8b98a3d0060ffecedd49e86e0a8727ac0d00`. The analysis refuses a different digest.

```bash
python3 scripts/analysis-earnings-night.py selfcheck
python3 scripts/analysis-earnings-night.py measure \
  --manifest research/earnings-night-events-2026-09-12.json \
  --output /tmp/earnings-night-result.json
```

An independent recomputation from the saved ledger reproduced 32 events, 21 nights, -56.402810 bp mean,
-35.232270 bp median and t -0.556868 exactly. The source manifest and complete result JSON are retained so the
failed idea remains auditable.
