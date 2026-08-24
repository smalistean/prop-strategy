package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.IssueSeverity;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.SnapshotIssue;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.EvaluatedPair;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.SignalEvaluation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Orchestrates one isolated shadow capture and persists exactly one final audit run. */
public final class XvfShadowSnapshotService {

    /**
     * Shared upper bound for concurrent symbol-level HTTP requests. Individual venues apply their
     * own stricter limits inside their source implementation.
     */
    static final int SHARED_SYMBOL_CONCURRENCY = 20;

    private final SignalSource signalSource;
    private final XvfFundingSnapshotSource fundingSource;
    private final List<XvfVenueSnapshotSource> venueSources;
    private final XvfShadowDecisionPlanner planner;
    private final RunSink runSink;
    private final XvfShadowConfiguration configuration;
    private final Clock clock;

    public XvfShadowSnapshotService(
            SignalSource signalSource,
            XvfFundingSnapshotSource fundingSource,
            List<XvfVenueSnapshotSource> venueSources,
            XvfShadowDecisionPlanner planner,
            RunSink runSink,
            XvfShadowConfiguration configuration,
            Clock clock) {
        this.signalSource = java.util.Objects.requireNonNull(signalSource, "signalSource");
        this.fundingSource = java.util.Objects.requireNonNull(fundingSource, "fundingSource");
        this.venueSources = List.copyOf(java.util.Objects.requireNonNull(
                venueSources, "venueSources"));
        this.planner = java.util.Objects.requireNonNull(planner, "planner");
        this.runSink = java.util.Objects.requireNonNull(runSink, "runSink");
        this.configuration = java.util.Objects.requireNonNull(configuration, "configuration");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        Set<String> names = new LinkedHashSet<>();
        for (XvfVenueSnapshotSource source : venueSources) {
            if (!names.add(source.venue())) {
                throw new IllegalArgumentException("Duplicate venue source " + source.venue());
            }
        }
    }

    /**
     * Runs signal and public reads, then writes one COMPLETE/PARTIAL/FAILED immutable record.
     * Persistence is outside the capture catch: a database write failure is never mistaken for a
     * second failed capture with the same UUID.
     */
    public XvfSignalRun capture(LocalDate asOf) {
        return captureInternal(asOf, null);
    }

    /** Captures a scheduler run while preserving its intended decision timestamp across retries. */
    public XvfSignalRun capture(LocalDate asOf, Instant scheduledDecisionAt) {
        return captureInternal(asOf,
                java.util.Objects.requireNonNull(scheduledDecisionAt, "scheduledDecisionAt"));
    }

    private XvfSignalRun captureInternal(LocalDate asOf, Instant suppliedScheduledDecisionAt) {
        java.util.Objects.requireNonNull(asOf, "asOf");
        UUID runId = UUID.randomUUID();
        Instant captureStartedAt = now();
        Instant scheduledDecisionAt = suppliedScheduledDecisionAt == null
                ? captureStartedAt : suppliedScheduledDecisionAt;
        if (scheduledDecisionAt.getNano() % 1_000 != 0) {
            throw new IllegalArgumentException(
                    "scheduledDecisionAt exceeds PostgreSQL microsecond precision");
        }
        if (scheduledDecisionAt.isAfter(captureStartedAt)) {
            throw new IllegalArgumentException("scheduledDecisionAt cannot follow capture start");
        }
        if (!asOf.equals(scheduledDecisionAt.atZone(
                configuration.productionZone()).toLocalDate())) {
            throw new IllegalArgumentException(
                    "asOf must equal scheduledDecisionAt date in production zone");
        }
        ExecutorService coordinator = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("xvf-shadow-capture-", 0).factory());
        Future<XvfSignalRun> attempt = coordinator.submit(() -> captureAttempt(
                runId, asOf, scheduledDecisionAt, captureStartedAt));
        Instant cutoffUtc = captureStartedAt;
        XvfSignalRun run;
        try {
            run = attempt.get(configuration.maximumCaptureDuration().toNanos(),
                    TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            attempt.cancel(true);
            Instant captureEndedAt = now();
            cutoffUtc = captureEndedAt;
            XvfCaptureTiming timing = new XvfCaptureTiming(
                    scheduledDecisionAt, cutoffUtc,
                    captureStartedAt, captureEndedAt,
                    configuration.scheduledAttemptId());
            run = planner.failed(runId, timing, now(), configuration,
                    "CAPTURE_DEADLINE_EXCEEDED",
                    "Capture exceeded configured maximum "
                            + configuration.maximumCaptureDuration()
                            + "; unfinished work was cancelled");
        } catch (InterruptedException interrupted) {
            attempt.cancel(true);
            Thread.currentThread().interrupt();
            Instant captureEndedAt = now();
            cutoffUtc = captureEndedAt;
            XvfCaptureTiming timing = new XvfCaptureTiming(
                    scheduledDecisionAt, cutoffUtc,
                    captureStartedAt, captureEndedAt,
                    configuration.scheduledAttemptId());
            run = planner.failed(runId, timing, now(), configuration,
                    "SHADOW_CAPTURE_INTERRUPTED", failureDetail(interrupted));
        } catch (ExecutionException failure) {
            throw new IllegalStateException("Unexpected shadow capture task failure",
                    failure.getCause());
        } finally {
            coordinator.shutdownNow();
        }
        runSink.insert(run);
        return run;
    }

