-- Production-aligned Binance-Bybit calibration for XVF narrow-v1.
--
-- This replaces the superseded pending-direction analysis documented in
-- XVF_NARROW_V1_REVIEW_DISCUSSION.md. Production does NOT choose direction from pending rates: it
-- chooses the widest direction from trailing settled funding, then asks whether four pending
-- observations support that fixed direction. This query preserves that order.
--
-- Exact from persisted facts:
--   * seven-day settled-funding direction and raw spread;
--   * per-observation funding interval inferred like PostgresXvfFundingSnapshotSource;
--   * four distinct consecutive observed hours, fixed direction and strictly positive gaps;
--   * causal entry after both observations arrived;
--   * settled funding after entry through each requested horizon;
--   * a greedy 24-hour no-reentry ledger per base;
--   * 24/48/72/96/120-hour results, symbol contribution and leave-best-base-out.
--
-- Historical proxies, reported separately and never called exact production facts:
--   * 1-minute open prices replace synchronized executable bid/ask;
--   * trailing 24-hour kline turnover x7 replaces the live ticker's weekly-volume estimate;
--   * yesterday's raw settled-spread eligibility approximates production's stale flag because the
--     historical live ticker snapshot used by production was not persisted.
--
-- Remaining limitation: historical exchange instrument-type metadata was not persisted. The query
-- excludes non-crypto bases confirmed by Bybit's current public metadata on 2026-08-25, while
-- production still makes the final
-- authoritative API validation. Do not call the proxy cohort an exact execution replay.
--
-- Scope is Binance-Bybit USDT. Other venue pairs must be reported as separate route cohorts with
-- their own fee schedules.
--
-- Usage:
--   psql -X -U prop_strategy_app -d prop_strategy \
--     -f scripts/analysis-narrow-forecast-calibration.sql

\timing on

CREATE TEMP TABLE narrow_parameters AS
SELECT 7::int AS lookback_days,
       20.0::numeric AS minimum_annual_spread_pct,
       0.65::numeric AS stale_discount,
       0.90::numeric AS completeness_ratio,
       180::int AS typical_window_days,
       500000::numeric AS minimum_weekly_quote_volume,
       22.6::numeric AS round_trip_fee_bps,
       2.0::numeric AS primary_hurdle_multiple;

-- Execution delay between "the decision data exists" and "an order is live": funding-export lag,
-- decision-job cadence, and run time. This is INFRASTRUCTURE, not strategy. It changes whenever the
-- schedule is revisited, so it is a swept, reported dimension rather than a constant folded into
-- entry_at - read the row matching whatever schedule is actually deployed. Baking one value in would
-- make this query silently wrong the next time a plist changes, with nothing linking the two.
--
-- At the 2026-08-25 schedule the delay is roughly one hour: observations are sampled at HH:50, the
-- local export writes them at HH:55, and the xvf-narrow-dry-run LaunchAgent evaluates at minute 5 of
-- the following hour.
--
-- venue_funding_observation.created_at is deliberately NOT used as the availability floor, despite
-- looking like the measured answer. The early part of that table was bulk-backfilled: 2026-08-16 has
-- 21,372 rows spread across 3 distinct create-hours and 2026-08-17 has 39,544 across 1, against 23-24
-- for a live day. Across that span created_at records import time, not readability.
CREATE TEMP TABLE narrow_execution_delay (execution_delay interval PRIMARY KEY);
INSERT INTO narrow_execution_delay(execution_delay) VALUES
  (interval '0'),
  (interval '15 minutes'),
  (interval '30 minutes'),
  (interval '1 hour'),
  (interval '1 hour 15 minutes');

CREATE TEMP TABLE known_non_crypto_base (base text PRIMARY KEY);
INSERT INTO known_non_crypto_base(base) VALUES
  ('BNC'), ('BOT'), ('CSOPSAMSUNG2L'), ('CSOPSKHYNIX2L'), ('CXMT'), ('FWDI'),
  ('KODEX200'), ('KO'), ('KUAISHOU'), ('LGELECTRONICS'), ('SAMSUNGEM'), ('SHAZ'), ('SKHYNIX');

