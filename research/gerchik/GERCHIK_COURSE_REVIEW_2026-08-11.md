# Gerchik "Трейдинг от А до Я 3.0" — review (2026-08-11)

Reviews the material that `GERCHIK_COURSE_STRATEGY_RESEARCH.md` explicitly excluded: it covered
"34 PDF documents, 655 pages" and "298 PNG images" and stated that "JPG images and videos were
intentionally excluded". The folder also holds **60 videos, 59 JPGs and 11 office documents**, and
the office documents turn out to carry the most precise rules in the entire course.

Primary sources for this review: `Доп. материалы/Алгоритмы/Объединённый конспект 1.0.docx`
(54 KB, a merged synopsis of seminar + book + earlier notes), five student algorithm documents, and
`Блок 4/6. Таблица.xlsx`.

## 1. The headline finding: this course is far more mechanizable than Apollo

The Apollo source deliberately withholds numbers - `APOLLO_COURSE_SOURCE_NOTES.md` records that it
"intentionally teaches chart reading rather than fixed measurements" and does not define touch
tolerance, base geometry, or acceptance candle count. Every constant in Apollo V5 is ours.

Gerchik specifies constants outright:

| Rule | Value | Source |
| --- | --- | --- |
| Расчётный стоп (calculated stop) | `price x 0.2%` | конспект; confirmed in `Таблица.xlsx` as `цена*0,2%=размер стопа` |
| Люфт (backlash) | `price x 0.04%`, equivalently 20% of the stop | конспект |
| ТВХ (entry price) | `level ± люфт` | конспект |
| Stop anchor | measured from **ТВХ**, not from the level | конспект |
| Minimum reward:risk | 3:1 ("33% correct trades = breakeven") | конспект |
| Channel tradeable only if | width >= 8 stops | конспект |
| Technical stop bound | <= calculated stop + 20%; <= 20% of channel width | конспект |
| False-breakout depth | preferably <= 1/3 ATR | конспект |
| Cancel resting order when | price closes 2 stops away | конспект |
| Counter-trend stop | roughly halved | конспект |
| ATR exhaustion | at 70-75% of ATR consumed, prefer counter-trend | student algorithms |

That is a specification, not a framework. It is the single biggest difference from Apollo and it
matters: nine Apollo hypotheses failed this session partly because every threshold had to be
invented, and inventing thresholds is what creates the overfitting we kept diagnosing.

## 2. The core model was never implemented

**БСУ / БПУ, as specified:**

- **БСУ** — the bar that forms the level.
- **БПУ-1** — must hit БСУ **"копейка в копейку"** (to the kopeck: *exact* price equality).
  Any number of bars may sit between them; the level may even be broken between them; the gap may
  reach 5-7 days on a minute timeframe or a month on hourly.
- **БПУ-2** — must come **immediately after БПУ-1, with no intervening bars**. It may fall short of
  the level by up to люфт but must **not** break it.
- БСУ and БПУ-1 may sit on either side of the level; **БПУ-1 and БПУ-2 must be on the same side.**
- **Entry:** 30 seconds before БПУ-2 closes, place a **limit order** at `level ± люфт`. Stop placed
  simultaneously or immediately after.

**What `GerchikLevelStrategy` actually does** (`findLevel`, lines 157-181): collects local pivots
using `pivotStrength` bars either side, clusters anything within `levelToleranceAtr x ATR`, and
requires `minimumConfirmations` touches inside that band. Entry is taken on the signal bar.

Absent from the implementation:

1. exact price equality between БСУ and БПУ-1 — replaced by an ATR-wide tolerance band;
2. the adjacency requirement between БПУ-1 and БПУ-2;
3. the same-side constraint on БПУ-1/БПУ-2;
4. люфт-offset **limit** entry — the engine could not rest a limit at a chosen price until the
   `EnterAtLimit` decision added on 2026-08-11;
5. stop measured from ТВХ rather than the level;
6. the percentage-of-price stop and люфт; `stopBufferAtr x ATR` is used instead;
7. order-cancellation rules.

**Points 1 and 2 are the model's entire selectivity.** Exact equality plus bar adjacency is a rare
conjunction; a pivot cluster inside an ATR band is a common one.

## 3. Which makes the recorded failure uninformative

