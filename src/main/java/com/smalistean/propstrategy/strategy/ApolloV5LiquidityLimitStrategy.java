package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.VariableBaseDetector;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Set;

/**
 * Apollo V5 Family B: liquidity/POC-limit entry (APOLLO_COURSE_SOURCE_NOTES.md Family B). Reuses
 * V5's exact same persistent map ({@link com.smalistean.propstrategy.feature.VariableBaseDetectorV5},
 * {@link com.smalistean.propstrategy.feature.VolumeProfileFeatureAssemblerV5}) unchanged - the only
 * difference from Family A ({@link ApolloV5BasePocContinuationStrategy}) is the entry decision: this
 * strategy enters as soon as the zone is reclaimed on its first revisit, without waiting for the
 * completed lower-timeframe swing reversal Family A requires. Stop is behind the whole base zone
 * plus one quarter of its height; target is the next mapped liquidity zone, same as Family A.
 */
public final class ApolloV5LiquidityLimitStrategy implements VolumeProfileAwareStrategy {
    public record Config(int atrPeriod, int volumePeriod, int minimumBaseBars, int maximumBaseBars,
                         BigDecimal minimumBaseRangeAtr, BigDecimal maximumBaseRangeAtr,
                         BigDecimal maximumCenterDriftAtr, BigDecimal maximumSlopeAtrPerBar,
                         BigDecimal maximumPenetrationFraction, BigDecimal boundaryPenetrationAtr,
                         BigDecimal entranceDistanceAtr, int breakoutSearchBars, int reclaimWindowBars,
                         BigDecimal breakoutAtr, BigDecimal minimumBreakoutVolumeRatio,
                         BigDecimal minimumZoneShare, BigDecimal minimumPocShare, BigDecimal minimumBaseVolumeRatio,
                         BigDecimal stopBaseHeightFraction, BigDecimal minimumRewardRisk,
                         int maximumHoldingBars, int baseMapLookbackDays, int maximumBoundaryTouches,
                         BigDecimal pocBinAtrFraction, BigDecimal internalWaveMinimumShare) { }
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int BARS_PER_DAY_15M = 96;
    private final Config c; private final FeatureKey atr, volume;
    public ApolloV5LiquidityLimitStrategy(Config c) { this.c=c; atr=FeatureKey.atr(c.atrPeriod()); volume=FeatureKey.volumeRatio(c.volumePeriod()); }
    @Override public String name(){return "apollo-v5-liquidity-limit";}
    @Override public int profileLookbackBuckets(){return effectiveMaximumBars();}
    private int effectiveMaximumBars(){ return Math.max(c.maximumBaseBars(), c.baseMapLookbackDays() * BARS_PER_DAY_15M); }
    public VariableBaseDetector.Config detectorConfig(){return new VariableBaseDetector.Config(c.minimumBaseBars(),effectiveMaximumBars(),c.minimumBaseRangeAtr(),c.maximumBaseRangeAtr(),c.maximumCenterDriftAtr(),c.maximumSlopeAtrPerBar(),c.maximumPenetrationFraction(),c.boundaryPenetrationAtr(),c.entranceDistanceAtr());}
    public BigDecimal pocBinAtrFraction(){ return c.pocBinAtrFraction(); }
    public BigDecimal internalWaveMinimumShare(){ return c.internalWaveMinimumShare(); }
    public FeatureKey atrKey(){return atr;}
    public int volumePeriod(){ return c.volumePeriod(); }
    public BigDecimal breakoutAtr(){ return c.breakoutAtr(); }
    public int reclaimWindowBars(){ return c.reclaimWindowBars(); }
    public int referenceBars(){ return c.maximumBaseBars(); }
    public int maximumBoundaryTouches(){ return c.maximumBoundaryTouches(); }
    @Override public Set<FeatureKey> requiredFeatures(){return Set.of(FeatureKey.open(),FeatureKey.close(),FeatureKey.high(),FeatureKey.low(),atr,volume,FeatureKey.selectedBaseId(),FeatureKey.selectedBaseBars(),FeatureKey.selectedBaseLow(),FeatureKey.selectedBaseHigh(),FeatureKey.selectedBaseZoneLow(),FeatureKey.selectedBaseZoneHigh(),FeatureKey.selectedBaseZoneShare(),FeatureKey.selectedBasePocShare(),FeatureKey.selectedBaseTotalQuote(),FeatureKey.selectedBaseVolumeRatio(),FeatureKey.selectedBaseBreakoutSide(),FeatureKey.selectedBaseBreakoutVolumeRatio(),FeatureKey.selectedBaseFirstRevisit(),FeatureKey.selectedBaseTarget());}
    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h,int index,PositionView p){
        if(p.isOpen()) return p.barsHeld()>=c.maximumHoldingBars()?new StrategyDecision.Exit("Apollo V5 liquidity-limit holding period expired"):StrategyDecision.hold();
        return candidate(h,index);
    }
    private StrategyDecision candidate(List<FeatureSnapshot> h,int i){
        var x=h.get(i);
        if(!x.values().containsKey(FeatureKey.selectedBaseId())||!x.values().containsKey(FeatureKey.selectedBaseTarget())) return StrategyDecision.hold();
        if(x.require(FeatureKey.selectedBaseBreakoutVolumeRatio()).compareTo(c.minimumBreakoutVolumeRatio())<0) return StrategyDecision.hold();
        if(x.require(FeatureKey.selectedBaseZoneShare()).compareTo(c.minimumZoneShare())<0) return StrategyDecision.hold();
        if(x.require(FeatureKey.selectedBasePocShare()).compareTo(c.minimumPocShare())<0) return StrategyDecision.hold();
        if(x.require(FeatureKey.selectedBaseVolumeRatio()).compareTo(c.minimumBaseVolumeRatio())<0) return StrategyDecision.hold();
        // Family B acts directly on the zone's first revisit: no swing-reversal confirmation wait.
        if(x.require(FeatureKey.selectedBaseFirstRevisit()).signum()<=0) return StrategyDecision.hold();
        Side s=x.require(FeatureKey.selectedBaseBreakoutSide()).signum()>0?Side.LONG:Side.SHORT;
        var zh=x.require(FeatureKey.selectedBaseZoneHigh()); var zl=x.require(FeatureKey.selectedBaseZoneLow());
        var close=x.require(FeatureKey.close());
        boolean reclaim=s==Side.LONG?close.compareTo(zh)>0:close.compareTo(zl)<0;
        if(!reclaim) return StrategyDecision.hold();
        var lo=x.require(FeatureKey.selectedBaseLow()); var hi=x.require(FeatureKey.selectedBaseHigh());
        var buffer=hi.subtract(lo,MC).multiply(c.stopBaseHeightFraction(),MC);
        var stop=s==Side.LONG?lo.subtract(buffer,MC):hi.add(buffer,MC);
        var risk=s==Side.LONG?close.subtract(stop,MC):stop.subtract(close,MC);
        BigDecimal target=x.require(FeatureKey.selectedBaseTarget());
        BigDecimal reward=s==Side.LONG?target.subtract(close,MC):close.subtract(target,MC);
        if(risk.signum()<=0||reward.signum()<=0||reward.divide(risk,MC).compareTo(c.minimumRewardRisk())<0) return StrategyDecision.hold();
        return new StrategyDecision.EnterAtLevels(s,stop,target);
    }
}