-- Match PostgresXvfFundingSnapshotSource for every observation: use the nearest earlier distinct
-- target stamp visible at that observation, not one global median interval for the contract.
CREATE TEMP TABLE narrow_observation AS
SELECT o.venue,
       normalise_perp_base(left(o.venue_symbol, length(o.venue_symbol) - 4)) AS base,
       o.venue_symbol,
       o.observed_hour,
       o.observed_at,
       o.target_stamp,
       o.funding_rate,
       (extract(epoch FROM (o.target_stamp - previous.target_stamp)) / 3600)::int AS interval_hours,
       o.funding_rate /
           ((extract(epoch FROM (o.target_stamp - previous.target_stamp)) / 3600)::int) AS hourly_rate
FROM venue_funding_observation o
JOIN LATERAL (
  SELECT older.target_stamp
  FROM venue_funding_observation older
  WHERE older.venue = o.venue
    AND older.venue_symbol = o.venue_symbol
    AND older.observed_at <= o.observed_at
    AND o.target_stamp IS NOT NULL
    AND older.target_stamp < o.target_stamp
  GROUP BY older.target_stamp
  ORDER BY older.target_stamp DESC
  LIMIT 1
) previous ON true
WHERE o.venue IN ('binance', 'bybit')
  AND o.venue_symbol LIKE '%USDT'
  AND extract(epoch FROM (o.target_stamp - previous.target_stamp)) > 0
  AND mod(extract(epoch FROM (o.target_stamp - previous.target_stamp))::numeric, 3600) = 0;
CREATE INDEX ON narrow_observation (venue, venue_symbol, observed_hour);
CREATE INDEX ON narrow_observation (base, observed_hour, venue);

-- A decision can occur only after an hour exists on both venues. The baseline is date-based in
-- production, interpreted in the application's local timezone.
CREATE TEMP TABLE narrow_decision_day AS
SELECT DISTINCT ((b.observed_hour + interval '1 hour') AT TIME ZONE 'Europe/Chisinau')::date AS as_of
FROM narrow_observation b
JOIN narrow_observation y
  ON y.base = b.base AND y.observed_hour = b.observed_hour AND y.venue = 'bybit'
WHERE b.venue = 'binance';

CREATE TEMP TABLE narrow_analysis_day AS
SELECT as_of FROM narrow_decision_day
UNION
SELECT as_of - 1 FROM narrow_decision_day;

-- Causal version of production's completeness reference. At a historical decision, rows after the
-- decision date did not exist and therefore cannot influence the median weekly payment count.
CREATE TEMP TABLE narrow_typical_payment_count AS
SELECT d.as_of, w.venue, w.venue_symbol,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY w.payments) AS median_weekly_payments
FROM narrow_analysis_day d
JOIN LATERAL (
  SELECT f.venue, f.venue_symbol, date_trunc('week', f.funding_time) AS funding_week,
         count(*) AS payments
  FROM perp_funding_all f, narrow_parameters p
  WHERE f.venue IN ('binance', 'bybit')
    AND f.venue_symbol LIKE '%USDT'
    AND f.funding_time >= d.as_of - p.typical_window_days
    AND f.funding_time <= d.as_of
  GROUP BY 1, 2, 3
) w ON true
GROUP BY 1, 2, 3;
CREATE INDEX ON narrow_typical_payment_count (as_of, venue, venue_symbol);

-- Seven-day settled funding per usable contract for today and yesterday.
CREATE TEMP TABLE narrow_settled_leg AS
SELECT d.as_of, f.venue,
       normalise_perp_base(left(f.venue_symbol, length(f.venue_symbol) - 4)) AS base,
       f.venue_symbol,
       sum(f.funding_rate) AS trailing_rate,
       count(*) AS payments
