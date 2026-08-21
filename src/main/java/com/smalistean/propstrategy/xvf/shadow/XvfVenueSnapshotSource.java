package com.smalistean.propstrategy.xvf.shadow;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only public market data used by the XVF shadow collector.
 *
 * <p>This is deliberately separate from {@code VenueGateway}: implementations cannot place,
 * cancel, or inspect orders and do not read trading credentials. Every exchange value keeps the
 * local request/receive window and, when the venue publishes it, the venue source timestamp.
 */
public interface XvfVenueSnapshotSource {

    String venue();

    /** Fetches one coherent best-effort public snapshot for the requested venue symbols. */
    VenueSnapshot fetch(Set<String> venueSymbols);

    record VenueSnapshot(
            String venue,
            Map<String, InstrumentSnapshot> instruments,
            List<SnapshotIssue> issues) {

        public VenueSnapshot {
            requireText(venue, "venue");
            Objects.requireNonNull(instruments, "instruments");
            Map<String, InstrumentSnapshot> copy = new LinkedHashMap<>();
            instruments.forEach((symbol, snapshot) -> {
                requireText(symbol, "instrument map key");
                Objects.requireNonNull(snapshot, "instrument snapshot");
                if (!venue.equals(snapshot.venue()) || !symbol.equals(snapshot.venueSymbol())) {
                    throw new IllegalArgumentException("Instrument snapshot identity does not match map/venue");
                }
                copy.put(symbol, snapshot);
            });
            instruments = Collections.unmodifiableMap(copy);
            issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        }
    }

    record InstrumentSnapshot(
            String venue,
            String venueSymbol,
            String canonicalBase,
            BigDecimal baseUnitsPerContract,
            Optional<ReferenceSnapshot> reference,
            Optional<ActivitySnapshot> activity,
            Optional<TopOfBookSnapshot> topOfBook,
            Optional<InstrumentRules> rules,
            Optional<OrderBookSnapshot> orderBook,
            List<String> missingData) {

        public InstrumentSnapshot {
            requireText(venue, "venue");
            requireText(venueSymbol, "venueSymbol");
            requireText(canonicalBase, "canonicalBase");
            requirePositive(baseUnitsPerContract, "baseUnitsPerContract");
            reference = requireOptional(reference, "reference");
            activity = requireOptional(activity, "activity");
            topOfBook = requireOptional(topOfBook, "topOfBook");
            rules = requireOptional(rules, "rules");
            orderBook = requireOptional(orderBook, "orderBook");
            missingData = List.copyOf(Objects.requireNonNull(missingData, "missingData"));
            missingData.forEach(value -> requireText(value, "missingData entry"));
        }
    }

    /** Mark/reference price and the still-pending funding observation. */
    record ReferenceSnapshot(
            Optional<BigDecimal> markPrice,
            Optional<BigDecimal> indexPrice,
            Optional<BigDecimal> midPrice,
            Optional<BigDecimal> pendingFundingRate,
            Optional<Instant> nextFundingTime,
            Optional<Integer> fundingIntervalHours,
            Optional<BigDecimal> openInterest,
            ResponseTiming timing) {

        public ReferenceSnapshot {
            markPrice = positiveDecimal(markPrice, "markPrice");
            indexPrice = positiveDecimal(indexPrice, "indexPrice");
            midPrice = positiveDecimal(midPrice, "midPrice");
            pendingFundingRate = requireOptional(pendingFundingRate, "pendingFundingRate");
            nextFundingTime = requireOptional(nextFundingTime, "nextFundingTime");
            fundingIntervalHours = requireOptional(fundingIntervalHours, "fundingIntervalHours");
            fundingIntervalHours.ifPresent(value -> {
                if (value <= 0) {
                    throw new IllegalArgumentException("fundingIntervalHours must be positive");
                }
            });
            openInterest = nonNegativeDecimal(openInterest, "openInterest");
            Objects.requireNonNull(timing, "timing");
        }
    }

    record ActivitySnapshot(Optional<BigDecimal> quoteVolume24hUsd, ResponseTiming timing) {
        public ActivitySnapshot {
            quoteVolume24hUsd = nonNegativeDecimal(quoteVolume24hUsd, "quoteVolume24hUsd");
            Objects.requireNonNull(timing, "timing");
        }
    }

