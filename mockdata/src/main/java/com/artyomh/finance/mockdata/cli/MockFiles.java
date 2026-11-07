package com.artyomh.finance.mockdata.cli;

import com.artyomh.finance.mockdata.generator.GeneratedStatement;
import com.artyomh.finance.mockdata.generator.StatementGenerator;
import com.artyomh.finance.mockdata.scenario.Scenario;
import com.artyomh.finance.mockdata.writer.CsvStatementWriter;
import com.artyomh.finance.mockdata.writer.StatementWriter;
import com.artyomh.finance.mockdata.writer.XlsxStatementWriter;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.random.RandomGeneratorFactory;

/**
 * Generates a scenario and writes it as .xlsx and .csv, refusing any target that could be a real statement.
 */
final class MockFiles {

    private static final String MOCK_PREFIX = "mock-";
    private static final String RANDOM_ALGORITHM = "L64X128MixRandom";
    private static final String NOT_A_MOCK_FILE =
            "Not a mock file; put real statements in ~/.fin-track-mcp/statements/";
    private static final String DATA_FOLDER = "data";
    private static final String REAL_FOLDER = "real";
    private static final Path HOME_DATA = Path.of(System.getProperty("user.home"), ".fin-track-mcp");

    private MockFiles() {
    }

    static int write(Scenario scenario, long seed, Path directory, String baseName) throws IOException {
        Iterable<StatementWriter> writers =
                List.of(new XlsxStatementWriter(clockFor(scenario)), new CsvStatementWriter());
        // check every target before touching the disk, so a refusal writes nothing
        for (StatementWriter writer : writers) {
            requireMockFile(target(directory, baseName, writer));
        }
        GeneratedStatement statement = new StatementGenerator()
                .generate(scenario, RandomGeneratorFactory.of(RANDOM_ALGORITHM).create(seed));
        Files.createDirectories(directory);
        for (StatementWriter writer : writers) {
            // NOFOLLOW_LINKS closes the gap between the check and the open
            try (OutputStream out = Files.newOutputStream(target(directory, baseName, writer),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                writer.write(statement, out);
            }
        }
        return statement.lines().size();
    }

    static Path rootFrom(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one argument: the repository root directory");
        }
        return Path.of(args[0]);
    }

    // the period's last day, so regenerating the same scenario never changes a byte
    private static Clock clockFor(Scenario scenario) {
        return Clock.fixed(scenario.lastMonth().atEndOfMonth().atStartOfDay(ZoneOffset.UTC).toInstant(),
                ZoneOffset.UTC);
    }

    private static Path target(Path directory, String baseName, StatementWriter writer) {
        return directory.resolve(baseName + "." + writer.fileExtension());
    }

    private static void requireMockFile(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path real = realLocation(absolute);
        if (!isMockName(absolute) || replacesSomethingElse(absolute) || isUnderRealStatements(real)
                || isUnder(real, realLocation(HOME_DATA))) {
            throw new IllegalArgumentException(NOT_A_MOCK_FILE);
        }
    }

    private static boolean isMockName(Path absolute) {
        return absolute.getFileName().toString().startsWith(MOCK_PREFIX);
    }

    private static boolean replacesSomethingElse(Path absolute) {
        return Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS);
    }

    // follows symlinked parent folders, so a linked data/ cannot smuggle the file into a real-statement folder
    private static Path realLocation(Path absolute) throws IOException {
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        return existing.toRealPath().resolve(existing.relativize(absolute));
    }

    // names compare case-insensitively, as the default macOS file system does
    private static boolean isUnderRealStatements(Path real) {
        for (Path folder = real.getParent(); folder != null; folder = folder.getParent()) {
            if (isNamed(folder, REAL_FOLDER) && isNamed(folder.getParent(), DATA_FOLDER)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNamed(Path folder, String name) {
        return folder != null && folder.getFileName() != null && folder.getFileName().toString().equalsIgnoreCase(name);
    }

    private static boolean isUnder(Path path, Path folder) {
        String candidate = lowerCase(path);
        String root = lowerCase(folder);
        return candidate.equals(root) || candidate.startsWith(root + File.separator);
    }

    private static String lowerCase(Path path) {
        return path.toString().toLowerCase(Locale.ROOT);
    }
}