    private XvfSignalRun captureAttempt(
            UUID runId,
            LocalDate asOf,
            Instant scheduledDecisionAt,
            Instant captureStartedAt) {
        Instant cutoffUtc = captureStartedAt;
        XvfSignalRun run;
        try {
            SignalEvaluation signal = signalSource.evaluate(asOf);
            Map<String, Set<String>> requested = requestedSymbols(signal);
            Map<String, VenueSnapshot> markets = fetchMarkets(requested);
            cutoffUtc = now();
            if (!asOf.equals(scheduledDecisionAt.atZone(configuration.productionZone()).toLocalDate())
                    || !asOf.equals(cutoffUtc.atZone(configuration.productionZone()).toLocalDate())) {
                throw new IllegalStateException("The capture date crossed or differs from the live "
                        + "production date; historical public-market snapshots are not supported");
            }
            XvfFundingSnapshot funding = fundingSource.read(cutoffUtc,
                    new XvfFundingSnapshotSource.FreshnessPolicy(
                            configuration.maximumPendingFundingAge(),
                            configuration.maximumSettledFundingAge()));
            Instant captureEndedAt = now();
            Duration captureDuration = Duration.between(captureStartedAt, captureEndedAt);
            if (captureDuration.compareTo(configuration.maximumCaptureDuration()) > 0) {
                markets = withCaptureDurationIssue(markets,
                        "CAPTURE_DURATION_EXCEEDED",
                        "Capture window " + captureDuration + " exceeds configured maximum "
                                + configuration.maximumCaptureDuration());
            }
            XvfCaptureTiming timing = new XvfCaptureTiming(
                    scheduledDecisionAt, cutoffUtc,
                    captureStartedAt, captureEndedAt,
                    configuration.scheduledAttemptId());
            run = planner.plan(runId, timing, now(), signal, funding, markets, configuration);
        } catch (Exception failure) {
            Instant captureEndedAt = now();
            XvfCaptureTiming timing = new XvfCaptureTiming(
                    scheduledDecisionAt, cutoffUtc,
                    captureStartedAt, captureEndedAt,
                    configuration.scheduledAttemptId());
            run = planner.failed(runId, timing, now(), configuration,
                    "SHADOW_CAPTURE_FAILED", failureDetail(failure));
        }
        return run;
    }