FROM narrow_analysis_day d
JOIN perp_funding_all f ON f.funding_time > d.as_of - 7 AND f.funding_time <= d.as_of
JOIN narrow_typical_payment_count t
  ON t.as_of = d.as_of AND t.venue = f.venue AND t.venue_symbol = f.venue_symbol
JOIN narrow_parameters p ON true
WHERE f.venue IN ('binance', 'bybit')
  AND f.venue_symbol LIKE '%USDT'
GROUP BY d.as_of, f.venue, f.venue_symbol, t.median_weekly_payments, p.completeness_ratio
HAVING count(*) >= p.completeness_ratio * t.median_weekly_payments;
CREATE INDEX ON narrow_settled_leg (as_of, base, venue);

-- Resolve normalized-base symbol collisions before pending windows are formed. Production chooses
-- the widest legitimate cross-venue symbol pair for the base; deterministic symbol ordering breaks
-- exact ties for reproducibility.
CREATE TEMP TABLE narrow_baseline_option AS
SELECT b.as_of, b.base,
       b.venue_symbol AS binance_symbol,
       y.venue_symbol AS bybit_symbol,
       CASE WHEN b.trailing_rate >= y.trailing_rate THEN 'binance' ELSE 'bybit' END AS short_venue,
       abs(b.trailing_rate - y.trailing_rate) * (365.0 / 7) * 100 AS raw_spread_annual_pct,
       row_number() OVER (
         PARTITION BY b.as_of, b.base
         ORDER BY abs(b.trailing_rate - y.trailing_rate) DESC,
                  b.venue_symbol, y.venue_symbol) AS widest_rank
FROM narrow_settled_leg b
JOIN narrow_settled_leg y ON y.as_of = b.as_of AND y.base = b.base AND y.venue = 'bybit'
WHERE b.venue = 'binance';

CREATE TEMP TABLE narrow_baseline AS
SELECT today.as_of, today.base, today.binance_symbol, today.bybit_symbol, today.short_venue,
       today.raw_spread_annual_pct,
       (yesterday.raw_spread_annual_pct > p.minimum_annual_spread_pct) AS raw_eligible_yesterday,
       CASE WHEN yesterday.raw_spread_annual_pct > p.minimum_annual_spread_pct
            THEN p.stale_discount ELSE 1::numeric END AS freshness_factor,
       today.raw_spread_annual_pct *
         CASE WHEN yesterday.raw_spread_annual_pct > p.minimum_annual_spread_pct
              THEN p.stale_discount ELSE 1::numeric END AS adjusted_spread_annual_pct
FROM narrow_baseline_option today
JOIN narrow_parameters p ON true
LEFT JOIN narrow_baseline_option yesterday
  ON yesterday.as_of = today.as_of - 1
 AND yesterday.base = today.base
 AND yesterday.widest_rank = 1
WHERE today.widest_rank = 1;
CREATE INDEX ON narrow_baseline (as_of, base, binance_symbol, bybit_symbol);

-- Candidate endpoints use the settled-funding direction. Entry occurs at the first complete minute
-- after both observations were actually recorded, rather than at observed_hour.
CREATE TEMP TABLE narrow_endpoint AS
SELECT bl.*,
       b.observed_hour,
       greatest(b.observed_at, y.observed_at) AS latest_observed_at,
       -- Information floor only: the earliest instant at which BOTH venue observations existed.
       -- Never an entry time on its own - narrow_funding_gate adds the swept execution delay.
       date_trunc('minute', greatest(b.observed_at, y.observed_at))
         + interval '1 minute' AS decision_ready_at
FROM narrow_baseline bl
JOIN narrow_parameters p ON true
LEFT JOIN known_non_crypto_base x ON x.base = bl.base
JOIN narrow_observation b
  ON b.venue = 'binance' AND b.venue_symbol = bl.binance_symbol
JOIN narrow_observation y
  ON y.venue = 'bybit' AND y.venue_symbol = bl.bybit_symbol
 AND y.observed_hour = b.observed_hour
