package com.smalistean.propstrategy.feature;

import com.smalistean.propstrategy.database.DatabaseConfig;
import com.smalistean.propstrategy.database.FundingRate;
import com.smalistean.propstrategy.database.Kline;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.PostgresFundingRateRepository;
import com.smalistean.propstrategy.database.PostgresKlineRepository;
import com.smalistean.propstrategy.database.PostgresSupportingMarketDataRepository;
import com.smalistean.propstrategy.database.TraderRatio;
import com.smalistean.propstrategy.database.TraderRatio.RatioType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class BtcFeaturePreviewApplication {

    private static final String SYMBOL = "BTCUSDT";
    private static final String CANDLE_INTERVAL = "15m";
    private static final String CONTEXT_PERIOD = "5m";
    private static final int INPUT_CANDLES = 200;
    private static final int OUTPUT_ROWS = 10;

    private BtcFeaturePreviewApplication() {
    }

    public static void main(String[] args) {
        DatabaseConfig config = DatabaseConfig.fromEnvironment();
        PostgresKlineRepository klineRepository = new PostgresKlineRepository(config);
        PostgresFundingRateRepository fundingRepository =
                new PostgresFundingRateRepository(config);
        PostgresSupportingMarketDataRepository contextRepository =
                new PostgresSupportingMarketDataRepository(config);

        List<Kline> klines = klineRepository.findLatest(SYMBOL, CANDLE_INTERVAL, INPUT_CANDLES);
        if (klines.isEmpty()) {
            throw new IllegalStateException("No BTCUSDT 15m candles are stored");
        }
        Instant end = klines.getLast().closeTime();
        List<FundingRate> funding = fundingRepository.findThrough(SYMBOL, end);
        List<OpenInterestStatistic> openInterest =
                contextRepository.findOpenInterestThrough(SYMBOL, CONTEXT_PERIOD, end);
        List<TraderRatio> global = contextRepository.findRatiosThrough(
                SYMBOL, CONTEXT_PERIOD, RatioType.GLOBAL_ACCOUNT, end);
        List<TraderRatio> topAccount = contextRepository.findRatiosThrough(
                SYMBOL, CONTEXT_PERIOD, RatioType.TOP_ACCOUNT, end);
        List<TraderRatio> topPosition = contextRepository.findRatiosThrough(
                SYMBOL, CONTEXT_PERIOD, RatioType.TOP_POSITION, end);

        List<FeatureRow> features = new FeatureGenerator().generate(
                klines, funding, openInterest, global, topAccount, topPosition);
        System.out.printf("Generated %,d BTCUSDT 15m feature rows from %,d candles; showing latest %d.%n",
                features.size(), klines.size(), Math.min(OUTPUT_ROWS, features.size()));
        System.out.println("available_at | close | ema20 | ema50 | rsi14 | atr14 | vol_ratio | oi_change_pct | funding | global | top_account | top_position");
        features.stream().skip(Math.max(0, features.size() - OUTPUT_ROWS)).forEach(row ->
                System.out.printf("%s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s | %s%n",
                        row.availableAt(), value(row.close()), value(row.ema20()), value(row.ema50()),
                        value(row.rsi14()), value(row.atr14()), value(row.volumeRatio20()),
                        value(row.openInterestChangePercent()), value(row.fundingRate()),
                        value(row.globalAccountRatio()), value(row.topAccountRatio()),
                        value(row.topPositionRatio())));
    }

    private static String value(BigDecimal value) {
        return value == null ? "n/a" : value.stripTrailingZeros().toPlainString();
    }
}
