package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.FundingRate;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceFundingRateClientTest {

    private final BinanceFundingRateClient client = new BinanceFundingRateClient(
            HttpClient.newHttpClient(), new ObjectMapper(), "http://localhost", Duration.ZERO);

    @Test
    void parsesFundingRateFields() throws Exception {
        String json = """
                [{"symbol":"BTCUSDT","fundingTime":1722945600000,
                  "fundingRate":"0.00010000","markPrice":"60050.12345678",
                  "rateType":"Regular"}]
                """;

        List<FundingRate> result = client.parse(json);

        assertEquals(1, result.size());
        FundingRate rate = result.getFirst();
        assertEquals("BTCUSDT", rate.symbol());
        assertEquals(Instant.ofEpochMilli(1722945600000L), rate.fundingTime());
        assertEquals("0.00010000", rate.fundingRate().toPlainString());
        assertEquals("60050.12345678", rate.markPrice().toPlainString());
        assertEquals("Regular", rate.rateType());
    }

    @Test
    void defaultsLegacyResponseToRegularRateType() throws Exception {
        FundingRate rate = client.parse("""
                [{"symbol":"BTCUSDT","fundingTime":1722945600000,
                  "fundingRate":"0.0001","markPrice":"60050.12"}]
                """).getFirst();

        assertEquals("Regular", rate.rateType());
    }

    @Test
    void preservesUnavailableHistoricalMarkPriceAsNull() throws Exception {
        FundingRate rate = client.parse("""
                [{"symbol":"BTCUSDT","fundingTime":1691280000000,
                  "fundingRate":"0.00010000","markPrice":"","rateType":"Regular"}]
                """).getFirst();

        assertNull(rate.markPrice());
    }

    @Test
    void rejectsMalformedResponse() {
        assertThrows(Exception.class, () -> client.parse("[{\"symbol\":\"BTCUSDT\"}]"));
    }
}
