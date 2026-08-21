# Codex XVF improvement studies

**Created by:** OpenAI Codex  
**Analysis date:** 2026-08-21

This directory is isolated research material. Nothing here is part of the production XVF source or
the canonical `XVF_STRATEGY.md` / `XVF_IMPLEMENTATION.md` documents.

Start with `XVF_IMPROVEMENT_REVIEW.md`. It consolidates the findings and gives a prioritized
experiment sequence.

Supporting reports:

- `XVF_BASIS_CONVERGENCE_CODEX_STUDY.md` — actual-lifecycle price-basis overlay and entry-basis
  direction study.
- `XVF_MAKER_ROUTING_STUDY.md` — current maker route versus Bybit-maker and other fee-only
  counterfactuals.
- `XVF_SYMBOL_SELECTION_AND_BOOK_CONSTRUCTION.md` — fee gates, position-count sensitivity and
  constrained-selector design.
- `XVF_LEVERAGE_AND_CAPITAL_STUDY.md` — gross leverage, reserves, fixed allocations and conservative
  replenishment.

Reproduction scripts live beside the reports. Basis-generated CSV evidence is under `generated/`
and is ignored by the repository's global CSV rule.
