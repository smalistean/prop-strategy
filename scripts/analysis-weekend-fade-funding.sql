-- Weekend fade with funding P&L — pre-registered in WEEKEND_FADE_FUNDING_PREREGISTRATION.md
-- (definitions frozen 2026-08-30 before execution; do not edit thresholds/timestamps here
--  without a new pre-registration).
-- Run: psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-weekend-fade-funding.sql

\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';

CREATE TEMP TABLE syms(symbol text, grp text);
INSERT INTO syms VALUES
 ('SPYUSDT','eq'),('QQQUSDT','eq'),('EWJUSDT','eq'),('EWYUSDT','eq'),
 ('COINUSDT','eq'),('TSLAUSDT','eq'),('MSTRUSDT','eq'),('PLTRUSDT','eq'),
 ('HOODUSDT','eq'),('AAPLUSDT','eq'),('AMZNUSDT','eq'),('METAUSDT','eq'),
 ('INTCUSDT','eq'),('MUUSDT','eq'),('CRCLUSDT','eq'),('NVDAUSDT','eq'),
 ('LLYUSDT','eq'),('JPMUSDT','eq'),('QCOMUSDT','eq'),('TSMUSDT','eq'),
 ('PAYPUSDT','eq'),('SNDKUSDT','eq'),('AAOIUSDT','eq'),('AXTIUSDT','eq'),
 ('NOKUSDT','eq'),('OPENAIUSDT','eq'),('SPCXUSDT','eq'),
 ('XAUUSDT','met'),('XAGUSDT','met'),('XPTUSDT','met'),('XPDUSDT','met'),
 ('COPPERUSDT','met'),('CLUSDT','met'),('BZUSDT','met'),('NATGASUSDT','met');

CREATE TEMP TABLE legs AS
WITH fridays AS (
  SELECT g.d::date AS fri
  FROM generate_series(DATE '2025-12-12', DATE '2026-08-21', INTERVAL '7 days') g(d)
  WHERE g.d::date NOT IN (DATE '2026-01-16', DATE '2026-02-13', DATE '2026-04-03',
                          DATE '2026-05-22', DATE '2026-07-03')
),
spec AS (
  SELECT s.symbol, s.grp, f.fri,
    -- Friday reference bar open hour (bar closes at the underlying close)
    CASE WHEN s.grp='eq'  THEN CASE WHEN f.fri > DATE '2026-03-08' THEN 19 ELSE 20 END
         ELSE                   CASE WHEN f.fri > DATE '2026-03-08' THEN 20 ELSE 21 END
    END AS fri_h,
    -- exit bar open hour and day offset from Friday
    CASE WHEN s.grp='eq'  THEN CASE WHEN f.fri + 3 >= DATE '2026-03-09' THEN 14 ELSE 15 END
         ELSE                   CASE WHEN f.fri + 2 >= DATE '2026-03-08' THEN 23 ELSE 24 END
    END AS exit_h_raw,
    CASE WHEN s.grp='eq' THEN 3 ELSE 2 END AS exit_day_off_base
  FROM syms s CROSS JOIN fridays f
),
spec2 AS (
  SELECT symbol, grp, fri, fri_h,
    CASE WHEN exit_h_raw = 24 THEN 0 ELSE exit_h_raw END AS exit_h,
    CASE WHEN exit_h_raw = 24 THEN exit_day_off_base + 1 ELSE exit_day_off_base END AS exit_off
  FROM spec
)
SELECT sp.symbol, sp.grp, sp.fri,
  bf.close_price AS p_fri,
  be.close_price AS p_entry,
  bx.close_price AS p_exit,
  ((sp.fri + 2)::timestamp + INTERVAL '20 hours') AT TIME ZONE 'UTC' AS entry_ts,
  ((sp.fri + sp.exit_off)::timestamp + (sp.exit_h + 1) * INTERVAL '1 hour') AT TIME ZONE 'UTC' AS exit_ts,
  (be.close_price / bf.close_price - 1) * 10000 AS wknd_bp,
  (bx.close_price / be.close_price - 1) * 10000 AS price_bp
FROM spec2 sp
JOIN binance_perp_kline bf ON bf.symbol = sp.symbol AND bf."interval" = '1h'
  AND bf.open_time = (sp.fri::timestamp + sp.fri_h * INTERVAL '1 hour') AT TIME ZONE 'UTC'
JOIN binance_perp_kline be ON be.symbol = sp.symbol AND be."interval" = '1h'
  AND be.open_time = ((sp.fri + 2)::timestamp + INTERVAL '19 hours') AT TIME ZONE 'UTC'
