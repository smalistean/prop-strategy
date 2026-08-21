\set ON_ERROR_STOP on
SET TIME ZONE 'Europe/Chisinau';

-- Codex artifact outputs. psql's \copy does not expand variables in its filename argument, so
-- these are explicit workspace-local paths.

-- Temporary, no-lookahead replay export. It deliberately differs from the repository export:
--   * signal cutoff is the start of the rebalance LocalDate, as in XvfSignalEngine;
--   * funding events are bucketed by the first midnight cutoff that can see them;
--   * 7-day windows are calendar windows, not ROWS over possibly missing dates;
--   * volume is the preceding completed day, not the whole calendar week;
--   * completeness is enforced from completed historical weeks only;
--   * every eligible rank is exported so a capital-constrained simulator can backfill.

CREATE TEMP TABLE rebalances AS
SELECT d::date AS d
FROM generate_series(date '2024-08-21', date '2026-08-20', interval '1 day') d
WHERE (d::date - date '2024-01-02') % 3 = 0;
CREATE UNIQUE INDEX ON rebalances(d);

CREATE TEMP TABLE evals AS
SELECT d AS rebalance_day, d AS signal_day FROM rebalances
UNION ALL
SELECT d, d - 1 FROM rebalances;
CREATE INDEX ON evals(signal_day);

-- Keep the raw events so the lower-exclusive / upper-inclusive production cutoff can be
-- represented exactly at local midnight. cutoff_day is the first LocalDate whose <= date cutoff
-- includes the event.
CREATE TEMP TABLE funding_events AS
SELECT venue,
       normalise_perp_base(
         CASE WHEN venue='hyperliquid' THEN venue_symbol
              WHEN venue_symbol LIKE '%USDT' OR venue_symbol LIKE '%USDC'
                   THEN left(venue_symbol, length(venue_symbol)-4)
              ELSE venue_symbol END) AS base,
       venue_symbol AS sym,
       funding_time,
       funding_rate::double precision AS rate,
       CASE WHEN funding_time = date_trunc('day', funding_time)
            THEN funding_time::date ELSE funding_time::date + 1 END AS cutoff_day
FROM perp_funding_all
WHERE venue IN ('binance','bybit','hyperliquid')
  AND funding_time > date '2024-02-20'
  AND funding_time <= date '2026-08-21';
CREATE INDEX ON funding_events(venue,sym,funding_time);
CREATE INDEX ON funding_events(cutoff_day,venue,sym);

CREATE TEMP TABLE series AS
SELECT DISTINCT venue, base, sym FROM funding_events;
CREATE UNIQUE INDEX ON series(venue,sym);

CREATE TEMP TABLE cutoff_daily AS
SELECT venue, base, sym, cutoff_day,
       sum(rate) AS rate, count(*)::int AS payments
FROM funding_events
GROUP BY 1,2,3,4;
CREATE UNIQUE INDEX ON cutoff_daily(venue,sym,cutoff_day);

-- Zero-fill calendar days before applying ROWS, otherwise a missing day silently lengthens a
-- seven-row window beyond seven calendar days.
CREATE TEMP TABLE calendar AS
SELECT d::date AS d
FROM generate_series(date '2024-08-13', date '2026-08-20', interval '1 day') d;

CREATE TEMP TABLE rolling AS
SELECT s.venue, s.base, s.sym, c.d,
       sum(coalesce(fd.rate,0.0)) OVER
         (PARTITION BY s.venue,s.sym ORDER BY c.d ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS back7,
       sum(coalesce(fd.payments,0)) OVER
         (PARTITION BY s.venue,s.sym ORDER BY c.d ROWS BETWEEN 6 PRECEDING AND CURRENT ROW) AS payments7
FROM series s CROSS JOIN calendar c
LEFT JOIN cutoff_daily fd
  ON fd.venue=s.venue AND fd.sym=s.sym AND fd.cutoff_day=c.d;
CREATE INDEX ON rolling(d,base,venue);

-- Use only completed weeks strictly before each historical cutoff: no future rows and no partial
-- current week. The median is insensitive to the partial oldest week omitted here.
CREATE TEMP TABLE weekly_counts AS
SELECT venue, sym, date_trunc('week',funding_time)::date AS w, count(*)::int AS n
FROM funding_events
GROUP BY 1,2,3;
CREATE INDEX ON weekly_counts(venue,sym,w);

CREATE TEMP TABLE typical AS
SELECT e.signal_day, wc.venue, wc.sym,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY wc.n) AS med
FROM (SELECT DISTINCT signal_day FROM evals) e
JOIN weekly_counts wc
  ON wc.w >= date_trunc('week', e.signal_day - 180)::date
 AND wc.w <  date_trunc('week', e.signal_day)::date
GROUP BY 1,2,3;
CREATE INDEX ON typical(signal_day,venue,sym);

