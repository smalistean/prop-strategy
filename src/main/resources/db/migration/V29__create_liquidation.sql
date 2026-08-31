-- Forced liquidations from Binance's all-market forceOrder stream.
--
-- Binance publishes no liquidation history at all - the futures archive carries aggTrades,
-- bookDepth, bookTicker, klines, indexPriceKlines, markPriceKlines, metrics, premiumIndexKlines
-- and trades, and nothing else. Unlike every other series in this database, a gap here can never
-- be backfilled, which is the whole reason for recording it before there is a use for it.
--
-- Rows are individual events rather than a rollup: the stream is throttled to roughly one order
-- per second per symbol, so volume is low enough to keep in full, and a cascade's shape over
-- seconds is the part worth having.
--
-- KNOWN LIMITATION, recorded here so no later analysis mistakes it for completeness: that same
-- throttle means totals are UNDERSTATED exactly during cascades, when many liquidations occur
-- inside one second. Counts and notionals from this table are lower bounds, not measurements.
-- Timing is reliable; magnitude is not.
CREATE TABLE binance_liquidation (
    id                 BIGSERIAL                PRIMARY KEY,
    symbol             VARCHAR(30)              NOT NULL,
    event_time         TIMESTAMP WITH TIME ZONE NOT NULL,
    trade_time         TIMESTAMP WITH TIME ZONE NOT NULL,
    -- SELL means a long was force-closed; BUY means a short was. The side is the liquidating
    -- order's direction, which is the opposite of the position that was held.
    side               VARCHAR(8)               NOT NULL,
    order_type         VARCHAR(20)              NOT NULL,
    time_in_force      VARCHAR(10)              NOT NULL,
    order_status       VARCHAR(20)              NOT NULL,
    quantity           NUMERIC(38,12)           NOT NULL,
    price              NUMERIC(30,12)           NOT NULL,
    average_price      NUMERIC(30,12)           NOT NULL,
    last_filled_qty    NUMERIC(38,12)           NOT NULL,
    filled_accum_qty   NUMERIC(38,12)           NOT NULL,
    notional           NUMERIC(38,12)           NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- A reconnect can re-deliver the frame that was in flight when the socket dropped, so the same
-- liquidation must not be stored twice.
CREATE UNIQUE INDEX uq_liquidation_event
    ON binance_liquidation (symbol, trade_time, side, price, quantity, filled_accum_qty);
CREATE INDEX idx_liquidation_time ON binance_liquidation (trade_time);
CREATE INDEX idx_liquidation_symbol_time ON binance_liquidation (symbol, trade_time);
