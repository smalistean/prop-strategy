package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.PostgresXvfCandidateOutcomeRepository.DueCandidate;
import com.smalistean.propstrategy.xvf.shadow.PostgresXvfCandidateOutcomeRepository.FundingFact;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.BookLevel;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ResponseTiming;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.SnapshotIssue;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Captures public exit books and already-settled funding for due shadow candidates. */
public final class XvfCandidateOutcomeService {

    private static final short FORMULA_INPUTS_VERSION = 1;

    private final PostgresXvfCandidateOutcomeRepository repository;
    private final List<XvfVenueSnapshotSource> venueSources;
    private final Clock clock;
    private final int captureToleranceSeconds;

    public XvfCandidateOutcomeService(
            PostgresXvfCandidateOutcomeRepository repository,
            List<XvfVenueSnapshotSource> venueSources,
            Clock clock,
            int captureToleranceSeconds) {
        this.repository = java.util.Objects.requireNonNull(repository, "repository");
        this.venueSources = List.copyOf(java.util.Objects.requireNonNull(venueSources, "venueSources"));
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        if (captureToleranceSeconds < 0) {
            throw new IllegalArgumentException("captureToleranceSeconds must not be negative");
        }
        this.captureToleranceSeconds = captureToleranceSeconds;
    }

    /** Captures at most {@code limit} candidates and returns every appended attempt. */
    public List<XvfCandidateOutcome> captureDue(int limit) {
        Instant captureStartedAt = now();
        List<DueCandidate> due = repository.findDue(captureStartedAt, limit);
        if (due.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> requested = requestedSymbols(due);
        Map<String, VenueSnapshot> markets = fetchMarkets(requested);
        List<XvfCandidateOutcome> outcomes = new ArrayList<>();
        for (DueCandidate candidate : due) {
            XvfCandidateOutcome outcome = outcome(candidate, markets, captureStartedAt);
            repository.insert(outcome);
            outcomes.add(outcome);
        }
        return List.copyOf(outcomes);
    }

    private XvfCandidateOutcome outcome(
            DueCandidate candidate, Map<String, VenueSnapshot> markets, Instant captureStartedAt) {
        List<Map<String, Object>> issues = new ArrayList<>();
        Optional<InstrumentSnapshot> shortMarket = instrument(
                markets, candidate.shortVenue(), candidate.shortVenueSymbol(), issues);
        Optional<InstrumentSnapshot> longMarket = instrument(
                markets, candidate.longVenue(), candidate.longVenueSymbol(), issues);
        boolean shortMarketComplete = usableExitBook(shortMarket, "short", candidate, issues);
        boolean longMarketComplete = usableExitBook(longMarket, "long", candidate, issues);
        boolean marketComplete = shortMarketComplete && longMarketComplete;

        List<FundingFact> funding;
        try {
            funding = repository.findFunding(candidate);
        } catch (RuntimeException failure) {
            issues.add(issue("FUNDING_QUERY_FAILED", failure.getMessage(), null, null));
            return record(candidate, captureStartedAt, now(), XvfCandidateOutcome.CaptureStatus.FAILED,
                    shortMarket, longMarket, List.of(), issues);
        }
        boolean shortFunding = funding.stream().anyMatch(fact ->
                fact.venue().equals(candidate.shortVenue())
                        && fact.venueSymbol().equals(candidate.shortVenueSymbol()));
        boolean longFunding = funding.stream().anyMatch(fact ->
                fact.venue().equals(candidate.longVenue())
                        && fact.venueSymbol().equals(candidate.longVenueSymbol()));
        if (!shortFunding) {
            issues.add(issue("SHORT_SETTLED_FUNDING_MISSING",
                    "No settled funding observation exists in the measurement window",
                    candidate.shortVenue(), candidate.shortVenueSymbol()));
        }
        if (!longFunding) {
            issues.add(issue("LONG_SETTLED_FUNDING_MISSING",
                    "No settled funding observation exists in the measurement window",
                    candidate.longVenue(), candidate.longVenueSymbol()));
        }

        Instant capturedAt = now();
        if (capturedAt.isAfter(candidate.targetExitUtc().plusSeconds(captureToleranceSeconds))) {
            issues.add(issue("OUTCOME_CAPTURE_LATE",
                    "Exit facts were captured after the configured target tolerance", null, null));
        }
        XvfCandidateOutcome.CaptureStatus status = marketComplete && shortFunding && longFunding
                ? XvfCandidateOutcome.CaptureStatus.COMPLETE
                : XvfCandidateOutcome.CaptureStatus.PARTIAL;
        return record(candidate, captureStartedAt, capturedAt, status,
                shortMarket, longMarket, funding, issues);
    }

    private XvfCandidateOutcome record(
            DueCandidate candidate,
            Instant captureStartedAt,
            Instant capturedAt,
            XvfCandidateOutcome.CaptureStatus status,
            Optional<InstrumentSnapshot> shortMarket,
            Optional<InstrumentSnapshot> longMarket,
            List<FundingFact> funding,
            List<Map<String, Object>> issues) {
        return new XvfCandidateOutcome(
                UUID.randomUUID(), candidate.signalRunId(), candidate.evaluationOrder(),
                candidate.horizonHours(), candidate.targetExitUtc(), captureStartedAt, capturedAt,
                captureToleranceSeconds, status,
                shortMarket.map(XvfCandidateOutcomeService::snapshot)
                        .orElseGet(JsonDocument::emptyObject),
                longMarket.map(XvfCandidateOutcomeService::snapshot)
                        .orElseGet(JsonDocument::emptyObject),
                XvfShadowJson.array(funding.stream().map(XvfCandidateOutcomeService::funding).toList()),
                XvfShadowJson.object(watermarks(candidate, funding)),
                XvfShadowJson.array(issues), FORMULA_INPUTS_VERSION);
    }

    private Map<String, VenueSnapshot> fetchMarkets(Map<String, Set<String>> requested) {
        Map<String, VenueSnapshot> markets = new LinkedHashMap<>();
        ExecutorService executor = Executors.newFixedThreadPool(
                XvfShadowSnapshotService.SHARED_SYMBOL_CONCURRENCY,
                Thread.ofPlatform().daemon().name("xvf-outcome-market-", 0).factory());
        try {
            for (XvfVenueSnapshotSource source : venueSources) {
                Set<String> symbols = requested.getOrDefault(source.venue(), Set.of());
                if (symbols.isEmpty()) {
                    continue;
                }
                try {
                    markets.put(source.venue(), source.fetch(symbols, executor));
                } catch (RuntimeException failure) {
                    markets.put(source.venue(), new VenueSnapshot(source.venue(), Map.of(), List.of(
                            new SnapshotIssue(XvfVenueSnapshotSource.IssueSeverity.ERROR,
                                    source.venue(), Optional.empty(), "VENUE_SNAPSHOT_FAILED",
                                    failure.getMessage() == null ? failure.getClass().getSimpleName()
                                            : failure.getMessage()))));
                }
            }
            return Map.copyOf(markets);
        } finally {
            executor.shutdownNow();
        }
    }

    private static Optional<InstrumentSnapshot> instrument(
            Map<String, VenueSnapshot> markets, String venue, String symbol,
            List<Map<String, Object>> issues) {
        VenueSnapshot venueSnapshot = markets.get(venue);
        if (venueSnapshot == null) {
            issues.add(issue("VENUE_SNAPSHOT_MISSING", "No source is configured for the venue",
                    venue, symbol));
            return Optional.empty();
        }
        venueSnapshot.issues().stream()
                .filter(value -> value.venueSymbol().isEmpty()
                        || value.venueSymbol().orElseThrow().equals(symbol))
                .forEach(value -> issues.add(issue(value.code(), value.detail(),
                        value.venue(), value.venueSymbol().orElse(null))));
        InstrumentSnapshot instrument = venueSnapshot.instruments().get(symbol);
        if (instrument == null) {
            issues.add(issue("INSTRUMENT_SNAPSHOT_MISSING", "No instrument snapshot was returned",
                    venue, symbol));
            return Optional.empty();
        }
        return Optional.of(instrument);
    }

    private static boolean usableExitBook(Optional<InstrumentSnapshot> market, String leg,
                                          DueCandidate candidate,
                                          List<Map<String, Object>> issues) {
        if (market.isEmpty()) {
            return false;
        }
        InstrumentSnapshot value = market.orElseThrow();
        boolean complete = true;
        if (value.reference().isEmpty()
                || value.reference().orElseThrow().markPrice().isEmpty()
                || value.reference().orElseThrow().indexPrice().isEmpty()) {
            issues.add(issue(leg.toUpperCase() + "_EXIT_REFERENCE_MISSING",
                    "Exit mark and index prices are required for basis diagnostics",
                    value.venue(), value.venueSymbol()));
            complete = false;
        }
        if (value.topOfBook().isEmpty()) {
            issues.add(issue(leg.toUpperCase() + "_EXIT_TOP_OF_BOOK_MISSING",
                    "Exit bid and ask are required for capture diagnostics",
                    value.venue(), value.venueSymbol()));
            complete = false;
        }
        if (value.orderBook().isEmpty()) {
            issues.add(issue(leg.toUpperCase() + "_EXIT_DEPTH_MISSING",
                    "Exit order-book depth is required for realized P&L",
                    value.venue(), value.venueSymbol()));
            return false;
        }
        List<BookLevel> closingSide = leg.equals("short")
                ? value.orderBook().orElseThrow().asks()
                : value.orderBook().orElseThrow().bids();
        boolean enough = closingSide.stream()
                .map(level -> level.price().multiply(level.quantity()))
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
                .compareTo(candidate.requestedLegNotionalUsd()) >= 0;
        if (!enough) {
            issues.add(issue(leg.toUpperCase() + "_EXIT_DEPTH_INSUFFICIENT",
                    "Captured close-side depth does not cover requested leg notional",
                    value.venue(), value.venueSymbol()));
        }
        return complete && enough;
    }

    private static JsonDocument snapshot(InstrumentSnapshot value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("venue", value.venue());
        out.put("venueSymbol", value.venueSymbol());
        out.put("canonicalBase", value.canonicalBase());
        out.put("baseUnitsPerContract", value.baseUnitsPerContract());
        out.put("missingData", value.missingData());
        value.reference().ifPresent(reference -> {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("markPrice", reference.markPrice().orElse(null));
            data.put("indexPrice", reference.indexPrice().orElse(null));
            data.put("midPrice", reference.midPrice().orElse(null));
            data.put("timing", timing(reference.timing()));
            out.put("reference", data);
        });
        value.topOfBook().ifPresent(top -> out.put("topOfBook", Map.of(
                "bidPrice", top.bidPrice(), "bidQuantity", top.bidQuantity(),
                "askPrice", top.askPrice(), "askQuantity", top.askQuantity(),
                "timing", timing(top.timing()))));
        value.orderBook().ifPresent(book -> out.put("orderBook", Map.of(
                "bids", levels(book.bids()), "asks", levels(book.asks()),
                "timing", timing(book.timing()))));
        return XvfShadowJson.object(out);
    }

    private static List<Map<String, Object>> levels(List<BookLevel> values) {
        return values.stream().map(level -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("price", level.price());
            out.put("quantity", level.quantity());
            out.put("orderCount", level.orderCount().orElse(null));
            return out;
        }).toList();
    }

