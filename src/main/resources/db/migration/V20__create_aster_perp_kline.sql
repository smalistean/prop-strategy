-- Aster daily candles, same shape as bybit_perp_kline/dydx_perp_kline, needed to enforce the $500k
-- weekly liquidity floor on Aster the way analysis-venue-sets.sql already does for the other four
-- venues - without this, Aster's funding history (V19) cannot be validated against real tradeable
-- volume, and an unfiltered comparison is exactly the wrong-answer failure mode dYdX already
-- produced once (XVF_V1_SCOPE.md: 82.2% untradeable legs looked like the best venue, unfiltered).
CREATE TABLE aster_perp_kline (
    venue_symbol  VARCHAR(120)    NOT NULL,
    base          VARCHAR(30)     NOT NULL,
    interval      VARCHAR(10)     NOT NULL,
    open_time     TIMESTAMPTZ     NOT NULL,
    open_price    NUMERIC(30,12)  NOT NULL,
    high_price    NUMERIC(30,12)  NOT NULL,
    low_price     NUMERIC(30,12)  NOT NULL,
    close_price   NUMERIC(30,12)  NOT NULL,
    mid_price     NUMERIC(30,12),
    base_volume   NUMERIC(38,12)  NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT aster_perp_kline_pk PRIMARY KEY (venue_symbol, interval, open_time)
);
CREATE INDEX aster_perp_kline_base_idx ON aster_perp_kline (base, open_time);