-- Historical approximation to LiveVolume.fetch(): the last completed calendar day, annual signal
-- evaluated at midnight. Unlike a calendar-week aggregate, this never includes future candles.
CREATE TEMP TABLE volume_daily AS
SELECT 'binance'::text AS venue, symbol::text AS sym,
       open_time::date + 1 AS cutoff_day,
       sum((volume*close_price)::double precision) AS q24
FROM binance_perp_kline
WHERE interval='1h' AND open_time >= date '2024-08-20' AND open_time < date '2026-08-21'
GROUP BY 1,2,3
UNION ALL
SELECT 'bybit', venue_symbol, open_time::date + 1,
       sum((base_volume*close_price)::double precision)
FROM bybit_perp_kline
WHERE interval='1d' AND open_time >= date '2024-08-20' AND open_time < date '2026-08-21'
GROUP BY 1,2,3
UNION ALL
SELECT 'hyperliquid', coin, open_time::date + 1,
       sum((base_volume*close_price)::double precision)
FROM hyperliquid_perp_kline
WHERE interval='1d' AND open_time >= date '2024-08-20' AND open_time < date '2026-08-21'
GROUP BY 1,2,3;
CREATE INDEX ON volume_daily(cutoff_day,venue,sym);

-- For yesterday eligibility, production reuses today's LiveVolume snapshot. Therefore volume is
-- keyed by rebalance_day, while rates/completeness are keyed by signal_day.
CREATE TEMP TABLE usable_legs AS
SELECT e.rebalance_day, e.signal_day,
       r.venue, r.base, r.sym, r.back7,
       v.q24 * 7.0 AS weekly_q
FROM evals e
JOIN rolling r ON r.d=e.signal_day
JOIN typical t
  ON t.signal_day=e.signal_day AND t.venue=r.venue AND t.sym=r.sym
JOIN volume_daily v
  ON v.cutoff_day=e.rebalance_day AND v.venue=r.venue AND v.sym=r.sym
WHERE r.payments7 >= 0.9 * t.med
  AND v.q24 * 7.0 >= 500000;
CREATE INDEX ON usable_legs(rebalance_day,signal_day,base,venue);
ANALYZE usable_legs;

CREATE TEMP TABLE pairs AS
SELECT DISTINCT ON (a.rebalance_day,a.signal_day,a.base)
       a.rebalance_day, a.signal_day, a.base,
       (a.back7-b.back7)*(365.0/7)*100 AS raw_spread,
       a.venue AS sv, a.sym AS sv_sym,
       b.venue AS lv, b.sym AS lv_sym,
       CASE WHEN a.venue='hyperliquid' OR b.venue='hyperliquid'
            THEN 'CEX-DEX' ELSE 'CEX-CEX' END AS pair_type,
       least(a.weekly_q,b.weekly_q) AS thin
FROM usable_legs a
JOIN usable_legs b
  ON b.rebalance_day=a.rebalance_day
 AND b.signal_day=a.signal_day
 AND b.base=a.base
 AND b.venue<>a.venue
ORDER BY a.rebalance_day,a.signal_day,a.base,(a.back7-b.back7) DESC;
CREATE INDEX ON pairs(rebalance_day,signal_day,base);

CREATE TEMP TABLE raw_eligible AS
SELECT * FROM pairs WHERE raw_spread > 20 AND thin >= 500000;

CREATE TEMP TABLE candidates AS
SELECT t.rebalance_day AS w, t.base, t.sv, t.sv_sym, t.lv, t.lv_sym, t.pair_type,
       CASE WHEN y.base IS NULL THEN t.raw_spread ELSE t.raw_spread*0.65 END AS spread,
       t.raw_spread, (y.base IS NULL) AS fresh, t.thin
FROM raw_eligible t
LEFT JOIN raw_eligible y
  ON y.rebalance_day=t.rebalance_day
 AND y.signal_day=t.rebalance_day-1
 AND y.base=t.base
WHERE t.signal_day=t.rebalance_day
  AND CASE WHEN y.base IS NULL THEN t.raw_spread ELSE t.raw_spread*0.65 END > 20;

\copy (SELECT *, row_number() OVER (PARTITION BY w ORDER BY spread DESC) AS rk FROM candidates ORDER BY w,rk) TO '/Users/stas/workspace/other/prop-strategy/CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/generated/candidates_production_like.csv' WITH CSV HEADER
\copy (SELECT venue,sym AS venue_symbol,cutoff_day AS d,rate AS rate_sum,payments FROM cutoff_daily WHERE cutoff_day BETWEEN date '2024-08-21' AND date '2026-08-21' ORDER BY cutoff_day,venue,sym) TO '/Users/stas/workspace/other/prop-strategy/CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/generated/funding_cutoff_daily.csv' WITH CSV HEADER