    record TopOfBookSnapshot(
            BigDecimal bidPrice,
            BigDecimal bidQuantity,
            BigDecimal askPrice,
            BigDecimal askQuantity,
            ResponseTiming timing) {

        public TopOfBookSnapshot {
            requirePositive(bidPrice, "bidPrice");
            requirePositive(bidQuantity, "bidQuantity");
            requirePositive(askPrice, "askPrice");
            requirePositive(askQuantity, "askQuantity");
            if (bidPrice.compareTo(askPrice) > 0) {
                throw new IllegalArgumentException("bidPrice cannot exceed askPrice");
            }
            Objects.requireNonNull(timing, "timing");
        }
    }

    record InstrumentRules(
            Optional<BigDecimal> tickSize,
            Optional<BigDecimal> quantityStep,
            Optional<BigDecimal> minimumQuantity,
            Optional<BigDecimal> minimumNotionalUsd,
            Optional<BigDecimal> maximumQuantity,
            Optional<Integer> maximumLeverage,
            boolean trading,
            ResponseTiming timing) {

        public InstrumentRules {
            tickSize = positiveDecimal(tickSize, "tickSize");
            quantityStep = positiveDecimal(quantityStep, "quantityStep");
            minimumQuantity = positiveDecimal(minimumQuantity, "minimumQuantity");
            minimumNotionalUsd = positiveDecimal(minimumNotionalUsd, "minimumNotionalUsd");
            maximumQuantity = positiveDecimal(maximumQuantity, "maximumQuantity");
            maximumLeverage = requireOptional(maximumLeverage, "maximumLeverage");
            maximumLeverage.ifPresent(value -> {
                if (value <= 0) {
                    throw new IllegalArgumentException("maximumLeverage must be positive");
                }
            });
            Objects.requireNonNull(timing, "timing");
        }
    }

    record OrderBookSnapshot(
            List<BookLevel> bids,
            List<BookLevel> asks,
            ResponseTiming timing) {

        public OrderBookSnapshot {
            bids = List.copyOf(Objects.requireNonNull(bids, "bids"));
            asks = List.copyOf(Objects.requireNonNull(asks, "asks"));
            if (bids.isEmpty() || asks.isEmpty()) {
                throw new IllegalArgumentException("An order book requires at least one level per side");
            }
            Objects.requireNonNull(timing, "timing");
        }
    }

    record BookLevel(BigDecimal price, BigDecimal quantity, Optional<Integer> orderCount) {
        public BookLevel {
            requirePositive(price, "price");
            requirePositive(quantity, "quantity");
            orderCount = requireOptional(orderCount, "orderCount");
            orderCount.ifPresent(value -> {
                if (value < 0) {
                    throw new IllegalArgumentException("orderCount must not be negative");
                }
            });
        }
    }

    record ResponseTiming(Instant requestedAt, Optional<Instant> sourceAt, Instant receivedAt) {
        public ResponseTiming {
            Objects.requireNonNull(requestedAt, "requestedAt");
            sourceAt = requireOptional(sourceAt, "sourceAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
            if (receivedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("receivedAt cannot precede requestedAt");
            }
        }
    }

    record SnapshotIssue(
            IssueSeverity severity,
            String venue,
            Optional<String> venueSymbol,
            String code,
            String detail) {

        public SnapshotIssue {
            Objects.requireNonNull(severity, "severity");
            requireText(venue, "venue");
            venueSymbol = requireOptional(venueSymbol, "venueSymbol");
            venueSymbol.ifPresent(value -> requireText(value, "venueSymbol"));
            requireText(code, "code");
            requireText(detail, "detail");
        }
    }

    enum IssueSeverity { WARNING, ERROR }

    private static <T> Optional<T> requireOptional(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name);
    }

    private static Optional<BigDecimal> positiveDecimal(Optional<BigDecimal> value, String name) {
        value = requireOptional(value, name);
        value.ifPresent(decimal -> requirePositive(decimal, name));
        return value;
    }

    private static Optional<BigDecimal> nonNegativeDecimal(Optional<BigDecimal> value, String name) {
        value = requireOptional(value, name);
        value.ifPresent(decimal -> {
            if (decimal.signum() < 0) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        });
        return value;
    }

    private static void requirePositive(BigDecimal value, String name) {
        Objects.requireNonNull(value, name);
        if (value.signum() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
