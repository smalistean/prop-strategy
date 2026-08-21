-- Measures whether a candidate's trailing signal is a reliable predictor of what it goes on to
-- realize, split by how many consecutive days it has already been eligible. Backs
-- XvfConfig.STALE_SIGNAL_DISCOUNT (0.65) and the freshness check in
-- XvfSignalEngine.rankedCandidates (src/main/java/com/smalistean/propstrategy/xvf/signal/).
--
-- CORRECTED 2026-08-21 after an independent review (XVF_CALCULATIONS_INDEPENDENT_REVIEW.md) found
-- the "forward" realized window shared its first day with the trailing signal window (both queried
-- funding_time as of / from the same day D). A large print on day D could both qualify a candidate
-- as eligible AND get recounted as part of what it went on to realize - especially inflating the
-- "first day eligible" bucket, which is exactly the number that mattered most for this discount.
-- Fixed by starting the realized window at D+1, so no day is shared between signal and outcome.
--
-- Original (buggy) result: streak-1 (fresh) read 99%/90% calibrated (CEX-CEX/CEX-DEX); streak-2+
-- (stale) read 46%/51%. STALE_SIGNAL_DISCOUNT=0.5 shipped on this.
--
-- Corrected result, this run:
--   CEX-CEX streak 1 (first day eligible): realized 16.4%, signal 38.1% -> 43% calibrated, n=8421
--   CEX-CEX streak 2+:                     realized ~15-26%, signal ~53-82% -> ~28-31% calibrated
--   CEX-DEX streak 1:                      realized 17.4%, signal 26.4% -> 66% calibrated, n=2317
--   CEX-DEX streak 2+:                     realized ~17-22%, signal ~31-48% -> ~43-56% calibrated
-- The direction still holds - fresh is better calibrated than stale - but the gap is smaller than
-- first measured (roughly 1.3-1.5x, not ~2x), and the bigger surprise is that even a FRESH signal
-- now over-reads its own forward realization by more than 2x. STALE_SIGNAL_DISCOUNT moved to 0.65
-- on this result; the broader over-read is not corrected for anywhere and is a larger open question
-- than this discount's exact value.
--
-- Runtime: ~40s.

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

-- back3/back7 unchanged (matches production's asOf::date <= convention: the signal includes day
-- D). fwd3/fwd7 now start at D+1, not D - no day is shared between signal and outcome.
CREATE TEMP TABLE rolling AS
SELECT venue, base, sym, d, date_trunc('week', d) w,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) back3,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) back7,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 1 FOLLOWING AND 3 FOLLOWING) fwd3,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 1 FOLLOWING AND 7 FOLLOWING) fwd7
FROM daily;
CREATE INDEX ON rolling (base, d, venue);

CREATE TEMP TABLE ok AS
SELECT r.*, v.q FROM rolling r
JOIN vol v ON v.venue=r.venue AND v.sym=r.sym AND v.w=r.w
WHERE v.q >= 500000 AND r.d BETWEEN '2024-01-02' AND '2026-08-10';
CREATE INDEX ON ok (base, d, venue);
ANALYZE ok;

CREATE TEMP TABLE pairs AS
SELECT DISTINCT ON (a.base, a.d)
       a.base, a.d,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid' THEN 'CEX-DEX' ELSE 'CEX-CEX' END pair_type,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid'
            THEN (a.back7-b.back7)*(365.0/7)*100 ELSE (a.back3-b.back3)*(365.0/3)*100 END AS spread,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid'
            THEN (a.fwd7-b.fwd7)*(365.0/7)*100 ELSE (a.fwd3-b.fwd3)*(365.0/3)*100 END AS realized,
       LEAST(a.q,b.q) thin
FROM ok a JOIN ok b ON a.base=b.base AND a.d=b.d AND a.venue<>b.venue
ORDER BY a.base, a.d,
  (CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid'
        THEN (a.back7-b.back7)*(365.0/7)*100 ELSE (a.back3-b.back3)*(365.0/3)*100 END) DESC;

CREATE TEMP TABLE elig AS
SELECT * FROM pairs WHERE spread > 20 AND thin >= 500000;
CREATE INDEX ON elig (base, d);

CREATE TEMP TABLE streaked AS
WITH lagged AS (
  SELECT *, LAG(d) OVER (PARTITION BY base ORDER BY d) AS prev_d FROM elig
), grp AS (
  SELECT *, SUM(CASE WHEN prev_d IS NULL OR d::date - prev_d::date > 1 THEN 1 ELSE 0 END)
             OVER (PARTITION BY base ORDER BY d) AS grp_id
  FROM lagged
)
SELECT *, ROW_NUMBER() OVER (PARTITION BY base, grp_id ORDER BY d) AS streak_len
FROM grp;

SELECT pair_type,
       CASE WHEN streak_len = 1 THEN '1 (first day eligible)'
            WHEN streak_len = 2 THEN '2'
            WHEN streak_len BETWEEN 3 AND 5 THEN '3-5'
            ELSE '6+' END AS streak_bucket,
       round(avg(realized)::numeric,1) avg_realized_annualized,
       round(avg(spread)::numeric,1) avg_signal_annualized,
       round((avg(realized)/nullif(avg(spread),0)*100)::numeric,0) calibration_pct,
       count(*) n
FROM streaked
WHERE realized IS NOT NULL
GROUP BY 1,2
ORDER BY 1, min(streak_len);