JOIN binance_perp_kline bx ON bx.symbol = sp.symbol AND bx."interval" = '1h'
  AND bx.open_time = ((sp.fri + sp.exit_off)::timestamp + sp.exit_h * INTERVAL '1 hour') AT TIME ZONE 'UTC';

CREATE TEMP TABLE ledger AS
SELECT l.*,
  COALESCE(fx.fund_sum, 0) * -10000 AS funding_bp,   -- long pays positive funding
  l.price_bp + COALESCE(fx.fund_sum, 0) * -10000 - 9 AS net_bp
FROM legs l
LEFT JOIN LATERAL (
  -- binance_perp_funding_rate stores each print 1-3x (identical rates); dedupe before summing
  SELECT SUM(d.funding_rate) AS fund_sum
  FROM (SELECT DISTINCT f.funding_time, f.funding_rate
        FROM binance_perp_funding_rate f
        WHERE f.symbol = l.symbol AND f.funding_time > l.entry_ts AND f.funding_time <= l.exit_ts) d
) fx ON true;

\echo '=== Triggered events (weekend move <= -50bp), equities/ETFs ==='
SELECT fri, symbol, round(wknd_bp,1) AS wknd_bp, round(price_bp,1) AS price_bp,
       round(funding_bp,2) AS funding_bp, round(net_bp,1) AS net_bp
FROM ledger WHERE grp='eq' AND wknd_bp <= -50 ORDER BY fri, symbol;

\echo '=== De-clustered weekend summary, PRIMARY (equities, trigger <= -50bp) ==='
WITH w AS (
  SELECT fri, avg(price_bp) p, avg(funding_bp) f, avg(net_bp) n, count(*) k
  FROM ledger WHERE grp='eq' AND wknd_bp <= -50 GROUP BY fri)
SELECT count(*) AS n_weekends, sum(k) AS n_events,
  round(avg(n),1) AS mean_net_bp,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY n)::numeric,1) AS median_net_bp,
  round(stddev(n),1) AS sd_bp,
  round((avg(n)/nullif(stddev(n)/sqrt(count(*)),0))::numeric,2) AS t_stat,
  round(min(n),1) AS worst_bp,
  round(avg(p),1) AS mean_price_bp,
  round(avg(f),2) AS mean_funding_bp
FROM w;

\echo '=== De-clustered weekend summary, EXPLORATORY (metals/energy, trigger <= -50bp) ==='
WITH w AS (
  SELECT fri, avg(price_bp) p, avg(funding_bp) f, avg(net_bp) n, count(*) k
  FROM ledger WHERE grp='met' AND wknd_bp <= -50 GROUP BY fri)
SELECT count(*) AS n_weekends, sum(k) AS n_events,
  round(avg(n),1) AS mean_net_bp,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY n)::numeric,1) AS median_net_bp,
  round(stddev(n),1) AS sd_bp,
  round((avg(n)/nullif(stddev(n)/sqrt(count(*)),0))::numeric,2) AS t_stat,
  round(min(n),1) AS worst_bp,
  round(avg(p),1) AS mean_price_bp,
  round(avg(f),2) AS mean_funding_bp
FROM w;

\echo '=== CONTROL: all weekends, no trigger (equities) ==='
WITH w AS (
  SELECT fri, avg(price_bp) p, avg(funding_bp) f, avg(net_bp) n, count(*) k
  FROM ledger WHERE grp='eq' GROUP BY fri)
SELECT count(*) AS n_weekends, sum(k) AS n_events,
  round(avg(n),1) AS mean_net_bp,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY n)::numeric,1) AS median_net_bp,
  round(stddev(n),1) AS sd_bp,
  round((avg(n)/nullif(stddev(n)/sqrt(count(*)),0))::numeric,2) AS t_stat,
  round(avg(p),1) AS mean_price_bp,
  round(avg(f),2) AS mean_funding_bp
FROM w;

\echo '=== Funding component distribution on triggered equity events ==='
SELECT round(min(funding_bp),2) AS min_f, round(percentile_cont(0.25) WITHIN GROUP (ORDER BY funding_bp)::numeric,2) AS p25,
  round(percentile_cont(0.5) WITHIN GROUP (ORDER BY funding_bp)::numeric,2) AS median_f,
  round(percentile_cont(0.75) WITHIN GROUP (ORDER BY funding_bp)::numeric,2) AS p75,
  round(max(funding_bp),2) AS max_f
FROM ledger WHERE grp='eq' AND wknd_bp <= -50;
