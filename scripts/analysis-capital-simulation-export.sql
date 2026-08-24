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
-- EXTENDED 2026-08-22 with entry-time price basis (entry_basis_bps), after measuring that a
-- deeply adverse entry basis (the venue about to be shorted already >50bp cheaper in price than
-- the venue being longed) predicts realized funding flipping fully negative roughly 54% of the
-- time, versus ~18% for a moderately aligned entry - see funding-vs-basis correlation and
-- funding-sign-flip work, 2026-08-22. Bybit's volume filter now pins interval='1d' explicitly:
-- an earlier version of this query had no interval filter at all, which was harmless while Bybit
-- only had daily klines but would have double-counted volume once Bybit's 1h history was imported
-- the same day this basis column was added.
--
-- entry_basis_bps is NULL whenever either leg's daily open price is unavailable - most of
-- Hyperliquid's history (only 7 of 232 coins have 1h depth before 2026, and CEX-DEX pairs are
-- priced here from the SAME daily klines used for the liquidity floor, which is broad for
-- Hyperliquid too, so coverage is usually fine; NULL mainly shows up for a base that just listed).
-- A NULL means "not measured," not "flat" - the simulator must not silently treat it as zero.
--
-- Usage:
--   psql -U prop_strategy_app -d prop_strategy -f scripts/analysis-capital-simulation-export.sql
--   (writes /tmp/candidates_fresh.csv and /tmp/funding_daily_fresh.csv)
--   CANDIDATES_CSV=/tmp/candidates_fresh.csv FUNDING_CSV=/tmp/funding_daily_fresh.csv \
--     SIM_START=2025-08-21 SIM_END=2026-08-20 python3 scripts/xvf-capital-simulation.py 1500 1500 1500
--
-- Runtime: ~60s for both exports.

CREATE TEMP TABLE vol AS
SELECT 'binance' venue, symbol sym, date_trunc('week',open_time) w, sum(volume*close_price) q
FROM binance_perp_kline WHERE interval='1h' AND open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'bybit', venue_symbol, date_trunc('week',open_time), sum(base_volume*close_price)
FROM bybit_perp_kline WHERE interval='1d' AND open_time>='2023-11-01' GROUP BY 1,2,3
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

-- Daily open price per (venue, base), for entry_basis_bps below. Binance has no native daily
-- klines - DISTINCT ON its own 1h feed's first candle of the day is the same technique used in
-- the funding-vs-basis check, before binance_perp_kline's 1d interval was imported; both branches
-- now read real daily klines directly, since that import landed the same day as this change.
CREATE TEMP TABLE daily_price AS
SELECT 'binance' venue, normalise_perp_base(left(symbol, length(symbol)-4)) AS base,
       date_trunc('day', open_time) d, open_price::double precision px
FROM binance_perp_kline WHERE interval='1d' AND symbol LIKE '%USDT' AND symbol <> 'FTTUSDT'
UNION ALL
SELECT 'bybit', normalise_perp_base(left(venue_symbol, length(venue_symbol)-4)),
       date_trunc('day', open_time), open_price::double precision
FROM bybit_perp_kline WHERE interval='1d' AND venue_symbol LIKE '%USDT' AND venue_symbol <> 'FTTUSDT'
UNION ALL
SELECT 'hyperliquid', coin, date_trunc('day', open_time), open_price::double precision
FROM hyperliquid_perp_kline WHERE interval='1d' AND coin <> 'FTT';
CREATE INDEX ON daily_price (venue, base, d);

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
SELECT pr.*, ln(ps.px/pl.px)*10000 AS entry_basis_bps
FROM pairs pr
LEFT JOIN daily_price ps ON ps.venue=pr.sv AND ps.base=pr.base AND ps.d=pr.d
LEFT JOIN daily_price pl ON pl.venue=pr.lv AND pl.base=pr.base AND pl.d=pr.d
WHERE pr.spread > 20 AND pr.thin >= 500000;
CREATE INDEX ON elig (base, d);

-- Freshness discount, mirroring XvfSignalEngine.rankedCandidates: apply STALE_SIGNAL_DISCOUNT to
-- any base also eligible the day before, evaluated only at the production rebalance cadence.
CREATE TEMP TABLE gapped AS
SELECT *, d::date - LAG(d::date) OVER (PARTITION BY base ORDER BY d) AS gap_back
FROM elig;

CREATE TEMP TABLE discounted AS
SELECT base, d, pair_type, sv, sv_sym, lv, lv_sym, thin, entry_basis_bps,
       CASE WHEN gap_back IS NULL OR gap_back > 1 THEN spread ELSE spread * 0.65 END AS disc_spread
FROM gapped;

CREATE TEMP TABLE ranked AS
SELECT *, row_number() OVER (PARTITION BY d ORDER BY disc_spread DESC) rk
FROM discounted
WHERE disc_spread > 20 AND (d::date - date '2024-01-02') % 3 = 0;

-- No rk<=20 cap: xvf-capital-simulation.py's ranked mode re-sorts by spread and stops at POSITIONS
-- slots itself, so this is unchanged for that mode - but its random-selection mode (added 2026-08-23
-- to test whether ranking by trailing magnitude beats picking randomly from the same eligible day's
-- pool) needs every eligible candidate, not just the ones that already won the ranking.
\copy (SELECT d w, base, sv, sv_sym, lv, lv_sym, pair_type, disc_spread spread, entry_basis_bps FROM ranked ORDER BY d, rk) TO '/tmp/candidates_fresh.csv' WITH CSV HEADER
\copy (SELECT venue, sym venue_symbol, d, rate rate_sum FROM daily) TO '/tmp/funding_daily_fresh.csv' WITH CSV HEADER
