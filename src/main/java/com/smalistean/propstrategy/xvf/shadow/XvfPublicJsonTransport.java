package com.smalistean.propstrategy.xvf.shadow;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Package-private transport seam: production checks HTTP/JSON, tests return fixture responses. */
interface XvfPublicJsonTransport {

    TimedJson get(URI uri);

    TimedJson post(URI uri, String jsonBody);

    record TimedJson(JsonNode body, Instant requestedAt, Instant receivedAt) {
        public TimedJson {
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(receivedAt, "receivedAt");
            if (receivedAt.isBefore(requestedAt)) {
                throw new IllegalArgumentException("receivedAt cannot precede requestedAt");
            }
        }
    }
}

final class JdkXvfPublicJsonTransport implements XvfPublicJsonTransport {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private final XvfHttpSender sender;
    private final Clock clock;

    JdkXvfPublicJsonTransport() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), Clock.systemUTC());
    }

    JdkXvfPublicJsonTransport(HttpClient client, Clock clock) {
        this(request -> {
            HttpResponse<String> response = Objects.requireNonNull(client, "client")
                    .send(request, HttpResponse.BodyHandlers.ofString());
            return new XvfRawHttpResponse(response.statusCode(), response.body());
        }, clock);
    }

    JdkXvfPublicJsonTransport(XvfHttpSender sender, Clock clock) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public TimedJson get(URI uri) {
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "prop-strategy-xvf-shadow").GET().build());
    }

    @Override
    public TimedJson post(URI uri, String jsonBody) {
        Objects.requireNonNull(jsonBody, "jsonBody");
        return send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "prop-strategy-xvf-shadow")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody)).build());
    }

    private TimedJson send(HttpRequest request) {
        Instant requestedAt = now();
        try {
            XvfRawHttpResponse response = sender.send(request);
            Instant receivedAt = now();
            if (response.statusCode() != 200) {
                throw new IllegalStateException("XVF public market request " + request.uri()
                        + " returned HTTP " + response.statusCode());
            }
            if (response.body() == null || response.body().isBlank()) {
                throw new IllegalStateException("XVF public market request " + request.uri()
                        + " returned an empty body");
            }
            JsonNode body = MAPPER.readTree(response.body());
            if (body == null || body.isNull() || body.isMissingNode()) {
                throw new IllegalStateException("XVF public market request " + request.uri()
                        + " returned JSON null");
            }
            return new TimedJson(body, requestedAt, receivedAt);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("XVF public market request " + request.uri()
                    + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching XVF public market data", e);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}

@FunctionalInterface
interface XvfHttpSender {
    XvfRawHttpResponse send(HttpRequest request) throws java.io.IOException, InterruptedException;
}

record XvfRawHttpResponse(int statusCode, String body) { }
