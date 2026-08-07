package com.smalistean.propstrategy.backtester;

import com.smalistean.propstrategy.strategy.PositionView;
import com.smalistean.propstrategy.strategy.Side;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Account {

    private static final MathContext MC = new MathContext(16, RoundingMode.HALF_UP);
    private final BigDecimal initialBalance;
    private BigDecimal balance;
    private BigDecimal peakEquity;
    private Position position;
    private final List<Trade> closedTrades = new ArrayList<>();
    private final List<BigDecimal> equityCurve = new ArrayList<>();

    public Account(BigDecimal initialBalance) {
        if (initialBalance.signum() <= 0) {
            throw new IllegalArgumentException("Initial balance must be positive");
        }
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
        this.peakEquity = initialBalance;
        equityCurve.add(initialBalance);
    }

    public BigDecimal initialBalance() {
        return initialBalance;
    }

    public BigDecimal balance() {
        return balance;
    }

    public BigDecimal peakBalance() {
        return peakEquity;
    }

    public boolean hasOpenPosition() {
        return position != null;
    }

    public BigDecimal stopPrice() {
        return requirePosition().stopPrice;
    }

    public BigDecimal targetPrice() {
        return requirePosition().targetPrice;
    }

    public Side side() {
        return requirePosition().side;
    }

    public BigDecimal quantity() {
        return requirePosition().quantity;
    }

    public BigDecimal entryPrice() {
        return requirePosition().entryPrice;
    }

    public BigDecimal entryFeePerUnit() {
        Position current = requirePosition();
        return current.entryFee.divide(current.quantity, MC);
    }

    public BigDecimal initialRiskDistance() {
        return requirePosition().initialRiskDistance;
    }

    public boolean breakEvenActive() {
        return requirePosition().breakEvenActive;
    }

    public void activateBreakEven(BigDecimal stopPrice) {
        Position current = requirePosition();
        boolean improvesStop = current.side == Side.LONG
                ? stopPrice.compareTo(current.stopPrice) > 0
                : stopPrice.compareTo(current.stopPrice) < 0;
        if (improvesStop) {
            current.stopPrice = stopPrice;
            current.breakEvenActive = true;
        }
    }

    public void improveStop(BigDecimal stopPrice) {
        Position current = requirePosition();
        boolean improvesStop = current.side == Side.LONG
                ? stopPrice.compareTo(current.stopPrice) > 0
                : stopPrice.compareTo(current.stopPrice) < 0;
        if (improvesStop) {
            current.stopPrice = stopPrice;
        }
    }

    public boolean partialProfitTaken() {
        return requirePosition().partialProfitTaken;
    }

    public void markPartialProfitTaken() {
        requirePosition().partialProfitTaken = true;
    }

    public List<Trade> closedTrades() {
        return List.copyOf(closedTrades);
    }

    public List<BigDecimal> equityCurve() {
        return List.copyOf(equityCurve);
    }

    public PositionView positionView() {
        return position == null ? PositionView.flat() : new PositionView(
                position.side, position.entryTime, position.entryPrice,
                position.quantity, position.barsHeld);
    }

    public void open(Instant time, Side side, BigDecimal quantity,
                     BigDecimal stopPrice, BigDecimal targetPrice,
                     ExecutionModel.Fill fill) {
        if (hasOpenPosition()) {
            throw new IllegalStateException("Position already open");
        }
        balance = balance.subtract(fill.fee(), MC);
        position = new Position(side, time, fill.fillPrice(), quantity,
                stopPrice, targetPrice, fill.fee(), fill.slippageCost());
    }

    public void incrementBarsHeld() {
        if (position != null) {
            position.barsHeld++;
        }
    }

    public void applyFunding(BigDecimal cashFlow) {
        Position current = requirePosition();
        balance = balance.add(cashFlow, MC);
        current.fundingPnl = current.fundingPnl.add(cashFlow, MC);
    }

    public Trade close(Instant time, ExecutionModel.Fill fill, String reason) {
        return closeQuantity(time, fill, reason, true);
    }

    public Trade closePartial(Instant time, BigDecimal quantity, ExecutionModel.Fill fill,
                              String reason) {
        if (quantity.signum() <= 0 || quantity.compareTo(quantity()) >= 0) {
            throw new IllegalArgumentException("Partial close quantity must be positive and smaller than position");
        }
        return closeQuantity(time, quantity, fill, reason, false);
    }

    private Trade closeQuantity(Instant time, ExecutionModel.Fill fill, String reason,
                                boolean closeEntirePosition) {
        return closeQuantity(time, quantity(), fill, reason, closeEntirePosition);
    }

    private Trade closeQuantity(Instant time, BigDecimal requestedQuantity,
                                ExecutionModel.Fill fill, String reason,
                                boolean closeEntirePosition) {
        Position current = requirePosition();
        BigDecimal closingQuantity = closeEntirePosition ? current.quantity : requestedQuantity;
        BigDecimal fraction = closingQuantity.divide(current.quantity, MC);
        BigDecimal allocatedEntryFee = current.entryFee.multiply(fraction, MC);
        BigDecimal allocatedFunding = current.fundingPnl.multiply(fraction, MC);
        BigDecimal grossPnl = current.side == Side.LONG
                ? fill.fillPrice().subtract(current.entryPrice, MC).multiply(closingQuantity, MC)
                : current.entryPrice.subtract(fill.fillPrice(), MC).multiply(closingQuantity, MC);
        balance = balance.add(grossPnl, MC).subtract(fill.fee(), MC);
        BigDecimal netPnl = grossPnl.subtract(allocatedEntryFee, MC)
                .subtract(fill.fee(), MC).add(allocatedFunding, MC);
        Trade trade = new Trade(
                current.entryTime, time, current.entryPrice, fill.fillPrice(), closingQuantity,
                current.side, grossPnl, allocatedEntryFee, fill.fee(), allocatedFunding,
                current.entrySlippageCost.multiply(fraction, MC), fill.slippageCost(), netPnl, reason);
        closedTrades.add(trade);
        if (closeEntirePosition) {
            position = null;
        } else {
            current.quantity = current.quantity.subtract(closingQuantity, MC);
            current.entryFee = current.entryFee.subtract(allocatedEntryFee, MC);
            current.fundingPnl = current.fundingPnl.subtract(allocatedFunding, MC);
            current.entrySlippageCost = current.entrySlippageCost
                    .multiply(BigDecimal.ONE.subtract(fraction, MC), MC);
        }
        return trade;
    }

    public BigDecimal markToMarket(BigDecimal price) {
        BigDecimal equity = balance;
        if (position != null) {
            BigDecimal unrealized = position.side == Side.LONG
                    ? price.subtract(position.entryPrice, MC).multiply(position.quantity, MC)
                    : position.entryPrice.subtract(price, MC).multiply(position.quantity, MC);
            equity = equity.add(unrealized, MC);
        }
        equityCurve.add(equity);
        peakEquity = peakEquity.max(equity);
        return equity;
    }

    private Position requirePosition() {
        if (position == null) {
            throw new IllegalStateException("No open position");
        }
        return position;
    }

    private static final class Position {
        private final Side side;
        private final Instant entryTime;
        private final BigDecimal entryPrice;
        private BigDecimal quantity;
        private BigDecimal stopPrice;
        private final BigDecimal targetPrice;
        private final BigDecimal initialRiskDistance;
        private BigDecimal entryFee;
        private BigDecimal entrySlippageCost;
        private BigDecimal fundingPnl = BigDecimal.ZERO;
        private boolean breakEvenActive;
        private boolean partialProfitTaken;
        private int barsHeld;

        private Position(Side side, Instant entryTime, BigDecimal entryPrice,
                         BigDecimal quantity, BigDecimal stopPrice, BigDecimal targetPrice,
                         BigDecimal entryFee, BigDecimal entrySlippageCost) {
            this.side = side;
            this.entryTime = entryTime;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.stopPrice = stopPrice;
            this.targetPrice = targetPrice;
            this.initialRiskDistance = entryPrice.subtract(stopPrice).abs();
            this.entryFee = entryFee;
            this.entrySlippageCost = entrySlippageCost;
        }
    }
}
