package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Instrument;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.IntervalSource;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Candidate;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Leg;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TopOfBook;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XvfNarrowExecutionSignalTest {

    private static final Instant CUTOFF = Instant.parse("2026-08-24T10:30:00Z");

    @Test
    void optsIntoBybitMakerAndReturnsOnlyCandidatesPassingAllNarrowGates() {
        Candidate passing = candidate("PASS", "binance", "PASSUSDT", "bybit", "PASSUSDT");
        Candidate failing = candidate("FAIL", "binance", "FAILUSDT", "bybit", "FAILUSDT");
        XvfFundingSnapshot funding = snapshot(Map.of(
                new Instrument("binance", "PASSUSDT"), history("binance", "PASSUSDT", "0.005"),
                new Instrument("bybit", "PASSUSDT"), history("bybit", "PASSUSDT", "0"),
                new Instrument("binance", "FAILUSDT"), history("binance", "FAILUSDT", "0.001"),
                new Instrument("bybit", "FAILUSDT"), history("bybit", "FAILUSDT", "0")));

        var selected = XvfNarrowExecutionSignal.select(
                List.of(passing, failing), funding, instrument ->
                        instrument.venue().equals("binance")
                                ? new TopOfBook(new BigDecimal("101"), new BigDecimal("102"), 0)
                                : new TopOfBook(new BigDecimal("100"), new BigDecimal("101"), 0));

        assertEquals(List.of("PASS"), selected.eligible().stream().map(Candidate::base).toList());
        assertEquals("bybit", selected.evaluated().getFirst().evaluation().route().makerVenue());
    }

    @Test
    void routeMatchesTheNewExecutionPolicyForEveryVenuePair() {
        assertEquals("bybit", XvfNarrowExecutionSignal.makerVenue("binance", "bybit"));
        assertEquals("bybit", XvfNarrowExecutionSignal.makerVenue("hyperliquid", "bybit"));
        assertEquals("hyperliquid",
                XvfNarrowExecutionSignal.makerVenue("binance", "hyperliquid"));
    }

    @Test
    void nonCryptoInstrumentCannotAppearAsAnEligibleNewSignal() {
        Candidate candidate = candidate(
                "CSOPSAMSUNG2L", "binance", "CSOPSAMSUNG2LUSDT",
                "bybit", "CSOPSAMSUNG2LUSDT");
        XvfFundingSnapshot funding = snapshot(Map.of(
                new Instrument("binance", "CSOPSAMSUNG2LUSDT"),
                history("binance", "CSOPSAMSUNG2LUSDT", "0.005"),
                new Instrument("bybit", "CSOPSAMSUNG2LUSDT"),
                history("bybit", "CSOPSAMSUNG2LUSDT", "0")));

        var selected = XvfNarrowExecutionSignal.select(
                List.of(candidate),
                funding,
                instrument -> instrument.venue().equals("binance")
                        ? new TopOfBook(new BigDecimal("101"), new BigDecimal("102"), 0)
                        : new TopOfBook(new BigDecimal("100"), new BigDecimal("101"), 0),
                instrument -> {
                    if (instrument.venue().equals("bybit")) {
                        throw new IllegalStateException("ETF listing");
                    }
                });

        assertEquals(List.of(), selected.eligible());
        assertEquals("ETF listing", selected.evaluated().getFirst().instrumentRejection());
    }

    private static Candidate candidate(
            String base, String shortVenue, String shortSymbol,
            String longVenue, String longSymbol) {
        return new Candidate(base,
                new Leg(shortVenue, shortSymbol, 0.01, 0.01, 1_000_000, 1),
                new Leg(longVenue, longSymbol, 0, 0, 1_000_000, 1),
                100, 1_000_000);
    }

    private static XvfFundingSnapshot snapshot(Map<Instrument, List<PendingObservation>> histories) {
        Map<Instrument, PendingObservation> latest = new LinkedHashMap<>();
        histories.forEach((instrument, history) -> latest.put(instrument, history.getLast()));
        return new XvfFundingSnapshot(CUTOFF, latest, histories, List.of(), List.of());
    }

    private static List<PendingObservation> history(String venue, String symbol, String rate) {
        return java.util.stream.IntStream.rangeClosed(6, 9).mapToObj(hour -> {
            Instant observedHour = Instant.parse("2026-08-24T00:00:00Z").plusSeconds(hour * 3_600L);
            return new PendingObservation(
                    new Instrument(venue, symbol), new BigDecimal(rate), observedHour,
                    observedHour.plusSeconds(3_000), Instant.parse("2026-08-25T00:00:00Z"),
                    24, IntervalSource.TARGET_STAMP_DELTA, Freshness.FRESH);
        }).toList();
    }
}
