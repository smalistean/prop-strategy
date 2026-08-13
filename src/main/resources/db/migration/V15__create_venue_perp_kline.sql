-- Daily candles for Bybit and dYdX, to charge BASIS DRIFT on the multi-venue funding spread.
--
-- The cross-venue trade is long a coin on one venue and short it on another, so price exposure
-- cancels only insofar as the two venues' marks move together. The four-venue funding measurement
-- reached a Sharpe-like 4.11 with this term entirely unmeasured, and the extreme spreads concentrate
-- on dydx/bybit pairs - the thinnest markets, where marks diverge most. An unmeasured cost sitting
-- exactly where the apparent edge lives is not a result to act on.
--
-- Daily, matching hyperliquid_perp_kline and CarryHarvestApplication, because the hold is one week.
--
-- mid_price exists because dYdX publishes orderbookMidPriceClose alongside the traded close. On a
-- thin market a last-trade close can sit far from the book, which would show up as basis "drift"
-- that no one could have traded. It is nullable: Bybit publishes no equivalent.
CREATE TABLE bybit_perp_kline (
    venue_symbol VARCHAR(40)     NOT NULL,
    base         VARCHAR(30)     NOT NULL,
    interval     VARCHAR(10)     NOT NULL,
    open_time    TIMESTAMPTZ     NOT NULL,
    open_price   NUMERIC(30,12)  NOT NULL,
    high_price   NUMERIC(30,12)  NOT NULL,
    low_price    NUMERIC(30,12)  NOT NULL,
    close_price  NUMERIC(30,12)  NOT NULL,
    mid_price    NUMERIC(30,12),
    base_volume  NUMERIC(38,12)  NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT bybit_perp_kline_pk PRIMARY KEY (venue_symbol, interval, open_time)
);
CREATE INDEX bybit_perp_kline_base_idx ON bybit_perp_kline (base, open_time);

CREATE TABLE dydx_perp_kline (
    venue_symbol VARCHAR(40)     NOT NULL,
    base         VARCHAR(30)     NOT NULL,
    interval     VARCHAR(10)     NOT NULL,
    open_time    TIMESTAMPTZ     NOT NULL,
    open_price   NUMERIC(30,12)  NOT NULL,
    high_price   NUMERIC(30,12)  NOT NULL,
    low_price    NUMERIC(30,12)  NOT NULL,
    close_price  NUMERIC(30,12)  NOT NULL,
    mid_price    NUMERIC(30,12),
    base_volume  NUMERIC(38,12)  NOT NULL,
    created_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT dydx_perp_kline_pk PRIMARY KEY (venue_symbol, interval, open_time)
);
CREATE INDEX dydx_perp_kline_base_idx ON dydx_perp_kline (base, open_time);
