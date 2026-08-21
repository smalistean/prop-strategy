-- Exports the two CSVs xvf-capital-simulation.py consumes: a freshness-discounted top-20 candidate
-- list at every 3rd day (production's rebalance cadence), and the raw daily funding sums the
-- simulator replays forward against.
--
-- This mirrors production (XvfSignalEngine.rankedCandidatesRaw + rankedCandidates) as closely as a
-- single SQL pass can - not a separately-tuned backtest ranking. If XvfConfig's constants change,
-- this query's literals (7-day lookback for both pair types, 20% floor, 0.65 discount, $500k
-- liquidity floor) need to change with them or the simulation stops reflecting production.
--
-- CORRECTED 2026-08-21 after an independent review (XVF_CALCULATIONS_INDEPENDENT_REVIEW.md) found
-- two real bugs upstream of this export: LOOKBACK_DAYS_CEX_CEX was 3 on a backtest that, once a
-- pair-type-mislabeling bug and a same-day signal/outcome leakage bug were both fixed, no longer
-- supported anything other than the shared 7-day window (see XvfConfig.java's LOOKBACK_DAYS_CEX_CEX
-- javadoc) - so this export no longer branches by pair type for the lookback, both use back7.
-- STALE_SIGNAL_DISCOUNT moved from 0.5 to 0.65 on the same corrected calibration (see
-- XvfConfig.java's STALE_SIGNAL_DISCOUNT javadoc and analysis-freshness-discount.sql).
--
-- Usage:
--   psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-capital-simulation-export.sql
--   (writes /tmp/candidates_fresh.csv and /tmp/funding_daily_fresh.csv)
--   CANDIDATES_CSV=/tmp/candidates_fresh.csv FUNDING_CSV=/tmp/funding_daily_fresh.csv \
--     SIM_START=2025-08-21 SIM_END=2026-08-20 python3 scripts/xvf-capital-simulation.py 1500 1500 1500
--
-- Runtime: ~50s for both exports.

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

CREATE TEMP TABLE rolling AS
SELECT venue, base, sym, d, date_trunc('week', d) w,
       sum(rate) OVER (PARTITION BY venue, sym ORDER BY d ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) back7
FROM daily;
CREATE INDEX ON rolling (base, d, venue);

CREATE TEMP TABLE ok AS
SELECT r.*, v.q FROM rolling r
JOIN vol v ON v.venue=r.venue AND v.sym=r.sym AND v.w=r.w
WHERE v.q >= 500000 AND r.d BETWEEN '2024-01-02' AND '2026-08-20';
CREATE INDEX ON ok (base, d, venue);
ANALYZE ok;

-- Best cross-venue pair per base per day - mirrors XvfSignalEngine.bestCrossVenuePair (full
-- pairwise scan, at most 3 venues so this is cheap). pair_type is still recorded (Hyperliquid on
-- either side = CEX-DEX) since XvfConfig keeps the two lookback constants independently settable -
-- it just no longer changes which window is used, since both equal 7.
CREATE TEMP TABLE pairs AS
SELECT DISTINCT ON (a.base, a.d)
       a.base, a.d,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid' THEN 'CEX-DEX' ELSE 'CEX-CEX' END pair_type,
       (a.back7-b.back7)*(365.0/7)*100 AS spread,
       a.venue sv, a.sym sv_sym, b.venue lv, b.sym lv_sym,
       LEAST(a.q,b.q) thin
FROM ok a JOIN ok b ON a.base=b.base AND a.d=b.d AND a.venue<>b.venue
ORDER BY a.base, a.d, (a.back7-b.back7) DESC;

CREATE TEMP TABLE elig AS
SELECT * FROM pairs WHERE spread > 20 AND thin >= 500000;
CREATE INDEX ON elig (base, d);

-- Freshness discount, mirroring XvfSignalEngine.rankedCandidates: apply STALE_SIGNAL_DISCOUNT to
-- any base also eligible the day before, evaluated only at the production rebalance cadence.
CREATE TEMP TABLE gapped AS
SELECT *, d::date - LAG(d::date) OVER (PARTITION BY base ORDER BY d) AS gap_back
FROM elig;

CREATE TEMP TABLE discounted AS
SELECT base, d, pair_type, sv, sv_sym, lv, lv_sym, thin,
       CASE WHEN gap_back IS NULL OR gap_back > 1 THEN spread ELSE spread * 0.65 END AS disc_spread
FROM gapped;

CREATE TEMP TABLE ranked AS
SELECT *, row_number() OVER (PARTITION BY d ORDER BY disc_spread DESC) rk
FROM discounted
WHERE disc_spread > 20 AND (d::date - date '2024-01-02') % 3 = 0;

\copy (SELECT d w, base, sv, sv_sym, lv, lv_sym, pair_type, disc_spread spread FROM ranked WHERE rk<=20 ORDER BY d, rk) TO '/tmp/candidates_fresh.csv' WITH CSV HEADER
\copy (SELECT venue, sym venue_symbol, d, rate rate_sum FROM daily) TO '/tmp/funding_daily_fresh.csv' WITH CSV HEADER
