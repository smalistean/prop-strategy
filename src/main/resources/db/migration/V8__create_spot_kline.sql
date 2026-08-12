-- Spot klines, kept in their own table rather than alongside perpetuals.
--
-- Spot data was first loaded into futures_kline under a "BTCUSDT.S" symbol suffix. That was wrong on
-- two counts. The table is named for futures, so spot rows there are simply mislabelled data; and
-- separation depended on every query remembering `AND symbol NOT LIKE '%.S'`. Within an hour of the
-- suffix existing, CrossSectionalMomentumApplication was already reading it - a coin's spot series
-- would have ranked as an independent asset against its own perpetual, double-counting every pair,
-- with no error raised and plausible-looking output.
--
-- A separate table makes that class of mistake unrepresentable instead of merely avoidable: a query
-- against futures_kline cannot accidentally include spot, because spot is not there.
--
-- Schema deliberately mirrors futures_kline so the same parsing and upsert logic serves both.
CREATE TABLE spot_kline (
    symbol                  VARCHAR(30)     NOT NULL,
    interval                VARCHAR(10)     NOT NULL,
    open_time               TIMESTAMPTZ     NOT NULL,
    open_price              NUMERIC(30,12)  NOT NULL,
    high_price              NUMERIC(30,12)  NOT NULL,
    low_price               NUMERIC(30,12)  NOT NULL,
    close_price             NUMERIC(30,12)  NOT NULL,
    volume                  NUMERIC(38,12)  NOT NULL,
    close_time              TIMESTAMPTZ     NOT NULL,
    quote_asset_volume      NUMERIC(38,12)  NOT NULL,
    trade_count             INTEGER         NOT NULL,
    taker_buy_base_volume   NUMERIC(38,12)  NOT NULL,
    taker_buy_quote_volume  NUMERIC(38,12)  NOT NULL,
    created_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT spot_kline_pk PRIMARY KEY (symbol, interval, open_time)
);

-- Move whatever the suffixed import already wrote, stripping the suffix as it goes, then remove it
-- from the futures table so no ".S" symbol survives anywhere.
INSERT INTO spot_kline (symbol, interval, open_time, open_price, high_price, low_price, close_price,
                        volume, close_time, quote_asset_volume, trade_count,
                        taker_buy_base_volume, taker_buy_quote_volume)
SELECT left(symbol, length(symbol) - 2), interval, open_time, open_price, high_price, low_price,
       close_price, volume, close_time, quote_asset_volume, trade_count,
       taker_buy_base_volume, taker_buy_quote_volume
FROM futures_kline
WHERE symbol LIKE '%.S'
ON CONFLICT (symbol, interval, open_time) DO NOTHING;

DELETE FROM futures_kline WHERE symbol LIKE '%.S';

-- The carry test joins spot to perp by symbol and time, so this supports the join direction that
-- the primary-key ordering does not.
CREATE INDEX spot_kline_time_idx ON spot_kline (open_time);
