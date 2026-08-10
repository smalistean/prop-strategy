package com.smalistean.propstrategy.marketdownloader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

public final class BinanceArchiveDownloader {

    public record DownloadedArchive(Path path, String sha256, long size) {
    }

    private final HttpClient client;

    public BinanceArchiveDownloader() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    BinanceArchiveDownloader(HttpClient client) {
        this.client = client;
    }

    public DownloadedArchive download(URI archiveUri, Path directory) {
        try {
            Files.createDirectories(directory);
            String filename = Path.of(archiveUri.getPath()).getFileName().toString();
            Path target = directory.resolve(filename);
            String expected = fetchChecksum(URI.create(archiveUri + ".CHECKSUM"), filename);
            if (Files.isRegularFile(target) && expected.equals(sha256(target))) {
                return new DownloadedArchive(target, expected, Files.size(target));
            }
            Path partial = directory.resolve(filename + ".part");
            long existing = Files.isRegularFile(partial) ? Files.size(partial) : 0;
            HttpRequest.Builder request = HttpRequest.newBuilder(archiveUri)
                    .timeout(Duration.ofMinutes(30)).GET();
            if (existing > 0) {
                request.header("Range", "bytes=" + existing + "-");
            }
            HttpResponse<InputStream> response = client.send(request.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            boolean append = existing > 0 && response.statusCode() == 206;
            if (response.statusCode() == 416 && existing > 0) {
                // The server rejects a stale/incomplete Range resume. The partial archive
                // is disposable; retry from byte zero instead of making the import fail.
                Files.deleteIfExists(partial);
                return download(archiveUri, directory);
            }
            if (response.statusCode() != 200 && response.statusCode() != 206) {
                throw new IllegalStateException("Archive download returned HTTP " + response.statusCode());
            }
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
                input.transferTo(output);
            }
            String actual = sha256(partial);
            if (!expected.equals(actual)) {
                throw new IllegalStateException("Checksum mismatch for " + filename);
            }
            Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return new DownloadedArchive(target, actual, Files.size(target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to download Binance archive " + archiveUri, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to download Binance archive " + archiveUri, e);
        }
    }

    private String fetchChecksum(URI uri, String filename) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Checksum download returned HTTP " + response.statusCode());
        }
        String[] fields = response.body().trim().split("\\s+");
        if (fields.length < 2 || !fields[1].equals(filename) || fields[0].length() != 64) {
            throw new IllegalStateException("Invalid Binance checksum response for " + filename);
        }
        return fields[0].toLowerCase(java.util.Locale.ROOT);
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[1 << 20];
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
