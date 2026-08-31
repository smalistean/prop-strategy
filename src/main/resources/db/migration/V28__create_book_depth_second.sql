-- Top-of-book queue sizes, aggregated to one row per symbol-second.
--
-- Separate from binance_book_ticker_second because it answers a different question. bookTicker says
-- where the touch is and how much sits on it; this says how the queue behind the touch is shaped,
-- which is what decides whether a passive order placed now is reached before it is cancelled.
--
-- Sourced from the depth20@100ms stream, so it covers twenty levels per side and no further. On
-- BTCUSDC at one-tick granularity twenty levels span roughly 0.26 bp - narrow in price terms, but
-- that is precisely the region a resting order lives in, and the archive has nothing closer than
-- 20 bp. Wider bands would need the full diff stream and a locally maintained book; they are not
-- attempted here rather than being stored as a misleading partial sum.
CREATE TABLE binance_book_depth_second (
    symbol             VARCHAR(20)              NOT NULL,
    second_time        TIMESTAMP WITH TIME ZONE NOT NULL,
    snapshots          INTEGER                  NOT NULL,
    -- Queue at the touch: the quantity an order joining now would have to wait behind.
    mean_bid_qty_1     NUMERIC(38,12)           NOT NULL,
    mean_ask_qty_1     NUMERIC(38,12)           NOT NULL,
    min_bid_qty_1      NUMERIC(38,12)           NOT NULL,
    min_ask_qty_1      NUMERIC(38,12)           NOT NULL,
    -- Cumulative across the twenty visible levels, in quote currency.
    mean_bid_notional  NUMERIC(38,12)           NOT NULL,
    mean_ask_notional  NUMERIC(38,12)           NOT NULL,
    -- How far twenty levels actually reach, so the coverage above is never assumed.
    mean_bid_span_bps  NUMERIC(18,8)            NOT NULL,
    mean_ask_span_bps  NUMERIC(18,8)            NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, second_time)
);

CREATE INDEX idx_book_depth_second_time ON binance_book_depth_second (second_time);
