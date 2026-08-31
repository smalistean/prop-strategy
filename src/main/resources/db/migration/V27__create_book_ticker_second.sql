-- Best bid/ask, aggregated to one row per symbol-second.
--
-- Binance stopped publishing bookTicker to its public archive after 2024-03-30, and the bookDepth
-- files that replaced it report cumulative depth only at +/-0.20% and wider - 20 bp away from mid,
-- against a BTCUSDC spread whose median is 0.044 bp. That is 400x too coarse to say anything about
-- the touch, so the only way to observe current best bid/ask is to record it live.
--
-- One row per second rather than per update: BTCUSDC alone produced 7.16M quote updates on
-- 2024-03-15 (~83/s), and the strategies this feeds decide on minute-scale signals and hold for
-- minutes. Per-second extremes preserve what a resting order would have experienced - whether the
-- touch ever traded through a given price - without storing 600M rows a week.
CREATE TABLE binance_book_ticker_second (
    symbol           VARCHAR(20)              NOT NULL,
    second_time      TIMESTAMP WITH TIME ZONE NOT NULL,
    updates          INTEGER                  NOT NULL,
    open_bid         NUMERIC(30,12)           NOT NULL,
    open_ask         NUMERIC(30,12)           NOT NULL,
    close_bid        NUMERIC(30,12)           NOT NULL,
    close_ask        NUMERIC(30,12)           NOT NULL,
    -- Extremes are what decide a passive fill: a bid resting at P is reachable only if the best
    -- bid fell to P or below, which close_bid alone would miss inside a busy second.
    min_bid          NUMERIC(30,12)           NOT NULL,
    max_bid          NUMERIC(30,12)           NOT NULL,
    min_ask          NUMERIC(30,12)           NOT NULL,
    max_ask          NUMERIC(30,12)           NOT NULL,
    close_bid_qty    NUMERIC(38,12)           NOT NULL,
    close_ask_qty    NUMERIC(38,12)           NOT NULL,
    -- Time-weighting is not available from a per-second rollup, so the mean over updates in the
    -- second is stored explicitly rather than reconstructed from open/close later.
    mean_spread_bps  NUMERIC(18,8)            NOT NULL,
    min_spread_bps   NUMERIC(18,8)            NOT NULL,
    max_spread_bps   NUMERIC(18,8)            NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, second_time)
);

CREATE INDEX idx_book_ticker_second_time ON binance_book_ticker_second (second_time);
