package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.CaptureStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ExpectedNet;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.PairType;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Ranks;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.SignalScore;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

final class XvfSignalRunFixtures {

    static final String CONFIGURATION_HASH = "a".repeat(64);
    static final Instant CUTOFF = Instant.parse("2026-08-21T09:00:00.123456Z");

    private XvfSignalRunFixtures() {
    }

    static XvfSignalRun complete(UUID runId, List<Candidate> candidates) {
        return run(runId, CUTOFF, CaptureStatus.COMPLETE, new BigDecimal("4500.00"),
                JsonDocument.emptyArray(), null, null, candidates);
    }

    static XvfSignalRun complete(UUID runId, Instant cutoff, List<Candidate> candidates) {
        return run(runId, cutoff, CaptureStatus.COMPLETE, new BigDecimal("4500.00"),
                JsonDocument.emptyArray(), null, null, candidates);
    }

    static XvfSignalRun partial(UUID runId, List<Candidate> candidates) {
        return run(runId, CUTOFF, CaptureStatus.PARTIAL, new BigDecimal("4500.00"),
                JsonDocument.array("[{\"code\":\"MISSING_DEPTH\",\"venue\":\"bybit\"}]"),
                "MISSING_DEPTH", "Bybit depth was unavailable", candidates);
    }

    static XvfSignalRun failed(UUID runId) {
        return run(runId, CUTOFF, CaptureStatus.FAILED, null,
                JsonDocument.array("[{\"code\":\"SIGNAL_QUERY_FAILED\"}]"),
                "SIGNAL_QUERY_FAILED", "PostgreSQL funding query failed", List.of());
    }

    static Candidate binanceBybit(int evaluationOrder, int grossRank,
                                  Integer baselineRank, Integer shadowRank) {
        return candidate(evaluationOrder, grossRank, baselineRank, shadowRank,
                "ETH", PairType.CEX_CEX,
                "binance", "ETHUSDT", "bybit", "ETHUSDT",
                "bybit", "binance");
    }

    static Candidate binanceHyperliquid(int evaluationOrder, int grossRank,
                                        Integer baselineRank, Integer shadowRank) {
        return candidate(evaluationOrder, grossRank, baselineRank, shadowRank,
                "PEPE", PairType.CEX_DEX,
                "binance", "1000PEPEUSDT", "hyperliquid", "PEPE",
                "hyperliquid", "binance");
    }

    private static Candidate candidate(
            int evaluationOrder,
            int grossRank,
            Integer baselineRank,
            Integer shadowRank,
            String base,
            PairType pairType,
            String shortVenue,
            String shortSymbol,
            String longVenue,
            String longSymbol,
            String makerVenue,
            String takerVenue) {
        return new Candidate(
                evaluationOrder,
                new Pair(base, pairType, shortVenue, shortSymbol, longVenue, longSymbol),
                new Ranks(grossRank, baselineRank, shadowRank),
                new SignalScore(
                        new BigDecimal("27.12500000"),
                        true,
                        new BigDecimal("0.650000000000"),
                        new BigDecimal("17.63125000"),
                        true,
                        new BigDecimal("12345678.123456789012")),
                new Route(makerVenue, takerVenue, 72),
                new ExpectedNet(
                        new BigDecimal("4.25000000"),
                        new BigDecimal("8.50000000"),
                        new BigDecimal("14.50000000"),
                        new BigDecimal("2.75000000"),
                        new BigDecimal("1.50000000"),
                        new BigDecimal("4.00000000"),
                        new BigDecimal("2.25000000"),
                        new BigDecimal("1.00000000"),
                        new BigDecimal("8.50000000")),
                new BigDecimal("112.500000000000"),
                JsonDocument.object("""
                        {"bid":"3999.90","ask":"4000.10","baseUnitsPerContract":"1",\
                         "sourceTime":"2026-08-21T09:00:00Z"}
                        """),
                JsonDocument.object("""
                        {"bid":"4001.00","ask":"4001.20","baseUnitsPerContract":"1",\
                         "sourceTime":"2026-08-21T09:00:00Z"}
                        """),
                JsonDocument.object("{\"formulaVersion\":1,\"units\":\"hold_bps\"}"),
                JsonDocument.object("{\"fundingFresh\":true,\"basisAligned\":true}"),
                ScoreStatus.SCORABLE,
                JsonDocument.emptyArray());
    }

    private static XvfSignalRun run(
            UUID runId,
            Instant cutoff,
            CaptureStatus status,
            BigDecimal capital,
            JsonDocument issues,
            String failureCode,
            String failureDetail,
            List<Candidate> candidates) {
        Instant started = cutoff.minusSeconds(2);
        Instant ended = cutoff.plusSeconds(1);
        return new XvfSignalRun(
                runId,
                (short) 1,
                cutoff,
                cutoff,
                started,
                ended,
                LocalDate.of(2026, 8, 21),
                ZoneId.of("Europe/Chisinau"),
                cutoff.plusSeconds(3),
                "test-attempt-" + runId,
                "codex-shadow-ledger-v1",
                "xvf-v1",
                CONFIGURATION_HASH,
                JsonDocument.object("{\"positions\":20,\"legLeverage\":1.0}"),
                JsonDocument.object("{\"binance\":\"2026-08-21T08:00:00Z\"}"),
                JsonDocument.object("{\"binance\":\"2026-08-21T09:00:00Z\"}"),
                JsonDocument.object("{\"binance\":{\"availableMarginUsd\":\"1500.00\"}}"),
                capital,
                issues,
                status,
                failureCode,
                failureDetail,
                candidates);
    }
}
