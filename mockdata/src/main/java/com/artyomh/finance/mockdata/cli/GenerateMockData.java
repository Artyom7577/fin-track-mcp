package com.artyomh.finance.mockdata.cli;

import com.artyomh.finance.mockdata.scenario.Scenarios;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Writes the default six-month mock statement to data/ under the repository root given as the only argument.
 */
public final class GenerateMockData {

    static final long SEED = 20260501L;
    static final String BASE_NAME = "mock-evoca-2026-05-to-10";
    private static final String DATA_DIRECTORY = "data";

    private GenerateMockData() {
    }

    public static void main(String[] args) {
        try {
            Path directory = MockFiles.rootFrom(args).resolve(DATA_DIRECTORY);
            int lines = MockFiles.write(Scenarios.defaultSixMonths(), SEED, directory, BASE_NAME);
            System.err.println("Wrote 2 files, " + lines + " rows to " + directory);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }
}
