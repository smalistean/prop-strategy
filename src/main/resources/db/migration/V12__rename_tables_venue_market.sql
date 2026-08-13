-- Renames every market-data table to <venue>_<market>_<datatype>.
--
-- Done before importing Bybit, OKX, Gate, Bitget and dYdX, because the current names only work while
-- there is exactly one exchange. "futures_kline" says nothing about which venue produced it, and the
-- entire research direction is now comparing the same coin across seven venues - a schema where the
-- venue is implicit makes the one distinction that matters invisible.
--
-- "perp" rather than "futures": verified, not assumed. Zero symbols in futures_kline or
-- futures_funding_rate carry a dated-contract form (underscore or trailing YYMMDD); every row is a
-- perpetual, quoted in USDT or USDC. Binance calls the product "USD-M Futures", but what was actually
-- imported is perpetuals only, and the table should say what it holds. Naming it for the venue's
-- product line would also make binance_futures_* sit beside okx_swap_* and bybit_linear_*, where
-- three names would mean the same thing - unusable for cross-venue joins.
--
-- deribit_option_quote is already venue-and-market explicit and is left alone.
--
-- Renames are metadata-only in PostgreSQL, so the 16 GB and 14 GB tables move instantly.

ALTER TABLE futures_kline                   RENAME TO binance_perp_kline;
ALTER TABLE spot_kline                      RENAME TO binance_spot_kline;
ALTER TABLE futures_funding_rate            RENAME TO binance_perp_funding_rate;
ALTER TABLE futures_agg_trade_minute        RENAME TO binance_perp_agg_trade_minute;
ALTER TABLE futures_agg_trade_import        RENAME TO binance_perp_agg_trade_import;
ALTER TABLE futures_metric_snapshot         RENAME TO binance_perp_metric_snapshot;
ALTER TABLE futures_metric_import           RENAME TO binance_perp_metric_import;
ALTER TABLE futures_open_interest_statistic RENAME TO binance_perp_open_interest_statistic;
ALTER TABLE futures_trader_ratio            RENAME TO binance_perp_trader_ratio;
ALTER TABLE futures_volume_profile_bin      RENAME TO binance_perp_volume_profile_bin;
ALTER TABLE futures_volume_profile_import   RENAME TO binance_perp_volume_profile_import;

ALTER TABLE hyperliquid_funding_rate        RENAME TO hyperliquid_perp_funding_rate;
ALTER TABLE hyperliquid_kline               RENAME TO hyperliquid_perp_kline;

-- Constraints and indexes keep their original names through a table rename. Renaming them too keeps
-- an error message or an EXPLAIN plan pointing at a name that still exists.
ALTER INDEX IF EXISTS futures_kline_pk               RENAME TO binance_perp_kline_pk;
ALTER INDEX IF EXISTS spot_kline_pk                  RENAME TO binance_spot_kline_pk;
ALTER INDEX IF EXISTS spot_kline_time_idx            RENAME TO binance_spot_kline_time_idx;
ALTER INDEX IF EXISTS hyperliquid_funding_rate_pk    RENAME TO hyperliquid_perp_funding_rate_pk;
ALTER INDEX IF EXISTS hyperliquid_funding_rate_time_idx
                                                     RENAME TO hyperliquid_perp_funding_rate_time_idx;
ALTER INDEX IF EXISTS hyperliquid_kline_pk           RENAME TO hyperliquid_perp_kline_pk;
ALTER INDEX IF EXISTS hyperliquid_kline_time_idx     RENAME TO hyperliquid_perp_kline_time_idx;

-- New venues follow the same shape: bybit_perp_funding_rate, okx_perp_funding_rate,
-- gate_perp_funding_rate, bitget_perp_funding_rate, dydx_perp_funding_rate.
