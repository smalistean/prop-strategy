# Idea testing protocol

Written 2026-09-12 07:36 UTC. Standing rules for how a new idea in this repository is measured, decided and sized.
Applies to every idea declared after this date. Existing pre-registrations are not retroactively governed by
it; the first application is amendment A8 of `WEEKEND_FADE_FUNDING_PREREGISTRATION.md` (newcomer cohort N1).

## The problem this solves

Edges in this market decay as they are found and crowded. The defence is to test new ideas quickly. The wrong
way to be quick is to lower the evidence bar, which just converts decay risk into false-positive risk: this
repository has already killed the BTC round-number fade, the XVF narrow policy, the holiday skip, the pre-close
run-up skip and the A7 correlation shortcut, every one of which looked good on a first look.

The right way is to raise **information per unit of calendar time**, and to spend a fixed error budget across
several scheduled looks instead of one. Nothing here weakens the standard of evidence. A sequential test with
properly computed boundaries has the same false-positive rate as the single test it replaces.

## 1. Declare before the first observation

An idea is not under test until a pre-registration names all of the following, with a UTC timestamp, and
**before any outcome in the test window exists**:

1. **Population** — the exact instrument list, frozen. Names added to the venue later belong to the next cohort.
2. **Window** — the first weekend/day that counts. Data before it is discarded even where it exists, because a
   window that starts where the data happens to look good is not a window.
3. **Event definition** — entry, exit, cost, and the trigger, referencing the frozen definition it inherits.
4. **Information metric** — the count the statistic is computed on. This is almost never calendar time; see §2.
5. **Look schedule** — the information levels at which the test is examined. Looking off-schedule voids the test.
6. **Boundaries** — computed by §3, stated numerically.
7. **Futility rule** — when the idea is declared dead early.
8. **Maximum wait** — a calendar backstop and what happens if the information target is never reached.
9. **What each outcome means** — specifically, what gets traded, on which account, at what size.

## 2. Information, not calendar

The statistic is computed on de-clustered observations: one number per period, the mean across whatever
triggered in it. Periods with no trigger carry no information and are not observations. This matters more than
it looks. A twenty-name cohort on the weekend fade produces a usable weekend about 63% of the time (measured on
random twenty-name subsets of the 95-name E1 population), so twenty calendar weekends is about twelve
observations, not twenty. A rule written in calendar weeks silently tests a smaller sample than it claims.

The de-clustered weekend is the unit for the **statistic**, because it is what stops many correlated names in
one period from being counted as independent replication. It is not the unit for **return on capital**: a
period with eight triggers deploys eight times the notional of a period with one, so the money earned follows
the event-weighted mean. A declaration that sets an economic bar must say which of the two it applies to, and
in general should require both.

Two ways to raise information density, both preferred over waiting:
- **Cross-section.** Pool many instruments under one definition. The fade reached t = 1.86 in eight months only
  because 94 events across 27 names were pooled; per name it would have taken years.
- **Event rate.** When choosing between ideas, the number of observations per month is part of the idea's value,
  because it is the speed at which you can find out whether it is real.

## 3. Computing the boundaries

Fix the total one-sided error budget at whatever the single fixed-sample test would have spent, so the
sequential version is not a weaker test. Shape the boundaries O'Brien-Fleming style, `c_k = C / sqrt(m_k / m_max)`,
so early looks demand a much larger statistic and the final look is close to the original bar. Then find `C` by
simulation:

- Build the null by **centring an empirical distribution of the same shape** as the thing being tested: resample
  the observation-level distribution from the closest available population, subtract its mean so the true effect
  is exactly zero. Do not assume normality; these distributions are fat-tailed and left-skewed.
- Re-run the calibration under a normal null and a t(4) null at the same standard deviation, and **keep the C
  that controls the error rate under the worst of the three.** For N1 the empirical null was the most forgiving
  (0.061) and the normal null the harshest (0.075); calibrating on the empirical one alone would have understated
  the error rate by a fifth.
- Report the realised error rate under each null in the amendment, not just the target.

Conditions other than the test statistic (an economic floor, a tail bar) must be checked for whether they make
early stopping *easier*. A "worst observation so far" bar does exactly that: with fewer observations there are
fewer chances to breach it, which rewards stopping early. Such conditions are **standing**, applied continuously
including after admission, and are not part of the crossing rule.

## 4. Sizing follows evidence

| State | Size |
|---|---|
| Declared, under test | zero; shadow ledger only |
| Efficacy boundary crossed | the size named in the declaration, in dollars, on the account named in the declaration. A declaration that says "the spec's size" without a number does not satisfy §1 item 9 and the crossing does not authorise a trade until the number is filed. |
| Two consecutive looks above the final boundary, or the full information target reached | full participation |
| Futility boundary crossed, or a standing condition breached | zero, test closed |

Capital is never committed to an idea before its boundary is crossed. The shadow period is free; the
information it buys is the same information a traded period would buy, minus the execution facts.

## 5. What voids a test

Changing the population, window, definition, metric, look schedule or boundaries after the first observation
exists. Looking at the statistic off-schedule. Computing an outcome for a name outside the declared population
and then adding it.

**Voiding must not be cheaper than failing.** A test that fails carries an embargo before the same population
may be re-declared. A voided test carries **at least the same embargo**, counted from the date the completed
test would have ended, and the voiding event, its date and its cause are written into the original amendment.
Without this, the cheapest escape from a cohort that is running badly is to trip a voiding condition, which
inverts the incentive at exactly the point where the discipline is supposed to bind.

Discovering a bug in the measurement code does not void a test. The fix, its date and the before/after numbers
are recorded. If the bug touched the null calibration the boundaries are recomputed, and **a recomputation may
only make them stricter**; if the corrected calibration would loosen them, the old boundaries stand. A bug that
would loosen a boundary by more than a trivial amount voids and restarts the test under the embargo.

**Across tests there is no family-wise control, and that is a deliberate choice with a cost.** Each declared
test spends its own budget. Four independent cohorts at a one-sided 7.5% each carry roughly a one-in-four
chance that at least one false admission occurs somewhere in the stream. The protocol therefore requires: the
number of tests running concurrently at full budget is stated in §6; an admission is provisional until the
cohort has been traded for a declared confirmation period; and a second cohort is not admitted while an earlier
admitted cohort is still inside its confirmation period.

## 6. Active tests

| Test | Declared | Population | Looks (m) | Boundaries | Backstop | State |
|---|---|---|---|---|---|---|
| Newcomer cohort N1 (A8) | 2026-09-12 07:36 UTC | 20 names listed 2026-08-06 → 09-03 | 6, 9, 12 | t ≥ 2.55 / 2.08 / 1.80 | 2027-04-30 | window opens 2026-09-18, zero observations |

Only one test runs at full budget at a time; N1 is it.

Operating the N1 test: run `python3 scripts/analysis-sequential-test.py count <friday>` after each weekend to
advance the bookkeeping. `count` reads only the anchor and entry bars, computes **no outcome** and prints **no
statistic**, so running it weekly cannot become a look, and it refuses if the bars it needs are not yet stored.
When it reports that a scheduled m has been reached, run `… look <friday>`, which refuses unless a look is
outstanding, evaluates a missed look on its own prefix at its own boundary, and appends to the tracked ledger
`research/n1_sequential_ledger.jsonl`. That ledger is the evidence that no look happened off schedule, so it is
committed after every run. `… boundaries` recomputes the calibration, `… power` the power tables, and
`… schedule` prints the weekend enumeration.
