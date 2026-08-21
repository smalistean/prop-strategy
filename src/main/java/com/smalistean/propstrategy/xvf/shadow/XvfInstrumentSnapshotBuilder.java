package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ActivitySnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentRules;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.OrderBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ReferenceSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.TopOfBookSnapshot;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Mutable package-private assembly; only the immutable record returned by {@link #build()} escapes. */
final class XvfInstrumentSnapshotBuilder {
    final String venue;
    final String symbol;
    final String canonicalBase;
    final BigDecimal baseUnitsPerContract;
    Optional<ReferenceSnapshot> reference = Optional.empty();
    Optional<ActivitySnapshot> activity = Optional.empty();
    Optional<TopOfBookSnapshot> topOfBook = Optional.empty();
    Optional<InstrumentRules> rules = Optional.empty();
    Optional<OrderBookSnapshot> orderBook = Optional.empty();
    final List<String> missing = new ArrayList<>();

    XvfInstrumentSnapshotBuilder(String venue, String symbol, String canonicalBase,
                                 BigDecimal baseUnitsPerContract) {
        this.venue = venue;
        this.symbol = symbol;
        this.canonicalBase = canonicalBase;
        this.baseUnitsPerContract = baseUnitsPerContract;
    }

    InstrumentSnapshot build() {
        return new InstrumentSnapshot(venue, symbol, canonicalBase, baseUnitsPerContract,
                reference, activity, topOfBook, rules, orderBook, missing);
    }
}
