package com.smalistean.propstrategy.backtester;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Account {

    private static final MathContext MC = new MathContext(12, RoundingMode.HALF_UP);

    private final BigDecimal initialBalance;
    private BigDecimal balance;
    private BigDecimal peakBalance;
    private BigDecimal positionQty = BigDecimal.ZERO;
    private BigDecimal entryPrice = BigDecimal.ZERO;
    private Instant entryTime;
    private String side;
    private final List<Trade> closedTrades = new ArrayList<>();
    private final List<BigDecimal> equityCurve = new ArrayList<>();

    public Account(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
        this.balance = initialBalance;
        this.peakBalance = initialBalance;
        equityCurve.add(initialBalance);
    }

    public BigDecimal initialBalance() {
        return initialBalance;
    }

    public BigDecimal balance() {
        return balance;
    }

    public BigDecimal peakBalance() {
        return peakBalance;
    }

    public boolean hasOpenPosition() {
        return positionQty.signum() != 0;
    }

    public List<Trade> closedTrades() {
        return List.copyOf(closedTrades);
    }

    public List<BigDecimal> equityCurve() {
        return List.copyOf(equityCurve);
    }

    public void openLong(Instant time, BigDecimal price, BigDecimal quantity) {
        if (hasOpenPosition()) {
            throw new IllegalStateException("Position already open");
        }
        positionQty = quantity;
        entryPrice = price;
        entryTime = time;
        side = "LONG";
    }

    public void openShort(Instant time, BigDecimal price, BigDecimal quantity) {
        if (hasOpenPosition()) {
            throw new IllegalStateException("Position already open");
        }
        positionQty = quantity.negate();
        entryPrice = price;
        entryTime = time;
        side = "SHORT";
    }

    public Trade close(Instant time, BigDecimal price) {
        if (!hasOpenPosition()) {
            throw new IllegalStateException("No open position");
        }
        BigDecimal qty = positionQty.abs();
        BigDecimal pnl = positionQty.signum() > 0
                ? price.subtract(entryPrice, MC).multiply(qty, MC)
                : entryPrice.subtract(price, MC).multiply(qty, MC);
        balance = balance.add(pnl, MC);
        peakBalance = balance.max(peakBalance);
        Trade trade = new Trade(entryTime, time, entryPrice, price, qty, pnl, side);
        closedTrades.add(trade);
        positionQty = BigDecimal.ZERO;
        entryPrice = BigDecimal.ZERO;
        side = null;
        return trade;
    }

    public void applyFee(BigDecimal fee) {
        balance = balance.subtract(fee, MC);
    }

    public void markToMarket(BigDecimal price) {
        BigDecimal equity = balance;
        if (hasOpenPosition()) {
            BigDecimal unrealized = positionQty.signum() > 0
                    ? price.subtract(entryPrice, MC).multiply(positionQty.abs(), MC)
                    : entryPrice.subtract(price, MC).multiply(positionQty.abs(), MC);
            equity = balance.add(unrealized, MC);
        }
        equityCurve.add(equity);
        peakBalance = equity.max(peakBalance);
    }
}
