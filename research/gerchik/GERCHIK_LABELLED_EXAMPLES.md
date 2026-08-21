# Gerchik — labelled trade examples (2026-08-11)

All 13 images in `Доп. материалы/Уровни/Примеры уровней/` read. Broker-terminal screenshots
annotated by hand with trend, level, model, entry, stop, target and **outcome**.

These matter for the same reason `APOLLO_LABELLED_EXAMPLES.md` did: they check stated constants
against trades actually taken, rather than against our reading of the prose.

**Coverage:** 7 fully annotated with numbers, 1 pre-trade scenario, 1 US-stock process description,
4 unannotated screenshots carrying only trade markers.

## Documented outcomes

| # | Date | Instrument | Level | Model | Stop | Target | Outcome |
| --- | --- | --- | --- | --- | ---: | ---: | --- |
| 1 | 13.10.17 | SBRF | 20003, mirror 1H | breakout + acceptance, entry on test | 30 п | 126 п (1/3) | **-21 п** |
| 2 | 17.10.17 | SBRF | 20003, mirror 1H | СЛП, return above, hourly close above | 30 п | 168 п (1/4) | **б/у** |
| 3 | 09.11 | SBRF | 21375, daily high | ЛП of daily level | 19 п | 140 п (7к1) | **win** |
| 4 | 28.09.17 | EU | 69941, mirror Daily | ЛП with trend on pullback | 100 п | 333 п / 444 п | **+349 п** |
| 5 | 5.10.17 | RI | 113660, mirror 1H | breakout + acceptance, entry on test | 200 п | — | **-80 п** |
| 6 | 4.10.17 | GAZR | 12243, mirror Daily | ЛП, hourly close above in проторговка | 40 п | 157 п (1/3) | **+128 п** |
| 7a | 16.10.17 | RI | 115620, yesterday's high | breakout + acceptance, on test | 200 п | — | **-210 п** |
| 7b | 16.10.17 | RI | same | second attempt | — | — | **+70 п** |
| 8 | 13.10.17 | PCG (US) | 59.15 support | sell stop-limit, then acceptance below | — | structural | **+$121** |

**Nine outcomes: five wins, one breakeven, three losses.** The teaching set keeps its losers - not a
highlight reel, which matters when weighing claims about this method.

## Constants checked against real trades

**Stop as a percentage of price** - the прос states расчётный стоп = `price x 0.2%`:

| Trade | Stop | % of price |
| --- | ---: | ---: |
| 09.11 SBRF | 19 п | 0.089% |
| 28.09 EU | 100 п | 0.143% |
| 13.10 / 17.10 SBRF | 30 п | 0.150% |
| 16.10 RI | 200 п | 0.173% |
| 5.10 RI | 200 п | 0.176% |
| 4.10 GAZR | 40 п | **0.327%** |

Six of seven sit **below** 0.2%, median ~0.150%. So **0.2% is an upper bound, not the operating
value** - an implementation using it would risk ~35% more per trade than these examples do.

The GAZR outlier is instructive: at 0.327% it exceeds both the 0.2% calculated stop and the stated
"ТС <= РС + 20%" bound. It is explicitly anchored *"за хвост ЛП"* - behind the false-breakout wick.
**Structure overrode the formula.** Any faithful implementation needs the structural anchor to win,
with the percentage as a sanity check rather than the rule.

**Люфт = 0.04% of price** - confirmed exactly once (09.11: level 21375, ТВХ 21365, a 10-point offset
= 0.047%). The EU trade offsets by 1 tick. The other four enter *at* the level with no offset, so
люфт is applied selectively and the prose does not say when.

**"1/3" and "1/4" are risk:reward ratios, not position fractions.** Settled by the EU trade:
Тейк 1 = 333 п labelled 1/3 against a 100 п stop (3.3R), Тейк 2 = 444 п labelled 1/4 (4.4R). Two
targets, scaled out. Explains the `тейк 1/3 / 1/4 / 1/5` columns in `Блок 4/6. Таблица.xlsx`.

## Exits are managed, not mechanical

This is the most consequential finding, and it is not reproducible by any of our backtests.

**Losses are usually cut before the stop:**

- 13.10 SBRF: stop 30 п, result **-21 п** (70% of stop)
- 5.10 RI: stop 200 п, result **-80 п** (40% of stop)

but not always - 16.10 RI lost **210 п against a 200 п stop**, exceeding it.

**Winners are also cut short:** 4.10 GAZR targeted 157 п and closed at **+128 п**.

**Exit rules where stated are structural or volume-based, not fixed R.** The PCG example:
*"Первый выход возле ближайшего уровня. Второй выход после аномальных объемов."* - first exit near
the nearest level, second after anomalous volume. Neither is an R multiple.

Our backtests hold to a fixed stop and a fixed target by construction. On the two losses that were
cut early, discretion saved 30-60% of nominal risk. That is a large effect operating independently
of entry selection, and it is a concrete, measured example of why a mechanical implementation of
this method is **structurally handicapped** versus the discretionary version rather than merely a
noisier approximation of it.

## Structure common to the annotated examples

1. Level identified on a **higher timeframe** - 1H in three cases, Daily in three, prior-year
   extremum in one.
2. **Зеркальный уровень dominates**: 5 of 8 levels are mirror levels (support becoming resistance or
   vice versa), well ahead of the other six types the course enumerates. Remaining: yesterday's
   high, the issuer's daily high, a post-correction support.
3. Entry triggered on the **5-minute chart** after a named event - breakout plus acceptance on 1H, or
   a false breakout with return above the level.
4. Trend stated **first**, before the level - matching the checklist order in
   `Доп. материалы/Сценарий/чек-лист дневка.jpg`.
5. **Scaling is standard** - the PCG example enters twice and exits twice; the EU trade uses two
   targets.
6. Entry order type varies by model: **limit** at level ± люфт for bounce, **stop-limit** for
   breakouts (PCG, and the конспект's slippage warning).

## Level persistence

The 29.10.2017 scenario trades a level drawn at **last year's extremum** - marked in January,
traded in October. Nine months.

Worth noting against `APOLLO_LABELLED_EXAMPLES.md`, where the mechanical map saturated at 42 days
while the trader's annotations persisted at least 10 weeks. Gerchik's horizon is far longer again,
which supports the recorded hypothesis there that levels persist as *prices* decoupled from the
structure that formed them.

## Caveats

- Nine outcomes is an anecdote, not a hit rate. A 56% win rate on this sample means little.
- All examples are 2017 - Russian futures (SBRF, RI, GAZR), EURUSD futures, and one US stock.
  Nothing here is crypto; point values, tick grids and session structure all differ.
- Four of the thirteen images carry no numbers, so they contribute markers but no verifiable rules.