WHERE ((b.observed_hour + interval '1 hour') AT TIME ZONE 'Europe/Chisinau')::date = bl.as_of
  AND bl.adjusted_spread_annual_pct > p.minimum_annual_spread_pct
  AND x.base IS NULL;
CREATE INDEX ON narrow_endpoint (base, decision_ready_at);

-- Re-evaluate all four historical points in the CURRENT settled-funding direction. This is the
-- production semantic that the superseded query missed.
CREATE TEMP TABLE narrow_forecast AS
SELECT e.as_of, e.base, e.binance_symbol, e.bybit_symbol, e.short_venue,
       e.raw_spread_annual_pct, e.raw_eligible_yesterday, e.freshness_factor,
       e.adjusted_spread_annual_pct, e.observed_hour, e.decision_ready_at,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY
         CASE WHEN e.short_venue = 'binance'
              THEN (b.hourly_rate - y.hourly_rate) * 24 * 10000
              ELSE (y.hourly_rate - b.hourly_rate) * 24 * 10000 END) AS forecast_bps,
       min(CASE WHEN e.short_venue = 'binance'
                THEN (b.hourly_rate - y.hourly_rate) * 24 * 10000
                ELSE (y.hourly_rate - b.hourly_rate) * 24 * 10000 END) AS minimum_point_bps,
       count(DISTINCT b.observed_hour) AS paired_hours
FROM narrow_endpoint e
JOIN narrow_observation b
  ON b.venue = 'binance' AND b.venue_symbol = e.binance_symbol
 AND b.observed_hour BETWEEN e.observed_hour - interval '3 hours' AND e.observed_hour
JOIN narrow_observation y
  ON y.venue = 'bybit' AND y.venue_symbol = e.bybit_symbol
 AND y.observed_hour = b.observed_hour
GROUP BY e.as_of, e.base, e.binance_symbol, e.bybit_symbol, e.short_venue,
         e.raw_spread_annual_pct, e.raw_eligible_yesterday, e.freshness_factor,
         e.adjusted_spread_annual_pct, e.observed_hour, e.decision_ready_at
HAVING count(DISTINCT b.observed_hour) = 4
   AND max(b.observed_hour) - min(b.observed_hour) = interval '3 hours'
   AND min(CASE WHEN e.short_venue = 'binance'
                THEN (b.hourly_rate - y.hourly_rate) * 24 * 10000
                ELSE (y.hourly_rate - b.hourly_rate) * 24 * 10000 END) > 0;
CREATE INDEX ON narrow_forecast (base, decision_ready_at);

-- Funding-only primary gate. Confirmed non-crypto bases are removed. Historical API symbolType was
-- not persisted, so production's current instrument validation remains authoritative.
-- The delay sweep enters HERE rather than at narrow_endpoint: the forecast and both gates depend only
-- on observed_hour, so multiplying them by the delay set would be pure repeated work. Only entry_at
-- and everything downstream of it (prices, ledger occupancy, realized funding) actually vary.
CREATE TEMP TABLE narrow_funding_gate AS
SELECT f.*,
       d.execution_delay,
       f.decision_ready_at + d.execution_delay AS entry_at
FROM narrow_forecast f
CROSS JOIN narrow_execution_delay d
JOIN narrow_parameters p ON true
LEFT JOIN known_non_crypto_base x ON x.base = f.base
WHERE f.adjusted_spread_annual_pct > p.minimum_annual_spread_pct
  AND f.forecast_bps > p.primary_hurdle_multiple * p.round_trip_fee_bps
  AND x.base IS NULL;
CREATE INDEX ON narrow_funding_gate (execution_delay, base, entry_at);

