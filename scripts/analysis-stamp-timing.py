#!/usr/bin/env python3
"""Does timing entry and exit around funding stamps pay? Measured: no, ~$7/yr on $10k.

Simulates a 72-hour XVF hold at one-hour granularity over the pairs the strategy
actually selected, using each leg's real annualised rate and real cadence.

The rule under test: be in the position only for stamps whose net cashflow is
positive, acted on at entry and exit where it costs no fees. It looks promising
because stamps are not equal - at 100% annualised an 8h payment is 9.1bp against
an hourly payment's 1.1bp - so a coincident stamp is net negative whenever
rate_short < k * rate_long, while the entry signal only needs
rate_short > rate_long.

Two anchorings are compared, because the obvious objection is that delaying
entry shortens the hold:

  FIXED EXIT  the rebalance happens at a scheduled wall-clock time
  FIXED HOLD  always 72 hours from entry

It turns out to make almost no difference. See XVF_IMPLEMENTATION.md section 4.

Input is pairs.csv, produced by:

  \\copy (
    WITH wk AS (
      SELECT venue, normalise_perp_base(
               CASE WHEN venue='dydx' THEN split_part(split_part(venue_symbol,',',1),'-',1)
                    WHEN venue='hyperliquid' THEN venue_symbol
                    WHEN venue_symbol LIKE '%USDT' OR venue_symbol LIKE '%USDC'
                         THEN left(venue_symbol, length(venue_symbol)-4) ELSE venue_symbol END) AS base,
             date_trunc('week', funding_time) AS w,
             sum(funding_rate)*52*100 AS annual, count(*) AS pays_per_week
      FROM perp_funding_all WHERE venue IN ('binance','bybit','hyperliquid','dydx')
        AND funding_time >= '2023-11-01' GROUP BY 1,2,3),
    b AS (SELECT base, w, (max(annual)-min(annual)) AS spread,
                 (array_agg(venue ORDER BY annual DESC))[1] AS sv,
                 (array_agg(annual ORDER BY annual DESC))[1] AS s_rate,
                 (array_agg(pays_per_week ORDER BY annual DESC))[1] AS s_pays,
                 (array_agg(venue ORDER BY annual))[1] AS lv,
                 (array_agg(annual ORDER BY annual))[1] AS l_rate,
                 (array_agg(pays_per_week ORDER BY annual))[1] AS l_pays
          FROM wk GROUP BY 1,2 HAVING count(DISTINCT venue)>=2)
    SELECT base, w::date, sv, s_rate, s_pays, lv, l_rate, l_pays
    FROM (SELECT *, row_number() OVER (PARTITION BY w ORDER BY spread DESC) rk
          FROM b WHERE spread>20) z WHERE rk<=20
  ) TO 'pairs.csv' CSV HEADER

CAVEAT ON LEVELS: this uses each week's own realised rate as if it persisted
through the following hold, so the absolute funding figures are far above what
the strategy actually earns forward. Only the RATIO between policies is
meaningful, which is why results are reported as a percentage of the baseline
rather than in absolute terms.
"""
import csv
import statistics
import sys

HOURS = 72
PATH = sys.argv[1] if len(sys.argv) > 1 else "pairs.csv"


def cadence_hours(pays_per_week):
    """Hours between stamps, from the realised weekly payment count."""
    if pays_per_week <= 0:
        return None
    hours = round(168.0 / pays_per_week)
    # Only the schedules venues actually run; anything else is a gap in history.
    return hours if hours in (1, 2, 4, 8) else None


