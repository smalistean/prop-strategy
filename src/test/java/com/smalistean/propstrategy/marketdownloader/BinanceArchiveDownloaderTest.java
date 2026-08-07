package com.smalistean.propstrategy.marketdownloader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinanceArchiveDownloaderTest {

    @TempDir Path directory;

    @Test
    void calculatesSha256WithoutLoadingFileIntoMemory() throws Exception {
        Path file = directory.resolve("sample.zip");
        Files.writeString(file, "aggregate trades");
        assertEquals("3e620dbaaf9c44850798a2392b072b8bb07153d382b7e651e1f365439b3d694f",
                BinanceArchiveDownloader.sha256(file));
    }
}
