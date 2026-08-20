# Perpetual-DEX vault LP (HLP) — exploratory measurement, 2026-08-20

Item 3 in `RESEARCH_OPTIONS.md`. Exploratory, not a pre-registered test: public data, one API call,
run to answer the shape question the item itself names as the thing to settle before committing
capital. Nothing here is a backtest of a trading rule.

Source: Hyperliquid `vaultDetails` for HLP (`0xdfc24b077bc1425ad1dea75bcb6f8158e10df303`), public,
no key. 97 `pnlHistory` / `accountValueHistory` points, 2023-05 to 2026-08, ~12.8 days apart. Period
return = change in cumulative PnL divided by vault equity at the start of that period; periods with a
vault under $1M excluded as too small to be meaningful.

## The shape fear was wrong

The item predicted: "a vault that is short volatility pays a smooth stream and then gives back a
multiple of it at once." Measured over 91 periods, the asymmetry runs the **other** way:

| | Value |
| --- | ---: |
| Mean period return | +1.146% |
| Median period return | +0.378% |
| p05 | -1.145% |
| Worst period | **-4.42%** |
| Best period | **+17.84%** |
| Positive periods | 79/91 (87%) |

Mean exceeds median because of large *gains*, not large losses. The worst observed period loses 4.4%;
the best gains 17.8%. That is the opposite signature to the variance-risk-premium test run the same
day (`OPTIONS_FORWARD_RESEARCH.md`), where a positive median sat on top of a -187-point tail.

**This does not mean the tail risk is absent.** HLP has only existed since 2023-05 and has not traded
through a crash on the scale of May 2021 or the FTX collapse. Every one of its worst periods happened
in 2023 on a vault under $14M. Twelve-day sampling also cannot show an intra-period drawdown that
recovered before the next observation. The honest statement is that the feared shape **has not
appeared in the observable history**, not that it cannot.

## The disqualifying finding is capacity, not risk

The API reports a current APR of 2.42% against an all-time mean that annualises near +38%. That gap is
the result:

| Vault size regime | n | Avg vault | Median period | **Annualised median** | Worst | Positive |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| under $50M | 20 | $8.6M | +1.750% | **+68.0%** | -4.42% | 70% |
| $50-200M | 24 | $142.4M | +1.047% | **+27.3%** | +0.03% | 100% |
| over $200M | 47 | $376.4M | +0.230% | **+6.0%** | -0.80% | 87% |

By calendar year, the same decay:

| Year | Avg vault | Annualised median |
| --- | ---: | ---: |
| 2023 | $5.5M | +18.7% |
| 2024 | $141.5M | +31.2% |
| 2025 | $402.7M | +8.9% |
| 2026 | $330.6M | **+0.6%** |
| trailing 12 months | $391.3M | **+1.1%** |

**The edge scaled away.** A depositor today is buying a trailing-twelve-month median of roughly 1%
annualised, consistent with the 2.42% APR the venue itself advertises, while taking vault, protocol
and smart-contract risk. That is below a risk-free rate for materially more risk.

It also kills the item's actual proposal, which was not plain deposit but deposit *plus a hedge* of
the vault's directional exposure on Binance. Hedging costs something; at a 1-2% gross yield there is
nothing left to hedge with. The hedgeability question the item raises first - can you even observe and
offset positions you do not control - never has to be answered, because the return it would protect
is gone.

## Status

**Not adopted.** Refuted on capacity rather than on the risk shape the item was written to test. Cost:
one public API call, no import, no new schema.

Two things this does not close:
- **GMX GM pools and other perp-DEX vaults are untested.** Same family, different size and different
  fee split; a smaller venue may sit where HLP was in 2024. The measurement above is now a template -
  it took one endpoint and one distribution.
- **HLP at a future crash.** If the observable history is missing the event that defines the strategy's
  real risk, then a later crash is informative in a way nothing here can substitute for. Worth
  re-reading this file after the next one rather than treating 87% positive periods as settled.
