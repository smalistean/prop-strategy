package com.smalistean.propstrategy.database;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class CsvKlineRepository {

    private static final String HEADER = "openTime,open,high,low,close,volume";

    public void save(List<Kline> klines, Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (BufferedWriter writer = Files.newBufferedWriter(file)) {
                writer.write(HEADER);
                writer.newLine();
                for (Kline kline : klines) {
                    writer.write(toCsvLine(kline));
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save klines to " + file, e);
        }
    }

    public List<Kline> load(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            List<Kline> klines = new ArrayList<>();
            String line = reader.readLine();
            if (line == null || !line.startsWith("openTime")) {
                throw new IllegalArgumentException("Invalid CSV header in " + file);
            }
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                klines.add(fromCsvLine(line));
            }
            return klines;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load klines from " + file, e);
        }
    }

    private static String toCsvLine(Kline kline) {
        return String.join(",",
                kline.openTime().toString(),
                kline.open().toPlainString(),
                kline.high().toPlainString(),
                kline.low().toPlainString(),
                kline.close().toPlainString(),
                kline.volume().toPlainString()
        );
    }

    private static Kline fromCsvLine(String line) {
        String[] parts = line.split(",");
        return new Kline(
                Instant.parse(parts[0]),
                new BigDecimal(parts[1]),
                new BigDecimal(parts[2]),
                new BigDecimal(parts[3]),
                new BigDecimal(parts[4]),
                new BigDecimal(parts[5])
        );
    }
}
