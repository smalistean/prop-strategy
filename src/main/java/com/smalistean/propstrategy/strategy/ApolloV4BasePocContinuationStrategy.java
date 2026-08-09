package com.smalistean.propstrategy.strategy;

import com.smalistean.propstrategy.feature.FeatureKey;
import com.smalistean.propstrategy.feature.FeatureSnapshot;
import com.smalistean.propstrategy.feature.VariableBaseDetector;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** BTC-only V4: base profile POC revisit is an alert; entry needs reclaim then a separate break. */
public final class ApolloV4BasePocContinuationStrategy implements VolumeProfileAwareStrategy {
    public record Config(int atrPeriod, int volumePeriod, int minimumBaseBars, int maximumBaseBars,
                         BigDecimal minimumBaseRangeAtr, BigDecimal maximumBaseRangeAtr,
                         BigDecimal maximumCenterDriftAtr, BigDecimal maximumSlopeAtrPerBar,
                         BigDecimal maximumPenetrationFraction, BigDecimal boundaryPenetrationAtr,
                         BigDecimal entranceDistanceAtr, int breakoutSearchBars, int reclaimWindowBars,
                         int localBreakBars, BigDecimal breakoutAtr, BigDecimal minimumBreakoutVolumeRatio,
                         BigDecimal minimumZoneShare, BigDecimal minimumPocShare, BigDecimal pocTouchAtr,
                         BigDecimal stopBaseHeightFraction, BigDecimal minimumRewardRisk,
                         int maximumHoldingBars) { }
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private final Config c; private final FeatureKey atr, volume;
    public ApolloV4BasePocContinuationStrategy(Config c) { this.c=c; atr=FeatureKey.atr(c.atrPeriod()); volume=FeatureKey.volumeRatio(c.volumePeriod()); }
    @Override public String name(){return "apollo-v4-base-poc-continuation";}
    @Override public int profileLookbackBuckets(){return c.maximumBaseBars();}
    public VariableBaseDetector.Config detectorConfig(){return new VariableBaseDetector.Config(c.minimumBaseBars(),c.maximumBaseBars(),c.minimumBaseRangeAtr(),c.maximumBaseRangeAtr(),c.maximumCenterDriftAtr(),c.maximumSlopeAtrPerBar(),c.maximumPenetrationFraction(),c.boundaryPenetrationAtr(),c.entranceDistanceAtr());}
    public FeatureKey atrKey(){return atr;}
    @Override public Set<FeatureKey> requiredFeatures(){return Set.of(FeatureKey.open(),FeatureKey.close(),FeatureKey.high(),FeatureKey.low(),atr,volume,FeatureKey.selectedBaseBars(),FeatureKey.selectedBaseLow(),FeatureKey.selectedBaseHigh(),FeatureKey.selectedBaseZoneLow(),FeatureKey.selectedBaseZoneHigh(),FeatureKey.selectedBaseZoneShare(),FeatureKey.selectedBasePocShare(),FeatureKey.selectedBaseTotalQuote());}
    @Override public StrategyDecision evaluate(List<FeatureSnapshot> h,int index,PositionView p){
        if(p.isOpen()) return p.barsHeld()>=c.maximumHoldingBars()?new StrategyDecision.Exit("Apollo V4 holding period expired"):StrategyDecision.hold();
        for(int b=Math.max(0,index-c.breakoutSearchBars()); b<=index-c.reclaimWindowBars()-c.localBreakBars()-1;b++) {
            var d=candidate(h,index,b,Side.LONG); if(!(d instanceof StrategyDecision.Hold)) return d;
            d=candidate(h,index,b,Side.SHORT); if(!(d instanceof StrategyDecision.Hold)) return d;
        } return StrategyDecision.hold();
    }
    private StrategyDecision candidate(List<FeatureSnapshot> h,int i,int b,Side s){
        var x=h.get(b); if(!x.values().containsKey(FeatureKey.selectedBaseBars())||x.require(volume).compareTo(c.minimumBreakoutVolumeRatio())<0||x.require(FeatureKey.selectedBaseZoneShare()).compareTo(c.minimumZoneShare())<0||x.require(FeatureKey.selectedBasePocShare()).compareTo(c.minimumPocShare())<0)return StrategyDecision.hold();
        var lo=x.require(FeatureKey.selectedBaseLow()); var hi=x.require(FeatureKey.selectedBaseHigh()); var t=x.require(atr).multiply(c.breakoutAtr(),MC);
        boolean up=x.require(FeatureKey.close()).compareTo(hi.add(t,MC))>0&&h.get(b+1).require(FeatureKey.close()).compareTo(hi)>0;
        boolean down=x.require(FeatureKey.close()).compareTo(lo.subtract(t,MC))<0&&h.get(b+1).require(FeatureKey.close()).compareTo(lo)<0;
        if(s==Side.LONG?!up:!down)return StrategyDecision.hold();
        var zl=x.require(FeatureKey.selectedBaseZoneLow());var zh=x.require(FeatureKey.selectedBaseZoneHigh()); var touch=h.get(i).require(atr).multiply(c.pocTouchAtr(),MC);
        int revisit=-1; for(int j=b+2;j<i;j++){boolean hit=s==Side.LONG?h.get(j).require(FeatureKey.low()).compareTo(zh.add(touch,MC))<=0:h.get(j).require(FeatureKey.high()).compareTo(zl.subtract(touch,MC))>=0;if(hit){revisit=j;break;}}
        if(revisit<0||i-revisit>c.reclaimWindowBars()+c.localBreakBars())return StrategyDecision.hold();
        var r=h.get(revisit); boolean reclaim=s==Side.LONG?r.require(FeatureKey.close()).compareTo(zh)>0:r.require(FeatureKey.close()).compareTo(zl)<0;
        if(!reclaim)return StrategyDecision.hold();
        BigDecimal bound=s==Side.LONG?h.get(i-c.localBreakBars()).require(FeatureKey.high()):h.get(i-c.localBreakBars()).require(FeatureKey.low());
        for(int j=i-c.localBreakBars()+1;j<i;j++) bound=s==Side.LONG?bound.max(h.get(j).require(FeatureKey.high())):bound.min(h.get(j).require(FeatureKey.low()));
        var now=h.get(i); if(s==Side.LONG?now.require(FeatureKey.close()).compareTo(bound)<=0:now.require(FeatureKey.close()).compareTo(bound)>=0)return StrategyDecision.hold();
        var buffer=hi.subtract(lo,MC).multiply(c.stopBaseHeightFraction(),MC); var stop=s==Side.LONG?lo.subtract(buffer,MC):hi.add(buffer,MC);var risk=s==Side.LONG?now.require(FeatureKey.close()).subtract(stop,MC):stop.subtract(now.require(FeatureKey.close()));
        if(risk.signum()<=0)return StrategyDecision.hold();var target=s==Side.LONG?now.require(FeatureKey.close()).add(risk.multiply(c.minimumRewardRisk(),MC)):now.require(FeatureKey.close()).subtract(risk.multiply(c.minimumRewardRisk(),MC));return new StrategyDecision.EnterAtLevels(s,stop,target);
    }
}
