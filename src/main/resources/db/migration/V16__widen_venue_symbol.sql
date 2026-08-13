-- Widens venue_symbol and fixes what dYdX permissionless markets exposed.
--
-- dYdX lists isolated markets whose ticker embeds a DEX and a contract address:
--
--     FARTCOIN,RAYDIUM,9BB6NFECJBCTNNLFKO2FQVQBQ8HHM13KCYYCDQBGPUMP-USD    (65 characters)
--
-- VARCHAR(40) rejected them, so six markets failed on both the funding and candle imports. The API
-- serves them correctly; the limit was mine. 120 covers the longest current ticker with room, and is
-- applied to every venue rather than only dYdX because permissionless listing is spreading and the
-- next venue to adopt it should not cost another failed import.
--
-- perp_funding_all is dropped and rebuilt around the alters: PostgreSQL refuses to change the type
-- of a column a view depends on. The view definition below is identical to V14's.
--
-- The companion bug is in the importers, not the schema: base was taken as split("-")[0], which for
-- these tickers returns 61 characters of contract address rather than FARTCOIN, and would have
-- stored a base that could never join to the same asset on another venue even after widening.
DROP VIEW IF EXISTS perp_funding_all;

ALTER TABLE dydx_perp_funding_rate   ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE dydx_perp_kline          ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE bybit_perp_funding_rate  ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE bybit_perp_kline         ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE okx_perp_funding_rate    ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE gate_perp_funding_rate   ALTER COLUMN venue_symbol TYPE VARCHAR(120);
ALTER TABLE bitget_perp_funding_rate ALTER COLUMN venue_symbol TYPE VARCHAR(120);

CREATE VIEW perp_funding_all AS
    SELECT 'binance'::text AS venue, symbol AS venue_symbol,
           normalise_perp_base(
               CASE WHEN symbol LIKE '%USDT' OR symbol LIKE '%USDC'
                    THEN left(symbol, length(symbol) - 4) ELSE symbol END) AS base,
           funding_time, rate AS funding_rate
    FROM (SELECT symbol, funding_time, max(funding_rate) AS rate
          FROM binance_perp_funding_rate GROUP BY symbol, funding_time) binance_deduplicated
UNION ALL
    SELECT 'hyperliquid', coin, normalise_perp_base(coin), funding_time, funding_rate
    FROM hyperliquid_perp_funding_rate
UNION ALL SELECT 'bybit',  venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM bybit_perp_funding_rate
UNION ALL SELECT 'okx',    venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM okx_perp_funding_rate
UNION ALL SELECT 'gate',   venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM gate_perp_funding_rate
UNION ALL SELECT 'bitget', venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM bitget_perp_funding_rate
UNION ALL SELECT 'dydx',   venue_symbol, normalise_perp_base(base), funding_time, funding_rate FROM dydx_perp_funding_rate;
