package com.smalistean.propstrategy.backtester;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BacktestConfigurationLoaderTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearConfirmation() {
        System.clearProperty("confirmFinalTest");
        System.clearProperty("backtestDataset");
    }

    @Test
    void loadsTrackedTrainingBoundaryExactly() {
        BacktestConfigurationLoader.LoadedConfiguration loaded =
                new BacktestConfigurationLoader().load(
                        Path.of("config/backtests/engine.properties"),
                        Path.of("config/backtests/ema-pullback.properties"));

        // Pins the tracked calendar. Shifted three months earlier on 2026-08-11 so the 2026 course
        // videos fall after the reserved final-test window; see engine.properties for the rationale.
        assertEquals(BacktestDataset.Type.TRAINING, loaded.dataset().type());
        assertEquals(Instant.parse("2023-02-07T00:00:00Z"), loaded.dataset().startInclusive());
        assertEquals(Instant.parse("2025-02-07T00:00:00Z"), loaded.dataset().endExclusive());
    }

    @Test
    void refusesFinalTestWithoutExplicitConfirmation() throws IOException {
        Path engine = temporaryDirectory.resolve("engine.properties");
        String content = Files.readString(Path.of("config/backtests/engine.properties"))
                .replace("backtest.dataset=TRAINING", "backtest.dataset=FINAL_TEST");
        Files.writeString(engine, content);

        assertThrows(IllegalStateException.class,
                () -> new BacktestConfigurationLoader().load(engine,
                        Path.of("config/backtests/ema-pullback.properties")));
    }
}