def stamps(cad, start, end):
    """Stamp hours in [start, end) for a leg on `cad`-hour cadence, aligned to 00:00."""
    return range(((start + cad - 1) // cad) * cad, end, cad)


def funding(s_rate, s_cad, l_rate, l_cad, entry, exit_):
    """Funding over [entry, exit_), as a percentage of one leg's notional.

    The short leg receives its rate, the long leg pays its own. Both legs carry
    equal notional, so the two accrue on the same base and can be summed.
    """
    got = sum(s_rate / (8760.0 / s_cad) for _ in stamps(s_cad, entry, exit_))
    paid = sum(l_rate / (8760.0 / l_cad) for _ in stamps(l_cad, entry, exit_))
    return (got - paid) * 100.0


def net_at(hour, s_rate, s_cad, l_rate, l_cad):
    """Net cashflow in percent if a stamp lands on `hour`; 0 if neither leg stamps."""
    net = 0.0
    if hour % s_cad == 0:
        net += s_rate / (8760.0 / s_cad)
    if hour % l_cad == 0:
        net -= l_rate / (8760.0 / l_cad)
    return net * 100.0


def load(path):
    rows = []
    with open(path) as handle:
        for row in csv.DictReader(handle):
            s_cad = cadence_hours(int(row["s_pays"]))
            l_cad = cadence_hours(int(row["l_pays"]))
            if s_cad and l_cad:
                rows.append((float(row["s_rate"]) / 100.0, s_cad,
                             float(row["l_rate"]) / 100.0, l_cad))
    return rows


def main():
    rows = load(PATH)
    print(f"pairs simulated: {len(rows):,}")

    negative_any = 0
    negative_share = []
    for s_rate, s_cad, l_rate, l_cad in rows:
        nets = [net_at(h, s_rate, s_cad, l_rate, l_cad)
                for h in range(0, max(s_cad, l_cad) * 2)
                if h % s_cad == 0 or h % l_cad == 0]
        share = sum(1 for n in nets if n < 0) / len(nets)
        negative_share.append(share)
        if share > 0:
            negative_any += 1
    print(f"pairs with ANY negative-net stamp: {negative_any:,} "
          f"({100 * negative_any / len(rows):.1f}%)")
    print(f"mean share of stamps that are negative: "
          f"{100 * statistics.mean(negative_share):.1f}%\n")

    for label, fixed_hold in (("FIXED EXIT (scheduled rebalance)", False),
                              ("FIXED HOLD (72h from entry)", True)):
        base, timed, delays = [], [], []
        cases = 0
        for s_rate, s_cad, l_rate, l_cad in rows:
            # Average over every starting phase: that is what a scheduler which
            # ignores stamps does in practice.
            for phase in range(24):
                scheduled_exit = phase + HOURS
                base.append(funding(s_rate, s_cad, l_rate, l_cad, phase, scheduled_exit))

                entry = phase
                for hour in range(phase, min(phase + max(s_cad, l_cad), scheduled_exit) + 1):
                    if net_at(hour, s_rate, s_cad, l_rate, l_cad) > 0:
                        entry = hour
                        break
                exit_ = entry + HOURS if fixed_hold else scheduled_exit
                # Pull the exit back over any trailing run of negative stamps.
                while exit_ > entry + 1 and net_at(exit_ - 1, s_rate, s_cad, l_rate, l_cad) < 0:
                    exit_ -= 1
                timed.append(funding(s_rate, s_cad, l_rate, l_cad, entry, exit_))

                cases += 1
                if entry > phase:
                    delays.append(entry - phase)

        mean_base = statistics.mean(base)
        mean_timed = statistics.mean(timed)
        gain = (mean_timed - mean_base) / abs(mean_base) * 100
        print(f"=== {label} ===")
        print(f"  baseline funding      {mean_base:7.3f}% of leg notional per {HOURS}h hold")
        print(f"  timed entry and exit  {mean_timed:7.3f}%   "
              f"delta {mean_timed - mean_base:+.4f}pp  = {gain:+.2f}% of funding")
        print(f"  entry delayed in      {100 * len(delays) / cases:.1f}% of cases, "
              f"mean delay {statistics.mean(delays) if delays else 0:.1f}h")
        print(f"  if gross is 19%/yr    {19 * gain / 100:+.3f}% of capital/yr = "
              f"${10000 * 19 * gain / 10000:+.2f}/yr on $10k\n")


if __name__ == "__main__":
    main()
