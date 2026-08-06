package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.OpenInterestStatistic;
import com.smalistean.propstrategy.database.TraderRatio;
import com.smalistean.propstrategy.database.TraderRatio.RatioType;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceSupportingMarketDataClientTest {

    private final BinanceSupportingMarketDataClient client =
            new BinanceSupportingMarketDataClient(
                    HttpClient.newHttpClient(), new ObjectMapper(),
                    "http://localhost", Duration.ZERO, "test-key");

    @Test
    void parsesOpenInterestStatistics() {
        OpenInterestStatistic statistic = client.parseOpenInterest("""
                [{"symbol":"BTCUSDT","sumOpenInterest":"20403.12345678",
                  "sumOpenInterestValue":"176196512.12345678",
                  "CMCCirculatingSupply":"165880.538","timestamp":1591261042378}]
                """, "5m").getFirst();

        assertEquals("BTCUSDT", statistic.symbol());
        assertEquals("5m", statistic.period());
        assertEquals(Instant.ofEpochMilli(1591261042378L), statistic.statisticTime());
        assertEquals("20403.12345678", statistic.sumOpenInterest().toPlainString());
        assertEquals("176196512.12345678", statistic.sumOpenInterestValue().toPlainString());
        assertEquals("165880.538", statistic.circulatingSupply().toPlainString());
    }

    @Test
    void permitsMissingCirculatingSupply() {
        OpenInterestStatistic statistic = client.parseOpenInterest("""
                [{"symbol":"BTCUSDT","sumOpenInterest":"1",
                  "sumOpenInterestValue":"2","timestamp":1591261042378}]
                """, "5m").getFirst();

        assertNull(statistic.circulatingSupply());
    }

    @Test
    void parsesEveryRatioTypeWithoutLosingItsMeaning() {
        String json = """
                [{"symbol":"BTCUSDT","longShortRatio":"1.8105",
                  "longAccount":"0.6442","shortAccount":"0.3558",
                  "timestamp":1591261042378}]
                """;

        for (RatioType type : RatioType.values()) {
            TraderRatio ratio = client.parseRatios(json, "5m", type).getFirst();
            assertEquals(type, ratio.ratioType());
            assertEquals("1.8105", ratio.longShortRatio().toPlainString());
            assertEquals("0.6442", ratio.longShare().toPlainString());
            assertEquals("0.3558", ratio.shortShare().toPlainString());
        }
    }

    @Test
    void rejectsMalformedRows() {
        assertThrows(IllegalArgumentException.class,
                () -> client.parseRatios("[{\"symbol\":\"BTCUSDT\"}]", "5m",
                        RatioType.GLOBAL_ACCOUNT));
    }
}
