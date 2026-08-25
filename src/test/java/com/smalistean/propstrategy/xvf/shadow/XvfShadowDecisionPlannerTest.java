package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Instrument;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.IntervalSource;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.SettledVenueWatermark;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ActivitySnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.BookLevel;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentRules;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.IssueSeverity;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.OrderBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ReferenceSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ResponseTiming;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.SnapshotIssue;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.TopOfBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.EvaluatedPair;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Leg;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.PairAlternative;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.PairType;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.SignalEvaluation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XvfShadowDecisionPlannerTest {

    private static final Instant CUTOFF = Instant.parse("2026-08-21T09:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-08-21T08:59:59Z");
    private static final String BYBIT_SYMBOL = "1000PEPEUSDT";
    private static final String HL_SYMBOL = "PEPE";

    @Test
    void choosesBybitMakerAndDoesNotAssumeBasisConvergenceProfit() {
        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(), funding(Freshness.FRESH),
                markets(), configuration());

        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(1, run.candidates().size());
        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.ScoreStatus.SCORABLE, candidate.scoreStatus());
        assertEquals("bybit", candidate.route().makerVenue());
        assertEquals("hyperliquid", candidate.route().takerVenue());
        assertEquals(1, candidate.ranks().baselineBookRank());
        assertEquals(1, candidate.ranks().shadowBookRank());
        assertDecimal("0.00000000", candidate.expectedNet().entryBasisBps());
        assertDecimal("0.00000000", candidate.expectedNet().expectedBasisPnlBps());
        assertDecimal("90.00000000", candidate.expectedNet().expectedFundingBps());
        assertDecimal("8.10000000", candidate.expectedNet().expectedEntryFeeBps());
        assertDecimal("14.50000000", candidate.expectedNet().expectedExitFeeBps());
        assertDecimal("67.40000000", candidate.expectedNet().expectedNetBps());
        assertTrue(candidate.shortLegSnapshot().json().contains("\"baseUnitsPerContract\":1000"));
        assertTrue(candidate.scoreComponents().json().contains(
                "LATEST_PENDING_RATE_REPEATED_AT_KNOWN_STAMPS"));
    }

    @Test
    void persistsFourHourFundingHistoryWithEachCandidateLeg() {
        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                withFourObservationHistory(funding(Freshness.FRESH)), markets(), configuration());

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertTrue(candidate.shortLegSnapshot().json().contains(
                "\"pendingFundingHistoryCount\":4"));
        assertTrue(candidate.longLegSnapshot().json().contains(
                "\"pendingFundingHistoryCount\":4"));
        assertTrue(candidate.shortLegSnapshot().json().contains(
                "\"observedHour\":\"2026-08-21T05:00:00Z\""));
        assertTrue(candidate.shortLegSnapshot().json().contains(
                "\"observedHour\":\"2026-08-21T08:00:00Z\""));
    }

    @Test
    void stalePendingFundingMakesTheRunPartialAndCandidateUnscorable() {
        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(), funding(Freshness.STALE),
                markets(), configuration());

        assertEquals(XvfSignalRun.CaptureStatus.PARTIAL, run.captureStatus());
        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.ScoreStatus.UNSCORABLE, candidate.scoreStatus());
        assertNull(candidate.ranks().shadowBookRank());
        assertNull(candidate.expectedNet().expectedNetBps());
        assertTrue(candidate.decisionReasons().json().contains("PENDING_FUNDING_STALE"));
        assertTrue(run.dataIssues().json().contains("PENDING_FUNDING_STALE"));
    }

    @Test
    void informationalCaptureTimingDoesNotInflatePartialIssueCount() {
        Map<String, VenueSnapshot> withWarning = new java.util.LinkedHashMap<>(markets());
        VenueSnapshot bybit = withWarning.get("bybit");
        withWarning.put("bybit", new VenueSnapshot("bybit", bybit.instruments(), List.of(
                new SnapshotIssue(IssueSeverity.WARNING, "bybit", Optional.empty(),
                        "FIXTURE_WARNING", "one actionable input problem"))));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), withWarning, configuration());

        assertEquals(XvfSignalRun.CaptureStatus.PARTIAL, run.captureStatus());
        assertEquals(2, run.dataIssues().size());
        assertEquals("1 shadow input problem(s); see data_issues", run.failureDetail());
        assertTrue(run.dataIssues().json().contains("CAPTURE_WINDOW_MILLIS"));
    }

    @Test
    void failedCaptureHasNoCandidatesAndKeepsTheConfigurationIdentity() {
        XvfSignalRun run = new XvfShadowDecisionPlanner().failed(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), configuration(),
                "PUBLIC_MARKET_FAILED", "timeout");

        assertEquals(XvfSignalRun.CaptureStatus.FAILED, run.captureStatus());
        assertTrue(run.candidates().isEmpty());
        assertEquals("PUBLIC_MARKET_FAILED", run.failureCode());
        assertEquals(64, run.configurationHash().length());
        assertTrue(run.configurationSnapshot().json().contains(
                "DECLARED_CONFIGURATION_NOT_EXCHANGE"));
    }

    @Test
    void schedulerAttemptIdentityDoesNotChangeTheStrategyConfigurationHash() {
        XvfShadowConfiguration secondConfiguration = withScheduledAttemptId("test-attempt-2");
        XvfSignalRun first = new XvfShadowDecisionPlanner().failed(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), configuration(),
                "FIXTURE_FAILURE", "fixture");
        XvfSignalRun second = new XvfShadowDecisionPlanner().failed(
                UUID.randomUUID(), timing("test-attempt-2"), CUTOFF.plusSeconds(1),
                secondConfiguration, "FIXTURE_FAILURE", "fixture");

        assertEquals(first.configurationHash(), second.configurationHash());
        assertFalse(first.configurationSnapshot().json().contains("scheduledAttemptId"));
    }

    @Test
    void enforcesDeclaredPerVenueCapitalBeforeAssigningShadowRank() {
        XvfShadowConfiguration constrained = configuration(Map.of(
                "binance", new BigDecimal("2900"),
                "bybit", new BigDecimal("100"),
                "hyperliquid", new BigDecimal("1500")));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), markets(), constrained);

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(XvfSignalRun.ScoreStatus.SCORABLE, candidate.scoreStatus());
        assertNull(candidate.ranks().shadowBookRank());
        assertTrue(candidate.decisionReasons().json().contains(
                "DECLARED_VENUE_CAPITAL_INSUFFICIENT"));
        assertTrue(candidate.gateResults().json().contains("\"venueCapitalPass\":false"));
    }

    @Test
    void choosesTheOtherMakerRouteWhenTheCheapestFeeRouteCannotHedgeInsideTheCap() {
        Map<String, VenueSnapshot> markets = new java.util.LinkedHashMap<>(markets());
        InstrumentSnapshot hl = markets.get("hyperliquid").instruments().get(HL_SYMBOL);
        OrderBookSnapshot book = hl.orderBook().orElseThrow();
        InstrumentSnapshot shallowHlAsk = withBook(hl, book.bids(), List.of(
                new BookLevel(book.asks().getFirst().price(), BigDecimal.ONE, Optional.empty())));
        markets.put("hyperliquid", new VenueSnapshot("hyperliquid",
                Map.of(HL_SYMBOL, shallowHlAsk), List.of()));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), markets, configuration());

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.ScoreStatus.SCORABLE, candidate.scoreStatus());
        assertEquals("hyperliquid", candidate.route().makerVenue());
        assertEquals("bybit", candidate.route().takerVenue());
        assertDecimal("63.70000000", candidate.expectedNet().expectedNetBps());
        assertTrue(candidate.scoreComponents().json().contains("\"feasible\":false"));
        assertTrue(candidate.scoreComponents().json().contains(
                "TAKER_DEPTH_INSUFFICIENT_WITHIN_WORST_PRICE_CAP"));
    }

    @Test
    void rejectsDepthThatExistsOnlyBeyondTheProductionWorstPriceCap() {
        Map<String, VenueSnapshot> markets = new java.util.LinkedHashMap<>(markets());
        InstrumentSnapshot hl = markets.get("hyperliquid").instruments().get(HL_SYMBOL);
        OrderBookSnapshot hlBook = hl.orderBook().orElseThrow();
        BigDecimal hlAsk = hlBook.asks().getFirst().price();
        markets.put("hyperliquid", new VenueSnapshot("hyperliquid", Map.of(HL_SYMBOL,
                withBook(hl, hlBook.bids(), List.of(
                        new BookLevel(hlAsk, BigDecimal.ONE, Optional.empty()),
                        new BookLevel(hlAsk.multiply(new BigDecimal("1.01")),
                                new BigDecimal("1000000000"), Optional.empty())))), List.of()));

        InstrumentSnapshot bybit = markets.get("bybit").instruments().get(BYBIT_SYMBOL);
        OrderBookSnapshot bybitBook = bybit.orderBook().orElseThrow();
        BigDecimal bybitBid = bybitBook.bids().getFirst().price();
        markets.put("bybit", new VenueSnapshot("bybit", Map.of(BYBIT_SYMBOL,
                withBook(bybit, List.of(
                        new BookLevel(bybitBid, BigDecimal.ONE, Optional.empty()),
                        new BookLevel(bybitBid.multiply(new BigDecimal("0.99")),
                                new BigDecimal("1000000000"), Optional.empty())),
                        bybitBook.asks())), List.of()));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), markets, configuration());

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(XvfSignalRun.ScoreStatus.UNSCORABLE, candidate.scoreStatus());
        assertNull(candidate.ranks().shadowBookRank());
        assertTrue(candidate.decisionReasons().json().contains(
                "TAKER_DEPTH_INSUFFICIENT_WITHIN_WORST_PRICE_CAP"));
    }

    @Test
    void rejectsAnInferredObservationCadenceThatDisagreesWithTheVenue() {
        XvfFundingSnapshot original = funding(Freshness.FRESH);
        Map<Instrument, PendingObservation> pending = new java.util.LinkedHashMap<>(
                original.pendingByInstrument());
        Instrument key = new Instrument("bybit", BYBIT_SYMBOL);
        PendingObservation value = pending.get(key);
        pending.put(key, new PendingObservation(value.instrument(), value.fundingRate(),
                value.observedHour(), value.observedAt(), value.targetStamp(), 16,
                IntervalSource.TARGET_STAMP_DELTA, value.freshness()));
        XvfFundingSnapshot mismatch = new XvfFundingSnapshot(CUTOFF, pending,
                original.pendingWatermarks(), original.settledWatermarks());

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(), mismatch,
                markets(), configuration());

        assertEquals(XvfSignalRun.CaptureStatus.PARTIAL, run.captureStatus());
        assertEquals(XvfSignalRun.ScoreStatus.UNSCORABLE,
                run.candidates().getFirst().scoreStatus());
        assertTrue(run.dataIssues().json().contains(
                "FUNDING_INTERVAL_DISAGREES_WITH_VENUE"));
    }

    @Test
    void preservesSignalInputsAndAllowsAnExplicitlyMissingActivityVolume() {
        Map<String, VenueSnapshot> markets = new java.util.LinkedHashMap<>(markets());
        InstrumentSnapshot bybit = markets.get("bybit").instruments().get(BYBIT_SYMBOL);
        ResponseTiming timing = bybit.activity().orElseThrow().timing();
        InstrumentSnapshot missingActivityValue = new InstrumentSnapshot(
                bybit.venue(), bybit.venueSymbol(), bybit.canonicalBase(),
                bybit.baseUnitsPerContract(), bybit.reference(),
                Optional.of(new ActivitySnapshot(Optional.empty(), timing)),
                bybit.topOfBook(), bybit.rules(), bybit.orderBook(), bybit.missingData());
        markets.put("bybit", new VenueSnapshot("bybit",
                Map.of(BYBIT_SYMBOL, missingActivityValue), List.of()));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), markets, configuration());

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(XvfSignalRun.ScoreStatus.SCORABLE, candidate.scoreStatus());
        assertTrue(candidate.shortLegSnapshot().json().contains("\"trailingRateCexDex\":0.02"));
        assertTrue(candidate.shortLegSnapshot().json().contains(
                "\"weeklyQuoteVolumeUsd\":7000000.0"));
        assertTrue(candidate.shortLegSnapshot().json().contains(
                "\"quoteVolume24hUsd\":null"));
    }

    @Test
    void storesExecutableEntryBasisSeparatelyFromTheMarkBasis() {
        Map<String, VenueSnapshot> markets = new java.util.LinkedHashMap<>(markets());
        InstrumentSnapshot bybit = markets.get("bybit").instruments().get(BYBIT_SYMBOL);
        TopOfBookSnapshot oldTop = bybit.topOfBook().orElseThrow();
        BigDecimal expensiveAsk = new BigDecimal("0.006006");
        TopOfBookSnapshot top = new TopOfBookSnapshot(oldTop.bidPrice(), oldTop.bidQuantity(),
                expensiveAsk, oldTop.askQuantity(), oldTop.timing());
        OrderBookSnapshot oldBook = bybit.orderBook().orElseThrow();
        OrderBookSnapshot book = new OrderBookSnapshot(oldBook.bids(), List.of(
                new BookLevel(expensiveAsk, new BigDecimal("1000000000"), Optional.empty())),
                oldBook.timing());
        InstrumentSnapshot dislocated = new InstrumentSnapshot(
                bybit.venue(), bybit.venueSymbol(), bybit.canonicalBase(),
                bybit.baseUnitsPerContract(), bybit.reference(), bybit.activity(),
                Optional.of(top), bybit.rules(), Optional.of(book), bybit.missingData());
        markets.put("bybit", new VenueSnapshot("bybit", Map.of(BYBIT_SYMBOL, dislocated), List.of()));

        XvfSignalRun run = new XvfShadowDecisionPlanner().plan(
                UUID.randomUUID(), timing(), CUTOFF.plusSeconds(1), signal(),
                funding(Freshness.FRESH), markets, configuration());

        XvfSignalRun.Candidate candidate = run.candidates().getFirst();
        assertTrue(candidate.expectedNet().entryBasisBps().signum() > 0);
        assertTrue(candidate.scoreComponents().json().contains("\"markBasisBps\":0"));
    }

    static SignalEvaluation signal() {
        Leg shortLeg = new Leg("bybit", BYBIT_SYMBOL, 0.02, 0.02, 7_000_000, 0.0);
        Leg longLeg = new Leg("hyperliquid", HL_SYMBOL, 0.001, 0.001, 7_000_000, 0.0);
        PairAlternative alternative = new PairAlternative(
                "PEPE", shortLeg, longLeg, PairType.CEX_DEX, 80.0, 7_000_000);
        EvaluatedPair evaluated = new EvaluatedPair(
                1, alternative, false, 1.0, 80.0, true,
                true, true, true, 1);
        Candidate baseline = new Candidate("PEPE", shortLeg, longLeg, 80.0, 7_000_000);
        return new SignalEvaluation(LocalDate.of(2026, 8, 21),
                List.of(evaluated), List.of(baseline));
    }

    static XvfFundingSnapshot funding(Freshness observationFreshness) {
        Instrument bybit = new Instrument("bybit", BYBIT_SYMBOL);
        Instrument hyperliquid = new Instrument("hyperliquid", HL_SYMBOL);
        Instant observed = observationFreshness == Freshness.FRESH
                ? CUTOFF.minusSeconds(60) : CUTOFF.minus(Duration.ofHours(3));
        PendingObservation bybitObservation = new PendingObservation(
                bybit, new BigDecimal("0.0002"), observed.truncatedTo(java.time.temporal.ChronoUnit.HOURS),
                observed, Instant.parse("2026-08-21T16:00:00Z"), 8,
                IntervalSource.TARGET_STAMP_DELTA, observationFreshness);
        PendingObservation hlObservation = new PendingObservation(
                hyperliquid, new BigDecimal("-0.0001"),
                observed.truncatedTo(java.time.temporal.ChronoUnit.HOURS), observed,
                Instant.parse("2026-08-21T10:00:00Z"), 1,
                IntervalSource.TARGET_STAMP_DELTA, observationFreshness);
        List<PendingVenueWatermark> pendingWatermarks = List.of(
                pendingWatermark("binance", Freshness.FRESH),
                pendingWatermark("bybit", observationFreshness),
                pendingWatermark("hyperliquid", observationFreshness));
        List<SettledVenueWatermark> settled = List.of(
                settled("binance"), settled("bybit"), settled("hyperliquid"));
        return new XvfFundingSnapshot(CUTOFF,
                Map.of(bybit, bybitObservation, hyperliquid, hlObservation),
                pendingWatermarks, settled);
    }

    private static XvfFundingSnapshot withFourObservationHistory(XvfFundingSnapshot snapshot) {
        Map<Instrument, List<PendingObservation>> histories = new java.util.LinkedHashMap<>();
        snapshot.pendingByInstrument().forEach((instrument, latest) -> {
            List<PendingObservation> observations = new java.util.ArrayList<>();
            for (int hoursAgo = 3; hoursAgo >= 0; hoursAgo--) {
                observations.add(new PendingObservation(
                        instrument,
                        latest.fundingRate(),
                        latest.observedHour().minus(Duration.ofHours(hoursAgo)),
                        latest.observedAt().minus(Duration.ofHours(hoursAgo)),
                        latest.targetStamp(),
                        latest.fundingIntervalHours(),
                        latest.intervalSource(),
                        latest.freshness()));
            }
            histories.put(instrument, List.copyOf(observations));
        });
        return new XvfFundingSnapshot(
                snapshot.cutoffUtc(), snapshot.pendingByInstrument(), histories,
                snapshot.pendingWatermarks(), snapshot.settledWatermarks());
    }

    static Map<String, VenueSnapshot> markets() {
        InstrumentSnapshot bybit = instrument("bybit", BYBIT_SYMBOL, "PEPE", "1000",
                "0.006000", "0.005999", "0.006001", "1");
        InstrumentSnapshot hl = instrument("hyperliquid", HL_SYMBOL, "PEPE", "1",
                "0.000006", "0.000005999", "0.000006001", "1");
        return Map.of(
                "bybit", new VenueSnapshot("bybit", Map.of(BYBIT_SYMBOL, bybit), List.of()),
                "hyperliquid", new VenueSnapshot(
                        "hyperliquid", Map.of(HL_SYMBOL, hl), List.of()),
                "binance", new VenueSnapshot("binance", Map.of(), List.of()));
    }

    static XvfShadowConfiguration configuration() {
        return configuration(Map.of("binance", new BigDecimal("1500"),
                "bybit", new BigDecimal("1500"),
                "hyperliquid", new BigDecimal("1500")));
    }

    static XvfShadowConfiguration configuration(Map<String, BigDecimal> venueCapital) {
        return new XvfShadowConfiguration(
                new BigDecimal("4500"),
                venueCapital,
                XvfShadowConfiguration.measuredFees(),
                72,
                Duration.ofMinutes(100),
                Duration.ofHours(36),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                Duration.ofSeconds(30),
                new BigDecimal("25"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                ZoneId.of("Europe/Chisinau"),
                "test-revision",
                "xvf-shadow-test",
                "test-attempt-1");
    }

    private static XvfShadowConfiguration withScheduledAttemptId(String scheduledAttemptId) {
        XvfShadowConfiguration base = configuration();
        return new XvfShadowConfiguration(
                base.capitalUsd(), base.venueCapitalUsd(), base.feeSchedules(),
                base.plannedHoldHours(), base.maximumPendingFundingAge(),
                base.maximumSettledFundingAge(), base.maximumQuoteAge(),
                base.maximumCrossVenueQuoteSkew(), base.maximumCaptureDuration(),
                base.maximumTakerSlippageBps(), base.expectedBasisCaptureFactor(),
                base.riskPenaltyBps(), base.productionZone(), base.codeRevision(),
                base.strategyVersion(), scheduledAttemptId);
    }

    private static InstrumentSnapshot withBook(
            InstrumentSnapshot original,
            List<BookLevel> bids,
            List<BookLevel> asks) {
        return new InstrumentSnapshot(
                original.venue(), original.venueSymbol(), original.canonicalBase(),
                original.baseUnitsPerContract(), original.reference(), original.activity(),
                original.topOfBook(), original.rules(), Optional.of(new OrderBookSnapshot(
                        bids, asks, original.orderBook().orElseThrow().timing())),
                original.missingData());
    }

    private static InstrumentSnapshot instrument(
            String venue,
            String symbol,
            String base,
            String multiplier,
            String mark,
            String bid,
            String ask,
            String quantityStep) {
        ResponseTiming timing = new ResponseTiming(
                RECEIVED.minusMillis(100), Optional.of(RECEIVED), RECEIVED);
        ReferenceSnapshot reference = new ReferenceSnapshot(
                Optional.of(new BigDecimal(mark)), Optional.of(new BigDecimal(mark)),
                Optional.of(new BigDecimal(mark)), Optional.of(BigDecimal.ZERO),
                Optional.of(Instant.parse("hyperliquid".equals(venue)
                        ? "2026-08-21T10:00:00Z" : "2026-08-21T16:00:00Z")),
                Optional.of("hyperliquid".equals(venue) ? 1 : 8), Optional.empty(), timing);
        BigDecimal bidPrice = new BigDecimal(bid);
        BigDecimal askPrice = new BigDecimal(ask);
        BigDecimal depthQuantity = new BigDecimal("1000000000");
        TopOfBookSnapshot top = new TopOfBookSnapshot(
                bidPrice, depthQuantity, askPrice, depthQuantity, timing);
        InstrumentRules rules = new InstrumentRules(
                Optional.of(new BigDecimal("0.000000001")),
                Optional.of(new BigDecimal(quantityStep)),
                Optional.of(new BigDecimal(quantityStep)),
                Optional.of(new BigDecimal("10")),
                Optional.empty(), Optional.of(20), true, timing);
        OrderBookSnapshot book = new OrderBookSnapshot(
                List.of(new BookLevel(bidPrice, depthQuantity, Optional.empty())),
                List.of(new BookLevel(askPrice, depthQuantity, Optional.empty())), timing);
        return new InstrumentSnapshot(
                venue, symbol, base, new BigDecimal(multiplier), Optional.of(reference),
                Optional.of(new ActivitySnapshot(Optional.of(new BigDecimal("10000000")), timing)),
                Optional.of(top), Optional.of(rules), Optional.of(book), List.of());
    }

    private static PendingVenueWatermark pendingWatermark(String venue, Freshness freshness) {
        return new PendingVenueWatermark(venue, CUTOFF.minusSeconds(60), 100,
                freshness == Freshness.FRESH ? 100 : 0,
                freshness == Freshness.FRESH ? 0 : 100, freshness);
    }

    private static SettledVenueWatermark settled(String venue) {
        return new SettledVenueWatermark(venue, CUTOFF.minusSeconds(3_600), Freshness.FRESH);
    }

    private static XvfCaptureTiming timing() {
        return timing("test-attempt-1");
    }

    private static XvfCaptureTiming timing(String scheduledAttemptId) {
        Instant started = CUTOFF.minusSeconds(2);
        Instant ended = CUTOFF.plusSeconds(1);
        return new XvfCaptureTiming(CUTOFF, CUTOFF, started, ended, scheduledAttemptId);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                () -> "expected " + expected + " but was " + actual);
    }
}