-- Optional historical proxy for the gates that require a live ticker and executable L1. Bybit is
-- maker in production; mid/last 1-minute opens cannot reproduce maker ask/bid versus taker bid/ask,
-- so entry_basis_proxy_bps is diagnostic only.
CREATE TEMP TABLE narrow_market_proxy AS
SELECT f.*,
       bp.price AS binance_price,
       yp.price AS bybit_price,
       CASE WHEN f.short_venue = 'binance'
            THEN ln(bp.price / yp.price) * 10000
            ELSE ln(yp.price / bp.price) * 10000 END AS entry_basis_proxy_bps,
       least(bv.quote_volume, yv.quote_volume) * 7 AS thin_weekly_volume_proxy
FROM narrow_funding_gate f
LEFT JOIN LATERAL (
  SELECT k.open_price AS price
  FROM binance_perp_kline k
  WHERE k.symbol = f.binance_symbol AND k.interval = '1m'
    AND k.open_time >= f.entry_at AND k.open_time < f.entry_at + interval '5 minutes'
  ORDER BY k.open_time LIMIT 1
) bp ON true
LEFT JOIN LATERAL (
  SELECT k.open_price AS price
  FROM bybit_perp_kline k
  WHERE k.venue_symbol = f.bybit_symbol AND k.interval = '1m'
    AND k.open_time >= f.entry_at AND k.open_time < f.entry_at + interval '5 minutes'
  ORDER BY k.open_time LIMIT 1
) yp ON true
LEFT JOIN LATERAL (
  SELECT sum(k.quote_asset_volume) AS quote_volume
  FROM binance_perp_kline k
  WHERE k.symbol = f.binance_symbol AND k.interval = '1m'
    AND k.open_time > f.entry_at - interval '24 hours' AND k.open_time <= f.entry_at
) bv ON true
LEFT JOIN LATERAL (
  SELECT sum(k.base_volume * k.close_price) AS quote_volume
  FROM bybit_perp_kline k
  WHERE k.venue_symbol = f.bybit_symbol AND k.interval = '1m'
    AND k.open_time > f.entry_at - interval '24 hours' AND k.open_time <= f.entry_at
) yv ON true;

CREATE TEMP TABLE narrow_gate_candidate AS
SELECT 'funding_only'::text AS policy, f.*
FROM narrow_funding_gate f
UNION ALL
SELECT 'basis_volume_1m_proxy'::text AS policy,
       m.as_of, m.base, m.binance_symbol, m.bybit_symbol, m.short_venue,
       m.raw_spread_annual_pct, m.raw_eligible_yesterday, m.freshness_factor,
       m.adjusted_spread_annual_pct, m.observed_hour, m.decision_ready_at,
       m.forecast_bps, m.minimum_point_bps, m.paired_hours,
       m.execution_delay, m.entry_at
FROM narrow_market_proxy m
JOIN narrow_parameters p ON true
WHERE m.entry_basis_proxy_bps >= p.round_trip_fee_bps
  AND m.thin_weekly_volume_proxy >= p.minimum_weekly_quote_volume;
CREATE INDEX ON narrow_gate_candidate (policy, execution_delay, base, entry_at);

-- Greedy causal ledger per tested horizon: the first eligible observation opens the base, and no
-- later observation for that base is accepted until that horizon has completed.
CREATE TEMP TABLE narrow_ledger AS
WITH RECURSIVE accepted AS (
  SELECT first_candidate.*
  FROM (
    SELECT DISTINCT ON (policy, execution_delay, base, h.horizon_hours) c.*, h.horizon_hours
    FROM narrow_gate_candidate c
    CROSS JOIN (VALUES (24), (48), (72), (96), (120)) h(horizon_hours)
    ORDER BY policy, execution_delay, base, h.horizon_hours, entry_at, forecast_bps DESC
  ) first_candidate

  UNION ALL

  SELECT next_candidate.*
  FROM accepted prior
  JOIN LATERAL (
    SELECT c.*, prior.horizon_hours
    FROM narrow_gate_candidate c
    WHERE c.policy = prior.policy AND c.base = prior.base
      AND c.execution_delay = prior.execution_delay
      AND c.entry_at >= prior.entry_at + make_interval(hours => prior.horizon_hours)
    ORDER BY c.entry_at, c.forecast_bps DESC
    LIMIT 1
  ) next_candidate ON true
)
SELECT * FROM accepted;
CREATE INDEX ON narrow_ledger (policy, execution_delay, horizon_hours, entry_at, base);