    private Map<String, VenueSnapshot> fetchMarkets(Map<String, Set<String>> requested) {
        Map<String, Future<VenueSnapshot>> futures = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(
                SHARED_SYMBOL_CONCURRENCY,
                Thread.ofPlatform().daemon().name("xvf-shadow-market-", 0).factory());
        try {
            for (XvfVenueSnapshotSource source : venueSources) {
                Set<String> symbols = requested.getOrDefault(source.venue(), Set.of());
                if (symbols.isEmpty()) {
                    futures.put(source.venue(), executor.submit(() ->
                            new VenueSnapshot(source.venue(), Map.of(), List.of())));
                } else {
                    futures.put(source.venue(), executor.submit(() ->
                            safeFetch(source, symbols, executor)));
                }
            }
            Map<String, VenueSnapshot> out = new LinkedHashMap<>();
            for (Map.Entry<String, Future<VenueSnapshot>> entry : futures.entrySet()) {
                try {
                    out.put(entry.getKey(), entry.getValue().get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted collecting shadow venue data", e);
                } catch (ExecutionException e) {
                    throw new IllegalStateException("Unexpected shadow venue task failure", e.getCause());
                }
            }
            return Map.copyOf(out);
        } finally {
            futures.values().forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private static VenueSnapshot safeFetch(XvfVenueSnapshotSource source, Set<String> symbols,
                                           ExecutorService executor) {
        try {
            return source.fetch(symbols, executor);
        } catch (RuntimeException failure) {
            SnapshotIssue issue = new SnapshotIssue(
                    IssueSeverity.ERROR,
                    source.venue(),
                    Optional.empty(),
                    "VENUE_SNAPSHOT_FAILED",
                    failureDetail(failure));
            return new VenueSnapshot(source.venue(), Map.of(), List.of(issue));
        }
    }

    static Map<String, Set<String>> requestedSymbols(SignalEvaluation signal) {
        Map<String, Set<String>> mutable = new LinkedHashMap<>();
        for (String venue : XvfConfig.VENUES) {
            mutable.put(venue, new LinkedHashSet<>());
        }
        for (EvaluatedPair pair : signal.alternatives()) {
            if (!pair.adjustedSpreadPass() || !pair.volumePass()) {
                continue;
            }
            mutable.computeIfAbsent(pair.alternative().shortLeg().venue(), ignored ->
                    new LinkedHashSet<>()).add(pair.alternative().shortLeg().venueSymbol());
            mutable.computeIfAbsent(pair.alternative().longLeg().venue(), ignored ->
                    new LinkedHashSet<>()).add(pair.alternative().longLeg().venueSymbol());
        }
        Map<String, Set<String>> out = new LinkedHashMap<>();
        mutable.forEach((venue, symbols) -> out.put(venue, Set.copyOf(symbols)));
        return Map.copyOf(out);
    }

    private static Map<String, VenueSnapshot> withCaptureDurationIssue(
            Map<String, VenueSnapshot> markets,
            String code,
            String detail) {
        Map<String, VenueSnapshot> out = new LinkedHashMap<>(markets);
        SnapshotIssue issue = new SnapshotIssue(
                IssueSeverity.WARNING, "CAPTURE", Optional.empty(), code, detail);
        for (String venue : XvfConfig.VENUES) {
            VenueSnapshot existing = out.get(venue);
            if (existing == null) {
                out.put(venue, new VenueSnapshot(venue, Map.of(), List.of(issue)));
            } else {
                List<SnapshotIssue> issues = new ArrayList<>(existing.issues());
                issues.add(issue);
                out.put(venue, new VenueSnapshot(venue, existing.instruments(), issues));
            }
        }
        return Map.copyOf(out);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String failureDetail(Throwable failure) {
        List<String> chain = new ArrayList<>();
        Throwable current = failure;
        while (current != null && chain.size() < 4) {
            String message = current.getMessage();
            chain.add(current.getClass().getSimpleName()
                    + (message == null || message.isBlank() ? "" : ": " + message));
            current = current.getCause();
        }
        return String.join(" <- ", chain);
    }

    @FunctionalInterface
    public interface SignalSource {
        SignalEvaluation evaluate(LocalDate asOf) throws Exception;
    }

    @FunctionalInterface
    public interface RunSink {
        void insert(XvfSignalRun run);
    }
}
