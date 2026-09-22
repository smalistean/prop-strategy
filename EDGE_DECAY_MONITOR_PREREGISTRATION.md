# Weekend-fade edge-decay monitor — pre-registration

**Status: the calibrated alarm declared 2026-09-21 18:42 UTC was WITHDRAWN 2026-09-22 04:15 UTC.**
A record-only accumulator is declared in its place (§2). The withdrawn declaration is kept in full (§3) and every
number in it reproduces from `scripts/analysis-edge-decay-v1-withdrawn.py`. Standing rules: `IDEA_TESTING_PROTOCOL.md`.

---

## 1. Withdrawal — 2026-09-22 04:15 UTC

### What was reviewed

The declaration in §3 was put through an adversarial review workflow: three independent critics (statistical
design, code-versus-document, protocol adversary) produced 21 findings; each finding was then judged by three
independent refuters instructed to refute it, majority rule. **8 confirmed, 13 refuted.** In parallel the author ran
three independent computations — the baseline's lag-1 autocorrelation, the sensitivity of the statistic to the
overnight endpoint hour, and a re-freeze of every baseline variant with holiday weekends included and parameter
uncertainty propagated — and reached the same conclusion before the refuters reported.

### Three confirmed causes, each sufficient on its own

**1. Holiday weekends were excluded, and they are the dominant outliers.** `fridays()` dropped every date in a
six-entry holiday list. The live rule trades holiday weekends with no skip, and the sibling test
`analysis-sequential-test.py` includes them. Put back, the baseline changes from 12 to 17 weekends and:

| baseline 2026-05-29 → 09-18 | n | mean R | sd | cv |
|---|---:|---:|---:|---:|
| holidays excluded (as declared) | 12 | 0.340 | 0.085 | **0.25** |
| holidays included | 17 | 0.406 | 0.190 | **0.47** |

Memorial Day weekend printed R = **1.81** (five times baseline), July 4th 0.71, Labor Day 0.58. A three-day closed
window drifts more, and the three-night preceding week gives a noisier denominator. Excluding them is what made the
statistic look tame.

**2. The "overnight" control was not a closed window.** The denominator's endpoint was the bar opening 12:00 NY,
whose close is the **13:00 NY** print — 3½ hours into the regular session. The declaration called this "the same
dislocation mechanism over a shorter closed window". It is the closed window plus the morning re-anchoring. The
endpoint hour was chosen in exploration without comparing alternatives; compared afterwards:

| overnight endpoint (bar open, NY) | baseline mean R | sd | cv |
|---|---:|---:|---:|
| 04:00 — deep in the closed window, the honest analogue of Sunday 20:00 UTC | 0.684 | 0.249 | 0.36 |
| 08:00 — last bar closing before the 09:30 open | 0.599 | 0.264 | 0.44 |
| 09:00 | 0.434 | 0.178 | 0.41 |
| **12:00 — as declared** | 0.340 | 0.085 | **0.25** |

The 12:00 endpoint has the lowest variance *because* the overnight gap has been half-reverted by 13:00 NY. The
published power table inherited that. One row shows the contamination directly: 2026-09-11 is the only weekend
where the 12:00 denominator is *smaller* than the 08:00 one (35.9 vs 42.8 bp/√h) — the preceding week's mornings
reverted so hard that 13:00 NY sat closer to the prior close than 09:00 did. That weekend printed R = 0.937.

**3. The calibration conditioned on point estimates from 12 weekends.** All three nulls fixed the mean at 0.3397
and the sd at 0.0852. C = 0.265 sat **3.04 standard errors** below a mean whose own 95% CI was [0.286, 0.394].
Propagating the baseline's sampling uncertainty (flat-prior posterior over mean and sd, redrawn per trial), the
family-wise false-alarm rate over 104 checks is **≈ 0.24**, not the 0.076 declared — computed by the critic (0.238)
and reproduced independently by two refuters (0.242, 0.243). The refuters added the decisive point: lowering C to
hold 0.10 takes the power at a 20% compression from 1.00 / median 14 weekends to **0.67 / median 46**, so the
declared power table cannot survive an honest calibration. This is a redesign, not a one-number edit. It is also
the standard Phase-I estimation-error problem in control charts: the monitor is really a two-sample comparison
(12 baseline vs a rolling 12) implemented as a one-sample test against a known mean.

### The rescue attempt, and why it fails too

Handling holidays by mechanism instead of exclusion — normalise each |drift| by √(closed hours), take log R so
multiplicative outliers become additive — does tame them (July 4th falls from 7 sd to about 2). It does not fix the
calibration, because the constraint was never the transform. **It is n.** At 17 weekends the statistic's sd is
0.37–0.49 in log units, so a 20% compression is **0.46–0.60 standard deviations** of the thing being watched:

