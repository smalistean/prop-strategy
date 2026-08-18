\timing on
-- Weekly quote volume per venue-symbol, all four venues.
CREATE TEMP TABLE vol AS
SELECT 'binance' venue, symbol sym, date_trunc('week',open_time) w, sum(volume*close_price) q
FROM binance_perp_kline WHERE interval='1h' AND open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'bybit', venue_symbol, date_trunc('week',open_time), sum(base_volume*close_price)
FROM bybit_perp_kline WHERE open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'hyperliquid', coin, date_trunc('week',open_time), sum(base_volume*close_price)
FROM hyperliquid_perp_kline WHERE interval='1d' AND open_time>='2023-11-01' GROUP BY 1,2,3
UNION ALL SELECT 'dydx', venue_symbol, date_trunc('week',open_time), sum(base_volume*close_price)
FROM dydx_perp_kline WHERE open_time>='2023-11-01' GROUP BY 1,2,3;
CREATE INDEX ON vol (venue, sym, w);

CREATE TEMP TABLE fw AS
SELECT f.venue, normalise_perp_base(
         CASE WHEN f.venue='dydx' THEN split_part(split_part(venue_symbol,',',1),'-',1)
              WHEN f.venue='hyperliquid' THEN venue_symbol
              WHEN venue_symbol LIKE '%USDT' OR venue_symbol LIKE '%USDC'
                   THEN left(venue_symbol, length(venue_symbol)-4) ELSE venue_symbol END) AS base,
       f.venue_symbol sym, date_trunc('week', funding_time) w, sum(funding_rate)*52*100 annual
FROM perp_funding_all f
WHERE f.venue IN ('binance','bybit','hyperliquid','dydx') AND funding_time >= '2023-11-01'
GROUP BY 1,2,3,4;
CREATE INDEX ON fw (base, w, venue);

-- Only legs that clear the $500k weekly floor are eligible, per XvfConfig.
CREATE TEMP TABLE ok AS
SELECT f.* FROM fw f JOIN vol v ON v.venue=f.venue AND v.sym=f.sym AND v.w=f.w
WHERE v.q >= 500000;
CREATE INDEX ON ok (base, w, venue);
ANALYZE ok;
\timing off

CREATE OR REPLACE FUNCTION vset2(vs text[]) RETURNS TABLE(
  cands numeric, full_pct numeric, realised numeric, deployed numeric, score numeric) AS $$
WITH sig AS (SELECT base, w, (max(annual)-min(annual)) spread,
               (array_agg(venue ORDER BY annual DESC))[1] sv,
               (array_agg(venue ORDER BY annual))[1] lv
        FROM ok WHERE venue = ANY(vs) GROUP BY 1,2 HAVING count(DISTINCT venue)>=2),
top AS (SELECT * FROM (SELECT *, row_number() OVER (PARTITION BY w ORDER BY spread DESC) rk
                       FROM sig WHERE spread>20) z WHERE rk<=20),
real AS (SELECT t.*, s.annual - l.annual AS r FROM top t
         JOIN ok s ON s.base=t.base AND s.venue=t.sv AND s.w=t.w+interval '7 days'
         JOIN ok l ON l.base=t.base AND l.venue=t.lv AND l.w=t.w+interval '7 days'),
legs AS (SELECT w, v, count(*) n FROM (
    SELECT w, sv v FROM top UNION ALL SELECT w, lv FROM top) x GROUP BY 1,2),
cap AS (SELECT sum(p90) slots FROM (
    SELECT percentile_disc(0.9) WITHIN GROUP (ORDER BY n) p90 FROM legs GROUP BY v) q)
SELECT (SELECT avg(c) FROM (SELECT w, count(*) c FROM sig WHERE spread>20 GROUP BY 1) a),
       (SELECT 100.0*count(*) FILTER (WHERE c>=20)/count(*) FROM (
          SELECT w, count(*) c FROM sig WHERE spread>20 GROUP BY 1) b),
       (SELECT avg(r) FROM real),
       (SELECT least(40.0/slots,1.0)*100 FROM cap),
       (SELECT avg(r) FROM real) * (SELECT least(40.0/slots,1.0) FROM cap);
$$ LANGUAGE sql;

\echo ''
\echo '=== WITH the $500k weekly liquidity floor on both legs ==='
SELECT 'binance+bybit           ' s, * FROM vset2(ARRAY['binance','bybit'])
UNION ALL SELECT 'binance+hyperliquid     ', * FROM vset2(ARRAY['binance','hyperliquid'])
UNION ALL SELECT 'binance+dydx            ', * FROM vset2(ARRAY['binance','dydx'])
UNION ALL SELECT 'binance+bybit+hyperliq  ', * FROM vset2(ARRAY['binance','bybit','hyperliquid'])
UNION ALL SELECT 'all four                ', * FROM vset2(ARRAY['binance','bybit','hyperliquid','dydx']);
