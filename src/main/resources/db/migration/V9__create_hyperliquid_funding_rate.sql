-- Hyperliquid perpetual funding, in its own table rather than as rows in futures_funding_rate.
--
-- Same reasoning as V8 separated spot from perpetuals: a venue column on the Binance table would
-- make every existing query silently wrong unless it remembered `AND venue = 'BINANCE'`, and
-- CarryHarvestApplication, CrossSectionalMomentumApplication and the funding analyses all read that
-- table today without such a filter. A separate table makes cross-venue contamination
-- unrepresentable rather than merely avoidable.
--
-- Two structural differences from Binance funding, which is why the schema is not a copy:
--   * Hyperliquid pays EVERY HOUR. Binance pays every 8h on most symbols and every 4h on 443 of
--     them. Any cross-venue comparison must annualise per venue rather than assume a shared
--     interval, so the interval is implicit in the row spacing here and must not be inferred.
--   * The API exposes `premium` alongside the realised rate. Hyperliquid's funding is the premium
--     plus a fixed interest-rate component, which pins 127 of 232 coins near +11% annualised in
--     quiet markets. Keeping the premium separately is what allows the pinned state to be identified
--     rather than mistaken for a live signal.
--
-- Coins are identified by Hyperliquid's own name ("BTC", "kPEPE"), not a Binance symbol. Mapping to
-- a Binance symbol is a research-time decision and is deliberately not baked into storage.
CREATE TABLE hyperliquid_funding_rate (
    coin            VARCHAR(30)     NOT NULL,
    funding_time    TIMESTAMPTZ     NOT NULL,
    funding_rate    NUMERIC(20,12)  NOT NULL,
    premium         NUMERIC(20,12),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT hyperliquid_funding_rate_pk PRIMARY KEY (coin, funding_time)
);

-- Cross-venue work slices by time across many coins at once, which the primary-key ordering
-- (coin first) does not support.
CREATE INDEX hyperliquid_funding_rate_time_idx ON hyperliquid_funding_rate (funding_time);