-- Reproducible horizon outcomes. One complete settled-funding window is required on both venues.
CREATE TEMP TABLE narrow_outcome AS
SELECT l.*,
       (coalesce(short_funding.rate, 0) - coalesce(long_funding.rate, 0)) * 10000 AS realized_bps,
       coalesce(short_funding.n, 0) + coalesce(long_funding.n, 0) AS settlements
FROM narrow_ledger l
JOIN LATERAL (
  SELECT sum(f.funding_rate) AS rate, count(*) AS n
  FROM perp_funding_all f
  WHERE f.venue = l.short_venue
    AND f.venue_symbol = CASE WHEN l.short_venue = 'binance'
                              THEN l.binance_symbol ELSE l.bybit_symbol END
    AND f.funding_time > l.entry_at
    AND f.funding_time <= l.entry_at + make_interval(hours => l.horizon_hours)
) short_funding ON short_funding.n > 0
JOIN LATERAL (
  SELECT sum(f.funding_rate) AS rate, count(*) AS n
  FROM perp_funding_all f
  WHERE f.venue = CASE WHEN l.short_venue = 'binance' THEN 'bybit' ELSE 'binance' END
    AND f.venue_symbol = CASE WHEN l.short_venue = 'binance'
                              THEN l.bybit_symbol ELSE l.binance_symbol END
    AND f.funding_time > l.entry_at
    AND f.funding_time <= l.entry_at + make_interval(hours => l.horizon_hours)
) long_funding ON long_funding.n > 0
WHERE l.entry_at + make_interval(hours => l.horizon_hours) <=
      (SELECT least(max(funding_time) FILTER (WHERE venue = 'binance'),
                    max(funding_time) FILTER (WHERE venue = 'bybit'))
       FROM perp_funding_all WHERE venue IN ('binance', 'bybit'));
CREATE INDEX ON narrow_outcome (policy, execution_delay, horizon_hours, base);

\echo ''
\echo '=== coverage and causal population ==='
SELECT (SELECT min(observed_at) FROM narrow_observation) AS first_observation,
       (SELECT max(observed_at) FROM narrow_observation) AS last_observation,
       (SELECT count(*) FROM narrow_forecast) AS persistent_fixed_direction_rows,
       (SELECT count(*) FROM narrow_funding_gate
        WHERE execution_delay = interval '1 hour') AS overlapping_funding_gate_rows,
       (SELECT count(*) FROM narrow_ledger WHERE policy = 'funding_only'
          AND horizon_hours = 24 AND execution_delay = interval '1 hour') AS causal_funding_entries,
       (SELECT count(*) FROM narrow_ledger WHERE policy = 'basis_volume_1m_proxy'
          AND horizon_hours = 24 AND execution_delay = interval '1 hour') AS causal_proxy_entries;

\echo ''
\echo '=== corrected forecast distribution after production direction and baseline spread ==='
WITH bucketed AS (
  SELECT CASE
           WHEN forecast_bps < 10 THEN 'a. <10bp'
           WHEN forecast_bps < 22.6 THEN 'b. 10-22.6bp'
           WHEN forecast_bps < 45.2 THEN 'c. 22.6-45.2bp'
           WHEN forecast_bps < 90 THEN 'd. 45.2-90bp'
           WHEN forecast_bps < 200 THEN 'e. 90-200bp'
           ELSE 'f. >200bp' END AS bucket,
         forecast_bps
  FROM narrow_forecast f, narrow_parameters p
  WHERE adjusted_spread_annual_pct > p.minimum_annual_spread_pct
)
SELECT bucket, count(*) AS overlapping_rows,
       round(avg(forecast_bps)::numeric, 1) AS average_forecast_bps
