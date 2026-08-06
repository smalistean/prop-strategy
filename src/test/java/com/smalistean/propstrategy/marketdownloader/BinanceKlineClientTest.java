package com.smalistean.propstrategy.marketdownloader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smalistean.propstrategy.database.Kline;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BinanceKlineClientTest {

    private final BinanceKlineClient client = new BinanceKlineClient(
            HttpClient.newHttpClient(), new ObjectMapper(), "http://localhost", Duration.ZERO);

    @Test
    void parsesAllRequiredFuturesKlineFields() throws Exception {
        String json = """
                [[1722945600000,"60000.10","60100.20","59900.30","60050.40","123.45",
                  1722945659999,"7412345.67",321,"60.12","3607890.12","0"]]
                """;

        List<Kline> result = client.parseKlines(json);

        assertEquals(1, result.size());
        Kline kline = result.getFirst();
        assertEquals(Instant.ofEpochMilli(1722945600000L), kline.openTime());
        assertEquals("60000.10", kline.open().toPlainString());
        assertEquals(321, kline.tradeCount());
        assertEquals("3607890.12", kline.takerBuyQuoteVolume().toPlainString());
    }

    @Test
    void rejectsMalformedRows() {
        assertThrows(Exception.class, () -> client.parseKlines("[[1,2,3]]"));
    }
}
