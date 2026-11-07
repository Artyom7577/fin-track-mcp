package com.artyomh.finance.mockdata.cli;

import com.artyomh.finance.mockdata.scenario.Scenarios;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Rewrites the writers' committed golden files; run only when a format change is intended.
 */
public final class GenerateGoldenSample {

    static final long SEED = 1L;
    static final String BASE_NAME = "mock-golden-sample";
    private static final String GOLDEN_DIRECTORY = "mockdata/src/test/resources/golden";

    private GenerateGoldenSample() {
    }

    public static void main(String[] args) {
        try {
            Path directory = MockFiles.rootFrom(args).resolve(GOLDEN_DIRECTORY);
            int lines = MockFiles.write(Scenarios.goldenSample(), SEED, directory, BASE_NAME);
            System.err.println("Wrote 2 golden files, " + lines + " rows to " + directory);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
