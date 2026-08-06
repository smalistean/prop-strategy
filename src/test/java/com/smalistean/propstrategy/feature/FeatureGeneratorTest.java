package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.TraderRatio;
import com.smalistean.propstrategy.database.TraderRatio.RatioType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeatureGeneratorTest {

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void skipsWarmupAndMakesRowsAvailableOnlyAfterCandleClose() {
        List<Kline> candles = candles(55);

        List<FeatureRow> rows = generate(candles, List.of(), List.of(), List.of(), List.of(), List.of());

        assertEquals(6, rows.size());
        FeatureRow first = rows.getFirst();
        assertEquals(candles.get(49).closeTime(), first.availableAt());
        assertEquals(first.availableAt().plusMillis(1), first.earliestExecutionTime());
        assertTrue(first.ema20().signum() > 0);
        assertEquals(0, first.ema50().compareTo(new BigDecimal("125.5")));
        assertEquals(0, first.rsi14().compareTo(new BigDecimal("100")));
        assertEquals(0, first.atr14().compareTo(new BigDecimal("4")));
    }

    @Test
    void neverUsesSupportingDataFromAfterCandleClose() {
        List<Kline> candles = candles(50);
        Instant close = candles.getLast().closeTime();
        OpenInterestStatistic before1 = openInterest(close.minusSeconds(600), "100");
        OpenInterestStatistic before2 = openInterest(close.minusSeconds(300), "110");
        OpenInterestStatistic future = openInterest(close.plusSeconds(1), "220");
        FundingRate fundingBefore = funding(close.minusSeconds(1), "0.0001");
        FundingRate fundingFuture = funding(close.plusSeconds(1), "0.9999");
        TraderRatio globalBefore = ratio(close, RatioType.GLOBAL_ACCOUNT, "1.2");
        TraderRatio globalFuture = ratio(close.plusSeconds(1), RatioType.GLOBAL_ACCOUNT, "9.9");

        FeatureRow row = generate(candles,
                List.of(fundingBefore, fundingFuture),
                List.of(before1, before2, future),
                List.of(globalBefore, globalFuture), List.of(), List.of()).getFirst();

        assertEquals(0, row.fundingRate().compareTo(new BigDecimal("0.0001")));
        assertEquals(0, row.openInterestChangePercent().compareTo(new BigDecimal("10")));
        assertEquals(0, row.globalAccountRatio().compareTo(new BigDecimal("1.2")));
        assertNull(row.topAccountRatio());
        assertNull(row.topPositionRatio());
    }

    @Test
    void calculatesCandleShapeAsPercentOfRange() {
        FeatureRow row = generate(candles(50), List.of(), List.of(), List.of(), List.of(), List.of())
                .getFirst();

        assertEquals(0, row.bodyPercent().compareTo(new BigDecimal("25")));
        assertEquals(0, row.upperWickPercent().compareTo(new BigDecimal("50")));
        assertEquals(0, row.lowerWickPercent().compareTo(new BigDecimal("25")));
    }

    @Test
    void rejectsNonChronologicalCandles() {
        List<Kline> candles = new ArrayList<>(candles(2));
        java.util.Collections.reverse(candles);

        assertThrows(IllegalArgumentException.class,
                () -> generate(candles, List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private static List<FeatureRow> generate(List<Kline> candles,
                                             List<FundingRate> funding,
                                             List<OpenInterestStatistic> openInterest,
                                             List<TraderRatio> global,
                                             List<TraderRatio> topAccount,
                                             List<TraderRatio> topPosition) {
        return new FeatureGenerator().generate(
                candles, funding, openInterest, global, topAccount, topPosition);
    }

    private static List<Kline> candles(int count) {
        List<Kline> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Instant openTime = START.plus(Duration.ofMinutes(15L * i));
            BigDecimal open = BigDecimal.valueOf(100 + i);
            result.add(new Kline(
                    openTime,
                    open,
                    open.add(BigDecimal.valueOf(3)),
                    open.subtract(BigDecimal.ONE),
                    open.add(BigDecimal.ONE),
                    BigDecimal.valueOf(1000 + i * 10L),
                    openTime.plus(Duration.ofMinutes(15)).minusMillis(1),
                    BigDecimal.ZERO,
                    1,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO));
        }
        return result;
    }

    private static OpenInterestStatistic openInterest(Instant time, String value) {
        return new OpenInterestStatistic("BTCUSDT", "5m", time,
                new BigDecimal(value), new BigDecimal(value), null);
    }

    private static FundingRate funding(Instant time, String value) {
        return new FundingRate("BTCUSDT", time, "Regular",
                new BigDecimal(value), null);
    }

    private static TraderRatio ratio(Instant time, RatioType type, String value) {
        return new TraderRatio("BTCUSDT", "5m", type, time,
                new BigDecimal(value), BigDecimal.ONE, BigDecimal.ONE);
    }
}
