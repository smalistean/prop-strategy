-- Aster: a Binance-API-shaped perpetual DEX, found while scoping a second DEX venue alongside
-- Hyperliquid (RESEARCH_OPTIONS.md item 1 / XVF_LIVE_FINDINGS.md §12). Real breadth (549 symbols,
-- BTC 24h volume comparable to Binance itself) and, unlike okx/bitget/gate, genuine multi-year
-- funding AND kline history via paginated REST - not a live-snapshot-only venue.
--
-- Also lists tokenized stocks (AAPL, TSLA, SKHYNIX, ...) under plain crypto-looking tickers, same
-- collision risk as the ON Semiconductor incident. Unlike the other venues checked so far, Aster's
-- own exchangeInfo carries an explicit, reliable filter: underlyingSubType contains "STOCK" for
-- every non-crypto listing found. VenueFundingImportApplication's aster adapter filters on this
-- before a single row is imported - the importer is the enforcement point, not a downstream guard.
CREATE TABLE aster_perp_funding_rate (
    venue_symbol  VARCHAR(120)    NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    funding_time  TIMESTAMPTZ     NOT NULL,
    funding_rate  NUMERIC(20,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT aster_perp_funding_rate_pk PRIMARY KEY (venue_symbol, funding_time)
);
CREATE INDEX aster_perp_funding_rate_base_idx ON aster_perp_funding_rate (base, funding_time);

-- perp_funding_all is UNION ALL across one table per venue - extended here rather than left for
-- every future query to UNION aster in by hand, the same reason the other six venues are in it.
CREATE OR REPLACE VIEW perp_funding_all AS
SELECT 'binance'::text AS venue, binance_deduplicated.symbol AS venue_symbol,
       normalise_perp_base((CASE
           WHEN binance_deduplicated.symbol LIKE '%USDT' OR binance_deduplicated.symbol LIKE '%USDC'
               THEN left(binance_deduplicated.symbol::text, length(binance_deduplicated.symbol::text) - 4)
           ELSE binance_deduplicated.symbol END)::text) AS base,
       binance_deduplicated.funding_time, binance_deduplicated.rate AS funding_rate
FROM (SELECT symbol, funding_time, max(funding_rate) AS rate
      FROM binance_perp_funding_rate GROUP BY symbol, funding_time) binance_deduplicated
UNION ALL
SELECT 'hyperliquid'::text, coin, normalise_perp_base(coin::text), funding_time, funding_rate
FROM hyperliquid_perp_funding_rate
UNION ALL
SELECT 'bybit'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM bybit_perp_funding_rate
UNION ALL
SELECT 'okx'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM okx_perp_funding_rate
UNION ALL
SELECT 'gate'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM gate_perp_funding_rate
UNION ALL
SELECT 'bitget'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM bitget_perp_funding_rate
UNION ALL
SELECT 'dydx'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM dydx_perp_funding_rate
UNION ALL
SELECT 'aster'::text, venue_symbol, normalise_perp_base(base::text), funding_time, funding_rate
FROM aster_perp_funding_rate;
