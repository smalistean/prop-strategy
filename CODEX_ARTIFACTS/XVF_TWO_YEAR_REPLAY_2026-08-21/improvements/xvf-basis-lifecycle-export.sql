\set ON_ERROR_STOP on
SET TIME ZONE 'UTC';

CREATE TEMP TABLE e (
  entry_id text, period text, entry_day date, close_day date, close_reason text,
  base text, spread double precision, raw_spread double precision, fresh boolean,
  thin double precision, rank int, pair_type text, sv text, sv_sym text,
  lv text, lv_sym text, notional double precision, entry_maker text, entry_taker text
);
\copy e FROM 'CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/generated/xvf_basis_selected_entries.csv' WITH CSV HEADER

CREATE TEMP TABLE fd(venue text,venue_symbol text,d date,rate_sum double precision,payments int);
\copy fd FROM 'CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/generated/funding_cutoff_daily.csv' WITH CSV HEADER
CREATE INDEX ON fd(venue,venue_symbol,d);

CREATE TEMP TABLE legs AS
SELECT entry_id,period,entry_day,close_day,base,spread,raw_spread,fresh,rank,pair_type,
       'short'::text AS side,sv AS venue,sv_sym AS sym,notional,
       entry_day::timestamp AT TIME ZONE 'UTC' AS t0,
       close_day::timestamp AT TIME ZONE 'UTC' AS tx
FROM e
UNION ALL
SELECT entry_id,period,entry_day,close_day,base,spread,raw_spread,fresh,rank,pair_type,
       'long',lv,lv_sym,notional,
       entry_day::timestamp AT TIME ZONE 'UTC',
       close_day::timestamp AT TIME ZONE 'UTC'
FROM e;
CREATE INDEX ON legs(venue,sym,t0);

CREATE TEMP TABLE priced AS
SELECT l.*, coalesce(b0h.open_price,b0d.open_price)::double precision AS p0,
       coalesce(bxh.open_price,bxd.open_price)::double precision AS px,
       CASE WHEN b0h.open_price IS NOT NULL THEN '1h' WHEN b0d.open_price IS NOT NULL THEN '1d' END AS p0_interval,
       CASE WHEN bxh.open_price IS NOT NULL THEN '1h' WHEN bxd.open_price IS NOT NULL THEN '1d' END AS px_interval
FROM legs l
LEFT JOIN binance_perp_kline b0h ON l.venue='binance' AND b0h.symbol=l.sym AND b0h.interval='1h' AND b0h.open_time=l.t0
LEFT JOIN binance_perp_kline bxh ON l.venue='binance' AND bxh.symbol=l.sym AND bxh.interval='1h' AND bxh.open_time=l.tx
LEFT JOIN binance_perp_kline b0d ON l.venue='binance' AND b0d.symbol=l.sym AND b0d.interval='1d' AND b0d.open_time=l.t0
LEFT JOIN binance_perp_kline bxd ON l.venue='binance' AND bxd.symbol=l.sym AND bxd.interval='1d' AND bxd.open_time=l.tx
WHERE l.venue='binance'
UNION ALL
SELECT l.*, b0.open_price::double precision,bx.open_price::double precision,
       CASE WHEN b0.open_price IS NOT NULL THEN '1d' END, CASE WHEN bx.open_price IS NOT NULL THEN '1d' END
FROM legs l
LEFT JOIN bybit_perp_kline b0 ON l.venue='bybit' AND b0.venue_symbol=l.sym AND b0.interval='1d' AND b0.open_time=l.t0
LEFT JOIN bybit_perp_kline bx ON l.venue='bybit' AND bx.venue_symbol=l.sym AND bx.interval='1d' AND bx.open_time=l.tx
WHERE l.venue='bybit'
UNION ALL
SELECT l.*, h0.open_price::double precision,hx.open_price::double precision,
       CASE WHEN h0.open_price IS NOT NULL THEN '1d' END, CASE WHEN hx.open_price IS NOT NULL THEN '1d' END
FROM legs l
LEFT JOIN hyperliquid_perp_kline h0 ON l.venue='hyperliquid' AND h0.coin=l.sym AND h0.interval='1d' AND h0.open_time=l.t0
LEFT JOIN hyperliquid_perp_kline hx ON l.venue='hyperliquid' AND hx.coin=l.sym AND hx.interval='1d' AND hx.open_time=l.tx
WHERE l.venue='hyperliquid';
CREATE INDEX ON priced(entry_id,side);

-- Exact reproduction of the canonical replay's local-midnight funding buckets.
CREATE TEMP TABLE f_model AS
SELECT l.entry_id,l.side,sum(fd.rate_sum) AS rate_model,sum(fd.payments) AS model_events
FROM legs l JOIN fd ON fd.venue=l.venue AND fd.venue_symbol=l.sym
 AND fd.d>l.entry_day AND fd.d<=l.close_day
GROUP BY 1,2;

-- Funding consistent with delayed UTC00 price entry/exit; retained for comparison only.
CREATE TEMP TABLE f_utc_event AS
SELECT l.entry_id,l.side,x.funding_time,max(x.funding_rate::double precision) AS rate
FROM legs l JOIN binance_perp_funding_rate x
  ON l.venue='binance' AND x.symbol=l.sym AND x.funding_time>l.t0 AND x.funding_time<=l.tx
GROUP BY 1,2,3
UNION ALL
SELECT l.entry_id,l.side,x.funding_time,x.funding_rate::double precision
FROM legs l JOIN bybit_perp_funding_rate x
  ON l.venue='bybit' AND x.venue_symbol=l.sym AND x.funding_time>l.t0 AND x.funding_time<=l.tx
UNION ALL
SELECT l.entry_id,l.side,x.funding_time,x.funding_rate::double precision
FROM legs l JOIN hyperliquid_perp_funding_rate x
  ON l.venue='hyperliquid' AND x.coin=l.sym AND x.funding_time>l.t0 AND x.funding_time<=l.tx;
CREATE TEMP TABLE f_utc AS SELECT entry_id,side,sum(rate) AS rate_utc,count(*) AS utc_events FROM f_utc_event GROUP BY 1,2;

\copy (SELECT p.*,coalesce(m.rate_model,0) AS funding_rate_model,coalesce(m.model_events,0) AS model_events,coalesce(u.rate_utc,0) AS funding_rate_utc,coalesce(u.utc_events,0) AS utc_events FROM priced p LEFT JOIN f_model m USING(entry_id,side) LEFT JOIN f_utc u USING(entry_id,side) ORDER BY entry_id,side) TO 'CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/improvements/generated/xvf_basis_lifecycle_legs.csv' WITH CSV HEADER

SELECT venue,count(*) AS legs,count(*) FILTER(WHERE p0 IS NOT NULL AND px IS NOT NULL) AS covered,
       count(*) FILTER(WHERE p0_interval='1h') AS used_1h,count(*) FILTER(WHERE p0_interval='1d') AS used_1d
FROM priced GROUP BY venue ORDER BY venue;
