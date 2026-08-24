package com.smalistean.propstrategy.xvf.shadow;

import com.smalistean.propstrategy.xvf.XvfConfig;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.Freshness;
import com.smalistean.propstrategy.xvf.shadow.XvfFundingSnapshot.PendingObservation;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Candidate;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.CaptureStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ExpectedNet;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Pair;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Ranks;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.Route;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.ScoreStatus;
import com.smalistean.propstrategy.xvf.shadow.XvfSignalRun.SignalScore;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.BookLevel;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentRules;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.InstrumentSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.OrderBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ReferenceSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.ResponseTiming;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.SnapshotIssue;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.TopOfBookSnapshot;
import com.smalistean.propstrategy.xvf.shadow.XvfVenueSnapshotSource.VenueSnapshot;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.EvaluatedPair;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.Leg;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.PairAlternative;
import com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.SignalEvaluation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Pure report-only planner that turns captured facts into the immutable shadow audit contract.
 *
 * <p>The v1 expected-net model deliberately assigns zero expected basis convergence and zero risk
 * penalty by default. It records the entry basis so subsequent realised observations can establish
 * whether that component deserves a non-zero model. No method in this class can place an order.
 */
public final class XvfShadowDecisionPlanner {

    private static final BigDecimal TEN_THOUSAND = new BigDecimal("10000");
    private static final BigDecimal TWO = new BigDecimal("2");

    public XvfSignalRun plan(
            UUID runId,
            XvfCaptureTiming timing,
            Instant generatedAt,
            SignalEvaluation signal,
            XvfFundingSnapshot funding,
            Map<String, VenueSnapshot> venueSnapshots,
            XvfShadowConfiguration configuration) {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(timing, "timing");
        Instant cutoffUtc = timing.cutoffUtc();
        generatedAt = micros(generatedAt);
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(funding, "funding");
        venueSnapshots = Map.copyOf(Objects.requireNonNull(venueSnapshots, "venueSnapshots"));
        Objects.requireNonNull(configuration, "configuration");
        if (!funding.cutoffUtc().equals(cutoffUtc)) {
            throw new IllegalArgumentException("Funding snapshot cutoff must equal run cutoff");
        }
        if (!signal.asOf().equals(cutoffUtc.atZone(configuration.productionZone()).toLocalDate())) {
            throw new IllegalArgumentException("Signal date must equal cutoff date in production zone");
        }
        validateVenueSnapshots(venueSnapshots);

        IssueCollector issues = new IssueCollector();
        collectVenueIssues(venueSnapshots, issues);
        collectWatermarkIssues(funding, issues);
        Map<InstrumentKey, InstrumentSnapshot> markets = flatten(venueSnapshots);

        BigDecimal standardLegNotional = money(configuration.capitalUsd()
                .multiply(BigDecimal.valueOf(XvfConfig.LEG_LEVERAGE))
                .divide(BigDecimal.valueOf(XvfConfig.POSITIONS).multiply(TWO),
                        12, RoundingMode.DOWN));

        List<Draft> drafts = new ArrayList<>();
        for (EvaluatedPair evaluated : signal.alternatives()) {
            drafts.add(score(evaluated, cutoffUtc, standardLegNotional, funding, markets,
                    configuration, issues));
        }
        Selection selection = selectShadowBook(drafts, configuration.venueCapitalUsd());
        List<Candidate> candidates = new ArrayList<>(drafts.size());
        for (int index = 0; index < drafts.size(); index++) {
            Draft draft = drafts.get(index);
            candidates.add(draft.toCandidate(index + 1,
                    selection.ranks().get(draft.grossRank())));
        }

        JsonDocument configurationSnapshot = configurationSnapshot(configuration);
        CaptureStatus status = issues.isEmpty() ? CaptureStatus.COMPLETE : CaptureStatus.PARTIAL;
        String failureCode = status == CaptureStatus.PARTIAL ? "INCOMPLETE_SHADOW_INPUTS" : null;
        String failureDetail = status == CaptureStatus.PARTIAL
                ? issues.size() + " shadow input issue(s); see data_issues" : null;
        addCaptureWindowIssues(timing, issues);
        return new XvfSignalRun(
                runId,
                (short) 1,
                timing.scheduledDecisionAt(),
                cutoffUtc,
                timing.captureStartedAt(),
                timing.captureEndedAt(),
                signal.asOf(),
                configuration.productionZone(),
                generatedAt,
                timing.scheduledAttemptId(),
                configuration.codeRevision(),
                configuration.strategyVersion(),
                XvfShadowJson.sha256(configurationSnapshot),
                configurationSnapshot,
                settledWatermarks(funding),
                pendingWatermarks(funding),
                venueState(configuration, venueSnapshots, selection),
                money(configuration.capitalUsd()),
                XvfShadowJson.array(issues.asJson()),
                status,
                failureCode,
                failureDetail,
                candidates);
    }

    /** Creates a durable failed attempt when orchestration cannot produce candidate rows. */
    public XvfSignalRun failed(
            UUID runId,
            XvfCaptureTiming timing,
            Instant generatedAt,
            XvfShadowConfiguration configuration,
            String failureCode,
            String failureDetail) {
        Objects.requireNonNull(timing, "timing");
        Instant cutoffUtc = timing.cutoffUtc();
        generatedAt = micros(generatedAt);
        JsonDocument configurationSnapshot = configurationSnapshot(configuration);
        List<Map<String, Object>> dataIssues = List.of(Map.of(
                "code", requireText(failureCode, "failureCode"),
                "detail", failureDetail == null ? "No detail available" : failureDetail,
                "severity", "ERROR"));
        return new XvfSignalRun(
                Objects.requireNonNull(runId, "runId"),
                (short) 1,
                timing.scheduledDecisionAt(),
                cutoffUtc,
                timing.captureStartedAt(),
                timing.captureEndedAt(),
                cutoffUtc.atZone(configuration.productionZone()).toLocalDate(),
                configuration.productionZone(),
                generatedAt,
                timing.scheduledAttemptId(),
                configuration.codeRevision(),
                configuration.strategyVersion(),
                XvfShadowJson.sha256(configurationSnapshot),
                configurationSnapshot,
                JsonDocument.emptyObject(),
                JsonDocument.emptyObject(),
                XvfShadowJson.object(Map.of(
                        "accountStateSource", "NOT_CAPTURED",
                        "collectorMode", "REPORT_ONLY")),
                money(configuration.capitalUsd()),
                XvfShadowJson.array(dataIssues),
                CaptureStatus.FAILED,
                failureCode,
                failureDetail,
                List.of());
    }

