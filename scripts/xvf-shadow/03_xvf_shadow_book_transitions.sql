-- Stateful transition ledger. Detailed rows retain the old/new exact pair identities while the
-- repeated cycle columns expose turnover, transition-only fees and venue capital occupancy.

SELECT policy, cycle_number, transition_phase, previous_signal_run_id, current_signal_run_id,
       previous_scheduled_decision_at, transition_at, seconds_since_previous_cycle,
       transition_type, base,
       old_short_venue, old_short_venue_symbol, old_long_venue, old_long_venue_symbol,
       new_short_venue, new_short_venue_symbol, new_long_venue, new_long_venue_symbol,
       old_leg_notional_usd, new_leg_notional_usd,
       entry_fee_charged_usd, exit_fee_charged_usd, transition_fees_usd, turnover_usd,
       realized_funding_usd, basis_price_pnl_usd, interval_gross_pnl_usd,
       stateful_realized_net_usd, stateful_expected_net_usd,
       fee_saving_vs_independent_usd, captured_within_tolerance,
       opens, retains, closes, reverses, selected_pairs_after, missing_outcomes,
       cycle_turnover_usd, cycle_transition_fees_usd,
       cycle_stateful_realized_net_usd, cycle_stateful_expected_net_usd,
       binance_capital_usd, binance_used_before_usd, binance_used_after_usd,
       binance_unused_after_usd, binance_close_first_peak_usd, binance_open_first_peak_usd,
       bybit_capital_usd, bybit_used_before_usd, bybit_used_after_usd,
       bybit_unused_after_usd, bybit_close_first_peak_usd, bybit_open_first_peak_usd,
       hyperliquid_capital_usd, hyperliquid_used_before_usd, hyperliquid_used_after_usd,
       hyperliquid_unused_after_usd, hyperliquid_close_first_peak_usd,
       hyperliquid_open_first_peak_usd,
       pair_identity_model, capital_execution_order, pnl_model
FROM xvf_shadow_book_transition_v1
ORDER BY policy, cycle_number, transition_phase, base, transition_type;