FROM bucketed GROUP BY bucket ORDER BY bucket;

\echo ''
\echo '=== causal non-overlapping 24-hour result, by execution delay ==='
\echo '    (schedule-independent: read the row matching the deployed cadence)'
SELECT policy, execution_delay, count(*) AS entries, count(DISTINCT base) AS bases,
       round(avg(forecast_bps)::numeric, 1) AS average_forecast_bps,
       round(avg(realized_bps)::numeric, 1) AS average_realized_bps,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY realized_bps)::numeric, 1)
         AS median_realized_bps,
       round((avg(realized_bps) - 22.6)::numeric, 1) AS average_net_after_fees_bps,
       round((100.0 * count(*) FILTER (WHERE realized_bps > 22.6) / count(*))::numeric, 1)
         AS pct_beating_fees
FROM narrow_outcome
WHERE horizon_hours = 24
GROUP BY policy, execution_delay ORDER BY policy, execution_delay;

\echo ''
\echo '=== reproducible hold-horizon sweep (at the currently deployed ~1h delay) ==='
SELECT policy, horizon_hours, count(*) AS entries, count(DISTINCT base) AS bases,
       round(avg(realized_bps)::numeric, 1) AS average_realized_bps,
       round((avg(realized_bps) - 22.6)::numeric, 1) AS average_net_after_fees_bps,
       round(((avg(realized_bps) - 22.6) / (horizon_hours / 24.0))::numeric, 1)
         AS net_per_capital_day_bps,
       round((100.0 * count(*) FILTER (WHERE realized_bps > 22.6) / count(*))::numeric, 1)
         AS pct_beating_fees
FROM narrow_outcome
WHERE execution_delay = interval '1 hour'
GROUP BY policy, horizon_hours ORDER BY policy, horizon_hours;

\echo ''
\echo '=== 24-hour symbol contribution (at the currently deployed ~1h delay) ==='
SELECT policy, base, count(*) AS entries,
       round(avg(realized_bps)::numeric, 1) AS average_realized_bps,
       round(sum(realized_bps - 22.6)::numeric, 1) AS total_net_bps
FROM narrow_outcome
WHERE horizon_hours = 24 AND execution_delay = interval '1 hour'
GROUP BY policy, base
ORDER BY policy, total_net_bps DESC;

\echo ''
\echo '=== leave-best-base-out robustness ==='
WITH contribution AS (
  SELECT policy, base, sum(realized_bps - 22.6) AS total_net_bps
  FROM narrow_outcome
  WHERE horizon_hours = 24 AND execution_delay = interval '1 hour'
  GROUP BY policy, base
), ranked AS (
  SELECT *, row_number() OVER (PARTITION BY policy ORDER BY total_net_bps DESC, base) AS contribution_rank
  FROM contribution
), best AS (
  SELECT policy, base AS removed_base FROM ranked WHERE contribution_rank = 1
)
SELECT o.policy, b.removed_base,
       count(*) FILTER (WHERE o.base <> b.removed_base) AS remaining_entries,
       count(DISTINCT o.base) FILTER (WHERE o.base <> b.removed_base) AS remaining_bases,
       round(avg(o.realized_bps - 22.6) FILTER (WHERE o.base <> b.removed_base)::numeric, 1)
         AS remaining_average_net_bps,
       round(sum(o.realized_bps - 22.6) FILTER (WHERE o.base <> b.removed_base)::numeric, 1)
         AS remaining_total_net_bps
FROM narrow_outcome o
JOIN best b USING (policy)
WHERE o.horizon_hours = 24 AND o.execution_delay = interval '1 hour'
GROUP BY o.policy, b.removed_base
ORDER BY o.policy;

\echo ''
\echo 'Interpretation: funding_only is auditable from persisted funding facts. basis_volume_1m_proxy'
\echo 'is diagnostic because candles are not executable bid/ask and historical symbolType is absent.'
