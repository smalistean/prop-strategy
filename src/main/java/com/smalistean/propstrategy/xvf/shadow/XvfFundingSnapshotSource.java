package com.smalistean.propstrategy.xvf.shadow;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Read-only source of funding facts known at a decision cutoff. */
public interface XvfFundingSnapshotSource {

    XvfFundingSnapshot read(Instant cutoffUtc, FreshnessPolicy freshnessPolicy);

    /** Thresholds are supplied by the caller so storage code does not silently choose trading policy. */
    record FreshnessPolicy(Duration maximumPendingAge, Duration maximumSettledAge) {
        public FreshnessPolicy {
            Objects.requireNonNull(maximumPendingAge, "maximumPendingAge");
            Objects.requireNonNull(maximumSettledAge, "maximumSettledAge");
            if (maximumPendingAge.isNegative() || maximumSettledAge.isNegative()) {
                throw new IllegalArgumentException("freshness ages must not be negative");
            }
        }
    }
}
