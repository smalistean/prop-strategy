-- Perp-listing event study (I55) — pre-registered in PERP_LISTING_EVENT_STUDY.md
-- (definitions frozen 2026-08-30 12:02 UTC before execution).
-- Run: psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-perp-listing-event-study.sql

\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';

CREATE TEMP TABLE ev AS
WITH firsts AS (
  SELECT symbol, min(open_time) AS t0
  FROM binance_perp_kline WHERE "interval"='1d'
  GROUP BY symbol
  HAVING min(open_time) > TIMESTAMPTZ '2020-01-01 00:00+00'
),
btc AS (
  SELECT open_time, close_price FROM binance_perp_kline
  WHERE symbol='BTCUSDT' AND "interval"='1d'
)
SELECT f.symbol, f.t0,
  extract(year FROM f.t0)::int AS lyear,
  date_trunc('month', f.t0) AS lmonth,
  (k0.close_price / k0.open_price - 1) * 100 AS day1_oc_pct,
  (k1.close_price / k0.close_price - 1) * 100
    - (b1.close_price / b0.close_price - 1) * 100 AS ex1_pct,
  (k7.close_price / k0.close_price - 1) * 100
    - (b7.close_price / b0.close_price - 1) * 100 AS ex7_pct,
  (k30.close_price / k0.close_price - 1) * 100
    - (b30.close_price / b0.close_price - 1) * 100 AS ex30_pct
FROM firsts f
JOIN binance_perp_kline k0 ON k0.symbol=f.symbol AND k0."interval"='1d' AND k0.open_time=f.t0
JOIN btc b0 ON b0.open_time=f.t0
LEFT JOIN binance_perp_kline k1  ON k1.symbol=f.symbol  AND k1."interval"='1d'  AND k1.open_time=f.t0 + INTERVAL '1 day'
LEFT JOIN btc b1  ON b1.open_time=f.t0 + INTERVAL '1 day'
LEFT JOIN binance_perp_kline k7  ON k7.symbol=f.symbol  AND k7."interval"='1d'  AND k7.open_time=f.t0 + INTERVAL '7 days'
LEFT JOIN btc b7  ON b7.open_time=f.t0 + INTERVAL '7 days'
LEFT JOIN binance_perp_kline k30 ON k30.symbol=f.symbol AND k30."interval"='1d' AND k30.open_time=f.t0 + INTERVAL '30 days'
LEFT JOIN btc b30 ON b30.open_time=f.t0 + INTERVAL '30 days';

\echo '=== Cohort sizes and horizon dropouts ==='
SELECT lyear, count(*) AS listings,
  count(ex1_pct) AS have_1d, count(ex7_pct) AS have_7d, count(ex30_pct) AS have_30d
FROM ev GROUP BY lyear ORDER BY lyear;

\echo '=== Descriptive: per-listing excess returns by era (pct) ==='
SELECT lyear,
  round(avg(day1_oc_pct)::numeric,1) AS day1_oc_mean,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ex1_pct)::numeric,1)  AS med_ex1,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ex7_pct)::numeric,1)  AS med_ex7,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY ex30_pct)::numeric,1) AS med_ex30,
  round(100.0*count(*) FILTER (WHERE ex7_pct  < 0)/nullif(count(ex7_pct),0),0)  AS pct_neg_7d,
  round(100.0*count(*) FILTER (WHERE ex30_pct < 0)/nullif(count(ex30_pct),0),0) AS pct_neg_30d
FROM ev GROUP BY lyear ORDER BY lyear;

\echo '=== PRIMARY: de-clustered listing-month means of excess returns, stats by era ==='
WITH m AS (
  SELECT lyear, lmonth, avg(ex7_pct) AS m7, avg(ex30_pct) AS m30
  FROM ev GROUP BY lyear, lmonth
)
SELECT lyear, count(*) AS n_months,
  round(avg(m7)::numeric,1) AS mean_ex7,
  round((avg(m7)/nullif(stddev(m7)/sqrt(count(m7)),0))::numeric,2) AS t_ex7,
  round(avg(m30)::numeric,1) AS mean_ex30,
  round((avg(m30)/nullif(stddev(m30)/sqrt(count(m30)),0))::numeric,2) AS t_ex30
FROM m GROUP BY lyear ORDER BY lyear;

\echo '=== Tail check: extreme 30d excess (top/bottom 5) ==='
(SELECT 'worst' AS side, symbol, t0::date, round(ex30_pct::numeric,0) AS ex30
 FROM ev WHERE ex30_pct IS NOT NULL ORDER BY ex30_pct ASC LIMIT 5)
UNION ALL
(SELECT 'best', symbol, t0::date, round(ex30_pct::numeric,0)
 FROM ev WHERE ex30_pct IS NOT NULL ORDER BY ex30_pct DESC LIMIT 5);