`GERCHIK_COURSE_STRATEGY_RESEARCH.md` reports the frozen v1 training results: breakout 849 trades
(-13.61%), false breakout 2,637 trades (-48.64%), channel 788 trades (-63.28%), plus an earlier
false-breakout variant at 2,637 trades and a defective breakout at 5,271 trades losing 98.52%.

Those trade counts are themselves the diagnosis. A method whose author says "there may be many
levels and not a single entry point - that is normal" does not generate 2,637 setups in two years on
one symbol. The proxy admitted one to two orders of magnitude more trades than the specification
permits, so the losses measure the proxy, not the method.

This is the same class of error found in Apollo today: a strategy named `apollo-v5-liquidity-limit`
that never placed a limit order at the liquidity. In both cases the implementation substituted a
loose statistical proxy for a narrow structural rule.

## 4. Convergence worth noting

Two independently authored courses both specify **a resting limit order placed just before the
level, with the stop behind it**:

- Apollo: *"place a limit order slightly before the principal volume and hide the stop behind the
  entire liquidity zone"* (pp. 24, 26).
- Gerchik: `ТВХ = level ± люфт`, entered by limit 30 seconds before БПУ-2 closes, stop behind the level.

Both of our implementations used a close-based trigger instead. That agreement between two unrelated
sources is the strongest argument in either body of material for the limit-entry mechanic - though
note that Apollo's version was implemented today and **failed** (-$8.41/trade, 15 symbols), so
agreement between sources is not evidence of profitability.

## 5. Asset-class mismatch — the real obstacle

The course is built for equities. `Таблица.xlsx` lists MOEX names (ГМК Норникель, Газпром, Лукойл,
Сбербанк); `DOMASHKA.xlsx` is US tickers (GOOS, BBBY, DDD, MRTX). Stops are quoted per market: "US
3-7 cents", "Forex 15-25 points", "gold $2-3", "oil 15-20 cents". Student algorithms target Sberbank
and Gazprom on FORTS with explicit session times.

Consequences for crypto:

- **`price x 0.2%` is calibrated to equity volatility.** On BTCUSDT 15m this is far tighter relative
  to ATR than intended, and the ratio differs per symbol - the constant does not transfer, and
  re-deriving it per asset re-opens exactly the free parameter the course otherwise closes.
- **"Копейка в копейку" presumes a coarse tick grid.** A $0.01 tick on a $30 stock is 3.3 bp; on
  BTC at $60,000 the equivalent is $20. Exact equality has to become a tolerance, and choosing that
  tolerance reintroduces the tuned parameter whose absence was this course's main advantage.
- **Gap levels do not exist** in 24/7 perpetuals, removing one of the seven level types outright.
- **Session rules do not map**: "уровень первого часа", "trade breakouts in the morning", the FORTS
  clearing schedule, and daily-close reasoning all assume a session boundary crypto lacks.

## 6. Assessment

**Worth testing, and better positioned than Apollo**, for one reason: its rules are numeric and
falsifiable rather than interpretive, so a test can fail for reasons that are about the method
instead of about our invented thresholds.

**Two things must be true for a test to be worth running:**

1. The БСУ/БПУ model implemented as specified - exact-match БСУ/БПУ-1, adjacent БПУ-1/БПУ-2,
   same-side constraint, люфт-offset limit entry, stop from ТВХ. Anything looser repeats the v1
   error and tells us nothing.
2. The tick-tolerance and stop-percentage adaptations for crypto **declared in advance and not
   tuned**, with the honest acknowledgement that both are ours, not Gerchik's.

**Expected obstacle:** selectivity cuts both ways. If the exact model yields very few setups across
15 symbols, we inherit the same statistical ceiling that ended the Apollo work - roughly 15-26
independent blocks, unable to resolve a small per-trade edge. The correct response would be to
report that the sample is too small rather than to loosen the rules until enough trades appear,
which is precisely how v1 arrived at 2,637 trades.

**Not reviewed:** the 60 videos and 59 JPGs. The office documents appear to be faithful, detailed
transcriptions of the lecture content, so the videos are more likely to add worked examples than new
rules - but that is an assumption, and the exact worked examples would be the natural way to
validate a detector, as the labelled-example work did for Apollo.
