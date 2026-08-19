package com.smalistean.propstrategy.xvf.execution;

import com.smalistean.propstrategy.xvf.venue.VenueGateway;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.PositionSnapshot;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.Side;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.SubmitResult;
import com.smalistean.propstrategy.xvf.venue.VenueGateway.TriggerWhen;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Resting exit triggers that close a leg while nothing is watching it.
 *
 * <h2>Two per leg, never one</h2>
 * The two legs of an XVF pair hold the same asset in opposite directions, so they move together and
 * oppositely: whatever the market does, one leg is losing exactly as much as the other is winning. A
 * single stop per leg therefore fires on the losing leg alone, closes the hedge, and leaves the
 * winning leg outright - a market-neutral position turned directional by its own safety mechanism, in
 * the middle of the move that triggered it.
 *
 * <p>Placing a trigger on <em>both</em> sides of <em>both</em> legs removes that. A move down closes
 * the long on its loss side and the short on its profit side; a move up does the mirror. Four orders,
 * and any excursion past the band closes all of them.
 *
 * <h2>The band is derived from liquidation, not chosen</h2>
 * A percentage would be a guess that ignores how much collateral is actually behind the leg. Every
 * venue publishes the price at which it will liquidate the position, so the trigger is placed a
 * fraction of the way there: it fires while the position is still ours to close, at our slippage
 * rather than the liquidation engine's, which measured 0.80% median and 3.27% mean.
 *
 * <h2>This is a brake, not an exit</h2>
 * XVF earns funding; price is the thing it is hedged against. Every trigger that fires costs a full
 * round trip of basis drag - measured at -10.4% annualised at 3-day cadence - to close a position that
 * was doing nothing wrong. So the band is set wide enough that ordinary volatility never reaches it.
 * It exists for the 2.1% of legs that liquidate at 1x, and for the case where the process that should
 * have rebalanced them is simply not running. The routine unwind belongs to {@link XvfReconciler}.
 */
public final class XvfBrackets {

    /**
     * How far toward liquidation the trigger sits.
     *
     * <p>Half leaves room for the order to be worked at a price of our choosing while the position is
     * still solvent. Closer to liquidation risks the venue getting there first; closer to entry turns
     * a disaster brake into a routine exit and pays basis drag for nothing.
     */
    private static final BigDecimal LIQUIDATION_FRACTION = new BigDecimal("0.5");

    /** Used only when a venue reports no liquidation price, as it does for a tiny cross-margin leg. */
    private static final BigDecimal FALLBACK_BAND = new BigDecimal("0.25");

    /** The two levels that close one leg, and the side that closes it. */
    public record Band(String venue, String venueSymbol, Side closingSide, BigDecimal quantity,
                       BigDecimal lower, BigDecimal upper, String derivedFrom) { }

    private XvfBrackets() {
    }

    /**
     * How far this leg could move, as a fraction of its own price, before its trigger should fire.
     *
     * <p>Relative rather than absolute because the two venues quote slightly different prices for the
     * same asset. Comparing absolute distances across venues would fold the basis into the comparison
     * and make the tighter leg look tighter than it is.
     */
    static BigDecimal relativeDistance(BigDecimal mark, BigDecimal liquidationPrice) {
        if (liquidationPrice != null && liquidationPrice.signum() > 0) {
            return mark.subtract(liquidationPrice).abs()
                    .multiply(LIQUIDATION_FRACTION)
                    .divide(mark, 8, RoundingMode.DOWN);
        }
        // No liquidation price means the venue sees no meaningful liquidation risk on this leg,
        // usually because cross margin dwarfs it. A wide fixed band still bounds a venue outage.
        return FALLBACK_BAND;
    }