    private static Draft score(
            EvaluatedPair evaluated,
            Instant cutoffUtc,
            BigDecimal standardLegNotional,
            XvfFundingSnapshot funding,
            Map<InstrumentKey, InstrumentSnapshot> markets,
            XvfShadowConfiguration configuration,
            IssueCollector runIssues) {
        PairAlternative alternative = evaluated.alternative();
        InstrumentKey shortKey = new InstrumentKey(
                alternative.shortLeg().venue(), alternative.shortLeg().venueSymbol());
        InstrumentKey longKey = new InstrumentKey(
                alternative.longLeg().venue(), alternative.longLeg().venueSymbol());
        Optional<InstrumentSnapshot> shortMarket = Optional.ofNullable(markets.get(shortKey));
        Optional<InstrumentSnapshot> longMarket = Optional.ofNullable(markets.get(longKey));
        Optional<PendingObservation> shortFunding = funding.pending(shortKey.venue(), shortKey.symbol());
        Optional<PendingObservation> longFunding = funding.pending(longKey.venue(), longKey.symbol());
        RouteChoice fallbackRoute = preferredFeeRoute(
                shortKey.venue(), longKey.venue(), configuration);

        BigDecimal rawSpread = rate(evaluated.alternative().rawSpreadAnnualPct());
        BigDecimal adjustedSpread = rate(evaluated.adjustedSpreadAnnualPct());
        BigDecimal discount = factor(evaluated.staleDiscountFactor());
        BigDecimal thinVolume = volume(alternative.thinLegWeeklyVolume());
        Boolean pendingFresh = pendingFresh(shortFunding, longFunding);
        List<String> reasons = new ArrayList<>();
        Map<String, Object> gates = new LinkedHashMap<>();
        gates.put("widestForBase", evaluated.widestForBase());
        gates.put("baselineTopBookSelected", evaluated.baselineBookRank() != null
                && evaluated.baselineBookRank() <= XvfConfig.POSITIONS);
        gates.put("rawSpreadPass", evaluated.rawSpreadPass());
        gates.put("adjustedSpreadPass", evaluated.adjustedSpreadPass());
        gates.put("weeklyVolumePass", evaluated.volumePass());

        if (!evaluated.adjustedSpreadPass()) {
            reasons.add("ADJUSTED_SPREAD_BELOW_THRESHOLD");
        }
        if (!evaluated.volumePass()) {
            reasons.add("THIN_LEG_VOLUME_BELOW_THRESHOLD");
        }
        BigDecimal participationCap = money(thinVolume
                .multiply(BigDecimal.valueOf(XvfConfig.MAX_PARTICIPATION)));
        BigDecimal requested = money(standardLegNotional.min(participationCap));
        boolean participationPass = requested.compareTo(
                standardLegNotional.multiply(new BigDecimal("0.5"))) >= 0;
        gates.put("participationCapacityPass", participationPass);
        if (!participationPass) {
            reasons.add("PARTICIPATION_CAP_BELOW_HALF_STANDARD_LEG");
        }

        boolean signalEligible = evaluated.adjustedSpreadPass()
                && evaluated.volumePass() && participationPass;
        ScoreResult result = signalEligible
                ? scoreInputs(shortKey, longKey, shortMarket, longMarket, shortFunding, longFunding,
                        requested, cutoffUtc, configuration, reasons, gates, runIssues)
                : ScoreResult.unscorable();
        RouteChoice route = result.route() == null ? fallbackRoute : result.route();

        JsonDocument shortSnapshot = legSnapshot(shortKey, alternative.shortLeg(), shortMarket, shortFunding,
                signalEligible);
        JsonDocument longSnapshot = legSnapshot(longKey, alternative.longLeg(), longMarket, longFunding,
                signalEligible);
        Map<String, Object> scoreComponents = scoreComponents(
                route, result, standardLegNotional, participationCap, configuration);

        ExpectedNet expected = result.scorable()
                ? result.expectedNet()
                : new ExpectedNet(null, null, null, null, null, null, null, null, null);
        ScoreStatus scoreStatus = result.scorable() ? ScoreStatus.SCORABLE : ScoreStatus.UNSCORABLE;
        if (result.scorable() && result.expectedNet().expectedNetBps().signum() <= 0) {
            reasons.add("EXPECTED_NET_NOT_POSITIVE");
        }
        if (!result.scorable() && reasons.isEmpty()) {
            reasons.add("REQUIRED_SHADOW_INPUT_MISSING");
        }

        return new Draft(
                evaluated.grossRank(),
                new Pair(alternative.base(), pairType(alternative.pairType()),
                        shortKey.venue(), shortKey.symbol(), longKey.venue(), longKey.symbol()),
                evaluated.baselineBookRank(),
                new SignalScore(rawSpread, evaluated.eligibleYesterday(), discount, adjustedSpread,
                        pendingFresh, thinVolume),
                new Route(route.makerVenue(), route.takerVenue(), configuration.plannedHoldHours()),
                expected,
                result.scorable() ? requested : null,
                shortSnapshot,
                longSnapshot,
                scoreComponents,
                gates,
                scoreStatus,
                reasons);
    }

