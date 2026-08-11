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
 * V5 adds a multi-day map search and a third-touch discount on top of the V4.1 lifecycle: the base
 * detector is no longer limited to the 12-48 candle window ending immediately before the breakout
 * (see {@link com.smalistean.propstrategy.feature.VariableBaseDetectorV5}), and a boundary already
 * approached three or more times before its own breakout is discounted rather than trusted. V4
 * ({@link ApolloV4BasePocContinuationStrategy}) is left unchanged; this is a separate, parallel
 * strategy so the two remain independently comparable.
 */
public final class ApolloV5BasePocContinuationStrategy implements VolumeProfileAwareStrategy {
    public record Config(int atrPeriod, int volumePeriod, int minimumBaseBars, int maximumBaseBars,
                         BigDecimal minimumBaseRangeAtr, BigDecimal maximumBaseRangeAtr,
                         BigDecimal maximumCenterDriftAtr, BigDecimal maximumSlopeAtrPerBar,
                         BigDecimal maximumPenetrationFraction, BigDecimal boundaryPenetrationAtr,
                         BigDecimal entranceDistanceAtr, int breakoutSearchBars, int reclaimWindowBars,
                         int swingPivotStrength, BigDecimal minimumSwingSizeAtr,
                         BigDecimal breakoutAtr, BigDecimal minimumBreakoutVolumeRatio,
                         BigDecimal minimumZoneShare, BigDecimal minimumPocShare, BigDecimal minimumBaseVolumeRatio, BigDecimal pocTouchAtr,
                         BigDecimal stopBaseHeightFraction, BigDecimal minimumRewardRisk,
                         int maximumHoldingBars, int baseMapLookbackDays, int maximumBoundaryTouches,
                         BigDecimal pocBinAtrFraction, BigDecimal internalWaveMinimumShare) { }
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final int BARS_PER_DAY_15M = 96;
    private final Config c; private final FeatureKey atr, volume;
    public ApolloV5BasePocContinuationStrategy(Config c) { this.c=c; atr=FeatureKey.atr(c.atrPeriod()); volume=FeatureKey.volumeRatio(c.volumePeriod()); }
    @Override public String name(){return "apollo-v5-persistent-base-poc";}
    @Override public int profileLookbackBuckets(){return effectiveMaximumBars();}
    /** The 12-48 candle ceiling stays the calibration scale for width/drift ATR bounds; the map search itself may run far beyond it. */
    private int effectiveMaximumBars(){ return Math.max(c.maximumBaseBars(), c.baseMapLookbackDays() * BARS_PER_DAY_15M); }
    public VariableBaseDetector.Config detectorConfig(){return new VariableBaseDetector.Config(c.minimumBaseBars(),effectiveMaximumBars(),c.minimumBaseRangeAtr(),c.maximumBaseRangeAtr(),c.maximumCenterDriftAtr(),c.maximumSlopeAtrPerBar(),c.maximumPenetrationFraction(),c.boundaryPenetrationAtr(),c.entranceDistanceAtr());}
    public FeatureKey atrKey(){return atr;}
    public int volumePeriod(){ return c.volumePeriod(); }
    public BigDecimal breakoutAtr(){ return c.breakoutAtr(); }
    public BigDecimal pocBinAtrFraction(){ return c.pocBinAtrFraction(); }
    public BigDecimal internalWaveMinimumShare(){ return c.internalWaveMinimumShare(); }
    public int reclaimWindowBars(){ return c.reclaimWindowBars(); }
    public int referenceBars(){ return c.maximumBaseBars(); }
    public int maximumBoundaryTouches(){ return c.maximumBoundaryTouches(); }
    @Override public Set<FeatureKey> requiredFeatures(){return Set.of(FeatureKey.open(),FeatureKey.close(),FeatureKey.high(),FeatureKey.low(),atr,volume,FeatureKey.selectedBaseId(),FeatureKey.selectedBaseBars(),FeatureKey.selectedBaseLow(),FeatureKey.selectedBaseHigh(),FeatureKey.selectedBaseZoneLow(),FeatureKey.selectedBaseZoneHigh(),FeatureKey.selectedBaseZoneShare(),FeatureKey.selectedBasePocShare(),FeatureKey.selectedBaseTotalQuote(),FeatureKey.selectedBaseVolumeRatio(),FeatureKey.selectedBaseBreakoutSide(),FeatureKey.selectedBaseBreakoutVolumeRatio(),FeatureKey.selectedBaseFirstRevisit(),FeatureKey.selectedBaseTarget());}
    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h,int index,PositionView p){
        if(p.isOpen()) return p.barsHeld()>=c.maximumHoldingBars()?new StrategyDecision.Exit("Apollo V5 holding period expired"):StrategyDecision.hold();
        return candidate(h,index);
    }
    private StrategyDecision candidate(List<FeatureSnapshot> h,int i){
        var x=h.get(i); if(!x.values().containsKey(FeatureKey.selectedBaseId())||!x.values().containsKey(FeatureKey.selectedBaseTarget())||x.require(FeatureKey.selectedBaseBreakoutVolumeRatio()).compareTo(c.minimumBreakoutVolumeRatio())<0||x.require(FeatureKey.selectedBaseZoneShare()).compareTo(c.minimumZoneShare())<0||x.require(FeatureKey.selectedBasePocShare()).compareTo(c.minimumPocShare())<0||x.require(FeatureKey.selectedBaseVolumeRatio()).compareTo(c.minimumBaseVolumeRatio())<0)return StrategyDecision.hold();
        Side s=x.require(FeatureKey.selectedBaseBreakoutSide()).signum()>0?Side.LONG:Side.SHORT;
        int revisit=-1; BigDecimal id=x.require(FeatureKey.selectedBaseId());
        for(int j=Math.max(0,i-c.reclaimWindowBars()+1);j<=i;j++) if(h.get(j).values().containsKey(FeatureKey.selectedBaseId())&&h.get(j).require(FeatureKey.selectedBaseId()).compareTo(id)==0&&h.get(j).require(FeatureKey.selectedBaseFirstRevisit()).signum()>0){revisit=j;break;}
        if(revisit<0)return StrategyDecision.hold();
        var lo=x.require(FeatureKey.selectedBaseLow()); var hi=x.require(FeatureKey.selectedBaseHigh());
        var zl=x.require(FeatureKey.selectedBaseZoneLow());var zh=x.require(FeatureKey.selectedBaseZoneHigh());
        var r=h.get(revisit); boolean reclaim=s==Side.LONG?r.require(FeatureKey.close()).compareTo(zh)>0:r.require(FeatureKey.close()).compareTo(zl)<0;
        if(!reclaim || !hasBrokenAndRetested(h, revisit, i, s)) return StrategyDecision.hold();
        var now=h.get(i);
        var buffer=hi.subtract(lo,MC).multiply(c.stopBaseHeightFraction(),MC); var stop=s==Side.LONG?lo.subtract(buffer,MC):hi.add(buffer,MC);var risk=s==Side.LONG?now.require(FeatureKey.close()).subtract(stop,MC):stop.subtract(now.require(FeatureKey.close()));
        BigDecimal target=x.require(FeatureKey.selectedBaseTarget());
        BigDecimal reward=s==Side.LONG?target.subtract(now.require(FeatureKey.close()),MC):now.require(FeatureKey.close()).subtract(target,MC);
        if(risk.signum()<=0||reward.signum()<=0||reward.divide(risk,MC).compareTo(c.minimumRewardRisk())<0)return StrategyDecision.hold();return new StrategyDecision.EnterAtLevels(s,stop,target);
    }

    /** A completed lower-timeframe swing reversal, not a raw N-bar breakout. */
    private boolean hasBrokenAndRetested(List<FeatureSnapshot> h, int revisit, int now, Side side) {
        int strength = c.swingPivotStrength();
        int lastConfirmedPivot = now - strength;
        if (lastConfirmedPivot < revisit + strength + 2) return false;
        FeatureKey firstKey = side == Side.LONG ? FeatureKey.high() : FeatureKey.low();
        FeatureKey secondKey = side == Side.LONG ? FeatureKey.low() : FeatureKey.high();
        BigDecimal sweepExtreme = h.get(revisit).require(secondKey);
        BigDecimal minimumMove = h.get(now).require(atr).multiply(c.minimumSwingSizeAtr(), MC);
        for (int first = revisit + strength; first <= lastConfirmedPivot - 1; first++) {
            if (!isPivot(h, first, strength, firstKey)) continue;
            BigDecimal firstPrice = h.get(first).require(firstKey);
            boolean initialMove = side == Side.LONG
                    ? firstPrice.subtract(sweepExtreme, MC).compareTo(minimumMove) >= 0
                    : sweepExtreme.subtract(firstPrice, MC).compareTo(minimumMove) >= 0;
            if (!initialMove) continue;
            for (int second = first + 1; second <= lastConfirmedPivot; second++) {
                if (!isPivot(h, second, strength, secondKey)) continue;
                BigDecimal secondPrice = h.get(second).require(secondKey);
                boolean changedStructure = side == Side.LONG
                        ? secondPrice.subtract(sweepExtreme, MC).compareTo(minimumMove) >= 0
                        : sweepExtreme.subtract(secondPrice, MC).compareTo(minimumMove) >= 0;
                if (!changedStructure) continue;
                int broken=-1;
                for(int j=second+strength;j<=now;j++) if(side==Side.LONG?h.get(j).require(FeatureKey.close()).compareTo(firstPrice)>0:h.get(j).require(FeatureKey.close()).compareTo(firstPrice)<0){broken=j;break;}
                if(broken<0) continue;
                for(int j=broken+1;j<=now;j++) {
                    boolean retest=side==Side.LONG?h.get(j).require(FeatureKey.low()).compareTo(firstPrice)<=0&&h.get(j).require(FeatureKey.close()).compareTo(firstPrice)>=0:h.get(j).require(FeatureKey.high()).compareTo(firstPrice)>=0&&h.get(j).require(FeatureKey.close()).compareTo(firstPrice)<=0;
                    if(retest) return true;
                }
            }
        }
        return false;
    }

    private static boolean isPivot(List<FeatureSnapshot> h, int index, int strength, FeatureKey key) {
        BigDecimal price = h.get(index).require(key);
        boolean high = key.equals(FeatureKey.high());
        for (int j = index - strength; j <= index + strength; j++) {
            if (j == index) continue;
            int comparison = price.compareTo(h.get(j).require(key));
            if (high ? comparison <= 0 : comparison >= 0) return false;
        }
        return true;
    }
}
