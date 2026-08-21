# Codex XVF two-year replay artifacts

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21  
**Repository changes:** none outside `CODEX_ARTIFACTS`; the pre-existing change to
`XVF_LIVE_BOOK.md` was not touched.

This directory contains the independent replay used to audit the repository's XVF capital
simulation. It is research material, not production execution code.

## Decision-grade artifacts

- `xvf-production-like-export.sql` builds a no-lookahead historical candidate and funding export.
  It aligns the signal with the production local-midnight cutoff, zero-fills calendar windows,
  applies historical completeness, uses preceding completed-day liquidity, and exports all ranks.
- `xvf-production-like-sim.py` runs the fixed-USD-112.50-leg, three-day reconciliation replay with
  exact-pair retention, rank backfill, and flat annual boundaries.
- `xvf-production-like-grid.py` compares fixed starting allocations using the same replay.
- `xvf-strict-capital-policy-sim.py` compares the exact baseline with fee filters and target-driven
  collateral-transfer counterfactuals.

The `provisional/` directory preserves earlier comparative work that still used the repository
export's full-day and calendar-week lookahead. It is retained for provenance only and is superseded
by the files above.

The `improvements/` directory contains the follow-up basis, maker-routing, symbol-selection,
capital-allocation and leverage studies. Start with `improvements/XVF_IMPROVEMENT_REVIEW.md`; its
README distinguishes the consolidated conclusion from the supporting reports and generated data.

## Generated inputs

The `generated/` directory contains local CSV exports. Repository-wide `*.csv` ignore rules prevent
these database-derived datasets from being committed.

- `candidates_production_like.csv` and `funding_cutoff_daily.csv` feed the final replay.
- `candidates_full.csv` and `funding_daily_fresh.csv` feed only the superseded provisional replay.

## Reproduce

Run from the repository root:

```bash
psql -v ON_ERROR_STOP=1 -U prop_strategy_app -d prop_strategy \
  -f CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-production-like-export.sql

python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-production-like-sim.py

SIM_START=2025-08-21 SIM_END_EXCL=2026-08-21 \
  python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-production-like-sim.py

python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-production-like-grid.py

python3 CODEX_ARTIFACTS/XVF_TWO_YEAR_REPLAY_2026-08-21/xvf-strict-capital-policy-sim.py --details
```

The SQL export uses this workspace's explicit absolute generated-data paths because psql's `\copy`
does not expand filename variables. Update the two final `\copy` paths if the checkout moves. The
Python scripts resolve their default inputs relative to this directory and also accept their
documented path overrides.

## Baseline results

Equal starting capital of USD 1,500 per venue, fixed USD 112.50 per leg:

| Independent period | Funding | Commissions | Net | Return |
| --- | ---: | ---: | ---: | ---: |
| 2024-08-21 to 2025-08-21 exclusive | +286.66 | -193.09 | +93.57 | +2.08% |
| 2025-08-21 to 2026-08-21 exclusive | +435.92 | -223.90 | +212.02 | +4.71% |

These are funding-minus-commission estimates. They do not include an actual basis-price path,
slippage, maker non-fills, forced delistings, liquidation risk, or transfer latency. The recent
period also has five missing held Binance leg-days. See the scripts' output for complete diagnostics.
