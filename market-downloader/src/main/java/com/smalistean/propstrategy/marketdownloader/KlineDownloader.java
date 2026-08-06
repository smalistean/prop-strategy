package com.smalistean.propstrategy.marketdownloader;

import com.smalistean.propstrategy.database.CsvKlineRepository;
import com.smalistean.propstrategy.database.Kline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class KlineDownloader {

    private static final Logger log = LoggerFactory.getLogger(KlineDownloader.class);
    private static final int MAX_LIMIT = 1000;

    private final BinanceKlineClient client;
    private final CsvKlineRepository repository;

    public KlineDownloader(BinanceKlineClient client, CsvKlineRepository repository) {
        this.client = client;
        this.repository = repository;
    }

    public Path download(String symbol, String interval, Instant start, Instant end, Path outputFile) {
        long startMs = start.toEpochMilli();
        long endMs = end.toEpochMilli();
        List<Kline> allKlines = new ArrayList<>();

        while (startMs < endMs) {
            List<Kline> batch = client.fetchKlines(symbol, interval, startMs, endMs, MAX_LIMIT);
            if (batch.isEmpty()) {
                break;
            }
            allKlines.addAll(batch);
            long lastOpenTime = batch.getLast().openTime().toEpochMilli();
            startMs = lastOpenTime + 1;
            log.info("Downloaded {} klines for {} (last: {})", batch.size(), symbol, batch.getLast().openTime());
            if (batch.size() < MAX_LIMIT) {
                break;
            }
        }

        repository.save(allKlines, outputFile);
        log.info("Saved {} klines to {}", allKlines.size(), outputFile);
        return outputFile;
    }
}
