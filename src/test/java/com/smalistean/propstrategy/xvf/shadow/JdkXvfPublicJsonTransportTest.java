package com.smalistean.propstrategy.xvf.shadow;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdkXvfPublicJsonTransportTest {

    @Test
    void rejectsNon200AndDuplicateKeyJson() {
        URI uri = URI.create("https://fixture.test/market");
        JdkXvfPublicJsonTransport failureTransport = new JdkXvfPublicJsonTransport(
                request -> new XvfRawHttpResponse(503, "unavailable"), Clock.systemUTC());
        IllegalStateException status = assertThrows(IllegalStateException.class,
                () -> failureTransport.get(uri));
        assertTrue(status.getMessage().contains("HTTP 503"));

        JdkXvfPublicJsonTransport duplicateTransport = new JdkXvfPublicJsonTransport(
                request -> new XvfRawHttpResponse(200, "{\"value\":1,\"value\":2}"),
                Clock.systemUTC());
        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> duplicateTransport.get(uri));
        assertTrue(duplicate.getMessage().contains("failed"));
    }

    @Test
    void recordsMicrosecondExactLocalRequestWindow() {
        Instant fixed = Instant.parse("2026-08-21T12:34:56.123456789Z");
        JdkXvfPublicJsonTransport transport = new JdkXvfPublicJsonTransport(
                request -> new XvfRawHttpResponse(200, "{\"ok\":true}"),
                Clock.fixed(fixed, ZoneOffset.UTC));

        XvfPublicJsonTransport.TimedJson response = transport.get(
                URI.create("https://fixture.test/market"));

        Instant expected = Instant.parse("2026-08-21T12:34:56.123456Z");
        assertEquals(expected, response.requestedAt());
        assertEquals(expected, response.receivedAt());
        assertTrue(response.body().path("ok").asBoolean());
    }
}
