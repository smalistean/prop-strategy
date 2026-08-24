-- Routing sensitivity. A missed maker is cash (zero P&L); no unobserved fallback price is invented.

WITH route_options AS (
    SELECT o.*, route.value ->> 'makerVenue' AS route_maker_venue,
           route.value ->> 'takerVenue' AS route_taker_venue,
           (route.value ->> 'makerTouch')::numeric AS maker_touch_raw,
           (route.value ->> 'takerVwap')::numeric AS taker_vwap_raw,
           (route.value ->> 'expectedEntryFeeBps')::numeric AS route_entry_fee_bps,
           (route.value ->> 'expectedSlippageBps')::numeric AS route_slippage_bps,
           row_number() OVER (
               PARTITION BY o.signal_run_id, o.evaluation_order
               ORDER BY (route.value ->> 'expectedEntryFeeBps')::numeric
                      + (route.value ->> 'expectedSlippageBps')::numeric,
                        route.value ->> 'makerVenue') AS execution_cost_rank
    FROM xvf_shadow_candidate_outcome_v1 o
    CROSS JOIN LATERAL jsonb_array_elements(o.score_components -> 'routeEvaluations') route(value)
    WHERE (route.value ->> 'feasible')::boolean
), selected_routes AS (
    SELECT 'CURRENT_ROUTING'::text AS scenario, r.*
    FROM route_options r
    WHERE r.route_maker_venue = r.maker_venue
    UNION ALL
    SELECT 'BYBIT_MAKER_WHEN_PRESENT', r.*
    FROM route_options r
    WHERE r.route_maker_venue = CASE
        WHEN 'bybit' IN (r.short_venue, r.long_venue) THEN 'bybit' ELSE r.maker_venue END
    UNION ALL
    SELECT 'CHEAPEST_FEASIBLE_EXECUTION', r.*
    FROM route_options r
    WHERE r.execution_cost_rank = 1
), normalized_routes AS (
    SELECT s.*,
           CASE WHEN route_maker_venue = short_venue THEN maker_touch_raw ELSE taker_vwap_raw END
               / short_multiplier AS scenario_short_entry,
           CASE WHEN route_maker_venue = long_venue THEN maker_touch_raw ELSE taker_vwap_raw END
               / long_multiplier AS scenario_long_entry
    FROM selected_routes s
), maker_results AS (
    SELECT scenario, signal_run_id, evaluation_order, base, short_venue, long_venue,
           route_maker_venue, route_taker_venue, leg_notional_usd,
           realized_funding_usd,
           leg_notional_usd / scenario_short_entry
             * (scenario_short_entry - short_exit_normalized)
           + leg_notional_usd / scenario_long_entry
             * (long_exit_normalized - scenario_long_entry) AS scenario_basis_pnl_usd,
           leg_notional_usd * (route_entry_fee_bps + expected_exit_fee_bps) / 10000
               AS scenario_fees_usd
    FROM normalized_routes
), maker_fill_grid(fill_probability) AS (
    VALUES (1.00::numeric), (0.75), (0.50), (0.25)
), maker_scenarios AS (
    SELECT m.scenario, g.fill_probability, m.signal_run_id, m.evaluation_order,
           m.realized_funding_usd * g.fill_probability AS realized_funding_usd,
           m.scenario_basis_pnl_usd * g.fill_probability AS basis_price_pnl_usd,
           m.scenario_fees_usd * g.fill_probability AS assumed_fees_usd,
           (m.realized_funding_usd + m.scenario_basis_pnl_usd - m.scenario_fees_usd)
             * g.fill_probability AS scenario_net_usd,
           false AS entry_depth_missing,
           'MAKER_MISS_STAYS_CASH_NO_FALLBACK_PRICE_ASSUMED'::text AS fill_model
    FROM maker_results m
    CROSS JOIN maker_fill_grid g
), all_taker_entries AS (
    SELECT o.*,
           xvf_shadow_depth_vwap(short_leg_snapshot, 'bids', leg_notional_usd)
               / short_multiplier AS all_taker_short_entry,
           xvf_shadow_depth_vwap(long_leg_snapshot, 'asks', leg_notional_usd)
               / long_multiplier AS all_taker_long_entry,
           (configuration_snapshot #>> ARRAY['feeSchedules', short_venue, 'takerBps'])::numeric
             + (configuration_snapshot #>> ARRAY['feeSchedules', long_venue, 'takerBps'])::numeric
               AS all_taker_entry_fee_bps
    FROM xvf_shadow_candidate_outcome_v1 o
), all_taker_scenarios AS (
    SELECT 'ALL_TAKER'::text AS scenario, 1::numeric AS fill_probability,
           signal_run_id, evaluation_order, realized_funding_usd,
           CASE WHEN all_taker_short_entry IS NOT NULL AND all_taker_long_entry IS NOT NULL
                THEN leg_notional_usd / all_taker_short_entry
                       * (all_taker_short_entry - short_exit_normalized)
                   + leg_notional_usd / all_taker_long_entry
                       * (long_exit_normalized - all_taker_long_entry) END AS basis_price_pnl_usd,
           leg_notional_usd * (all_taker_entry_fee_bps + expected_exit_fee_bps) / 10000
               AS assumed_fees_usd,
           CASE WHEN all_taker_short_entry IS NOT NULL AND all_taker_long_entry IS NOT NULL
                THEN realized_funding_usd
                   + leg_notional_usd / all_taker_short_entry
                       * (all_taker_short_entry - short_exit_normalized)
                   + leg_notional_usd / all_taker_long_entry
                       * (long_exit_normalized - all_taker_long_entry)
                   - leg_notional_usd * (all_taker_entry_fee_bps + expected_exit_fee_bps) / 10000
                END AS scenario_net_usd,
           all_taker_short_entry IS NULL OR all_taker_long_entry IS NULL AS entry_depth_missing,
           'BOTH_ENTRY_LEGS_DEPTH_VWAP'::text AS fill_model
    FROM all_taker_entries
), scenarios AS (
    SELECT * FROM maker_scenarios
    UNION ALL
    SELECT * FROM all_taker_scenarios
)
SELECT scenario, fill_probability,
       count(*) AS candidate_outcomes,
       count(*) FILTER (WHERE entry_depth_missing) AS entry_depth_missing,
       sum(realized_funding_usd) AS realized_funding_usd,
       sum(basis_price_pnl_usd) AS basis_price_pnl_usd,
       sum(assumed_fees_usd) AS assumed_fees_usd,
       sum(scenario_net_usd) AS scenario_net_usd,
       avg(scenario_net_usd) AS average_candidate_net_usd,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY scenario_net_usd) AS median_candidate_net_usd,
       min(fill_model) AS fill_model,
       'SETTLED_FUNDING_AND_EXIT_BOOK_HELD_FIXED'::text AS outcome_control,
       'NO_TRADE_THROUGH_DATA_AVAILABLE'::text AS trade_through_model
FROM scenarios
GROUP BY scenario, fill_probability
ORDER BY scenario, fill_probability DESC;
