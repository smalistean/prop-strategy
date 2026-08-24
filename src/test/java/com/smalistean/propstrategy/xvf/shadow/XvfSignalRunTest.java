package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.CaptureStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ExpectedNet;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.PairType;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class XvfSignalRunTest {

    @Test
    void defensivelyCopiesCandidateList() {
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(XvfSignalRunFixtures.binanceBybit(1, 1, 1, 2));

        XvfSignalRun run = XvfSignalRunFixtures.complete(UUID.randomUUID(), candidates);
        candidates.clear();

        assertEquals(1, run.candidates().size());
        assertThrows(UnsupportedOperationException.class, () -> run.candidates().clear());
    }

    @Test
    void failedRunCannotContainCandidates() {
        Candidate candidate = XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1);
        XvfSignalRun complete = XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of(candidate));

        assertThrows(IllegalArgumentException.class, () -> new XvfSignalRun(
                UUID.randomUUID(), complete.snapshotSchemaVersion(), complete.scheduledDecisionAt(),
                complete.cutoffUtc(), complete.captureStartedAt(), complete.captureEndedAt(),
                complete.productionDate(), complete.productionZone(), complete.generatedAt(),
                complete.scheduledAttemptId(), complete.codeRevision(), complete.strategyVersion(),
                complete.configurationHash(), complete.configurationSnapshot(),
                complete.settledFundingWatermarks(), complete.pendingFundingWatermarks(),
                complete.venueStateSnapshot(), null, JsonDocument.array("[{\"code\":\"FAILED\"}]"),
                CaptureStatus.FAILED, "FAILED", "failure", List.of(candidate)));
    }

    @Test
    void validatesJsonAtTheBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new JsonDocument("not-json"));
        assertThrows(IllegalArgumentException.class, () -> JsonDocument.object("[]"));
        assertThrows(IllegalArgumentException.class, () -> JsonDocument.array("{}"));
        assertThrows(IllegalArgumentException.class, () -> new JsonDocument("null"));
        assertThrows(IllegalArgumentException.class, () -> JsonDocument.object("{} {}"));
        assertThrows(IllegalArgumentException.class, () -> JsonDocument.object("{\"x\":1,\"x\":2}"));
    }

    @Test
    void rejectsValuesPostgresWouldSilentlyRound() {
        assertThrows(IllegalArgumentException.class, () -> new ExpectedNet(
                null, null, null, null,
                new BigDecimal("0.000000001"), null, null, null, null));

        XvfSignalRun valid = XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of());
        Instant overPrecise = valid.cutoffUtc().plusNanos(1);
        assertThrows(IllegalArgumentException.class, () -> new XvfSignalRun(
                UUID.randomUUID(), valid.snapshotSchemaVersion(), valid.scheduledDecisionAt(),
                overPrecise, valid.captureStartedAt(), valid.captureEndedAt(),
                valid.productionDate(), valid.productionZone(), valid.generatedAt(),
                valid.scheduledAttemptId(), valid.codeRevision(), valid.strategyVersion(),
                valid.configurationHash(), valid.configurationSnapshot(),
                valid.settledFundingWatermarks(), valid.pendingFundingWatermarks(),
                valid.venueStateSnapshot(), valid.capitalUsd(), valid.dataIssues(),
                valid.captureStatus(), null, null, List.of()));
    }

    @Test
    void productionDateMustMatchCutoffAndZone() {
        XvfSignalRun valid = XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of());

        assertThrows(IllegalArgumentException.class, () -> new XvfSignalRun(
                UUID.randomUUID(), valid.snapshotSchemaVersion(), valid.scheduledDecisionAt(),
                valid.cutoffUtc(), valid.captureStartedAt(), valid.captureEndedAt(),
                valid.productionDate().plusDays(1), valid.productionZone(), valid.generatedAt(),
                valid.scheduledAttemptId(), valid.codeRevision(), valid.strategyVersion(),
                valid.configurationHash(), valid.configurationSnapshot(),
                valid.settledFundingWatermarks(), valid.pendingFundingWatermarks(),
                valid.venueStateSnapshot(), valid.capitalUsd(), valid.dataIssues(),
                valid.captureStatus(), null, null, List.of()));
    }

    @Test
    void scoreStatusCannotHideMissingOrContradictoryData() {
        Candidate valid = XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1);
        ExpectedNet missingBasis = new ExpectedNet(
                valid.expectedNet().pendingFundingSpreadBps(), null,
                valid.expectedNet().expectedFundingBps(), valid.expectedNet().expectedBasisPnlBps(),
                valid.expectedNet().expectedEntryFeeBps(), valid.expectedNet().expectedExitFeeBps(),
                valid.expectedNet().expectedSlippageBps(), valid.expectedNet().riskPenaltyBps(),
                valid.expectedNet().expectedNetBps());

        assertThrows(IllegalArgumentException.class, () -> new Candidate(
                valid.evaluationOrder(), valid.pair(), valid.ranks(), valid.signalScore(), valid.route(),
                missingBasis, valid.requestedLegNotionalUsd(), valid.shortLegSnapshot(),
                valid.longLegSnapshot(), valid.scoreComponents(), valid.gateResults(),
                ScoreStatus.SCORABLE, valid.decisionReasons()));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(
                valid.evaluationOrder(), valid.pair(), valid.ranks(), valid.signalScore(), valid.route(),
                valid.expectedNet(), valid.requestedLegNotionalUsd(), valid.shortLegSnapshot(),
                valid.longLegSnapshot(), valid.scoreComponents(), valid.gateResults(),
                ScoreStatus.UNSCORABLE, JsonDocument.emptyArray()));
    }

    @Test
    void supportsDexToDexCandidates() {
        assertDoesNotThrow(() -> new Pair(
                "BTC", PairType.DEX_DEX,
                "hyperliquid", "BTC", "dydx", "BTC-USD"));
    }

    @Test
    void runRequiresContiguousEvaluationOrderAndGrossRanks() {
        Candidate first = XvfSignalRunFixtures.binanceBybit(1, 1, 1, 1);
        Candidate gapped = XvfSignalRunFixtures.binanceHyperliquid(2, 3, 2, 2);

        assertThrows(IllegalArgumentException.class,
                () -> XvfSignalRunFixtures.complete(UUID.randomUUID(), List.of(first, gapped)));
    }
}
