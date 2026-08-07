package com.smalistean.propstrategy.strategy;

public interface VolumeProfileAwareStrategy extends Strategy {
    int profileLookbackBuckets();
}
