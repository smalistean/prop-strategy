-- Measures whether CEX-CEX candidates are better selected by a 3-day trailing signal or the
-- shared 7-day one, and the mirror check for CEX-DEX. Motivated XvfConfig.LOOKBACK_DAYS_CEX_CEX in
-- src/main/java/com/smalistean/propstrategy/xvf/XvfConfig.java - see below for why it no longer
-- does.
--
-- CORRECTED 2026-08-21 after an independent review (XVF_CALCULATIONS_INDEPENDENT_REVIEW.md) found
-- two real bugs in the original version of this query, both now fixed here:
--   1. Pair type was assigned from every venue with data for a base that day
--      (`'hyperliquid' = ANY(array_agg(venue))`), not from the two venues actually selected as the
--      pair. A base with three venues of data could have its Binance-Bybit pair mislabeled CEX-DEX
--      merely because Hyperliquid also had a quote. Fixed by classifying pair_type from the actual
--      matched (a, b) pair in a DISTINCT ON join, the same shape XvfSignalEngine.bestCrossVenuePair
--      uses.
--   2. The "forward" realized window shared its first day with the trailing signal window (both
--      queried funding_time as of / from day D). A large print on day D could both qualify a
--      candidate for the signal AND get recounted as part of what it realized going forward. Fixed
--      by starting the realized window at D+1.
--
-- Original (buggy) result: 61.3% realized for a 3-day-lookback CEX-CEX selection against 50.4% for
-- 7-day (n=2573 vs 2546) - this is what LOOKBACK_DAYS_CEX_CEX=3 shipped on.
--
-- Corrected result, this run:
--   3d-lookback signal | CEX-CEX | 18.5% | n=9974
--   7d-lookback signal | CEX-CEX | 19.5% | n=8740
--   3d-lookback signal | CEX-DEX | 23.5% | n=8431
--   7d-lookback signal | CEX-DEX | 22.9% | n=7547
-- The finding reverses: 7-day is marginally BETTER for CEX-CEX once both bugs are fixed, not worse.
-- No evidence supports a shorter lookback for CEX-CEX; XvfConfig.LOOKBACK_DAYS_CEX_CEX was set back
-- to equal LOOKBACK_DAYS (7) on this result.
--
-- Runtime: this version's correlated subqueries ran ~34 min and ~8 min respectively in the run that
-- produced the numbers above (a much larger row count than the original buggy version, ~9,900 and
-- ~8,700 rows vs ~2,500 each) - budget significant time, or narrow the date range first.

CREATE TEMP TABLE vol AS
SELECT 'binance' venue, symbol sym, date_trunc('week',open_time) w, sum(volume*close_price) q
FROM binance_perp_kline WHERE interval='1h' AND open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'bybit', venue_symbol, date_trunc('week',open_time), sum(base_volume*close_price)
FROM bybit_perp_kline WHERE open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'hyperliquid', coin, date_trunc('week',open_time), sum(base_volume*close_price)
FROM hyperliquid_perp_kline WHERE interval='1d' AND open_time>='2023-11-01' GROUP BY 1,2,3;
CREATE INDEX ON vol (venue, sym, w);

CREATE TEMP TABLE daily AS
SELECT venue, normalise_perp_base(
         CASE WHEN venue='hyperliquid' THEN venue_symbol
              WHEN venue_symbol LIKE '%USDT' OR venue_symbol LIKE '%USDC'
                   THEN left(venue_symbol, length(venue_symbol)-4) ELSE venue_symbol END) AS base,
       venue_symbol sym, date_trunc('day', funding_time) d, sum(funding_rate) rate
FROM perp_funding_all
WHERE venue IN ('binance','bybit','hyperliquid') AND funding_time >= '2023-11-01'
GROUP BY 1,2,3,4;
CREATE INDEX ON daily (venue, sym, d);

-- back3/back7 only - matches production's asOf::date <= convention (the signal includes day D).
CREATE TEMP TABLE rolling AS
SELECT venue, base, sym, d, date_trunc('week', d) w,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) back3,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) back7
FROM daily;
CREATE INDEX ON rolling (base, d, venue);

CREATE TEMP TABLE ok AS
SELECT r.*, v.q FROM rolling r
JOIN vol v ON v.venue=r.venue AND v.sym=r.sym AND v.w=r.w
WHERE v.q >= 500000 AND (r.d::date - date '2024-01-01') % 3 = 0
  AND r.d BETWEEN '2024-01-08' AND '2026-08-10';
CREATE INDEX ON ok (base, d, venue);
ANALYZE ok;

-- Correct pairwise selection (DISTINCT ON per actual matched pair), evaluated once under the
-- 3-day signal and once under the 7-day signal, pair_type from the TWO SELECTED venues only.
CREATE TEMP TABLE sel_3d AS
SELECT DISTINCT ON (a.base, a.d)
       a.base, a.d,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid' THEN 'CEX-DEX' ELSE 'CEX-CEX' END pair_type,
       (a.back3-b.back3)*(365.0/3)*100 AS spread,
       a.venue sv, a.sym sv_sym, b.venue lv, b.sym lv_sym
FROM ok a JOIN ok b ON a.base=b.base AND a.d=b.d AND a.venue<>b.venue
ORDER BY a.base, a.d, (a.back3-b.back3) DESC;

CREATE TEMP TABLE sel_7d AS
SELECT DISTINCT ON (a.base, a.d)
       a.base, a.d,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid' THEN 'CEX-DEX' ELSE 'CEX-CEX' END pair_type,
       (a.back7-b.back7)*(365.0/7)*100 AS spread,
       a.venue sv, a.sym sv_sym, b.venue lv, b.sym lv_sym
FROM ok a JOIN ok b ON a.base=b.base AND a.d=b.d AND a.venue<>b.venue
ORDER BY a.base, a.d, (a.back7-b.back7) DESC;

CREATE TEMP TABLE top_3d AS SELECT * FROM sel_3d WHERE spread > 20;
CREATE TEMP TABLE top_7d AS SELECT * FROM sel_7d WHERE spread > 20;

-- Realized window starts at D+1 - no day shared with the trailing signal window above.
CREATE TEMP TABLE realized_3d AS
SELECT t.pair_type,
  (SELECT sum(rate) FROM daily WHERE venue=t.sv AND base=t.base AND d>=t.d+interval '1 day' AND d<t.d+interval '4 days') -
  (SELECT sum(rate) FROM daily WHERE venue=t.lv AND base=t.base AND d>=t.d+interval '1 day' AND d<t.d+interval '4 days')
  AS spread_sum
FROM top_3d t;

CREATE TEMP TABLE realized_7d AS
SELECT t.pair_type,
  (SELECT sum(rate) FROM daily WHERE venue=t.sv AND base=t.base AND d>=t.d+interval '1 day' AND d<t.d+interval '4 days') -
  (SELECT sum(rate) FROM daily WHERE venue=t.lv AND base=t.base AND d>=t.d+interval '1 day' AND d<t.d+interval '4 days')
  AS spread_sum
FROM top_7d t;

SELECT '3d-lookback signal' src, pair_type,
       round((avg(spread_sum)*(365.0/3)*100)::numeric,1) annualized, count(*) n
FROM realized_3d WHERE spread_sum IS NOT NULL GROUP BY 2
UNION ALL
SELECT '7d-lookback signal', pair_type,
       round((avg(spread_sum)*(365.0/3)*100)::numeric,1), count(*)
FROM realized_7d WHERE spread_sum IS NOT NULL GROUP BY 2
ORDER BY 2,1;
