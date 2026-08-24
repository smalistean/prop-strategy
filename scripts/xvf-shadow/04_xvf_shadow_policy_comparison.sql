-- Isolates candidate-selection policy. Route and notional are held fixed; exact pairs persist
-- between cycles and pay fees only on OPEN, CLOSE or REVERSE transitions.

WITH cycles AS (
    SELECT policy,
           cycle_number - (transition_phase = 'TERMINAL')::int AS accounting_cycle_number,
           max(capital_usd) AS capital_usd,
           count(*) FILTER (WHERE new_evaluation_order IS NOT NULL) AS measured_pairs,
           count(*) FILTER (WHERE new_evaluation_order IS NOT NULL
                              AND realized_net_usd IS NULL) AS missing_pairs,
           count(*) FILTER (WHERE captured_within_tolerance IS FALSE) AS late_pairs,
           sum(expected_net_usd) AS independent_expected_net_usd,
           sum(stateful_expected_net_usd) AS stateful_expected_net_usd,
           sum(realized_funding_usd) AS realized_funding_usd,
           sum(basis_price_pnl_usd) AS basis_price_pnl_usd,
           sum(independent_round_trip_fees_usd) AS independent_round_trip_fees_usd,
           sum(transition_fees_usd) AS transition_fees_usd,
           sum(stateful_realized_net_usd) AS stateful_realized_net_usd,
           sum(turnover_usd) AS turnover_usd,
           count(*) FILTER (WHERE transition_type = 'OPEN') AS opens,
           count(*) FILTER (WHERE transition_type = 'RETAIN') AS retains,
           count(*) FILTER (WHERE transition_type = 'CLOSE') AS closes,
           count(*) FILTER (WHERE transition_type = 'REVERSE') AS reverses
    FROM xvf_shadow_book_transition_v1
    GROUP BY policy, cycle_number - (transition_phase = 'TERMINAL')::int
)
SELECT policy,
       count(*) AS measured_cycles,
       sum(measured_pairs) AS measured_candidate_outcomes,
       sum(missing_pairs) AS missing_candidate_outcomes,
       sum(late_pairs) AS late_candidate_outcomes,
       sum(independent_expected_net_usd) AS independent_expected_net_usd,
       sum(stateful_expected_net_usd) AS stateful_expected_net_usd,
       sum(realized_funding_usd) AS realized_funding_usd,
       sum(basis_price_pnl_usd) AS basis_price_pnl_usd,
       sum(independent_round_trip_fees_usd) AS independent_round_trip_fees_usd,
       sum(transition_fees_usd) AS transition_fees_usd,
       sum(independent_round_trip_fees_usd) - sum(transition_fees_usd)
           AS fee_saving_from_retention_usd,
       sum(stateful_realized_net_usd) AS stateful_realized_net_usd,
       avg(stateful_realized_net_usd) AS average_cycle_net_usd,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY stateful_realized_net_usd)
           AS median_cycle_net_usd,
       sum(stateful_realized_net_usd) / NULLIF(sum(capital_usd), 0)
           AS return_on_cycle_capital,
       avg((stateful_realized_net_usd > 0)::int) AS profitable_cycle_ratio,
       sum(turnover_usd) AS turnover_usd,
       sum(opens) AS opens, sum(retains) AS retains, sum(closes) AS closes,
       sum(reverses) AS reverses,
       'STATEFUL_EXACT_PAIR_OPEN_RETAIN_CLOSE_REVERSE'::text AS transition_model,
       'SHADOW_SELECTED_ROUTE_HELD_FIXED_FOR_BOTH_POLICIES'::text AS route_model,
       'MISSING_OUTCOMES_REMAIN_NULL_AND_ARE_COUNTED'::text AS missing_data_policy
FROM cycles
GROUP BY policy
ORDER BY policy;
