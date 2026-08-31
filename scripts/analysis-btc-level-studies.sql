-- BTC level studies (I66 round-number fade + placebo, I71 ATH-break)
-- Pre-registered in BTC_LEVEL_STUDIES.md (frozen 2026-08-30 12:31 UTC before execution).
-- Run: psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-btc-level-studies.sql

\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';

CREATE TEMP TABLE btc1h AS
SELECT open_time, high_price AS hi, low_price AS lo, close_price AS cl
FROM binance_perp_kline WHERE symbol='BTCUSDT' AND "interval"='1h';
CREATE INDEX ON btc1h(open_time);

CREATE TEMP TABLE levels AS
SELECT (10000*k)::numeric AS lvl, 'round' AS lset FROM generate_series(1,12) k
UNION ALL
SELECT (10000*k + 5000)::numeric, 'placebo' FROM generate_series(0,12) k;

CREATE TEMP TABLE feat AS
SELECT l.lvl, l.lset, b.open_time, b.hi, b.lo, b.cl,
  max(b.hi) OVER w72e  AS prior72_hi,  -- placeholder to keep window list tidy
  max(b.hi) OVER w168  AS prior168_hi,
  min(b.lo) OVER w168  AS prior168_lo,
  min(b.lo) OVER w72   AS prior72_lo,
  max(b.hi) OVER w72   AS prior72_hi2,
  lead(b.cl, 1)  OVER wf AS cl_1,
  lead(b.cl, 24) OVER wf AS cl_24,
  lead(b.cl, 72) OVER wf AS cl_72
FROM btc1h b CROSS JOIN levels l
WINDOW
  w168 AS (PARTITION BY l.lvl ORDER BY b.open_time RANGE BETWEEN INTERVAL '168 hours' PRECEDING AND INTERVAL '1 hour' PRECEDING),
  w72  AS (PARTITION BY l.lvl ORDER BY b.open_time RANGE BETWEEN INTERVAL '72 hours'  PRECEDING AND INTERVAL '1 hour' PRECEDING),
  w72e AS (PARTITION BY l.lvl ORDER BY b.open_time RANGE BETWEEN INTERVAL '72 hours'  PRECEDING AND INTERVAL '1 hour' PRECEDING),
  wf   AS (PARTITION BY l.lvl ORDER BY b.open_time);

CREATE TEMP TABLE ev66 AS
SELECT lvl, lset, open_time, cl,
  'resistance' AS side,
  (cl_1/cl-1)*100 AS f1, (cl_24/cl-1)*100 AS f24, (cl_72/cl-1)*100 AS f72
FROM feat
WHERE hi >= lvl AND COALESCE(prior168_hi < lvl, false) AND prior72_lo <= lvl*0.97
UNION ALL
SELECT lvl, lset, open_time, cl,
  'support',
  (cl_1/cl-1)*100, (cl_24/cl-1)*100, (cl_72/cl-1)*100
FROM feat
WHERE lo <= lvl AND COALESCE(prior168_lo > lvl, false) AND prior72_hi2 >= lvl*1.03;

\echo '=== I66 event counts by set/side/year ==='
SELECT lset, side, extract(year FROM open_time)::int AS yr, count(*)
FROM ev66 GROUP BY 1,2,3 ORDER BY 1,2,3;

\echo '=== I66 PRIMARY: forward returns (pct) by set and side ==='
SELECT lset, side, count(*) AS n,
  round(avg(f1)::numeric,2)  AS mean_f1,  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY f1)::numeric,2)  AS med_f1,
  round(avg(f24)::numeric,2) AS mean_f24, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY f24)::numeric,2) AS med_f24,
  round((avg(f24)/nullif(stddev(f24)/sqrt(count(f24)),0))::numeric,2) AS t_f24,
  round(avg(f72)::numeric,2) AS mean_f72, round(percentile_cont(0.5) WITHIN GROUP (ORDER BY f72)::numeric,2) AS med_f72,
  round((avg(f72)/nullif(stddev(f72)/sqrt(count(f72)),0))::numeric,2) AS t_f72