| variant (holidays in, √h, log) | n | sd | boundary at a 26% compression: worst-iid FA | marginalised FA |
|---|---:|---:|---:|---:|
| endpoint 12:00, mean | 17 | 0.369 | 0.11 | **0.26** |
| endpoint 12:00, median | 17 | 0.397 | 0.19 | **0.31** |
| endpoint 08:00, mean | 17 | 0.482 | 0.45 | **0.50** |
| endpoint 08:00, median | 17 | 0.490 | 0.43 | **0.51** |

No variant holds a 10% family-wise false-alarm rate at any boundary that could still fire before a ~26% compression.
Detection-probability columns from those runs are deliberately not quoted: a rolling mean over 104 checks eventually
wanders below any boundary near its mean, and that wandering is the same thing that produces the false alarms.

### Findings refuted, kept so the record shows what was considered

- *Baseline excludes a lower-R regime (Feb–May, R ≈ 0.22).* Composition artefact: the universe was 8–19 names then.
  On a fixed 8-name sub-universe the pre-May mean is 0.320 against 0.356 after — not lower — but cv 1.26 on eight
  names, so it cannot lengthen the baseline either.
- *Declaration not prospective (three post-baseline weekends seen before constants were fixed).* Judged minor; the
  record-only series below starts after the declaration anyway, which removes the question.
- *"393 weekends vs 14" not apples to apples.* Judged not a defect as framed; the arithmetic point is correct and is
  adopted here: 393 is the sample size for **50% power** at one-sided 5%, not 95%.
- *No freshness refusal; ledger appended on every run; 3-decimal selfcheck; right tail unrepresented; R dominated by
  ~8 volatile names; "what an alarm does" contradicts §4.* All judged minor or not defects. The first two are fixed in
  the record-only script regardless because they cost nothing.
- *The iid assumption is untestable at n = 12.* Confirmed as minor; baseline lag-1 autocorrelation is +0.003, so
  there is no evidence against it, but n = 12 cannot exclude ρ ≈ 0.5.

### Reproducibility