    private static Map<String, Object> timing(ResponseTiming value) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestedAt", value.requestedAt().toString());
        out.put("sourceAt", value.sourceAt().map(Instant::toString).orElse(null));
        out.put("receivedAt", value.receivedAt().toString());
        return out;
    }

    private static Map<String, Object> funding(FundingFact value) {
        return Map.of(
                "venue", value.venue(), "venueSymbol", value.venueSymbol(),
                "fundingTime", value.fundingTime().toString(),
                "fundingRate", value.fundingRate());
    }

    private static Map<String, Object> watermarks(
            DueCandidate candidate, List<FundingFact> funding) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("short", watermark(funding, candidate.shortVenue(), candidate.shortVenueSymbol()));
        out.put("long", watermark(funding, candidate.longVenue(), candidate.longVenueSymbol()));
        return out;
    }

    private static String watermark(List<FundingFact> funding, String venue, String symbol) {
        return funding.stream()
                .filter(value -> value.venue().equals(venue) && value.venueSymbol().equals(symbol))
                .map(FundingFact::fundingTime).max(Instant::compareTo).map(Instant::toString)
                .orElse(null);
    }

    private static Map<String, Object> issue(
            String code, String detail, String venue, String symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("detail", detail == null ? "No detail supplied" : detail);
        out.put("venue", venue);
        out.put("venueSymbol", symbol);
        return out;
    }

    private static Map<String, Set<String>> requestedSymbols(List<DueCandidate> candidates) {
        Map<String, Set<String>> mutable = new LinkedHashMap<>();
        for (DueCandidate candidate : candidates) {
            mutable.computeIfAbsent(candidate.shortVenue(), ignored -> new LinkedHashSet<>())
                    .add(candidate.shortVenueSymbol());
            mutable.computeIfAbsent(candidate.longVenue(), ignored -> new LinkedHashSet<>())
                    .add(candidate.longVenueSymbol());
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        mutable.forEach((venue, symbols) -> result.put(venue, Set.copyOf(symbols)));
        return Map.copyOf(result);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
