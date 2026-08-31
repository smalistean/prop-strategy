# Prop Challenge Rules

Account/instrument: USDT-margined, Binance-quoted or very close to Binance prices. Not BTC-only.
Challenge leverage cap 1:5.

## Allowed symbols (posted 2026-08-25, extracted from screenshots of the account's watchlist)

The platform lists both crypto and Binance's tokenized-stock/ETF/commodity perpetuals under one
USDT-margined umbrella. Only the crypto set is relevant to this project; the rest is excluded below.

**Crypto (127) — this is the tradeable universe for strategy work:**

1INCH, AAVE, ACE, ADA, AEVO, ALT, ANKR, APE, API3, ARB, ARKM, AR, ASTER, ATOM, AUCTION, AVAX, BCH,
BEAMX, BEL, BICO, BIGTIME, BNB, BONK (Binance: `1000BONKUSDT`), BTC, CAKE, CATI, CFX, CHZ, CKB, COMP,
CYBER, DASH, DODOX, DOGE, DOT, DUSK, DYM, EDU, EGLD, ENA, ENJ, ENS, ETHFI, ETH, FET, FIL, FLOKI
(Binance: `1000FLOKIUSDT`), GAS, GMT, GMX, GRT, HBAR, HMSTR, HYPE, ICP, ID, ILV, IMX, INJ, IOST,
JASMY, JOE, LAB, LDO, LINEA, LINK, LIT, LPT, LQTY, LSK, LTC, MANA, MANTA, MASK, MAV, MON, NEO, ONDO,
ONG, OP, ORDI, PENDLE, PEOPLE, PEPE (Binance: `1000PEPEUSDT`), POLYX, PUMP, PYTH, RATS (Binance:
`1000RATSUSDT`), RIVER, ROSE, RSR, RUNE, SAGA, SAND, SEI, SHIB (Binance: `1000SHIBUSDT`), SOL, SPELL,
SSV, STRK, STX, SUI, TAO, TIA, TNSR, TRB, TRUMP, TRX, UB, UMA, UNI, USTC, VET, VIRTUAL, WIF, WLD,
WLFI, WOO, W, XAI, XLM, XMR, XRP, YGG, ZEC, ZETA, ZRX

Three of these (LAB, RIVER, UB) are recent 2026 listings not independently confirmed by name — worth a
quick sanity check before relying on them, everything else is a well-established token.

**Excluded — Binance tokenized stocks, ETFs, and commodities, not crypto (36):**
- Stocks: SPCX, AAOI, AXTI, LLY, JPM, OPENAI, NOK, QCOM, SAMSUNG, SKHYNIX, META, MU, PAYP, SNDK, TSM,
  MSTR, AMZN, CRCL, COIN, PLTR, AAPL, TSLA, INTC, HOOD
- ETFs: EWJ, EWY, QQQ, SPY
- Commodities/metals: COPPER, CL (crude oil), BZ (Brent), NATGAS, XAU (gold), XAG (silver),
  XPT (platinum), XPD (palladium)

All 163 posted symbols were cross-checked against `binance_perp_kline` (daily interval) and every one
resolved to a real, actively-tracked Binance perpetual with history through 2026-07-31 — the exclusion
above is by asset class, not by data availability.

Execution: **no bots/automated trading allowed** — the firm requires manual execution. A signal
generator (something that tells the trader what to do) is not itself prohibited; a program placing
orders on this account would be. Positions may be held as long as desired — no overnight or weekend
flattening requirement, per the user (2026-08-25), and daily reset is on UTC, per the user's
recollection (not yet independently confirmed against the firm's written terms).

| Criteria | Stage 1 | Stage 2 | Stage 3 |
|---|---|---|---|
| Account Type | Live | Live | Funded |
| Initial Balance | 50,000 USDT | 50,000 USDT | 50,000 USDT |
| Challenge Leverage | 1:5 | 1:5 | 1:5 |
| Trading Period | Unlimited | Unlimited | Unlimited |
| Minimum Trading Days | 7 (needs 5) | 5 | 0 |
| Maximum Daily Loss | 2,500 USDT | 2,500 USDT | 2,500 USDT |
| Maximum Loss (total) | 5,000 USDT | 4,000 USDT | 4,000 USDT |
| Profit Target | 4,000 USDT | 3,000 USDT | 0 USDT |

Notes:
- Currently on Stage 1.
- As of 2026-08-22: balance 49,927.02 USDT, i.e. ~72.98 USDT drawn down against the 5,000 USDT Stage 1 max loss.
- "Maximum Loss" is measured from the 50,000 initial balance (static, not trailing, based on stage-1 usage shown as 72/5,000).
- As of 2026-08-25 11:31 UTC: discretionary BTC short (opened 08-22 09:35:53, no stop) closed flat. Balance
  49,223.01 USDT, 0 open positions, 0 used margin — i.e. ~776.99 USDT drawn down against the 5,000 total limit.
  This is the checkpoint Phase 0's `PropRuleEngine` tests should reproduce.