`scripts/analysis-edge-decay-v1-withdrawn.py` is the declared script, unchanged except for a header line; its
`baseline`, `calibrate 3000`, `power 3000` and `selfcheck` modes reproduce every figure in §3 (the doc's figures were
produced at 3,000 trials; the script's default was 4,000, which gives 0.077 and slightly different medians).
`research/edge_decay_ledger_v1_withdrawn.jsonl` holds the 15 rolling-mean rows it wrote; that format is obsolete.

---

## 2. Record-only accumulator — declared 2026-09-22 04:15 UTC

### What survives from the withdrawn argument

The reason to watch the input rather than the returns is intact. Weekend returns have sd ≈ 347 bp on a mean of
143.9; a 25% decay needs ~252 weekends of returns for 50% power — about eight years. The drift is far quieter than
the payoff, and a boundary on it will need far fewer observations. What §1 established is the number: **about 35
baseline weekends, not 12.** At sd ≈ 0.37 in log units that is where the standard error of the baseline mean is small
enough for a boundary at a ~15% compression to hold a marginalised family-wise false-alarm rate of 0.10.

So the accumulator's job is to collect that baseline **blind** — with no boundary in existence, so the data cannot
have been seen against one — and to do nothing else.

### The statistic (`scripts/analysis-edge-decay.py`)

Per weekend, over the frozen 24-name live universe, **holiday weekends included**:

```
L = ln(  mean_s |weekend drift_s| / √(weekend hours)   /   mean_s overnight_s  )
```

- **weekend drift** — anchor (last US session close) → entry bar (19:00 UTC the evening before the next session), the
  live shifted form shared with `analysis-sequential-test.py`. Divided by √hours so a 73-hour holiday weekend is
  comparable to a 49-hour one instead of being dropped.
- **overnight_s** — the weekday nights of the *preceding* week (so the value exists at the decision bar), each
  |15:00 NY close → endpoint| / √hours, averaged. **Two endpoints are recorded for every weekend and no choice between
  them is made now:**
  - `e08` — the bar opening 08:00 NY; its close (09:00 NY) is the last print before the 09:30 open. A closed window.
  - `e12` — the bar opening 12:00 NY; close 13:00 NY. Lower variance, partly post-reversion. Not a closed window.
  
  The future declaration picks one and discloses why, with the accumulated series as the evidence.
- **ln** so that multiplicative outliers are additive and symmetric.
- A weekend where fewer than 20 names price every leg produces no row.

### What the script does, and does not do

| `record [date]` | computes every weekend through that Friday not already in the ledger and appends **one row per weekend** (Friday, n, hours, holiday flag, the two raw components, L08, L12, recorded time, newest bar). **Refuses** if the newest stored bar precedes the last Friday's entry bar. Idempotent: rows are keyed by Friday and never rewritten. |
|---|---|
| `show` | prints the rows. **No aggregate is computed** — no mean, no rolling window, no boundary, no alarm. |
| `selfcheck` | asserts the 17 pre-declaration weekends reproduce their frozen values. Those 17 rows are a reproducibility check only; they are **not** a baseline for any future boundary. |

Nothing in it is read at the decision bar, and the live trigger count that v1 printed alongside R is gone.

### Re-open condition

The blind series begins with the weekend of **2026-09-25**. A boundary **may** be declared once **35 blind weekends**
are recorded (`REOPEN_N`), which is the weekend of **2027-05-21** if every weekend records. At that point:

- the boundary is calibrated on the blind series alone;
- the standard is **marginalised** family-wise false-alarm rate ≤ 0.10 over 104 weekly checks — the baseline's
  parameter uncertainty propagated, never point-conditional;
- the endpoint (`e08` or `e12`) is chosen then, with the 35-weekend comparison as the stated reason;
- an alarm, if declared, mandates a re-measurement and nothing else, as §3 already said and as the refuters upheld.

Until then this file records and the registry row in `IDEA_TESTING_PROTOCOL.md` §6 says so.

### What voids the record

Changing `UNIVERSE`, `MIN_NAMES`, the bar definitions or the endpoints breaks comparability with every row already
written; the ledger would be restarted under a new declaration, not amended. If the live universe is amended (for
example if cohort N1 is admitted), the same applies. Reading L at the bar to influence which names are traded makes
it part of the strategy rather than a measurement of it.

### Operating instructions

Once per weekend, after the kline refresh, in the same slot as `analysis-sequential-test.py count`:

```
python3 scripts/analysis-edge-decay.py record
```

`show` at any time. The ledger `research/edge_decay_ledger.jsonl` is committed after every `record`.

### Reading at declaration, 2026-09-22 04:15 UTC

17 pre-declaration weekends recorded (2026-05-29 → 09-18, three of them holiday weekends), **0 blind**.
No statistic across weekends exists and none will until 2027-05-21 at the earliest.

---

## 3. Withdrawn declaration — 2026-09-21 18:42 UTC (kept for the record; see §1 for why it does not stand)

Implementation at the time: `scripts/analysis-edge-decay.py`, now `scripts/analysis-edge-decay-v1-withdrawn.py`.

### The question this answered, and the one it did not

**Answered:** is the weekend dislocation the fade trades still forming at its historical size?

**Did not answer:** is the fade still profitable. The pre-registered headline remains **+143.9 bp / t=1.86 on the
frozen 20 weekends** and the monitor never touched it.

### Why the inputs and not the returns

Weekend returns have sd ≈ 347 bp on a mean of 143.9. Detecting compression from returns alone, one-sided 5%,
**at 50% power** (the declaration mislabelled this as 95%; corrected here, figures unchanged):

| decay in the mean | weekends of returns needed |
|---|---:|
| 20% (144 → 115 bp) | 393 |
| 25% (144 → 108 bp) | 252 |
| 30% (144 → 101 bp) | 175 |
| 40% (144 → 86 bp) | 98 |

### The statistic

```
R  =  mean |weekend drift|  /  mean |overnight drift|
```

weekend drift = anchor → entry bar, live shifted form; overnight drift = same names, the weekday nights of the week
before, 15:00 NY close → the bar opening 12:00 NY, averaged. Gate: 20 names. R was deliberately not predictive of
returns: 2026-09-11 printed R = 0.937 and lost 85 bp; 2026-09-18 printed 0.210 and made 423.

### Frozen baseline

Twelve weekends, 2026-05-29 → 2026-08-21, holiday weekends excluded:

```
0.408  0.339  0.419  0.531  0.207  0.292  0.237  0.346  0.315  0.337  0.305  0.341
mean 0.3397   sd 0.0852
```

### Boundary

| Item | Declaration |
|---|---|
| Window | rolling **K = 12** usable weekends |
| Alarm | rolling mean of R **< C = 0.265** (78% of baseline) |
| Calibration | three nulls — bootstrap, normal, t(4) at the same sd — over 104 weekly checks, parameters fixed at the point estimates. Worst-null family-wise false-alarm rate **0.076**. |
| Checks | once per weekend |

**Power**, at C = 0.265, 3,000 trials, bootstrap null with multiplicative compression:

| true compression | P(detect within 104 checks) | median weekends to fire |
|---|---:|---:|
| 5% | 0.11 | 53 |
| 10% | 0.48 | 48 |
| 15% | 0.93 | 30 |
| 20% | 1.00 | 14 |
| 30% | 1.00 | 9 |
| 50% | 1.00 | 6 |

### What an alarm did

An alarm did not stop trading, reduce size, or change any rule. It mandated exactly one thing: a re-measurement of
the edge on post-alarm weekends only, declared and run under `IDEA_TESTING_PROTOCOL.md` as a new test with its own
boundary. This clause was reviewed and upheld.

### Reading at the withdrawn declaration, 2026-09-21 18:42 UTC

Three post-baseline weekends. Rolling mean 0.370, above the baseline's 0.340. No alarm. The window was reported as
full and the monitor as armed; §1 explains why neither claim stood.
