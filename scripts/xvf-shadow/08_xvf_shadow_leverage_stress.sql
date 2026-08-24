-- Diagnostic leverage stress for the frozen shadow-selected book. It recalculates depth at scaled
-- size and reports reserve breaches; it does not reselect around a breach or model liquidation.

WITH leverage_grid(leverage, reserve_ratio) AS (
    VALUES (1.00::numeric, .25::numeric), (1.25, .25), (1.50, .25), (2.00, .25)
), scaled AS (
    SELECT o.*, g.leverage, g.reserve_ratio,
           o.leg_notional_usd * g.leverage AS scaled_leg_notional_usd,
           (o.score_components ->> 'makerTouch')::numeric AS maker_touch_raw,
           CASE WHEN o.maker_venue = o.short_venue
                THEN (o.score_components ->> 'makerTouch')::numeric
                ELSE xvf_shadow_depth_vwap(o.short_leg_snapshot, 'bids',
                         o.leg_notional_usd * g.leverage) END / o.short_multiplier
               AS scaled_short_entry,
           CASE WHEN o.maker_venue = o.long_venue
                THEN (o.score_components ->> 'makerTouch')::numeric
                ELSE xvf_shadow_depth_vwap(o.long_leg_snapshot, 'asks',
                         o.leg_notional_usd * g.leverage) END / o.long_multiplier
               AS scaled_long_entry,
           xvf_shadow_depth_vwap(o.short_exit_snapshot, 'asks',
                   o.leg_notional_usd * g.leverage) / o.short_multiplier AS scaled_short_exit,
           xvf_shadow_depth_vwap(o.long_exit_snapshot, 'bids',
                   o.leg_notional_usd * g.leverage) / o.long_multiplier AS scaled_long_exit,
           (o.configuration_snapshot #>> ARRAY['venueCapitalUsd', 'binance'])::numeric
               AS binance_capital_usd,
           (o.configuration_snapshot #>> ARRAY['venueCapitalUsd', 'bybit'])::numeric
               AS bybit_capital_usd,
           (o.configuration_snapshot #>> ARRAY['venueCapitalUsd', 'hyperliquid'])::numeric
               AS hyperliquid_capital_usd
    FROM xvf_shadow_candidate_outcome_v1 o
    CROSS JOIN leverage_grid g
    WHERE o.shadow_book_rank IS NOT NULL
), candidate_results AS (
    SELECT s.*,
           scaled_short_entry IS NULL OR scaled_long_entry IS NULL
             OR scaled_short_exit IS NULL OR scaled_long_exit IS NULL AS depth_missing,
           CASE WHEN scaled_short_entry IS NOT NULL AND scaled_long_entry IS NOT NULL
                  AND scaled_short_exit IS NOT NULL AND scaled_long_exit IS NOT NULL
                THEN scaled_leg_notional_usd / scaled_short_entry
                       * (scaled_short_entry - scaled_short_exit)
                   + scaled_leg_notional_usd / scaled_long_entry
                       * (scaled_long_exit - scaled_long_entry) END AS scaled_basis_pnl_usd,
           scaled_leg_notional_usd * (short_rate_sum - long_rate_sum)
               AS scaled_funding_usd,
           scaled_leg_notional_usd * (expected_entry_fee_bps + expected_exit_fee_bps) / 10000
               AS scaled_fees_usd,
           scaled_leg_notional_usd / leverage AS initial_margin_per_leg_usd,
           greatest(coalesce(scaled_short_exit / scaled_short_entry - 1, 0),
                    coalesce(1 - scaled_long_exit / scaled_long_entry, 0), 0)
               AS observed_adverse_leg_move
    FROM scaled s
), cycles AS (
    SELECT leverage, reserve_ratio, signal_run_id,
           min(scheduled_decision_at) AS scheduled_decision_at,
           max(capital_usd) AS capital_usd,
           max(binance_capital_usd) AS binance_capital_usd,
           max(bybit_capital_usd) AS bybit_capital_usd,
           max(hyperliquid_capital_usd) AS hyperliquid_capital_usd,
           count(*) AS selected_pairs,
           count(*) FILTER (WHERE depth_missing) AS depth_missing_pairs,
           sum(initial_margin_per_leg_usd) FILTER (
               WHERE 'binance' IN (short_venue, long_venue)) AS binance_initial_margin_usd,
           sum(initial_margin_per_leg_usd) FILTER (
               WHERE 'bybit' IN (short_venue, long_venue)) AS bybit_initial_margin_usd,
           sum(initial_margin_per_leg_usd) FILTER (
               WHERE 'hyperliquid' IN (short_venue, long_venue)) AS hyperliquid_initial_margin_usd,
           sum(scaled_funding_usd) FILTER (WHERE NOT depth_missing) AS scaled_funding_usd,
           sum(scaled_basis_pnl_usd) FILTER (WHERE NOT depth_missing) AS scaled_basis_pnl_usd,
           sum(scaled_fees_usd) FILTER (WHERE NOT depth_missing) AS scaled_fees_usd,
           sum(scaled_funding_usd + scaled_basis_pnl_usd - scaled_fees_usd)
               FILTER (WHERE NOT depth_missing) AS scaled_net_usd,
           max(observed_adverse_leg_move) AS maximum_observed_adverse_leg_move
    FROM candidate_results
    GROUP BY leverage, reserve_ratio, signal_run_id
), reserve_checks AS (
    SELECT c.*,
           coalesce(binance_initial_margin_usd, 0)
               > coalesce(binance_capital_usd, capital_usd / 3) * (1 - reserve_ratio)
             OR coalesce(bybit_initial_margin_usd, 0)
               > coalesce(bybit_capital_usd, capital_usd / 3) * (1 - reserve_ratio)
             OR coalesce(hyperliquid_initial_margin_usd, 0)
               > coalesce(hyperliquid_capital_usd, capital_usd / 3) * (1 - reserve_ratio)
               AS reserve_breach
    FROM cycles c
), path AS (
    SELECT r.*,
           sum(coalesce(scaled_net_usd, 0)) OVER (
               PARTITION BY leverage ORDER BY scheduled_decision_at, signal_run_id) AS cumulative_net
    FROM reserve_checks r
), drawdowns AS (
    SELECT p.*,
           max(cumulative_net) OVER (
               PARTITION BY leverage ORDER BY scheduled_decision_at, signal_run_id
               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) - cumulative_net AS drawdown_usd
    FROM path p
)
SELECT leverage, min(reserve_ratio) AS reserve_ratio,
       count(*) AS measured_cycles,
       sum(selected_pairs) AS selected_pair_outcomes,
       sum(depth_missing_pairs) AS depth_missing_pairs,
       count(*) FILTER (WHERE reserve_breach) AS reserve_breach_cycles,
       max(binance_initial_margin_usd
           / NULLIF(coalesce(binance_capital_usd, capital_usd / 3), 0)) AS peak_binance_margin_ratio,
       max(bybit_initial_margin_usd
           / NULLIF(coalesce(bybit_capital_usd, capital_usd / 3), 0)) AS peak_bybit_margin_ratio,
       max(hyperliquid_initial_margin_usd
           / NULLIF(coalesce(hyperliquid_capital_usd, capital_usd / 3), 0))
           AS peak_hyperliquid_margin_ratio,
       sum(scaled_funding_usd) AS scaled_funding_usd,
       sum(scaled_basis_pnl_usd) AS scaled_basis_pnl_usd,
       sum(scaled_fees_usd) AS scaled_fees_usd,
       sum(scaled_net_usd) AS scaled_net_usd,
       sum(scaled_net_usd) / NULLIF(sum(capital_usd), 0) AS return_on_cycle_capital,
       max(drawdown_usd) AS independent_cycle_sequence_drawdown_usd,
       max(maximum_observed_adverse_leg_move) AS maximum_observed_adverse_leg_move,
       1 / leverage AS zero_maintenance_margin_liquidation_reference_move,
       'FROZEN_SHADOW_BOOK_DIAGNOSTIC_NO_RESELECTION_AFTER_RESERVE_OR_DEPTH_FAILURE'::text
           AS selection_model,
       'SCALED_NOTIONAL_DIVIDED_BY_SCENARIO_LEVERAGE'::text AS initial_margin_model,
       'NOT_CALCULATED_MAINTENANCE_MARGIN_TIERS_UNAVAILABLE'::text AS liquidation_model,
       'OVERLAPPING_72H_CYCLES_NOT_A_STATEFUL_EQUITY_CURVE'::text AS drawdown_model
FROM drawdowns
GROUP BY leverage
ORDER BY leverage;
