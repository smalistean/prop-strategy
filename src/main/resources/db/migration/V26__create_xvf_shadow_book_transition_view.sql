-- Stateful SQL book derived from consecutive immutable decision runs.
-- It changes no execution state and never writes back to the decision ledger.

CREATE VIEW xvf_shadow_book_transition_v1 AS
WITH runs AS (
    SELECT r.signal_run_id, r.scheduled_decision_at, r.generated_at, r.capital_usd,
           r.configuration_snapshot
    FROM xvf_signal_run r
    WHERE r.capture_status IN ('COMPLETE', 'PARTIAL')
), policy_cycles AS (
    SELECT p.policy, r.*,
           row_number() OVER (
               PARTITION BY p.policy
               ORDER BY r.scheduled_decision_at, r.generated_at, r.signal_run_id) AS cycle_number,
           lag(r.signal_run_id) OVER (
               PARTITION BY p.policy
               ORDER BY r.scheduled_decision_at, r.generated_at, r.signal_run_id)
               AS previous_signal_run_id,
           lag(r.scheduled_decision_at) OVER (
               PARTITION BY p.policy
               ORDER BY r.scheduled_decision_at, r.generated_at, r.signal_run_id)
               AS previous_scheduled_decision_at,
           count(*) OVER (PARTITION BY p.policy) AS cycle_count
    FROM runs r
    CROSS JOIN (VALUES ('BASELINE_RANKING'::text),
                       ('FEE_BASIS_AWARE_SHADOW'::text)) p(policy)
), selected AS (
    SELECT 'BASELINE_RANKING'::text AS policy, c.signal_run_id, c.evaluation_order,
           c.base, c.short_venue, c.short_venue_symbol, c.long_venue, c.long_venue_symbol,
           c.requested_leg_notional_usd AS leg_notional_usd,
           c.expected_entry_fee_bps, c.expected_exit_fee_bps,
           c.requested_leg_notional_usd * c.expected_net_bps / 10000 AS expected_net_usd,
           o.target_exit_utc, o.expected_net_usd AS measured_expected_net_usd,
           o.assumed_fees_usd AS independent_round_trip_fees_usd,
           o.realized_funding_usd, o.basis_price_pnl_usd, o.realized_net_usd,
           o.captured_within_tolerance
    FROM xvf_signal_candidate c
    LEFT JOIN xvf_shadow_candidate_outcome_v1 o
      ON o.signal_run_id = c.signal_run_id AND o.evaluation_order = c.evaluation_order
    WHERE c.baseline_book_rank IS NOT NULL

    UNION ALL

    SELECT 'FEE_BASIS_AWARE_SHADOW', c.signal_run_id, c.evaluation_order,
           c.base, c.short_venue, c.short_venue_symbol, c.long_venue, c.long_venue_symbol,
           c.requested_leg_notional_usd, c.expected_entry_fee_bps, c.expected_exit_fee_bps,
           c.requested_leg_notional_usd * c.expected_net_bps / 10000,
           o.target_exit_utc, o.expected_net_usd, o.assumed_fees_usd,
           o.realized_funding_usd, o.basis_price_pnl_usd, o.realized_net_usd,
           o.captured_within_tolerance
    FROM xvf_signal_candidate c
    LEFT JOIN xvf_shadow_candidate_outcome_v1 o
      ON o.signal_run_id = c.signal_run_id AND o.evaluation_order = c.evaluation_order
    WHERE c.shadow_book_rank IS NOT NULL
), current_transitions AS (
    SELECT pc.policy, pc.cycle_number, 'DECISION'::text AS transition_phase,
           pc.previous_signal_run_id, pc.signal_run_id AS current_signal_run_id,
           pc.previous_scheduled_decision_at, pc.scheduled_decision_at AS transition_at,
           pc.capital_usd, pc.configuration_snapshot,
           CASE WHEN old.base IS NULL THEN 'OPEN'
                WHEN old.short_venue = new.short_venue
                 AND old.short_venue_symbol = new.short_venue_symbol
                 AND old.long_venue = new.long_venue
                 AND old.long_venue_symbol = new.long_venue_symbol THEN 'RETAIN'
                ELSE 'REVERSE' END::text AS transition_type,
           new.base,
           old.evaluation_order AS old_evaluation_order,
           old.short_venue AS old_short_venue, old.short_venue_symbol AS old_short_venue_symbol,
           old.long_venue AS old_long_venue, old.long_venue_symbol AS old_long_venue_symbol,
           old.leg_notional_usd AS old_leg_notional_usd,
           old.expected_exit_fee_bps AS old_exit_fee_bps,
           new.evaluation_order AS new_evaluation_order,
           new.short_venue AS new_short_venue, new.short_venue_symbol AS new_short_venue_symbol,
           new.long_venue AS new_long_venue, new.long_venue_symbol AS new_long_venue_symbol,
           new.leg_notional_usd AS new_leg_notional_usd,
           new.expected_entry_fee_bps AS new_entry_fee_bps,
           new.expected_exit_fee_bps AS new_exit_fee_bps,
           new.expected_net_usd, new.measured_expected_net_usd,
           new.independent_round_trip_fees_usd,
           new.realized_funding_usd, new.basis_price_pnl_usd, new.realized_net_usd,
           new.captured_within_tolerance
    FROM policy_cycles pc
    JOIN selected new
      ON new.policy = pc.policy AND new.signal_run_id = pc.signal_run_id
    LEFT JOIN selected old
      ON old.policy = pc.policy AND old.signal_run_id = pc.previous_signal_run_id
     AND old.base = new.base
), close_transitions AS (
    SELECT pc.policy, pc.cycle_number, 'DECISION'::text AS transition_phase,
           pc.previous_signal_run_id, pc.signal_run_id AS current_signal_run_id,
           pc.previous_scheduled_decision_at, pc.scheduled_decision_at AS transition_at,
           pc.capital_usd, pc.configuration_snapshot, 'CLOSE'::text AS transition_type,
           old.base, old.evaluation_order AS old_evaluation_order,
           old.short_venue AS old_short_venue, old.short_venue_symbol AS old_short_venue_symbol,
           old.long_venue AS old_long_venue, old.long_venue_symbol AS old_long_venue_symbol,
           old.leg_notional_usd AS old_leg_notional_usd,
           old.expected_exit_fee_bps AS old_exit_fee_bps,
           NULL::integer AS new_evaluation_order,
           NULL::text AS new_short_venue, NULL::text AS new_short_venue_symbol,
           NULL::text AS new_long_venue, NULL::text AS new_long_venue_symbol,
           NULL::numeric AS new_leg_notional_usd, NULL::numeric AS new_entry_fee_bps,
           NULL::numeric AS new_exit_fee_bps,
           NULL::numeric AS expected_net_usd, NULL::numeric AS measured_expected_net_usd,
           NULL::numeric AS independent_round_trip_fees_usd,
           NULL::numeric AS realized_funding_usd, NULL::numeric AS basis_price_pnl_usd,
           NULL::numeric AS realized_net_usd, NULL::boolean AS captured_within_tolerance
    FROM policy_cycles pc
    JOIN selected old
      ON old.policy = pc.policy AND old.signal_run_id = pc.previous_signal_run_id
    LEFT JOIN selected new
      ON new.policy = pc.policy AND new.signal_run_id = pc.signal_run_id
     AND new.base = old.base
    WHERE new.base IS NULL
), terminal_closes AS (
    SELECT pc.policy, pc.cycle_number + 1, 'TERMINAL'::text,
           pc.signal_run_id, NULL::uuid,
           pc.scheduled_decision_at,
           coalesce(max(old.target_exit_utc) OVER (PARTITION BY pc.policy),
                    pc.scheduled_decision_at) AS transition_at,
           pc.capital_usd, pc.configuration_snapshot, 'CLOSE'::text,
           old.base, old.evaluation_order,
           old.short_venue, old.short_venue_symbol, old.long_venue, old.long_venue_symbol,
           old.leg_notional_usd, old.expected_exit_fee_bps,
           NULL::integer, NULL::text, NULL::text, NULL::text, NULL::text,
           NULL::numeric, NULL::numeric, NULL::numeric, NULL::numeric,
           NULL::numeric, NULL::numeric,
           NULL::numeric, NULL::numeric, NULL::numeric, NULL::boolean
    FROM policy_cycles pc
    JOIN selected old ON old.policy = pc.policy AND old.signal_run_id = pc.signal_run_id
    WHERE pc.cycle_number = pc.cycle_count
), transitions AS (
    SELECT * FROM current_transitions
    UNION ALL SELECT * FROM close_transitions
    UNION ALL SELECT * FROM terminal_closes
), charged AS (
    SELECT t.*,
           CASE WHEN transition_type IN ('OPEN', 'REVERSE')
                THEN new_leg_notional_usd * new_entry_fee_bps / 10000 ELSE 0 END
               AS entry_fee_charged_usd,
           CASE WHEN transition_type IN ('CLOSE', 'REVERSE')
                THEN old_leg_notional_usd * old_exit_fee_bps / 10000 ELSE 0 END
               AS exit_fee_charged_usd,
           CASE WHEN new_evaluation_order IS NOT NULL
                THEN realized_funding_usd + basis_price_pnl_usd END AS interval_gross_pnl_usd
    FROM transitions t
), valued AS (
    SELECT c.*,
           entry_fee_charged_usd + exit_fee_charged_usd AS transition_fees_usd,
           CASE WHEN new_evaluation_order IS NULL
                THEN -(entry_fee_charged_usd + exit_fee_charged_usd)
                WHEN interval_gross_pnl_usd IS NOT NULL
                THEN interval_gross_pnl_usd - entry_fee_charged_usd - exit_fee_charged_usd
                END AS stateful_realized_net_usd,
           CASE WHEN new_evaluation_order IS NULL
                THEN -(entry_fee_charged_usd + exit_fee_charged_usd)
                ELSE expected_net_usd + coalesce(independent_round_trip_fees_usd,
                       new_leg_notional_usd
                       * (coalesce(new_entry_fee_bps, 0) + coalesce(new_exit_fee_bps, 0)) / 10000)
                     - entry_fee_charged_usd - exit_fee_charged_usd
                END AS stateful_expected_net_usd,
           coalesce(independent_round_trip_fees_usd, 0)
             - entry_fee_charged_usd - exit_fee_charged_usd
               AS fee_saving_vs_independent_usd,
           2 * coalesce(new_leg_notional_usd, 0)
               * (transition_type IN ('OPEN', 'REVERSE'))::int
             + 2 * coalesce(old_leg_notional_usd, 0)
               * (transition_type IN ('CLOSE', 'REVERSE'))::int AS turnover_usd,
           coalesce((configuration_snapshot #>> '{venueCapitalUsd,binance}')::numeric,
                    capital_usd / 3) AS binance_capital_usd,
           coalesce((configuration_snapshot #>> '{venueCapitalUsd,bybit}')::numeric,
                    capital_usd / 3) AS bybit_capital_usd,
           coalesce((configuration_snapshot #>> '{venueCapitalUsd,hyperliquid}')::numeric,
                    capital_usd / 3) AS hyperliquid_capital_usd
    FROM charged c
), cycle_metrics AS (
    SELECT policy, cycle_number, transition_phase,
           count(*) FILTER (WHERE transition_type = 'OPEN') AS opens,
           count(*) FILTER (WHERE transition_type = 'RETAIN') AS retains,
           count(*) FILTER (WHERE transition_type = 'CLOSE') AS closes,
           count(*) FILTER (WHERE transition_type = 'REVERSE') AS reverses,
           count(*) FILTER (WHERE new_evaluation_order IS NOT NULL) AS selected_pairs_after,
           count(*) FILTER (WHERE new_evaluation_order IS NOT NULL
                              AND realized_net_usd IS NULL) AS missing_outcomes,
           sum(turnover_usd) AS cycle_turnover_usd,
           sum(transition_fees_usd) AS cycle_transition_fees_usd,
           sum(stateful_realized_net_usd) AS cycle_stateful_realized_net_usd,
           sum(stateful_expected_net_usd) AS cycle_stateful_expected_net_usd,
           sum(new_leg_notional_usd) FILTER (
               WHERE new_short_venue = 'binance' OR new_long_venue = 'binance')
               AS binance_used_after_usd,
           sum(new_leg_notional_usd) FILTER (
               WHERE new_short_venue = 'bybit' OR new_long_venue = 'bybit')
               AS bybit_used_after_usd,
           sum(new_leg_notional_usd) FILTER (
               WHERE new_short_venue = 'hyperliquid' OR new_long_venue = 'hyperliquid')
               AS hyperliquid_used_after_usd,
           sum(old_leg_notional_usd) FILTER (
               WHERE old_short_venue = 'binance' OR old_long_venue = 'binance')
               AS binance_used_before_usd,
           sum(old_leg_notional_usd) FILTER (
               WHERE old_short_venue = 'bybit' OR old_long_venue = 'bybit')
               AS bybit_used_before_usd,
           sum(old_leg_notional_usd) FILTER (
               WHERE old_short_venue = 'hyperliquid' OR old_long_venue = 'hyperliquid')
               AS hyperliquid_used_before_usd,
           sum(new_leg_notional_usd) FILTER (WHERE transition_type IN ('OPEN', 'REVERSE')
               AND (new_short_venue = 'binance' OR new_long_venue = 'binance'))
               AS binance_open_required_usd,
           sum(new_leg_notional_usd) FILTER (WHERE transition_type IN ('OPEN', 'REVERSE')
               AND (new_short_venue = 'bybit' OR new_long_venue = 'bybit'))
               AS bybit_open_required_usd,
           sum(new_leg_notional_usd) FILTER (WHERE transition_type IN ('OPEN', 'REVERSE')
               AND (new_short_venue = 'hyperliquid' OR new_long_venue = 'hyperliquid'))
               AS hyperliquid_open_required_usd
    FROM valued
    GROUP BY policy, cycle_number, transition_phase
)
SELECT v.*,
       m.opens, m.retains, m.closes, m.reverses, m.selected_pairs_after,
       m.missing_outcomes, m.cycle_turnover_usd, m.cycle_transition_fees_usd,
       m.cycle_stateful_realized_net_usd, m.cycle_stateful_expected_net_usd,
       coalesce(m.binance_used_before_usd, 0) AS binance_used_before_usd,
       coalesce(m.bybit_used_before_usd, 0) AS bybit_used_before_usd,
       coalesce(m.hyperliquid_used_before_usd, 0) AS hyperliquid_used_before_usd,
       coalesce(m.binance_used_after_usd, 0) AS binance_used_after_usd,
       coalesce(m.bybit_used_after_usd, 0) AS bybit_used_after_usd,
       coalesce(m.hyperliquid_used_after_usd, 0) AS hyperliquid_used_after_usd,
       v.binance_capital_usd - coalesce(m.binance_used_after_usd, 0)
           AS binance_unused_after_usd,
       v.bybit_capital_usd - coalesce(m.bybit_used_after_usd, 0)
           AS bybit_unused_after_usd,
       v.hyperliquid_capital_usd - coalesce(m.hyperliquid_used_after_usd, 0)
           AS hyperliquid_unused_after_usd,
       greatest(coalesce(m.binance_used_before_usd, 0),
                coalesce(m.binance_used_after_usd, 0)) AS binance_close_first_peak_usd,
       greatest(coalesce(m.bybit_used_before_usd, 0),
                coalesce(m.bybit_used_after_usd, 0)) AS bybit_close_first_peak_usd,
       greatest(coalesce(m.hyperliquid_used_before_usd, 0),
                coalesce(m.hyperliquid_used_after_usd, 0)) AS hyperliquid_close_first_peak_usd,
       coalesce(m.binance_used_before_usd, 0) + coalesce(m.binance_open_required_usd, 0)
           AS binance_open_first_peak_usd,
       coalesce(m.bybit_used_before_usd, 0) + coalesce(m.bybit_open_required_usd, 0)
           AS bybit_open_first_peak_usd,
       coalesce(m.hyperliquid_used_before_usd, 0) + coalesce(m.hyperliquid_open_required_usd, 0)
           AS hyperliquid_open_first_peak_usd,
       extract(epoch FROM v.transition_at - v.previous_scheduled_decision_at)::bigint
           AS seconds_since_previous_cycle,
       'EXACT_BASE_SHORT_VENUE_SYMBOL_LONG_VENUE_SYMBOL'::text AS pair_identity_model,
       'CLOSE_BEFORE_OPEN'::text AS capital_execution_order,
       'INITIAL_LEG_NOTIONAL_FUNDING_AND_CYCLE_EXECUTABLE_MARKOUT'::text AS pnl_model
FROM valued v
JOIN cycle_metrics m USING (policy, cycle_number, transition_phase);

COMMENT ON VIEW xvf_shadow_book_transition_v1 IS
    'SQL-derived OPEN/RETAIN/CLOSE/REVERSE book, transition-only fees and venue occupancy.';
