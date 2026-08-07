package com.smalistean.propstrategy.strategy;

public final class VolumeProfileLevelStrategyFactory implements StrategyFactory {
    @Override public String type() { return "volume-profile-level"; }

    @Override public Strategy create(StrategyParameters p) {
        return new VolumeProfileLevelStrategy(new VolumeProfileLevelStrategy.Config(
                VolumeProfileLevelStrategy.Reaction.valueOf(
                        p.requiredString("reaction").toUpperCase().replace('-', '_')),
                p.requiredInt("profileLookbackBuckets"), p.requiredInt("atrPeriod"),
                p.requiredInt("minimumPocStabilityBuckets"), p.requiredDecimal("minimumZoneShare"),
                p.requiredDecimal("breakoutAtr"), p.requiredDecimal("touchAtr"),
                p.requiredDecimal("stopBufferAtr"), p.requiredDecimal("minimumRewardRisk"),
                p.requiredInt("maximumHoldingBars")));
    }
}
