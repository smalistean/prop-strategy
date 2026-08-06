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
        Position current = requirePosition();
        BigDecimal grossPnl = current.side == Side.LONG
                ? fill.fillPrice().subtract(current.entryPrice, MC).multiply(current.quantity, MC)
                : current.entryPrice.subtract(fill.fillPrice(), MC).multiply(current.quantity, MC);
        balance = balance.add(grossPnl, MC).subtract(fill.fee(), MC);
        BigDecimal netPnl = grossPnl.subtract(current.entryFee, MC)
                .subtract(fill.fee(), MC).add(current.fundingPnl, MC);
        Trade trade = new Trade(
                current.entryTime, time, current.entryPrice, fill.fillPrice(), current.quantity,
                current.side, grossPnl, current.entryFee, fill.fee(), current.fundingPnl,
                current.entrySlippageCost, fill.slippageCost(), netPnl, reason);
        closedTrades.add(trade);
        position = null;
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
        private final BigDecimal quantity;
        private final BigDecimal stopPrice;
        private final BigDecimal targetPrice;
        private final BigDecimal entryFee;
        private final BigDecimal entrySlippageCost;
        private BigDecimal fundingPnl = BigDecimal.ZERO;
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
            this.entryFee = entryFee;
            this.entrySlippageCost = entrySlippageCost;
        }
    }
}