    /**
     * The two bands that close a whole pair, sharing one distance.
     *
     * <p><b>The shared distance is the point.</b> Sizing each leg from its own liquidation price looks
     * more precise and is actively dangerous: the legs sit at different distances from liquidation -
     * measured live at 0.71 against a 1.42 mark on one venue and 2.85 on the other - so the narrower
     * band fires first, closes its leg, and leaves the wider one outright. A bracket scheme that closes
     * one leg of a hedged pair has produced exactly the naked position it was installed to prevent.
     *
     * <p>So both legs take the <em>tighter</em> of the two distances. The leg with more room to spare
     * gets closed earlier than its own margin requires, which costs nothing, because these triggers are
     * a brake rather than an exit and are not supposed to fire at all.
     */
    public static List<Band> pair(PositionSnapshot first, BigDecimal firstMark,
                                  PositionSnapshot second, BigDecimal secondMark) {
        BigDecimal firstDistance = relativeDistance(firstMark, first.liquidationPrice());
        BigDecimal secondDistance = relativeDistance(secondMark, second.liquidationPrice());
        BigDecimal shared = firstDistance.min(secondDistance);
        String why = "%s%% band, the tighter of %s%% and %s%%".formatted(
                shared.movePointRight(2).setScale(2, RoundingMode.HALF_UP),
                firstDistance.movePointRight(2).setScale(2, RoundingMode.HALF_UP),
                secondDistance.movePointRight(2).setScale(2, RoundingMode.HALF_UP));
        return List.of(band(first, firstMark, shared, why), band(second, secondMark, shared, why));
    }

    /** One leg's band at an already-decided relative distance. */
    public static Band band(PositionSnapshot position, BigDecimal mark, BigDecimal relativeDistance,
                            String derivedFrom) {
        BigDecimal size = position.signedQuantity().abs();
        // A long is closed by selling, a short by buying.
        Side closing = position.signedQuantity().signum() > 0 ? Side.SELL : Side.BUY;
        BigDecimal distance = mark.multiply(relativeDistance);
        return new Band(position.venue(), position.venueSymbol(), closing, size,
                mark.subtract(distance).max(BigDecimal.ZERO), mark.add(distance), derivedFrom);
    }

    /**
     * Places both triggers for one leg. Returns true only when both were accepted.
     *
     * <p>A half-placed bracket is worse than none, because it is the single-stop arrangement this class
     * exists to avoid. If the second order fails the first is cancelled rather than left resting.
     */
    public static boolean place(VenueGateway gateway, Band band, String clientIdPrefix) {
        SubmitResult below = gateway.placeReduceOnlyTrigger(band.venueSymbol(), band.closingSide(),
                band.quantity(), band.lower(), TriggerWhen.PRICE_FALLS_TO, clientIdPrefix + "-lo");
        if (!below.accepted()) {
            System.out.printf("!!!! %s %s lower trigger rejected: %s%n",
                    band.venue(), band.venueSymbol(), below.detail());
            return false;
        }
        SubmitResult above = gateway.placeReduceOnlyTrigger(band.venueSymbol(), band.closingSide(),
                band.quantity(), band.upper(), TriggerWhen.PRICE_RISES_TO, clientIdPrefix + "-hi");
        if (!above.accepted()) {
            System.out.printf("!!!! %s %s upper trigger rejected: %s — cancelling the lower one, "
                    + "because a bracket with one side is the arrangement that strands a leg%n",
                    band.venue(), band.venueSymbol(), above.detail());
            try {
                gateway.cancel(below.handle());
            } catch (RuntimeException e) {
                System.out.printf("!!!! and the cancel failed too: %s — %s %s now has a one-sided "
                        + "trigger resting and needs manual attention%n",
                        e.getMessage(), band.venue(), band.venueSymbol());
            }
            return false;
        }
        System.out.printf("   %-12s %-10s %s %s brackets %s .. %s (%s)%n", band.venue(),
                band.venueSymbol(), band.closingSide(), band.quantity(),
                band.lower().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                band.upper().setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                band.derivedFrom());
        return true;
    }
}
