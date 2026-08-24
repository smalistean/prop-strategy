package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XvfShadowSnapshotServiceTest {

    private static final Instant CUTOFF = Instant.parse("2026-08-21T09:00:00Z");

    @Test
    void capturesAndPersistsOneReportOnlyRun() {
        List<XvfSignalRun> persisted = new ArrayList<>();
        RecordingVenueSource bybit = source("bybit",
                XvfShadowDecisionPlannerTest.markets().get("bybit"));
        RecordingVenueSource hyperliquid = source("hyperliquid",
                XvfShadowDecisionPlannerTest.markets().get("hyperliquid"));
        RecordingVenueSource binance = source("binance",
                XvfShadowDecisionPlannerTest.markets().get("binance"));
        XvfShadowSnapshotService service = new XvfShadowSnapshotService(
                ignored -> XvfShadowDecisionPlannerTest.signal(),
                (cutoff, policy) -> {
                    assertEquals(CUTOFF, cutoff);
                    return XvfShadowDecisionPlannerTest.funding(
                            XvfFundingSnapshot.Freshness.FRESH);
                },
                List.of(binance, bybit, hyperliquid),
                new XvfShadowDecisionPlanner(),
                persisted::add,
                XvfShadowDecisionPlannerTest.configuration(),
                Clock.fixed(CUTOFF, ZoneOffset.UTC));

        XvfSignalRun run = service.capture(LocalDate.of(2026, 8, 21));

        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(List.of(run), persisted);
        assertFalse(binance.called);
        assertEquals(Set.of("1000PEPEUSDT"), bybit.requested);
        assertEquals(Set.of("PEPE"), hyperliquid.requested);
    }

    @Test
    void recordsScheduledDecisionAndCaptureWindowTiming() {
        List<XvfSignalRun> persisted = new ArrayList<>();
        XvfShadowSnapshotService service = new XvfShadowSnapshotService(
                ignored -> XvfShadowDecisionPlannerTest.signal(),
                (cutoff, policy) -> XvfShadowDecisionPlannerTest.funding(
                        XvfFundingSnapshot.Freshness.FRESH),
                List.of(source("bybit", XvfShadowDecisionPlannerTest.markets().get("bybit")),
                        source("hyperliquid", XvfShadowDecisionPlannerTest.markets().get("hyperliquid")),
                        source("binance", XvfShadowDecisionPlannerTest.markets().get("binance"))),
                new XvfShadowDecisionPlanner(),
                persisted::add,
                XvfShadowDecisionPlannerTest.configuration(),
                Clock.fixed(CUTOFF, ZoneOffset.UTC));

        XvfSignalRun run = service.capture(LocalDate.of(2026, 8, 21));

        assertEquals(CUTOFF, run.scheduledDecisionAt());
        assertEquals(CUTOFF, run.cutoffUtc());
        assertNotNull(run.captureStartedAt());
        assertNotNull(run.captureEndedAt());
        assertEquals(CUTOFF, run.captureStartedAt());
        assertEquals(CUTOFF, run.captureEndedAt());
        assertEquals("test-attempt-1", run.scheduledAttemptId());
        assertEquals(run.captureStatus(), XvfSignalRun.CaptureStatus.COMPLETE);
    }

    @Test
    void acceptsMarketFactsCollectedAfterCaptureStartedAndBeforeCutoff() {
        Instant captureStarted = CUTOFF.minusSeconds(2);
        Instant captureEnded = CUTOFF.plusMillis(500);
        SequenceClock clock = new SequenceClock(ZoneOffset.UTC,
                captureStarted, CUTOFF, captureEnded, CUTOFF.plusSeconds(1));
        XvfShadowSnapshotService service = new XvfShadowSnapshotService(
                ignored -> XvfShadowDecisionPlannerTest.signal(),
                (cutoff, policy) -> {
                    assertEquals(CUTOFF, cutoff);
                    return XvfShadowDecisionPlannerTest.funding(
                            XvfFundingSnapshot.Freshness.FRESH);
                },
                List.of(source("bybit", XvfShadowDecisionPlannerTest.markets().get("bybit")),
                        source("hyperliquid",
                                XvfShadowDecisionPlannerTest.markets().get("hyperliquid")),
                        source("binance", XvfShadowDecisionPlannerTest.markets().get("binance"))),
                new XvfShadowDecisionPlanner(),
                ignored -> { },
                XvfShadowDecisionPlannerTest.configuration(),
                clock);

        XvfSignalRun run = service.capture(LocalDate.of(2026, 8, 21));

        assertEquals(XvfSignalRun.CaptureStatus.COMPLETE, run.captureStatus());
        assertEquals(XvfSignalRun.ScoreStatus.SCORABLE,
                run.candidates().getFirst().scoreStatus());
        assertEquals(captureStarted, run.captureStartedAt());
        assertEquals(CUTOFF, run.cutoffUtc());
        assertEquals(captureEnded, run.captureEndedAt());
    }

    @Test
    void signalFailureStillPersistsExactlyOneFailedAuditRow() {
        List<XvfSignalRun> persisted = new ArrayList<>();
        XvfShadowSnapshotService service = new XvfShadowSnapshotService(
                ignored -> {
                    throw new IllegalStateException("settled funding unavailable");
                },
                (cutoff, policy) -> {
                    throw new AssertionError("funding source must not run after signal failure");
                },
                List.of(),
                new XvfShadowDecisionPlanner(),
                persisted::add,
                XvfShadowDecisionPlannerTest.configuration(),
                Clock.fixed(CUTOFF, ZoneOffset.UTC));

        XvfSignalRun run = service.capture(LocalDate.of(2026, 8, 21));

        assertEquals(XvfSignalRun.CaptureStatus.FAILED, run.captureStatus());
        assertEquals(1, persisted.size());
        assertTrue(run.failureDetail().contains("settled funding unavailable"));
        assertTrue(run.candidates().isEmpty());
    }

    @Test
    void requestsDetailedMarketDataOnlyForCandidatesPassingSignalGates() {
        Map<String, Set<String>> requested = XvfShadowSnapshotService.requestedSymbols(
                XvfShadowDecisionPlannerTest.signal());

        assertEquals(Set.of("1000PEPEUSDT"), requested.get("bybit"));
        assertEquals(Set.of("PEPE"), requested.get("hyperliquid"));
        assertTrue(requested.get("binance").isEmpty());
    }

    private static RecordingVenueSource source(String venue, VenueSnapshot snapshot) {
        return new RecordingVenueSource(venue, snapshot);
    }

    private static final class RecordingVenueSource implements XvfVenueSnapshotSource {
        private final String venue;
        private final VenueSnapshot snapshot;
        private boolean called;
        private Set<String> requested = Set.of();

        private RecordingVenueSource(String venue, VenueSnapshot snapshot) {
            this.venue = venue;
            this.snapshot = snapshot;
        }

        @Override
        public String venue() {
            return venue;
        }

        @Override
        public VenueSnapshot fetch(Set<String> venueSymbols) {
            called = true;
            requested = Set.copyOf(venueSymbols);
            return snapshot;
        }
    }

    private static final class SequenceClock extends Clock {
        private final ZoneId zone;
        private final List<Instant> instants;
        private int index;

        private SequenceClock(ZoneId zone, Instant... instants) {
            this.zone = zone;
            this.instants = List.copyOf(Arrays.asList(instants));
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId requestedZone) {
            return new SequenceClock(requestedZone,
                    instants.subList(index, instants.size()).toArray(Instant[]::new));
        }

        @Override
        public synchronized Instant instant() {
            if (index >= instants.size()) {
                throw new AssertionError("Clock was read more times than expected");
            }
            return instants.get(index++);
        }
    }
}
