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
                         BigDecimal pocBinAtrFraction, BigDecimal internalWaveMinimumShare,
                         int requireHigherTimeframeAlignment, int higherTimeframePivotStrength,
                         int consumedBasesRemainTargets, BigDecimal minimumAbsorptionDelta,
                         BigDecimal acceptanceMinimumBodyFraction,
                         int acceptanceMinimumBodyCandles,
                         int stopMode, int profileBodyBoundedSelection,
                         int entryMode, int limitOrderLifetimeBars) { }
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
    public boolean consumedBasesRemainTargets(){ return c.consumedBasesRemainTargets() != 0; }
    public boolean profileBodyBoundedSelection(){ return c.profileBodyBoundedSelection() != 0; }
    public BigDecimal acceptanceMinimumBodyFraction(){ return c.acceptanceMinimumBodyFraction(); }
    public int acceptanceMinimumBodyCandles(){ return c.acceptanceMinimumBodyCandles(); }
    public BigDecimal minimumAbsorptionDelta(){ return c.minimumAbsorptionDelta(); }
    public boolean requiresHigherTimeframeAlignment(){ return c.requireHigherTimeframeAlignment() != 0; }
    public int higherTimeframePivotStrength(){ return c.higherTimeframePivotStrength(); }
    public FeatureKey atrKey(){return atr;}
    public int volumePeriod(){ return c.volumePeriod(); }
    public BigDecimal breakoutAtr(){ return c.breakoutAtr(); }
    public int reclaimWindowBars(){ return c.reclaimWindowBars(); }
    public int referenceBars(){ return c.maximumBaseBars(); }
    public int maximumBoundaryTouches(){ return c.maximumBoundaryTouches(); }
    @Override public Set<FeatureKey> requiredFeatures(){return Set.of(FeatureKey.open(),FeatureKey.close(),FeatureKey.high(),FeatureKey.low(),atr,volume,FeatureKey.selectedBaseId(),FeatureKey.selectedBaseBars(),FeatureKey.selectedBaseLow(),FeatureKey.selectedBaseHigh(),FeatureKey.selectedBaseZoneLow(),FeatureKey.selectedBaseZoneHigh(),FeatureKey.selectedBaseZoneShare(),FeatureKey.selectedBasePocShare(),FeatureKey.selectedBaseTotalQuote(),FeatureKey.selectedBaseVolumeRatio(),FeatureKey.selectedBaseBreakoutSide(),FeatureKey.selectedBaseBreakoutVolumeRatio(),FeatureKey.selectedBaseFirstRevisit(),FeatureKey.selectedBaseTarget(),FeatureKey.higherTimeframeBias(),FeatureKey.selectedBaseDelta());}
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
        // entryMode 1 is the source's actual Family B and must NOT wait for the revisit - a limit
        // has to be resting before price returns, otherwise there is nothing to be filled by.
        if(c.entryMode()==0 && x.require(FeatureKey.selectedBaseFirstRevisit()).signum()<=0)
            return StrategyDecision.hold();
        if(c.entryMode()==1 && x.require(FeatureKey.selectedBaseFirstRevisit()).signum()>0)
            return StrategyDecision.hold(); // already revisited: the zone is consumed, too late to rest an order
        Side s=x.require(FeatureKey.selectedBaseBreakoutSide()).signum()>0?Side.LONG:Side.SHORT;
        // Roadmap step 6: do not take a 15m entry that fights intact higher-timeframe structure
        // ("ВАША ПЯТНАДЦАТИМИНУТКА ... ПРОТИВ СТАРШЕГО", slom_trenda.mp4). Bias 0 is undecided and
        // never blocks; only a confirmed opposing 1h trend does.
        if(c.requireHigherTimeframeAlignment()!=0 && x.values().containsKey(FeatureKey.higherTimeframeBias())){
            int bias=x.require(FeatureKey.higherTimeframeBias()).signum();
            if(bias!=0 && ((s==Side.LONG&&bias<0)||(s==Side.SHORT&&bias>0))) return StrategyDecision.hold();
        }
        // Phase 1a: absorption filter. Hypothesis (predeclared): a zone that HELD while one side
        // aggressed was absorbed by passive flow on the other side, and the passive side is the one
        // that later wins. So a LONG wants the zone built on SELL aggression (negative delta) and a
        // SHORT on BUY aggression. Total volume cannot distinguish these; aggressor delta can.
        // 0 disables. This is the source's "concentration of unfilled orders" measured directly.
        if(c.minimumAbsorptionDelta().signum()!=0 && x.values().containsKey(FeatureKey.selectedBaseDelta())){
            BigDecimal d=x.require(FeatureKey.selectedBaseDelta());
            BigDecimal t=c.minimumAbsorptionDelta().abs();
            boolean ok;
            if(c.minimumAbsorptionDelta().signum()>0){
                // ABSORPTION (predeclared): long wants the zone built on sell aggression.
                ok = s==Side.LONG ? d.compareTo(t.negate())<=0 : d.compareTo(t)>=0;
            } else {
                // ALIGNED (post-hoc, suggested by the observed delta tilt): long wants buy
                // aggression. Recorded as a separate hypothesis, NOT a reinterpretation of the above.
                ok = s==Side.LONG ? d.compareTo(t)>=0 : d.compareTo(t.negate())<=0;
            }
            if(!ok) return StrategyDecision.hold();
        }
        var zh=x.require(FeatureKey.selectedBaseZoneHigh()); var zl=x.require(FeatureKey.selectedBaseZoneLow());
        var close=x.require(FeatureKey.close());
        if(c.entryMode()==0){
            boolean reclaim=s==Side.LONG?close.compareTo(zh)>0:close.compareTo(zl)<0;
            if(!reclaim) return StrategyDecision.hold();
        }
        var lo=x.require(FeatureKey.selectedBaseLow()); var hi=x.require(FeatureKey.selectedBaseHigh());
        BigDecimal stop;
        if(c.stopMode()==0){
            // Geometric: a fixed fraction of base height below/above the whole base. Carries no
            // reference to where trading actually happened, which is what the source asks for.
            var buffer=hi.subtract(lo,MC).multiply(c.stopBaseHeightFraction(),MC);
            stop=s==Side.LONG?lo.subtract(buffer,MC):hi.add(buffer,MC);
        } else if(c.stopMode()==2){
            // Source-exact Family B stop (pp. 24, 26): "hide the stop behind the ENTIRE LIQUIDITY
            // ZONE ... an additional buffer of one quarter of the LIQUIDITY-ZONE height."
            //
            // The frozen implementation applies the same 0.25 to the BASE height and anchors to the
            // BASE boundary. Both objects are wrong: the base is measured on candle bodies, the zone
            // on traded price, and the base is a median 1.5x the zone height (mean 2.0x, p90 4.2x,
            // measured over 372 training entries). That inflates R, and because the entry gate
            // demands reward >= 3R, it pushes every target proportionally further away - which is
            // the most likely mechanical cause of the 5.3% take-profit rate.
            var zoneHeight=zh.subtract(zl,MC);
            var buffer=zoneHeight.multiply(c.stopBaseHeightFraction(),MC);
            stop=s==Side.LONG?zl.subtract(buffer,MC):zh.add(buffer,MC);
        } else {
            // Structural: invalidation at a volume level (pp. 36, 52-54), with the deep retest given
            // the wider stop the source calls for (pp. 40-41). Depth is measured on the revisit bar:
            // the retest is deep when price actually traded into the POC zone, shallow when it only
            // reached the boundary. A deep retest has already consumed the zone, so the zone can no
            // longer serve as invalidation and the stop moves out beyond the base body.
            var buffer=x.require(atr).multiply(c.boundaryPenetrationAtr(),MC);
            boolean deep=s==Side.LONG
                    ? x.require(FeatureKey.low()).compareTo(zh)<=0
                    : x.require(FeatureKey.high()).compareTo(zl)>=0;
            stop=s==Side.LONG
                    ? (deep?lo:zl).subtract(buffer,MC)
                    : (deep?hi:zh).add(buffer,MC);
        }
        var risk=s==Side.LONG?close.subtract(stop,MC):stop.subtract(close,MC);
        BigDecimal target=x.require(FeatureKey.selectedBaseTarget());
        BigDecimal reward=s==Side.LONG?target.subtract(close,MC):close.subtract(target,MC);
        if(risk.signum()<=0||reward.signum()<=0||reward.divide(risk,MC).compareTo(c.minimumRewardRisk())<0) return StrategyDecision.hold();
        if(System.getProperty("dumpStop")!=null)
            System.err.printf("STOP base=%s zone=%s ratio=%s%n",
                    hi.subtract(lo,MC).toPlainString(), zh.subtract(zl,MC).toPlainString(),
                    zh.subtract(zl,MC).signum()==0?"inf"
                        :hi.subtract(lo,MC).divide(zh.subtract(zl,MC),MC).setScale(2,RoundingMode.HALF_UP).toPlainString());
        if(System.getProperty("dumpDelta")!=null && x.values().containsKey(FeatureKey.selectedBaseDelta()))
            // One line per ACCEPTED entry decision, so the zone delta and the prevailing market
            // regime can be joined to the resulting trade offline. This measures the delta x regime
            // interaction from a single baseline run instead of needing a regime-conditioned config
            // per hypothesis - the strategy itself is unchanged and still ignores both fields.
            System.err.printf("DECIDE %s %s delta=%s regime=%s taker=%s%n", x.candleOpenTime(), s,
                    x.require(FeatureKey.selectedBaseDelta()).setScale(4, RoundingMode.HALF_UP),
                    x.values().containsKey(FeatureKey.marketRegime())
                        ? x.require(FeatureKey.marketRegime()).toPlainString() : "NA",
                    x.values().containsKey(FeatureKey.marketTakerRatio())
                        ? x.require(FeatureKey.marketTakerRatio()).setScale(4, RoundingMode.HALF_UP) : "NA");
        if(c.entryMode()==1){
            // "Place a limit order slightly before the principal volume" (pp. 24, 26). Approaching
            // from outside the zone, "slightly before" is the near edge offset outward by the
            // already-declared entranceDistanceAtr - the same "a little before POC" distance the
            // course leaves unspecified and this codebase had only ever used for base geometry.
            var offset=x.require(atr).multiply(c.entranceDistanceAtr(),MC);
            var entry=s==Side.LONG?zh.add(offset,MC):zl.subtract(offset,MC);
            var limitRisk=s==Side.LONG?entry.subtract(stop,MC):stop.subtract(entry,MC);
            var limitReward=s==Side.LONG?target.subtract(entry,MC):entry.subtract(target,MC);
            if(limitRisk.signum()<=0||limitReward.signum()<=0
                    ||limitReward.divide(limitRisk,MC).compareTo(c.minimumRewardRisk())<0)
                return StrategyDecision.hold();
            return new StrategyDecision.EnterAtLimit(s,entry,stop,target,c.limitOrderLifetimeBars());
        }
        return new StrategyDecision.EnterAtLevels(s,stop,target);
    }
}