FROM ev66 GROUP BY 1,2 ORDER BY 2,1;

\echo '=== I66 round-minus-placebo difference test (Welch t), by side/horizon ==='
WITH s AS (
  SELECT side, lset, count(*) n, avg(f24) m24, stddev(f24) s24, avg(f72) m72, stddev(f72) s72
  FROM ev66 GROUP BY side, lset)
SELECT r.side,
  round((r.m24 - p.m24)::numeric,2) AS diff_f24,
  round(((r.m24-p.m24)/sqrt(r.s24^2/r.n + p.s24^2/p.n))::numeric,2) AS t_f24,
  round((r.m72 - p.m72)::numeric,2) AS diff_f72,
  round(((r.m72-p.m72)/sqrt(r.s72^2/r.n + p.s72^2/p.n))::numeric,2) AS t_f72
FROM s r JOIN s p ON p.side=r.side AND p.lset='placebo'
WHERE r.lset='round';

\echo '=== I66 era consistency: round-set mean f24 by year/side (placebo in brackets) ==='
SELECT extract(year FROM open_time)::int AS yr, side,
  round(avg(f24) FILTER (WHERE lset='round')::numeric,2)   AS round_f24,
  count(*) FILTER (WHERE lset='round')                     AS n_round,
  round(avg(f24) FILTER (WHERE lset='placebo')::numeric,2) AS placebo_f24,
  count(*) FILTER (WHERE lset='placebo')                   AS n_placebo
FROM ev66 GROUP BY 1,2 ORDER BY 2,1;

\echo '=== I66 cross-level simultaneity (events within 24h of an earlier event, same side) ==='
SELECT side, count(*) AS clustered_events FROM (
  SELECT side, open_time, lag(open_time) OVER (PARTITION BY side ORDER BY open_time) AS prev
  FROM ev66) x
WHERE prev IS NOT NULL AND open_time - prev < INTERVAL '24 hours'
GROUP BY side;

\echo '=== I71 ATH-break events (every row) ==='
WITH d AS (
  SELECT open_time::date AS d, close_price AS cl,
    max(close_price) OVER (ORDER BY open_time ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_ath,
    lead(close_price, 7)  OVER (ORDER BY open_time) AS cl7,
    lead(close_price, 30) OVER (ORDER BY open_time) AS cl30,
    lead(close_price, 90) OVER (ORDER BY open_time) AS cl90
  FROM binance_perp_kline WHERE symbol='BTCUSDT' AND "interval"='1d'),
brk AS (SELECT * FROM d WHERE prior_ath IS NOT NULL AND cl > prior_ath),
decl AS (
  SELECT *, lag(d) OVER (ORDER BY d) AS prev_d FROM brk)
SELECT d AS event_date, round(cl::numeric,0) AS close,
  round(((cl7/cl-1)*100)::numeric,1) AS f7_pct,
  round(((cl30/cl-1)*100)::numeric,1) AS f30_pct,
  round(((cl90/cl-1)*100)::numeric,1) AS f90_pct
FROM decl WHERE prev_d IS NULL OR d - prev_d > 30
ORDER BY d;

\echo '=== I71 baseline: unconditional BTC mean returns over same horizons ==='
WITH d AS (
  SELECT close_price AS cl,
    lead(close_price, 7)  OVER (ORDER BY open_time) AS cl7,
    lead(close_price, 30) OVER (ORDER BY open_time) AS cl30,
    lead(close_price, 90) OVER (ORDER BY open_time) AS cl90
  FROM binance_perp_kline WHERE symbol='BTCUSDT' AND "interval"='1d')
SELECT round(avg((cl7/cl-1)*100)::numeric,1) AS mean_f7,
  round(avg((cl30/cl-1)*100)::numeric,1) AS mean_f30,
  round(avg((cl90/cl-1)*100)::numeric,1) AS mean_f90
FROM d;
