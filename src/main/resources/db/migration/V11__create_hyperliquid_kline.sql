-- Hyperliquid perpetual candles, in their own table alongside hyperliquid_funding_rate.
--
-- Purpose: measure BASIS DRIFT for the cross-venue funding trade. That trade is long a coin on one
-- venue and short the same coin on the other, so price exposure cancels only to the extent the two
-- venues' marks track each other. Hyperliquid prices from an oracle; Binance from its own index.
-- Where they diverge, the "hedged" position takes a real loss or gain that funding alone does not
-- show.
--
-- This is the single largest unmeasured term in that strategy. The funding side measures well - the
-- spread persists at 0.463 lag-1 autocorrelation and every year from 2023 to 2026 is positive
-- entering above a 20% annualised spread - but all of it is quoted before basis. For comparison, the
-- same-asset spot carry charged basis drift explicitly and it came to -0.1% annualised; two venues
-- are not the same asset in the same sense, and the divergence is likeliest exactly when funding is
-- extreme.
--
-- Volume is BASE volume: Hyperliquid's candle carries no quote volume, unlike futures_kline's
-- quote_asset_volume. A liquidity filter must multiply by price rather than assume the columns match.
CREATE TABLE hyperliquid_kline (
    coin         VARCHAR(30)     NOT NULL,
    interval     VARCHAR(10)     NOT NULL,
    open_time    TIMESTAMPTZ     NOT NULL,
    close_time   TIMESTAMPTZ     NOT NULL,
    open_price   NUMERIC(30,12)  NOT NULL,
    high_price   NUMERIC(30,12)  NOT NULL,
    low_price    NUMERIC(30,12)  NOT NULL,
    close_price  NUMERIC(30,12)  NOT NULL,
    base_volume  NUMERIC(38,12)  NOT NULL,
    trade_count  INTEGER         NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT hyperliquid_kline_pk PRIMARY KEY (coin, interval, open_time)
);

-- Basis work joins Hyperliquid to Binance by time across many coins at once, which the primary key
-- ordering (coin first) does not serve.
CREATE INDEX hyperliquid_kline_time_idx ON hyperliquid_kline (open_time);
