package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Instrument;
import com.smalistean.propstrategy.xvf.shadow.XvfNarrowShadowPolicy;
import com.smalistean.propstrategy.xvf.shadow.XvfNarrowShadowPolicy.Evaluation;
import com.smalistean.propstrategy.xvf.shadow.XvfNarrowShadowPolicy.Input;
import com.smalistean.propstrategy.xvf.shadow.XvfShadowConfiguration;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.PairType;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TopOfBook;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Opt-in adapter from the existing settled-funding book to the narrow live-entry policy. */
final class XvfNarrowExecutionSignal {

    static final String POLICY_NAME = "narrow-v1";
    private static final BigDecimal BPS = new BigDecimal("10000");
    private static final XvfNarrowShadowPolicy POLICY = new XvfNarrowShadowPolicy();

    private XvfNarrowExecutionSignal() {
    }

    static Selection select(
            List<Candidate> baseline,
            XvfFundingSnapshot funding,
            QuoteSource quotes) {
        return select(baseline, funding, quotes, instrument -> { });
    }

    static Selection select(
            List<Candidate> baseline,
            XvfFundingSnapshot funding,
            QuoteSource quotes,
            InstrumentValidator instruments) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(funding, "funding");
        Objects.requireNonNull(quotes, "quotes");
        Objects.requireNonNull(instruments, "instruments");

        List<EvaluatedCandidate> evaluated = new ArrayList<>();
        for (Candidate candidate : baseline) {
            Pair pair = pair(candidate);
            String makerVenue = makerVenue(pair.shortVenue(), pair.longVenue());
            String takerVenue = makerVenue.equals(pair.shortVenue())
                    ? pair.longVenue() : pair.shortVenue();
            Route route = new Route(makerVenue, takerVenue, 24);
            List<XvfFundingSnapshot.PendingObservation> shortHistory = funding.pendingHistory(
                    pair.shortVenue(), pair.shortVenueSymbol());
            List<XvfFundingSnapshot.PendingObservation> longHistory = funding.pendingHistory(
                    pair.longVenue(), pair.longVenueSymbol());

            BigDecimal basis = executableBasis(pair, makerVenue, quotes);
            ScoreStatus status = latestFresh(shortHistory)
                    && latestFresh(longHistory)
                    && basis != null ? ScoreStatus.SCORABLE : ScoreStatus.UNSCORABLE;
            Evaluation result = POLICY.evaluate(new Input(
                    pair,
                    route,
                    status,
                    basis,
                    shortHistory,
                    longHistory,
                    XvfShadowConfiguration.measuredFees()));
            String instrumentRejection = null;
            if (result.eligible()) {
                try {
                    instruments.validate(new Instrument(pair.shortVenue(), pair.shortVenueSymbol()));
                    instruments.validate(new Instrument(pair.longVenue(), pair.longVenueSymbol()));
                } catch (IllegalStateException e) {
                    instrumentRejection = e.getMessage();
                }
            }
            evaluated.add(new EvaluatedCandidate(candidate, result, instrumentRejection));
        }

        evaluated.sort(Comparator
                .comparing(EvaluatedCandidate::eligible).reversed()
                .thenComparing(row -> row.evaluation().fundingSurplusBps(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.evaluation().entryBasisBps(),
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(row -> row.candidate().spreadAnnualPct(), Comparator.reverseOrder()));
        List<Candidate> eligible = evaluated.stream()
                .filter(EvaluatedCandidate::eligible)
                .map(EvaluatedCandidate::candidate)
                .toList();
        return new Selection(eligible, evaluated);
    }

    static String makerVenue(String firstVenue, String secondVenue) {
        if ("bybit".equals(firstVenue) || "bybit".equals(secondVenue)) {
            return "bybit";
        }
        return depthRank(firstVenue) <= depthRank(secondVenue) ? firstVenue : secondVenue;
    }

    private static int depthRank(String venue) {
        return switch (venue) {
            case "hyperliquid" -> 1;
            case "bybit" -> 2;
            default -> 3;
        };
    }

    private static boolean latestFresh(List<XvfFundingSnapshot.PendingObservation> history) {
        return !history.isEmpty() && history.getLast().freshness() == Freshness.FRESH;
    }

    private static BigDecimal executableBasis(Pair pair, String makerVenue, QuoteSource quotes) {
        try {
            TopOfBook shortBook = quotes.topOfBook(
                    new Instrument(pair.shortVenue(), pair.shortVenueSymbol()));
            TopOfBook longBook = quotes.topOfBook(
                    new Instrument(pair.longVenue(), pair.longVenueSymbol()));
            BigDecimal shortPrice = makerVenue.equals(pair.shortVenue())
                    ? shortBook.ask() : shortBook.bid();
            BigDecimal longPrice = makerVenue.equals(pair.longVenue())
                    ? longBook.bid() : longBook.ask();
            if (shortPrice == null || longPrice == null
                    || shortPrice.signum() <= 0 || longPrice.signum() <= 0) {
                return null;
            }
            return BigDecimal.valueOf(Math.log(
                            shortPrice.divide(longPrice, 20, RoundingMode.HALF_UP).doubleValue()))
                    .multiply(BPS).setScale(8, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Pair pair(Candidate candidate) {
        boolean dex = "hyperliquid".equals(candidate.shortLeg().venue())
                || "hyperliquid".equals(candidate.longLeg().venue());
        return new Pair(
                candidate.base(),
                dex ? PairType.CEX_DEX : PairType.CEX_CEX,
                candidate.shortLeg().venue(),
                candidate.shortLeg().venueSymbol(),
                candidate.longLeg().venue(),
                candidate.longLeg().venueSymbol());
    }

    @FunctionalInterface
    interface QuoteSource {
        TopOfBook topOfBook(Instrument instrument);
    }

    @FunctionalInterface
    interface InstrumentValidator {
        void validate(Instrument instrument);
    }

    record EvaluatedCandidate(
            Candidate candidate,
            Evaluation evaluation,
            String instrumentRejection) {

        boolean eligible() {
            return evaluation.eligible() && instrumentRejection == null;
        }
    }

    record Selection(List<Candidate> eligible, List<EvaluatedCandidate> evaluated) {
        Selection {
            eligible = List.copyOf(eligible);
            evaluated = List.copyOf(evaluated);
        }
    }
}