    private static ScoreResult scoreInputs(
            InstrumentKey shortKey,
            InstrumentKey longKey,
            Optional<InstrumentSnapshot> shortMarket,
            Optional<InstrumentSnapshot> longMarket,
            Optional<PendingObservation> shortFunding,
            Optional<PendingObservation> longFunding,
            BigDecimal requested,
            Instant cutoffUtc,
            XvfShadowConfiguration configuration,
            List<String> reasons,
            Map<String, Object> gates,
            IssueCollector runIssues) {
        if (shortMarket.isEmpty() || longMarket.isEmpty()) {
            missing(reasons, runIssues, shortMarket.isEmpty() ? shortKey : longKey,
                    "MARKET_SNAPSHOT_MISSING");
            gates.put("marketSnapshotsPresent", false);
            return ScoreResult.unscorable();
        }
        gates.put("marketSnapshotsPresent", true);
        if (shortFunding.isEmpty() || longFunding.isEmpty()) {
            missing(reasons, runIssues, shortFunding.isEmpty() ? shortKey : longKey,
                    "PENDING_FUNDING_MISSING");
            gates.put("pendingFundingPresent", false);
            return ScoreResult.unscorable();
        }
        gates.put("pendingFundingPresent", true);
        if (shortFunding.get().freshness() != Freshness.FRESH
                || longFunding.get().freshness() != Freshness.FRESH) {
            InstrumentKey stale = shortFunding.get().freshness() != Freshness.FRESH
                    ? shortKey : longKey;
            missing(reasons, runIssues, stale, "PENDING_FUNDING_STALE");
            gates.put("pendingFundingFresh", false);
            return ScoreResult.unscorable();
        }
        gates.put("pendingFundingFresh", true);

        MarketInputs shortInputs = marketInputs(shortKey, shortMarket.get(), cutoffUtc,
                configuration, reasons, runIssues);
        MarketInputs longInputs = marketInputs(longKey, longMarket.get(), cutoffUtc,
                configuration, reasons, runIssues);
        if (!shortInputs.valid() || !longInputs.valid()) {
            gates.put("marketDataFreshAndComplete", false);
            return ScoreResult.unscorable();
        }
        gates.put("marketDataFreshAndComplete", true);
        Duration quoteSkew = Duration.between(shortInputs.observedAt(), longInputs.observedAt()).abs();
        boolean skewPass = quoteSkew.compareTo(configuration.maximumCrossVenueQuoteSkew()) <= 0;
        gates.put("crossVenueQuoteSkewPass", skewPass);
        gates.put("crossVenueQuoteSkewMillis", quoteSkew.toMillis());
        if (!skewPass) {
            reasons.add("CROSS_VENUE_QUOTE_SKEW_EXCEEDED");
            runIssues.add("CROSS_VENUE_QUOTE_SKEW_EXCEEDED", "ERROR", null, null,
                    "Quote skew " + quoteSkew + " exceeds "
                            + configuration.maximumCrossVenueQuoteSkew());
            return ScoreResult.unscorable();
        }

        FundingProjection shortProjection = fundingProjection(
                shortFunding.get(), shortInputs.reference(), cutoffUtc,
                configuration.plannedHoldHours());
        FundingProjection longProjection = fundingProjection(
                longFunding.get(), longInputs.reference(), cutoffUtc,
                configuration.plannedHoldHours());
        if (!shortProjection.valid() || !longProjection.valid()) {
            InstrumentKey bad = !shortProjection.valid() ? shortKey : longKey;
            String problem = !shortProjection.valid()
                    ? shortProjection.problem() : longProjection.problem();
            missing(reasons, runIssues, bad, problem == null
                    ? "FUNDING_SCHEDULE_UNKNOWN" : problem);
            gates.put("fundingScheduleKnown", false);
            return ScoreResult.unscorable();
        }
        gates.put("fundingScheduleKnown", true);
        gates.put("shortFundingPayments", shortProjection.paymentCount());
        gates.put("longFundingPayments", longProjection.paymentCount());

        BigDecimal pendingSpread = bps(shortFunding.get().fundingRate()
                .subtract(longFunding.get().fundingRate()));
        BigDecimal expectedFunding = bps(shortFunding.get().fundingRate()
                .multiply(BigDecimal.valueOf(shortProjection.paymentCount()))
                .subtract(longFunding.get().fundingRate()
                        .multiply(BigDecimal.valueOf(longProjection.paymentCount()))));
        BigDecimal shortNormal = shortInputs.markPrice()
                .divide(shortMarket.get().baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal longNormal = longInputs.markPrice()
                .divide(longMarket.get().baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal markBasis = scale8(shortNormal.divide(longNormal, 20, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(TEN_THOUSAND));
        BigDecimal exitFee = scale8(configuration.feeSchedules().get(shortKey.venue()).takerBps()
                .add(configuration.feeSchedules().get(longKey.venue()).takerBps()));
        BigDecimal risk = scale8(configuration.riskPenaltyBps());
        List<RouteEvaluation> routeEvaluations = routeChoices(
                shortKey.venue(), longKey.venue(), configuration).stream()
                .map(route -> evaluateRoute(route, shortKey, longKey, shortInputs, longInputs,
                        shortMarket.get(), longMarket.get(), requested, pendingSpread,
                        expectedFunding, markBasis, exitFee, risk, configuration))
                .toList();
        routeEvaluations.forEach(routeEvaluation -> gates.put(
                "routeFeasible:" + routeEvaluation.route().makerVenue() + "Maker",
                routeEvaluation.scorable()));
        Optional<RouteEvaluation> chosen = routeEvaluations.stream()
                .filter(RouteEvaluation::scorable)
                .max(Comparator.comparing((RouteEvaluation routeEvaluation) ->
                                routeEvaluation.expectedNet().expectedNetBps())
                        .thenComparing(routeEvaluation -> routeEvaluation.route().makerVenue()));
        if (chosen.isEmpty()) {
            gates.put("oneMakerRouteFeasible", false);
            reasons.add("NO_FEASIBLE_ONE_MAKER_ROUTE");
            routeEvaluations.forEach(routeEvaluation -> routeEvaluation.reasons().forEach(reason ->
                    reasons.add(routeEvaluation.route().makerVenue() + "_MAKER:" + reason)));
            return ScoreResult.unscorable(routeEvaluations, markBasis);
        }
        RouteEvaluation selected = chosen.get();
        gates.put("oneMakerRouteFeasible", true);
        gates.putAll(selected.gates());
        return new ScoreResult(true, selected.expectedNet(), selected.sizing(), shortProjection,
                longProjection, selected.route(), routeEvaluations, markBasis);
    }

    private static RouteEvaluation evaluateRoute(
            RouteChoice route,
            InstrumentKey shortKey,
            InstrumentKey longKey,
            MarketInputs shortInputs,
            MarketInputs longInputs,
            InstrumentSnapshot shortMarket,
            InstrumentSnapshot longMarket,
            BigDecimal requested,
            BigDecimal pendingSpread,
            BigDecimal expectedFunding,
            BigDecimal markBasis,
            BigDecimal exitFee,
            BigDecimal risk,
            XvfShadowConfiguration configuration) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> gates = new LinkedHashMap<>();
        Sizing sizing = size(route, shortKey, longKey, shortInputs, longInputs, requested,
                configuration.maximumTakerSlippageBps(), reasons, gates);
        if (!sizing.valid()) {
            return new RouteEvaluation(route, false, null, sizing, null, Map.copyOf(gates),
                    List.copyOf(reasons));
        }
        BigDecimal shortEntry = sizing.shortEntryPrice()
                .divide(shortMarket.baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal longEntry = sizing.longEntryPrice()
                .divide(longMarket.baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal entryBasis = scale8(shortEntry.divide(longEntry, 20, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(TEN_THOUSAND));
        BigDecimal shortTouch = sizing.shortTouchEntryPrice()
                .divide(shortMarket.baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal longTouch = sizing.longTouchEntryPrice()
                .divide(longMarket.baseUnitsPerContract(), 20, RoundingMode.HALF_UP);
        BigDecimal touchBasis = scale8(shortTouch.divide(longTouch, 20, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE).multiply(TEN_THOUSAND));
        BigDecimal expectedBasis = scale8(markBasis
                .multiply(configuration.expectedBasisCaptureFactor())
                .add(touchBasis.subtract(markBasis)));
        XvfShadowConfiguration.FeeSchedule makerFee =
                configuration.feeSchedules().get(route.makerVenue());
        XvfShadowConfiguration.FeeSchedule takerFee =
                configuration.feeSchedules().get(route.takerVenue());
        BigDecimal entryFee = scale8(makerFee.makerBps().add(takerFee.takerBps()));
        BigDecimal slippage = scale8(sizing.takerSlippageBps());
        BigDecimal net = scale8(expectedFunding.add(expectedBasis)
                .subtract(entryFee).subtract(exitFee).subtract(slippage).subtract(risk));
        ExpectedNet expected = new ExpectedNet(
                pendingSpread, entryBasis, expectedFunding, expectedBasis, entryFee, exitFee,
                slippage, risk, net);
        return new RouteEvaluation(route, true, expected, sizing, touchBasis, Map.copyOf(gates),
                List.copyOf(reasons));
    }

    private static MarketInputs marketInputs(
            InstrumentKey key,
            InstrumentSnapshot market,
            Instant cutoffUtc,
            XvfShadowConfiguration configuration,
            List<String> reasons,
            IssueCollector runIssues) {
        if (market.reference().isEmpty() || market.topOfBook().isEmpty()
                || market.orderBook().isEmpty() || market.rules().isEmpty()) {
            missing(reasons, runIssues, key, "REQUIRED_MARKET_FIELD_MISSING");
            return MarketInputs.invalid();
        }
        ReferenceSnapshot reference = market.reference().get();
        TopOfBookSnapshot top = market.topOfBook().get();
        OrderBookSnapshot book = market.orderBook().get();
        InstrumentRules rules = market.rules().get();
        Optional<BigDecimal> mark = reference.markPrice().or(reference::midPrice);
        if (mark.isEmpty() || rules.quantityStep().isEmpty() || rules.minimumQuantity().isEmpty()
                || rules.minimumNotionalUsd().isEmpty() || !rules.trading()) {
            missing(reasons, runIssues, key, !rules.trading()
                    ? "INSTRUMENT_NOT_TRADING" : "REQUIRED_RULE_OR_MARK_MISSING");
            return MarketInputs.invalid();
        }
        Instant topAt = observedAt(top.timing());
        Instant bookAt = observedAt(book.timing());
        Instant referenceAt = observedAt(reference.timing());
        Instant oldest = topAt.isBefore(bookAt) ? topAt : bookAt;
        oldest = oldest.isBefore(referenceAt) ? oldest : referenceAt;
        Duration age = oldest.isAfter(cutoffUtc)
                ? Duration.ofNanos(-1) : Duration.between(oldest, cutoffUtc);
        if (age.isNegative() || age.compareTo(configuration.maximumQuoteAge()) > 0) {
            missing(reasons, runIssues, key, age.isNegative()
                    ? "MARKET_SOURCE_AFTER_CUTOFF" : "MARKET_SNAPSHOT_STALE");
            return MarketInputs.invalid();
        }
        return new MarketInputs(true, mark.get(), reference, top, book, rules, oldest);
    }

    private static Sizing size(
            RouteChoice route,
            InstrumentKey shortKey,
            InstrumentKey longKey,
            MarketInputs shortInputs,
            MarketInputs longInputs,
            BigDecimal requested,
            BigDecimal maximumSlippageBps,
            List<String> reasons,
            Map<String, Object> gates) {
        boolean makerIsShort = route.makerVenue().equals(shortKey.venue());
        MarketInputs maker = makerIsShort ? shortInputs : longInputs;
        MarketInputs taker = makerIsShort ? longInputs : shortInputs;
        boolean takerBuys = makerIsShort;
        BigDecimal makerTouch = makerIsShort
                ? maker.top().askPrice() : maker.top().bidPrice();
        BigDecimal takerTouch = takerBuys
                ? taker.top().askPrice() : taker.top().bidPrice();
        BigDecimal makerQuantity = floorQuantity(requested, makerTouch,
                maker.rules().quantityStep().orElseThrow());
        BigDecimal takerQuantity = floorQuantity(requested, takerTouch,
                taker.rules().quantityStep().orElseThrow());
        boolean rulesPass = quantityPass(makerQuantity, makerTouch, maker.rules())
                && quantityPass(takerQuantity, takerTouch, taker.rules());
        int makerSteps = stepCount(makerQuantity, maker.rules().quantityStep().orElseThrow());
        int takerSteps = stepCount(takerQuantity, taker.rules().quantityStep().orElseThrow());
        boolean stepPass = makerSteps >= XvfConfig.MIN_STEPS_PER_LEG
                && takerSteps >= XvfConfig.MIN_STEPS_PER_LEG;
        BigDecimal makerNotional = makerQuantity.multiply(makerTouch);
        BigDecimal takerNotional = takerQuantity.multiply(takerTouch);
        BigDecimal larger = makerNotional.max(takerNotional);
        BigDecimal imbalance = larger.signum() == 0 ? BigDecimal.ONE
                : makerNotional.subtract(takerNotional).abs()
                        .divide(larger, 20, RoundingMode.HALF_UP);
        boolean imbalancePass = imbalance.compareTo(
                BigDecimal.valueOf(XvfConfig.MAX_NOTIONAL_IMBALANCE)) <= 0;
        gates.put("instrumentRulesPass", rulesPass);
        gates.put("minimumStepCountPass", stepPass);
        gates.put("notionalImbalancePass", imbalancePass);
        gates.put("makerStepCount", makerSteps);
        gates.put("takerStepCount", takerSteps);
        gates.put("notionalImbalance", imbalance);
        if (!rulesPass) {
            reasons.add("ORDER_RULES_REJECT_NOTIONAL");
        }
        if (!stepPass) {
            reasons.add("QUANTITY_HAS_TOO_FEW_STEPS");
        }
        if (!imbalancePass) {
            reasons.add("LEG_NOTIONAL_IMBALANCE_ABOVE_CAP");
        }
        if (!rulesPass || !stepPass || !imbalancePass) {
            return Sizing.invalid();
        }

        List<BookLevel> levels = takerBuys ? taker.book().asks() : taker.book().bids();
        DepthResult depth = depth(levels, requested, takerBuys, maximumSlippageBps);
        gates.put("takerDepthWithinWorstPriceCap", depth.sufficient());
        gates.put("takerWorstPriceCap", depth.worstPrice());
        if (!depth.sufficient()) {
            reasons.add("TAKER_DEPTH_INSUFFICIENT_WITHIN_WORST_PRICE_CAP");
            return Sizing.invalid();
        }
        BigDecimal slippage = scale8(depth.slippageBps());
        boolean slippagePass = slippage.compareTo(maximumSlippageBps) <= 0;
        gates.put("takerSlippagePass", slippagePass);
        if (!slippagePass) {
            reasons.add("TAKER_SLIPPAGE_ABOVE_CAP");
            return Sizing.invalid();
        }
        BigDecimal shortEntryPrice = makerIsShort ? makerTouch : depth.vwap();
        BigDecimal longEntryPrice = makerIsShort ? depth.vwap() : makerTouch;
        BigDecimal shortTouchEntryPrice = makerIsShort ? makerTouch : takerTouch;
        BigDecimal longTouchEntryPrice = makerIsShort ? takerTouch : makerTouch;
        return new Sizing(true, makerQuantity, takerQuantity, makerTouch, takerTouch,
                depth.vwap(), depth.worstPrice(), shortEntryPrice, longEntryPrice,
                shortTouchEntryPrice, longTouchEntryPrice, depth.sufficient(), slippage);
    }

    private static FundingProjection fundingProjection(
            PendingObservation observation,
            ReferenceSnapshot reference,
            Instant cutoffUtc,
            int holdHours) {
        Instant observedNext = observation.targetStamp();
        Instant venueNext = reference.nextFundingTime().orElse(null);
        Integer observedInterval = observation.fundingIntervalHours();
        Integer venueInterval = reference.fundingIntervalHours().orElse(null);
        if (observedInterval != null && venueInterval != null
                && !observedInterval.equals(venueInterval)) {
            return FundingProjection.invalid("FUNDING_INTERVAL_DISAGREES_WITH_VENUE");
        }
        Integer interval = venueInterval != null ? venueInterval : observedInterval;
        if (observedNext != null && venueNext != null && !observedNext.equals(venueNext)) {
            return FundingProjection.invalid("FUNDING_TARGET_DISAGREES_WITH_VENUE");
        }
        Instant next = venueNext != null ? venueNext : observedNext;
        if (next == null || interval == null || interval <= 0) {
            return FundingProjection.invalid("FUNDING_SCHEDULE_UNKNOWN");
        }
        if (!next.isAfter(cutoffUtc)) {
            return FundingProjection.invalid("PENDING_FUNDING_TARGET_NOT_IN_FUTURE");
        }
        Instant end = cutoffUtc.plus(holdHours, ChronoUnit.HOURS);
        if (next.isAfter(end)) {
            return new FundingProjection(true, 0, next, interval,
                    venueInterval != null ? "VENUE_REFERENCE" : observation.intervalSource().name(),
                    null);
        }
        long seconds = Duration.between(next, end).getSeconds();
        int payments = Math.toIntExact(1 + seconds / Duration.ofHours(interval).getSeconds());
        return new FundingProjection(true, payments, next, interval,
                venueInterval != null ? "VENUE_REFERENCE" : observation.intervalSource().name(),
                null);
    }

    private static DepthResult depth(List<BookLevel> levels, BigDecimal targetNotional,
                                     boolean buying, BigDecimal maximumSlippageBps) {
        BigDecimal remaining = targetNotional;
        BigDecimal totalQuantity = BigDecimal.ZERO;
        BigDecimal totalNotional = BigDecimal.ZERO;
        BigDecimal touch = levels.getFirst().price();
        BigDecimal relativeCap = maximumSlippageBps.divide(
                TEN_THOUSAND, 24, RoundingMode.HALF_UP);
        BigDecimal worstPrice = buying
                ? touch.multiply(BigDecimal.ONE.add(relativeCap))
                : touch.multiply(BigDecimal.ONE.subtract(relativeCap));
        for (BookLevel level : levels) {
            boolean insideCap = buying
                    ? level.price().compareTo(worstPrice) <= 0
                    : level.price().compareTo(worstPrice) >= 0;
            if (!insideCap) {
                break;
            }
            BigDecimal levelNotional = level.price().multiply(level.quantity());
            BigDecimal usedNotional = levelNotional.min(remaining);
            BigDecimal usedQuantity = usedNotional.divide(level.price(), 24, RoundingMode.HALF_UP);
            totalNotional = totalNotional.add(usedNotional);
            totalQuantity = totalQuantity.add(usedQuantity);
            remaining = remaining.subtract(usedNotional);
            if (remaining.signum() <= 0) {
                break;
            }
        }
        if (remaining.signum() > 0 || totalQuantity.signum() == 0) {
            return new DepthResult(false, BigDecimal.ZERO, null, worstPrice);
        }
        BigDecimal vwap = totalNotional.divide(totalQuantity, 24, RoundingMode.HALF_UP);
        BigDecimal relative = buying
                ? vwap.divide(touch, 24, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                : BigDecimal.ONE.subtract(vwap.divide(touch, 24, RoundingMode.HALF_UP));
        return new DepthResult(true, relative.max(BigDecimal.ZERO).multiply(TEN_THOUSAND),
                vwap, worstPrice);
    }

    private static BigDecimal floorQuantity(BigDecimal notional, BigDecimal price, BigDecimal step) {
        BigDecimal raw = notional.divide(price, 24, RoundingMode.DOWN);
        return raw.divide(step, 0, RoundingMode.DOWN).multiply(step);
    }

    private static boolean quantityPass(BigDecimal quantity, BigDecimal price, InstrumentRules rules) {
        if (quantity.signum() <= 0
                || quantity.compareTo(rules.minimumQuantity().orElseThrow()) < 0
                || quantity.multiply(price).compareTo(rules.minimumNotionalUsd().orElseThrow()) < 0) {
            return false;
        }
        return rules.maximumQuantity().isEmpty()
                || quantity.compareTo(rules.maximumQuantity().get()) <= 0;
    }

    private static int stepCount(BigDecimal quantity, BigDecimal step) {
        try {
            return quantity.divide(step, 0, RoundingMode.DOWN).intValueExact();
        } catch (ArithmeticException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Selection selectShadowBook(
            List<Draft> drafts,
            Map<String, BigDecimal> declaredVenueCapital) {
        List<Draft> selectable = drafts.stream()
                .filter(draft -> draft.scoreStatus() == ScoreStatus.SCORABLE)
                .filter(draft -> draft.expectedNet().expectedNetBps().signum() > 0)
                .sorted(Comparator
                        .comparing((Draft draft) -> draft.expectedNet().expectedNetBps()).reversed()
                        .thenComparingInt(Draft::grossRank))
                .toList();
        Map<Integer, Integer> ranks = new LinkedHashMap<>();
        Map<String, BigDecimal> remaining = new LinkedHashMap<>(declaredVenueCapital);
        Set<String> selectedBases = new LinkedHashSet<>();
        for (Draft draft : selectable) {
            if (ranks.size() >= XvfConfig.POSITIONS) {
                draft.gateResults().put("shadowSelected", false);
                draft.decisionReasons().add("SHADOW_BOOK_POSITION_LIMIT_REACHED");
                continue;
            }
            if (selectedBases.contains(draft.pair().base())) {
                draft.gateResults().put("shadowSelected", false);
                draft.decisionReasons().add("HIGHER_EXPECTED_NET_PAIR_ALREADY_SELECTED_FOR_BASE");
                continue;
            }
            BigDecimal requested = draft.requestedLegNotionalUsd();
            boolean shortPass = remaining.get(draft.pair().shortVenue()).compareTo(requested) >= 0;
            boolean longPass = remaining.get(draft.pair().longVenue()).compareTo(requested) >= 0;
            draft.gateResults().put("shortVenueCapitalPass", shortPass);
            draft.gateResults().put("longVenueCapitalPass", longPass);
            draft.gateResults().put("venueCapitalPass", shortPass && longPass);
            if (!shortPass || !longPass) {
                draft.gateResults().put("shadowSelected", false);
                draft.decisionReasons().add("DECLARED_VENUE_CAPITAL_INSUFFICIENT");
                continue;
            }
            remaining.compute(draft.pair().shortVenue(), (venue, capital) ->
                    capital.subtract(requested));
            remaining.compute(draft.pair().longVenue(), (venue, capital) ->
                    capital.subtract(requested));
            selectedBases.add(draft.pair().base());
            ranks.put(draft.grossRank(), ranks.size() + 1);
            draft.gateResults().put("shadowSelected", true);
        }
        for (Draft draft : drafts) {
            if (draft.scoreStatus() != ScoreStatus.SCORABLE
                    || draft.expectedNet().expectedNetBps().signum() <= 0) {
                draft.gateResults().putIfAbsent("shadowSelected", false);
            }
        }
        return new Selection(Map.copyOf(ranks), Map.copyOf(remaining));
    }

    private static List<RouteChoice> routeChoices(
            String shortVenue,
            String longVenue,
            XvfShadowConfiguration configuration) {
        RouteChoice preferred = preferredFeeRoute(shortVenue, longVenue, configuration);
        RouteChoice shortMaker = new RouteChoice(shortVenue, longVenue,
                preferred.baselineMakerVenue(), preferred.baselineTakerVenue());
        RouteChoice longMaker = new RouteChoice(longVenue, shortVenue,
                preferred.baselineMakerVenue(), preferred.baselineTakerVenue());
        return List.of(shortMaker, longMaker);
    }

    private static RouteChoice preferredFeeRoute(String shortVenue, String longVenue,
                                                  XvfShadowConfiguration configuration) {
        BigDecimal shortMakerCost = configuration.feeSchedules().get(shortVenue).makerBps()
                .add(configuration.feeSchedules().get(longVenue).takerBps());
        BigDecimal longMakerCost = configuration.feeSchedules().get(longVenue).makerBps()
                .add(configuration.feeSchedules().get(shortVenue).takerBps());
        String baselineMaker = depthRank(shortVenue) <= depthRank(longVenue)
                ? shortVenue : longVenue;
        String baselineTaker = baselineMaker.equals(shortVenue) ? longVenue : shortVenue;
        if (shortMakerCost.compareTo(longMakerCost) < 0) {
            return new RouteChoice(shortVenue, longVenue, baselineMaker, baselineTaker);
        }
        if (longMakerCost.compareTo(shortMakerCost) < 0) {
            return new RouteChoice(longVenue, shortVenue, baselineMaker, baselineTaker);
        }
        return new RouteChoice(baselineMaker, baselineTaker, baselineMaker, baselineTaker);
    }

    private static int depthRank(String venue) {
        return switch (venue) {
            case "hyperliquid" -> 1;
            case "bybit" -> 2;
            default -> 3;
        };
    }

    private static JsonDocument configurationSnapshot(XvfShadowConfiguration configuration) {
        Map<String, Object> fees = new LinkedHashMap<>();
        configuration.feeSchedules().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> fees.put(entry.getKey(), Map.of(
                        "makerBps", entry.getValue().makerBps(),
                        "takerBps", entry.getValue().takerBps(),
                        "makerProvenance", entry.getValue().makerProvenance(),
                        "takerProvenance", entry.getValue().takerProvenance())));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("activeVenues", List.of(XvfConfig.VENUES));
        snapshot.put("capitalUsd", configuration.capitalUsd());
        snapshot.put("venueCapitalUsd", configuration.venueCapitalUsd());
        snapshot.put("accountStateSource", "DECLARED_CONFIGURATION_NOT_EXCHANGE");
        snapshot.put("positions", XvfConfig.POSITIONS);
        snapshot.put("legLeverage", XvfConfig.LEG_LEVERAGE);
        snapshot.put("plannedHoldHours", configuration.plannedHoldHours());
        snapshot.put("lookbackDaysCexDex", XvfConfig.LOOKBACK_DAYS);
        snapshot.put("lookbackDaysCexCex", XvfConfig.LOOKBACK_DAYS_CEX_CEX);
        snapshot.put("minimumAdjustedSpreadAnnualPct", XvfConfig.MIN_SPREAD_ANNUAL_PCT);
        snapshot.put("staleSignalDiscount", XvfConfig.STALE_SIGNAL_DISCOUNT);
        snapshot.put("completenessRatio", XvfConfig.COMPLETENESS_RATIO);
        snapshot.put("typicalWindowDays", XvfConfig.TYPICAL_WINDOW_DAYS);
        snapshot.put("minimumUsableSymbolsPerVenue", XvfConfig.MIN_USABLE_SYMBOLS);
        snapshot.put("minimumWeeklyQuoteVolumeUsd", XvfConfig.MIN_WEEKLY_QUOTE_VOLUME);
        snapshot.put("maximumParticipation", XvfConfig.MAX_PARTICIPATION);
        snapshot.put("minimumStepsPerLeg", XvfConfig.MIN_STEPS_PER_LEG);
        snapshot.put("maximumNotionalImbalance", XvfConfig.MAX_NOTIONAL_IMBALANCE);
        snapshot.put("maximumPendingFundingAgeSeconds",
                configuration.maximumPendingFundingAge().toSeconds());
        snapshot.put("maximumSettledFundingAgeSeconds",
                configuration.maximumSettledFundingAge().toSeconds());
        snapshot.put("maximumQuoteAgeSeconds", configuration.maximumQuoteAge().toSeconds());
        snapshot.put("maximumCrossVenueQuoteSkewSeconds",
                configuration.maximumCrossVenueQuoteSkew().toSeconds());
        snapshot.put("maximumCaptureDurationSeconds",
                configuration.maximumCaptureDuration().toSeconds());
        snapshot.put("scheduledAttemptId", configuration.scheduledAttemptId());
        snapshot.put("maximumTakerSlippageBps", configuration.maximumTakerSlippageBps());
        snapshot.put("makerRoutingPolicy",
                "SCORE_BOTH_ONE_MAKER_ROUTES_AND_CHOOSE_HIGHEST_FEASIBLE_EXPECTED_NET");
        snapshot.put("baselineDepthRanks", Map.of(
                "hyperliquid", 1, "bybit", 2, "binance", 3));
        snapshot.put("pairClassification", Map.of(
                "dexVenues", List.of("hyperliquid"),
                "cexDexWhenEitherLegIsDex", true));
        snapshot.put("settledWatermarkRole",
                "CONTEXT_CAPTURED_AFTER_SIGNAL_NOT_SAME_DATABASE_SNAPSHOT");
        snapshot.put("expectedBasisModel", "EXECUTABLE_ENTRY_BASIS_TIMES_CAPTURE_FACTOR");
        snapshot.put("expectedBasisCaptureFactor", configuration.expectedBasisCaptureFactor());
        snapshot.put("riskPenaltyBps", configuration.riskPenaltyBps());
        snapshot.put("feeSchedules", fees);
        return XvfShadowJson.object(snapshot);
    }

    private static JsonDocument settledWatermarks(XvfFundingSnapshot funding) {
        Map<String, Object> out = new LinkedHashMap<>();
        funding.settledWatermarks().forEach(watermark -> out.put(watermark.venue(), Map.of(
                "latestFundingTime", timestamp(watermark.latestFundingTime()),
                "freshness", watermark.freshness().name())));
        return XvfShadowJson.object(out);
    }

    private static JsonDocument pendingWatermarks(XvfFundingSnapshot funding) {
        Map<String, Object> out = new LinkedHashMap<>();
        funding.pendingWatermarks().forEach(watermark -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("latestObservedAt", timestamp(watermark.latestObservedAt()));
            value.put("symbolCount", watermark.symbolCount());
            value.put("freshSymbolCount", watermark.freshSymbolCount());
            value.put("staleSymbolCount", watermark.staleSymbolCount());
            value.put("freshness", watermark.freshness().name());
            out.put(watermark.venue(), value);
        });
        return XvfShadowJson.object(out);
    }

    private static JsonDocument venueState(
            XvfShadowConfiguration configuration,
            Map<String, VenueSnapshot> venueSnapshots,
            Selection selection) {
        Map<String, Object> venues = new LinkedHashMap<>();
        for (String venue : XvfConfig.VENUES) {
            VenueSnapshot snapshot = venueSnapshots.get(venue);
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("declaredCapitalUsd", configuration.venueCapitalUsd().get(venue));
            value.put("selectedLegNotionalUsd", money(configuration.venueCapitalUsd().get(venue)
                    .subtract(selection.remainingVenueCapital().get(venue))));
            value.put("remainingDeclaredCapitalUsd",
                    money(selection.remainingVenueCapital().get(venue)));
            value.put("accountStateSource", "DECLARED_CONFIGURATION_NOT_EXCHANGE");
            value.put("instrumentSnapshots", snapshot == null ? 0 : snapshot.instruments().size());
            value.put("sourceIssues", snapshot == null ? 0 : snapshot.issues().size());
            venues.put(venue, value);
        }
        return XvfShadowJson.object(Map.of(
                "collectorMode", "REPORT_ONLY_PUBLIC_MARKET_DATA",
                "selectedPairCount", selection.ranks().size(),
                "venues", venues));
    }

    private static JsonDocument legSnapshot(
            InstrumentKey key,
            Leg signalLeg,
            Optional<InstrumentSnapshot> market,
            Optional<PendingObservation> funding,
            boolean requested) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("venue", key.venue());
        out.put("venueSymbol", key.symbol());
        out.put("marketRequested", requested);
        out.put("productionSignalInputs", Map.of(
                "trailingRateCexDex", BigDecimal.valueOf(signalLeg.rateCexDex()),
                "trailingRateCexCex", BigDecimal.valueOf(signalLeg.rateCexCex()),
                "weeklyQuoteVolumeUsd", BigDecimal.valueOf(signalLeg.weeklyQuoteVolume()),
                "settledRateCutoff", "PRODUCTION_DATE_MIDNIGHT_FROM_SIGNAL_EVALUATION",
                "liveVolumeReceiptTime", "NOT_EXPOSED_BY_PRODUCTION_SIGNAL_V1"));
        if (market.isEmpty()) {
            out.put("marketPresent", false);
        } else {
            InstrumentSnapshot value = market.get();
            out.put("marketPresent", true);
            out.put("canonicalBase", value.canonicalBase());
            out.put("baseUnitsPerContract", value.baseUnitsPerContract());
            out.put("missingData", value.missingData());
            value.reference().ifPresent(reference -> out.put("reference", referenceJson(reference)));
            value.activity().ifPresent(activity -> {
                Map<String, Object> activityJson = new LinkedHashMap<>();
                activityJson.put("quoteVolume24hUsd", optional(activity.quoteVolume24hUsd()));
                activityJson.put("timing", timingJson(activity.timing()));
                out.put("activity", activityJson);
            });
            value.topOfBook().ifPresent(top -> out.put("topOfBook", Map.of(
                    "bidPrice", top.bidPrice(),
                    "bidQuantity", top.bidQuantity(),
                    "askPrice", top.askPrice(),
                    "askQuantity", top.askQuantity(),
                    "timing", timingJson(top.timing()))));
            value.rules().ifPresent(rules -> out.put("rules", rulesJson(rules)));
            value.orderBook().ifPresent(book -> out.put("orderBook", Map.of(
                    "bids", levelsJson(book.bids()),
                    "asks", levelsJson(book.asks()),
                    "timing", timingJson(book.timing()))));
        }
        if (funding.isEmpty()) {
            out.put("pendingFundingPresent", false);
        } else {
            PendingObservation value = funding.get();
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("fundingRate", value.fundingRate());
            pending.put("observedHour", value.observedHour().toString());
            pending.put("observedAt", value.observedAt().toString());
            pending.put("targetStamp", timestamp(value.targetStamp()));
            pending.put("fundingIntervalHours", value.fundingIntervalHours());
            pending.put("intervalSource", value.intervalSource().name());
            pending.put("freshness", value.freshness().name());
            out.put("pendingFundingPresent", true);
            out.put("pendingFunding", pending);
        }
        return XvfShadowJson.object(out);
    }

    private static Map<String, Object> referenceJson(ReferenceSnapshot reference) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("markPrice", optional(reference.markPrice()));
        out.put("indexPrice", optional(reference.indexPrice()));
        out.put("midPrice", optional(reference.midPrice()));
        out.put("venuePendingFundingRate", optional(reference.pendingFundingRate()));
        out.put("nextFundingTime", reference.nextFundingTime().map(Instant::toString).orElse(null));
        out.put("fundingIntervalHours", reference.fundingIntervalHours().orElse(null));
        out.put("openInterest", optional(reference.openInterest()));
        out.put("timing", timingJson(reference.timing()));
        return out;
    }

    private static Map<String, Object> rulesJson(InstrumentRules rules) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tickSize", optional(rules.tickSize()));
        out.put("quantityStep", optional(rules.quantityStep()));
        out.put("minimumQuantity", optional(rules.minimumQuantity()));
        out.put("minimumNotionalUsd", optional(rules.minimumNotionalUsd()));
        out.put("maximumQuantity", optional(rules.maximumQuantity()));
        out.put("maximumLeverage", rules.maximumLeverage().orElse(null));
        out.put("trading", rules.trading());
        out.put("timing", timingJson(rules.timing()));
        return out;
    }

    private static List<Map<String, Object>> levelsJson(List<BookLevel> levels) {
        return levels.stream().map(level -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("price", level.price());
            out.put("quantity", level.quantity());
            out.put("orderCount", level.orderCount().orElse(null));
            return out;
        }).toList();
    }

    private static Map<String, Object> timingJson(ResponseTiming timing) {
        return Map.of(
                "requestedAt", timing.requestedAt().toString(),
                "sourceAt", timing.sourceAt().map(Instant::toString).orElse("UNKNOWN"),
                "receivedAt", timing.receivedAt().toString());
    }

    private static Map<String, Object> scoreComponents(
            RouteChoice route,
            ScoreResult result,
            BigDecimal standardLegNotional,
            BigDecimal participationCap,
            XvfShadowConfiguration configuration) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("formulaVersion", 1);
        out.put("units", "hold_period_bps_per_leg_notional");
        out.put("standardLegNotionalUsd", standardLegNotional);
        out.put("participationCapUsd", participationCap);
        out.put("shadowMakerRoutingPolicy",
                "SCORE_BOTH_ONE_MAKER_ROUTES_AND_CHOOSE_HIGHEST_FEASIBLE_EXPECTED_NET");
        out.put("baselineMakerVenue", route.baselineMakerVenue());
        out.put("baselineTakerVenue", route.baselineTakerVenue());
        out.put("expectedFundingModel", "LATEST_PENDING_RATE_REPEATED_AT_KNOWN_STAMPS");
        out.put("pendingFundingSpreadTypedField",
                "RAW_NEXT_EVENT_RATE_DIFFERENCE_NOT_CADENCE_NORMALIZED");
        out.put("expectedBasisModel", "EXECUTABLE_ENTRY_BASIS_TIMES_CAPTURE_FACTOR");
        out.put("expectedBasisCaptureFactor", configuration.expectedBasisCaptureFactor());
        out.put("markBasisBps", result.markBasisBps());
        out.put("futureExitSlippageIncluded", false);
        out.put("makerFillProbabilityModelled", false);
        out.put("makerFillPriceAssumption", "CURRENT_PASSIVE_TOUCH");
        out.put("takerFillPrice", "DEPTH_VWAP_WITHIN_LIVE_WORST_PRICE_IOC_CAP");
        out.put("routeEvaluations", result.routeEvaluations().stream()
                .map(XvfShadowDecisionPlanner::routeEvaluationJson).toList());
        if (result.scorable()) {
            out.put("makerQuantity", result.sizing().makerQuantity());
            out.put("takerQuantity", result.sizing().takerQuantity());
            out.put("makerTouch", result.sizing().makerTouch());
            out.put("takerTouch", result.sizing().takerTouch());
            out.put("takerVwap", result.sizing().takerVwap());
            out.put("takerWorstPriceCap", result.sizing().takerWorstPriceCap());
            out.put("shortExecutableEntryPrice", result.sizing().shortEntryPrice());
            out.put("longExecutableEntryPrice", result.sizing().longEntryPrice());
            out.put("shortFundingPayments", result.shortProjection().paymentCount());
            out.put("longFundingPayments", result.longProjection().paymentCount());
            out.put("shortFundingIntervalSource", result.shortProjection().intervalSource());
            out.put("longFundingIntervalSource", result.longProjection().intervalSource());
        }
        return out;
    }

    private static Map<String, Object> routeEvaluationJson(RouteEvaluation evaluation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("makerVenue", evaluation.route().makerVenue());
        out.put("takerVenue", evaluation.route().takerVenue());
        out.put("feasible", evaluation.scorable());
        out.put("reasons", evaluation.reasons());
        out.put("gates", evaluation.gates());
        if (evaluation.scorable()) {
            out.put("entryBasisBps", evaluation.expectedNet().entryBasisBps());
            out.put("expectedEntryFeeBps", evaluation.expectedNet().expectedEntryFeeBps());
            out.put("expectedSlippageBps", evaluation.expectedNet().expectedSlippageBps());
            out.put("expectedNetBps", evaluation.expectedNet().expectedNetBps());
            out.put("makerTouch", evaluation.sizing().makerTouch());
            out.put("takerVwap", evaluation.sizing().takerVwap());
        }
        return out;
    }

    private static void validateVenueSnapshots(Map<String, VenueSnapshot> snapshots) {
        snapshots.forEach((venue, snapshot) -> {
            if (!venue.equals(snapshot.venue())) {
                throw new IllegalArgumentException("Venue snapshot key does not match its venue");
            }
        });
    }

    private static Map<InstrumentKey, InstrumentSnapshot> flatten(
            Map<String, VenueSnapshot> snapshots) {
        Map<InstrumentKey, InstrumentSnapshot> out = new LinkedHashMap<>();
        snapshots.values().forEach(venue -> venue.instruments().forEach((symbol, snapshot) ->
                out.put(new InstrumentKey(venue.venue(), symbol), snapshot)));
        return Map.copyOf(out);
    }

    private static void collectVenueIssues(Map<String, VenueSnapshot> snapshots,
                                           IssueCollector issues) {
        snapshots.values().stream().flatMap(snapshot -> snapshot.issues().stream())
                .forEach(issue -> issues.add(issue.code(), issue.severity().name(), issue.venue(),
                        issue.venueSymbol().orElse(null), issue.detail()));
    }

    private static void collectWatermarkIssues(XvfFundingSnapshot funding, IssueCollector issues) {
        funding.pendingWatermarks().stream()
                .filter(watermark -> watermark.freshness() != Freshness.FRESH)
                .forEach(watermark -> issues.add("PENDING_VENUE_WATERMARK_"
                                + watermark.freshness().name(), "ERROR", watermark.venue(), null,
                        "Latest pending observation is " + watermark.freshness()));
        funding.pendingWatermarks().stream()
                .filter(watermark -> watermark.freshSymbolCount() < XvfConfig.MIN_USABLE_SYMBOLS)
                .forEach(watermark -> issues.add("PENDING_VENUE_COVERAGE_BELOW_MINIMUM",
                        "ERROR", watermark.venue(), null,
                        "Fresh pending symbols " + watermark.freshSymbolCount()
                                + " below required " + XvfConfig.MIN_USABLE_SYMBOLS));
        funding.settledWatermarks().stream()
                .filter(watermark -> watermark.freshness() != Freshness.FRESH)
                .forEach(watermark -> issues.add("SETTLED_VENUE_WATERMARK_"
                                + watermark.freshness().name(), "ERROR", watermark.venue(), null,
                        "Latest settled funding row is " + watermark.freshness()));
    }

    private static void addCaptureWindowIssues(XvfCaptureTiming timing, IssueCollector issues) {
        Duration window = Duration.between(timing.captureStartedAt(), timing.captureEndedAt());
        if (window.isNegative()) {
            issues.add("CAPTURE_WINDOW_NEGATIVE", "ERROR", "CAPTURE", null,
                    "captureEndedAt is before captureStartedAt");
            return;
        }
        issues.add("CAPTURE_WINDOW_MILLIS", "INFO", "CAPTURE", null,
                "Wall-clock capture window was " + window.toMillis() + " ms");
    }

    private static Boolean pendingFresh(Optional<PendingObservation> shortFunding,
                                        Optional<PendingObservation> longFunding) {
        if (shortFunding.isEmpty() || longFunding.isEmpty()) {
            return null;
        }
        return shortFunding.get().freshness() == Freshness.FRESH
                && longFunding.get().freshness() == Freshness.FRESH;
    }

    private static void missing(List<String> reasons, IssueCollector issues, InstrumentKey key,
                                String code) {
        reasons.add(code + ":" + key.venue() + ":" + key.symbol());
        issues.add(code, "ERROR", key.venue(), key.symbol(),
                "Required shadow input is unavailable for " + key.venue() + " " + key.symbol());
    }

    private static Instant observedAt(ResponseTiming timing) {
        return timing.sourceAt().orElse(timing.receivedAt());
    }

    private static Object optional(Optional<?> value) {
        return value.orElse(null);
    }

    private static String timestamp(Instant value) {
        return value == null ? "MISSING" : value.toString();
    }

    private static XvfSignalRun.PairType pairType(
            com.smalistean.propstrategy.xvf.signal.XvfSignalEngine.PairType type) {
        return XvfSignalRun.PairType.valueOf(type.name());
    }

    private static BigDecimal bps(BigDecimal decimalRate) {
        return scale8(decimalRate.multiply(TEN_THOUSAND));
    }

    private static BigDecimal rate(double value) {
        return scale8(BigDecimal.valueOf(value));
    }

    private static BigDecimal factor(double value) {
        return BigDecimal.valueOf(value).setScale(12, RoundingMode.HALF_UP);
    }

    private static BigDecimal volume(double value) {
        return BigDecimal.valueOf(value).setScale(12, RoundingMode.HALF_UP);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(12, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale8(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    private static Instant micros(Instant value) {
        return Objects.requireNonNull(value, "timestamp").truncatedTo(ChronoUnit.MICROS);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record InstrumentKey(String venue, String symbol) { }

    private record RouteChoice(
            String makerVenue,
            String takerVenue,
            String baselineMakerVenue,
            String baselineTakerVenue) { }

    private record MarketInputs(
            boolean valid,
            BigDecimal markPrice,
            ReferenceSnapshot reference,
            TopOfBookSnapshot top,
            OrderBookSnapshot book,
            InstrumentRules rules,
            Instant observedAt) {

        static MarketInputs invalid() {
            return new MarketInputs(false, null, null, null, null, null, null);
        }
    }

    private record FundingProjection(
            boolean valid,
            int paymentCount,
            Instant firstFutureStamp,
            Integer intervalHours,
            String intervalSource,
            String problem) {

        static FundingProjection invalid(String problem) {
            return new FundingProjection(false, 0, null, null, null, problem);
        }
    }

    private record DepthResult(
            boolean sufficient,
            BigDecimal slippageBps,
            BigDecimal vwap,
            BigDecimal worstPrice) { }

    private record Sizing(
            boolean valid,
            BigDecimal makerQuantity,
            BigDecimal takerQuantity,
            BigDecimal makerTouch,
            BigDecimal takerTouch,
            BigDecimal takerVwap,
            BigDecimal takerWorstPriceCap,
            BigDecimal shortEntryPrice,
            BigDecimal longEntryPrice,
            BigDecimal shortTouchEntryPrice,
            BigDecimal longTouchEntryPrice,
            boolean depthSufficient,
            BigDecimal takerSlippageBps) {

        static Sizing invalid() {
            return new Sizing(false, null, null, null, null, null, null,
                    null, null, null, null, false, null);
        }
    }

    private record RouteEvaluation(
            RouteChoice route,
            boolean scorable,
            ExpectedNet expectedNet,
            Sizing sizing,
            BigDecimal touchBasisBps,
            Map<String, Object> gates,
            List<String> reasons) { }

    private record ScoreResult(
            boolean scorable,
            ExpectedNet expectedNet,
            Sizing sizing,
            FundingProjection shortProjection,
            FundingProjection longProjection,
            RouteChoice route,
            List<RouteEvaluation> routeEvaluations,
            BigDecimal markBasisBps) {

        static ScoreResult unscorable() {
            return unscorable(List.of(), null);
        }

        static ScoreResult unscorable(
                List<RouteEvaluation> routeEvaluations,
                BigDecimal markBasisBps) {
            return new ScoreResult(false, null, null, null, null, null,
                    List.copyOf(routeEvaluations), markBasisBps);
        }
    }

    private record Selection(
            Map<Integer, Integer> ranks,
            Map<String, BigDecimal> remainingVenueCapital) { }

    private record Draft(
            int grossRank,
            Pair pair,
            Integer baselineRank,
            SignalScore signalScore,
            Route route,
            ExpectedNet expectedNet,
            BigDecimal requestedLegNotionalUsd,
            JsonDocument shortLegSnapshot,
            JsonDocument longLegSnapshot,
            Map<String, Object> scoreComponents,
            Map<String, Object> gateResults,
            ScoreStatus scoreStatus,
            List<String> decisionReasons) {

        Candidate toCandidate(int evaluationOrder, Integer shadowRank) {
            List<String> finalReasons = new ArrayList<>(decisionReasons);
            if (scoreStatus == ScoreStatus.SCORABLE && shadowRank == null
                    && finalReasons.isEmpty()) {
                finalReasons.add("NOT_SELECTED_AFTER_EXPECTED_NET_AND_CAPITAL_RANK");
            }
            return new Candidate(
                    evaluationOrder,
                    pair,
                    new Ranks(grossRank, baselineRank, shadowRank),
                    signalScore,
                    route,
                    expectedNet,
                    requestedLegNotionalUsd,
                    shortLegSnapshot,
                    longLegSnapshot,
                    XvfShadowJson.object(scoreComponents),
                    XvfShadowJson.object(gateResults),
                    scoreStatus,
                    XvfShadowJson.array(finalReasons));
        }
    }

    private static final class IssueCollector {
        private final Map<String, Map<String, Object>> values = new LinkedHashMap<>();

        void add(String code, String severity, String venue, String symbol, String detail) {
            String key = code + "|" + venue + "|" + symbol;
            Map<String, Object> issue = new LinkedHashMap<>();
            issue.put("code", code);
            issue.put("severity", severity);
            issue.put("venue", venue);
            issue.put("venueSymbol", symbol);
            issue.put("detail", detail);
            values.putIfAbsent(key, issue);
        }

        boolean isEmpty() {
            return values.isEmpty();
        }

        int size() {
            return values.size();
        }

        List<Map<String, Object>> asJson() {
            return List.copyOf(values.values());
        }
    }
}
